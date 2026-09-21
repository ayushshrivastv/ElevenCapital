import type { ServerResponse } from 'node:http';
import { CatalogSchema, type Catalog, type Metric } from './schema.js';
import type { CatalogMutation, CatalogPatch } from './market-update.js';

const MAX_AGE_MS = 300_000;
const MAX_STREAM_BUFFER = 16 * 1024 * 1024;
type Status = { state: 'loading' | 'reconnecting'; message: string };
export type MarketEvent = { event: 'snapshot'; data: Catalog; patch?: CatalogPatch } | { event: 'status'; data: Status };
type Listener = (event: MarketEvent) => void;

function fresh(timestamp: string | null, now: number): boolean {
  if (timestamp === null) return false;
  const age = now - Date.parse(timestamp);
  return Number.isFinite(age) && age >= 0 && age <= MAX_AGE_MS;
}

/** Replaying a snapshot must never reset the age of its underlying observations. */
export function snapshotAt(snapshot: Catalog, now: number, refreshFailed = false): Catalog {
  const metricAt = (metric: Metric, updatedAt: string | null): Metric => metric.status === 'available' &&
    (!fresh(metric.receivedAt, now) || (updatedAt !== null && !fresh(updatedAt, now)))
    ? { ...metric, status: 'unavailable', value: null, reason: 'Statistics expired. Updates resume automatically when the source recovers.' }
    : metric;
  const stocks = snapshot.stocks.map(stock => {
    const quoteFresh = fresh(stock.quote.receivedAt, now) && (stock.quote.asOf === null || fresh(stock.quote.asOf, now));
    const activityFresh = fresh(stock.activity.receivedAt, now) &&
      (stock.activity.updatedAt === null || fresh(stock.activity.updatedAt, now));
    return {
      ...stock,
      quote: quoteFresh ? stock.quote : { ...stock.quote, price: null, changeAmount: null, changePercent: null },
      volume24h: activityFresh ? stock.volume24h : null,
      volume24hBasis: activityFresh ? stock.volume24hBasis : null,
      activity: activityFresh ? stock.activity : {
        ...stock.activity, volume24h: null, netVolume24h: null,
        volumeReason: stock.activity.volumeReason ?? 'Volume data expired. Updates resume automatically when the source recovers.',
        netVolumeReason: stock.activity.netVolumeReason ?? 'Net volume data expired. Updates resume automatically when the source recovers.',
      },
      statistics: {
        ...stock.statistics,
        marketCapitalization: metricAt(stock.statistics.marketCapitalization, stock.statistics.updatedAt),
        liquidity: metricAt(stock.statistics.liquidity, stock.statistics.updatedAt),
        holderCount: metricAt(stock.statistics.holderCount, stock.statistics.updatedAt),
        organicScore: metricAt(stock.statistics.organicScore, stock.statistics.updatedAt),
      },
    };
  });
  const providers = snapshot.providers.map(provider => {
    const rows = stocks.filter(stock => stock.provider === provider.id);
    const previouslyQuoted = snapshot.stocks.filter(stock => stock.provider === provider.id && stock.quote.price !== null).length;
    const remaining = rows.filter(stock => stock.quote.price !== null).length;
    const expired = previouslyQuoted - remaining;
    // A catalog member with no public quote is a coverage gap, not a disconnected feed.
    if (expired > 0) {
      return { ...provider, status: remaining ? 'stale' as const : 'unavailable' as const,
        message: `${expired} previously observed quotes expired. Updates resume automatically. ${provider.message ?? ''}`.trim() };
    }
    if (refreshFailed || !fresh(snapshot.receivedAt, now)) {
      return { ...provider, status: provider.status === 'unavailable' ? 'unavailable' as const : 'stale' as const,
        message: `Refreshing automatically. Retained observations keep their original timestamps and expire after five minutes. ${provider.message ?? ''}`.trim() };
    }
    return provider;
  });
  return CatalogSchema.parse({ ...snapshot, providers, stocks, receivedAt: new Date(now).toISOString() });
}

/**
 * Patch rows come from the same freshness-normalized catalog delivered in the
 * snapshot envelope. This prevents an incremental frame from reviving a quote
 * that the authoritative catalog has already expired.
 */
function patchAt(snapshot: Catalog, mutation: CatalogMutation): CatalogPatch {
  const changedIds = [...new Set(mutation.changedIds)];
  const changed = new Set(changedIds);
  const removedIds = [...new Set(mutation.removedIds)];
  return { ...mutation, changedIds, removedIds,
    stocks: snapshot.stocks.filter(stock => changed.has(stock.id)) };
}

/** One catalog comparison per refresh replaces one full-catalog comparison per client. */
function mutationBetween(before: Catalog, after: Catalog, requestedAt: number): CatalogMutation {
  const previous = new Map(before.stocks.map(stock => [stock.id, JSON.stringify(stock)]));
  const currentIds = after.stocks.map(stock => stock.id);
  const previousIds = before.stocks.map(stock => stock.id);
  const current = new Set(currentIds);
  const changedIds = after.stocks.filter(stock => previous.get(stock.id) !== JSON.stringify(stock)).map(stock => stock.id);
  const removedIds = previousIds.filter(id => !current.has(id));
  const membershipChanged = previousIds.length !== currentIds.length || previousIds.some((id, index) => id !== currentIds[index]);
  return { changedIds, removedIds, membershipChanged, requestedAt };
}

function mergeMutations(source: CatalogMutation | undefined, normalized: CatalogMutation | undefined): CatalogMutation | undefined {
  if (!source) return normalized;
  if (!normalized) return source;
  return {
    changedIds: [...new Set([...source.changedIds, ...normalized.changedIds])],
    removedIds: [...new Set([...source.removedIds, ...normalized.removedIds])],
    membershipChanged: source.membershipChanged || normalized.membershipChanged,
    requestedAt: source.requestedAt,
  };
}

/** One background refresh and one retained catalog, regardless of the number of connected devices. */
export class RealtimeCatalog {
  private value?: Catalog;
  private pending?: Promise<Catalog>;
  private failed = false;
  private running = false;
  private timer?: ReturnType<typeof setTimeout>;
  private lastEmitted?: Catalog;
  private readonly listeners = new Set<Listener>();
  constructor(private readonly load: () => Promise<Catalog>, private readonly now = Date.now,
    private readonly refreshMs = 10_000, private readonly retryMs = 5_000,
    private readonly monotonicNow = () => performance.now()) {}

  current(): Catalog | undefined { return this.value ? snapshotAt(this.value, this.now(), this.failed) : undefined; }

  /** Already-normalized source deltas arrive independently of slow catalog refreshes. */
  publish(value: Catalog, mutation?: CatalogMutation): void {
    this.value = value;
    this.failed = false;
    const snapshot = this.current()!;
    this.emitSnapshot(snapshot, mutation);
  }

  subscribe(listener: Listener): () => void {
    this.listeners.add(listener);
    const snapshot = this.current();
    listener(snapshot ? { event: 'snapshot', data: snapshot } : {
      event: 'status', data: { state: 'loading', message: 'Connecting to market data. Updates arrive automatically.' },
    });
    return () => { this.listeners.delete(listener); };
  }

  start(): void {
    if (this.running) return;
    this.running = true;
    void this.tick();
  }

  stop(): void {
    this.running = false;
    if (this.timer) clearTimeout(this.timer);
    this.timer = undefined;
    this.listeners.clear();
  }

  async snapshot(): Promise<Catalog> { return this.current() ?? this.refresh(); }

  refresh(): Promise<Catalog> {
    if (this.pending) return this.pending;
    this.pending = (async () => {
      try {
        const loaded = CatalogSchema.parse(await this.load());
        this.value = loaded;
        this.failed = false;
        const snapshot = this.current()!;
        this.emitSnapshot(snapshot);
        return snapshot;
      } catch (error) {
        this.failed = true;
        this.emit({ event: 'status', data: { state: 'reconnecting', message: 'Market data is reconnecting automatically.' } });
        const snapshot = this.current();
        if (snapshot) {
          this.emitSnapshot(snapshot);
          return snapshot;
        }
        throw error;
      } finally { this.pending = undefined; }
    })();
    return this.pending;
  }

  private emit(event: MarketEvent): void {
    for (const listener of this.listeners) {
      // A dead stream cannot abort the shared catalog refresh for other clients.
      try { listener(event); } catch { this.listeners.delete(listener); }
    }
  }

  private emitSnapshot(snapshot: Catalog, sourceMutation?: CatalogMutation): void {
    const requestedAt = sourceMutation?.requestedAt ?? this.monotonicNow();
    const normalizedMutation = this.lastEmitted ? mutationBetween(this.lastEmitted, snapshot, requestedAt) : undefined;
    const mutation = mergeMutations(sourceMutation, normalizedMutation);
    this.lastEmitted = snapshot;
    this.emit({ event: 'snapshot', data: snapshot, ...(mutation ? { patch: patchAt(snapshot, mutation) } : {}) });
  }

  private async tick(): Promise<void> {
    try { await this.refresh(); } catch { /* status is emitted; the next attempt is automatic */ }
    if (!this.running) return;
    const retrying = this.failed || this.value?.providers.some(provider => provider.status === 'stale');
    this.timer = setTimeout(() => { void this.tick(); }, retrying ? this.retryMs : this.refreshMs);
    this.timer.unref();
  }
}

/** Each slow client retains at most one newest update. Prolonged backpressure closes only that stream. */
export class MarketStreams {
  private readonly sessions = new Set<() => void>();
  constructor(private readonly feed: RealtimeCatalog, private readonly now = Date.now,
    private readonly heartbeatMs = 10_000, private readonly maxClients = 64) {}

  get size(): number { return this.sessions.size; }

  attach(response: ServerResponse): boolean {
    if (this.sessions.size >= this.maxClients) return false;
    let ended = false;
    let blocked = false;
    let pending: string | undefined;
    let blockedTimer: ReturnType<typeof setTimeout> | undefined;
    let unsubscribe = () => {};
    let heartbeat: ReturnType<typeof setInterval> | undefined;
    const close = () => {
      if (ended) return;
      ended = true;
      unsubscribe();
      if (heartbeat) clearInterval(heartbeat);
      if (blockedTimer) clearTimeout(blockedTimer);
      response.removeListener('drain', drain);
      response.removeListener('close', close);
      response.removeListener('error', close);
      this.sessions.delete(close);
      pending = undefined;
      if (!response.destroyed && !response.writableEnded) response.end();
    };
    const write = (frame: string, isHeartbeat = false) => {
      if (ended || response.destroyed || response.writableEnded) { close(); return; }
      if (Buffer.byteLength(frame) > MAX_STREAM_BUFFER) { response.destroy(); close(); return; }
      if (blocked) { if (!isHeartbeat) pending = frame; return; }
      if (response.writableLength + Buffer.byteLength(frame) > MAX_STREAM_BUFFER) { response.destroy(); close(); return; }
      try {
        if (!response.write(frame)) {
          blocked = true;
          blockedTimer = setTimeout(() => { response.destroy(); close(); }, 30_000);
          blockedTimer.unref();
        }
      } catch { close(); }
    };
    const drain = () => {
      blocked = false;
      if (blockedTimer) clearTimeout(blockedTimer);
      blockedTimer = undefined;
      const frame = pending;
      pending = undefined;
      if (frame) write(frame);
    };
    this.sessions.add(close);
    response.on('close', close);
    response.on('error', close);
    response.on('drain', drain);
    response.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-store, no-transform',
      'Connection': 'keep-alive', 'X-Accel-Buffering': 'no', 'X-Content-Type-Options': 'nosniff',
    });
    response.socket?.setTimeout(0);
    response.flushHeaders();
    write('retry: 2000\n\n');
    unsubscribe = this.feed.subscribe(event => write(`event: ${event.event}\ndata: ${JSON.stringify(event.data)}\n\n`));
    if (ended) { unsubscribe(); return true; }
    heartbeat = setInterval(() => write(`event: heartbeat\ndata: ${JSON.stringify({ serverTime: new Date(this.now()).toISOString() })}\n\n`, true), this.heartbeatMs);
    heartbeat.unref();
    this.feed.start();
    return true;
  }

  close(): void { for (const close of this.sessions) close(); }
}
