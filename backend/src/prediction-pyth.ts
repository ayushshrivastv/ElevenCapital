import { Decimal } from 'decimal.js';

/** The Hermes credential stays on this server; consumers receive only normalized price data. */
export const PYTH_HERMES_URL = 'https://pyth.dourolabs.app/hermes';
export const PYTH_ETH_USD_FEED_ID = 'ff61491a931112ddf1bd8147cd1b641375f79f5825126d665480874634fd0ace';
export const PYTH_BTC_USD_FEED_ID = 'e62df6c8b4a85fe1a67db44dc12de5db330f7ac66b72dc658afedf0f4a415b43';
export const PREDICTION_MAX_AGE_MS = 5_000;
export const PREDICTION_BOUNDARY_GAP_SECONDS = 5;
const MAX_RESPONSE_BYTES = 256 * 1024;
const MAX_BOUNDARY_CACHE = 256;
const MAX_BOUNDARY_REQUESTS = 8;
const ROUND_SECONDS = 90;
// Four authenticated boundaries close the three 90-second rounds before this pool.
const ROUND_SAMPLE_OFFSETS = [270, 180, 90, 0] as const;
const MAX_ROUND_CACHE = 256;
type PredictionAsset = 'eth' | 'btc';
const ASSET_CONFIG = Object.freeze({
  eth: Object.freeze({ feedId: PYTH_ETH_USD_FEED_ID, minBandUsd: new Decimal('0.05'),
    maxBandUsd: new Decimal('4.00'), maxForecastSigmaUsd: new Decimal('8.00'),
    maxAnchorConfidenceUsd: new Decimal('2.00') }),
  btc: Object.freeze({ feedId: PYTH_BTC_USD_FEED_ID, minBandUsd: new Decimal('0.01'),
    maxBandUsd: new Decimal('100.00'), maxForecastSigmaUsd: new Decimal('240.00'),
    maxAnchorConfidenceUsd: new Decimal('40.00') }),
});
const MIN_NINETY_SECOND_VOLATILITY_FRACTION = new Decimal('0.0001');
const RANGE_FRACTION = new Decimal('0.75');
const TYPICAL_MOVE_WEIGHT = new Decimal('0.60');
const RECENT_MOVE_WEIGHT = new Decimal('0.40');

export type PredictionQuote = Readonly<{ priceUsd: string; confidenceUsd: string; publishTime: number }>;
export type PredictionSnapshot = Readonly<{ schemaVersion: 1; status: 'live' | 'stale' | 'unavailable' | 'unconfigured';
  serverTimeMs: number; feedId: string; quote: PredictionQuote | null }>;
export type PredictionBoundary = Readonly<{ schemaVersion: 1; feedId: string; targetTimestamp: number;
  quote: PredictionQuote; updateData: string[] }>;
export type PredictionRound = Readonly<{ schemaVersion: 1; feedId: string; poolId: number;
  bandUsd: string; forecastSigmaUsd: string; quote: PredictionQuote }>;
export interface PredictionPriceService {
  snapshot(): Promise<PredictionSnapshot>;
  subscribe(listener: (snapshot: PredictionSnapshot) => void): () => void;
  atTimestamp(targetTimestamp: number): Promise<PredictionBoundary>;
  round(poolId: number): Promise<PredictionRound>;
  close(): void;
}
export class PredictionPriceError extends Error {
  constructor(readonly code: string, readonly statusCode: 400 | 429 | 503 = 503) {
    super(statusCode === 400 ? 'Prediction price timestamp is invalid.' : 'Prediction price is temporarily unavailable.');
  }
}

type Fetch = typeof globalThis.fetch;
type DecodedUpdate = { quote: PredictionQuote; previousPublishTime?: number; updateData?: string[] };
function record(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new PredictionPriceError('invalid_pyth_response');
  return value as Record<string, unknown>;
}
function timestamp(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value <= 0 || value > 0xffff_ffff) {
    throw new PredictionPriceError('invalid_pyth_response');
  }
  return value;
}
function decimal(integer: string, exponent: number): string {
  if (exponent === 0) return integer;
  const digits = integer.padStart(1 - exponent, '0');
  return `${digits.slice(0, exponent)}.${digits.slice(exponent)}`.replace(/\.?0+$/, '') || '0';
}

/** Preserve Pyth integer precision; a stream's EMA, another feed, or a future update can never become this asset's spot price. */
export function decodePythUpdate(value: unknown, nowMs: number, includeBinary = false,
  feedId: string = PYTH_ETH_USD_FEED_ID): DecodedUpdate {
  const body = record(value);
  if (!Array.isArray(body.parsed) || body.parsed.length !== 1) throw new PredictionPriceError('invalid_pyth_response');
  const feed = record(body.parsed[0]);
  if (typeof feed.id !== 'string' || feed.id.replace(/^0x/, '').toLowerCase() !== feedId) {
    throw new PredictionPriceError('wrong_pyth_feed');
  }
  const price = record(feed.price);
  if (typeof price.price !== 'string' || !/^[1-9][0-9]{0,18}$/.test(price.price) || BigInt(price.price) > (1n << 63n) - 1n ||
    typeof price.conf !== 'string' || !/^(0|[1-9][0-9]{0,19})$/.test(price.conf) || BigInt(price.conf) > (1n << 64n) - 1n ||
    typeof price.expo !== 'number' || !Number.isInteger(price.expo) || price.expo < -18 || price.expo > 0) {
    throw new PredictionPriceError('invalid_pyth_response');
  }
  const publishTime = timestamp(price.publish_time);
  if (publishTime > Math.floor(nowMs / 1_000)) throw new PredictionPriceError('future_pyth_price');
  const quote = Object.freeze({ priceUsd: decimal(price.price, price.expo), confidenceUsd: decimal(price.conf, price.expo), publishTime });
  let previousPublishTime: number | undefined;
  if (feed.metadata !== undefined) {
    const metadata = record(feed.metadata);
    if (metadata.prev_publish_time !== undefined) {
      previousPublishTime = timestamp(metadata.prev_publish_time);
      if (previousPublishTime >= publishTime) throw new PredictionPriceError('invalid_pyth_response');
    }
  }
  if (!includeBinary) return { quote, previousPublishTime };
  const binary = record(body.binary);
  if (binary.encoding !== 'hex' || !Array.isArray(binary.data) || binary.data.length !== 1) {
    throw new PredictionPriceError('invalid_pyth_update_data');
  }
  let bytes = 0;
  const updateData = binary.data.map((item: unknown) => {
    if (typeof item !== 'string') throw new PredictionPriceError('invalid_pyth_update_data');
    const hex = item.replace(/^0x/, '');
    if (!/^(?:[0-9a-fA-F]{2})+$/.test(hex) || (bytes += hex.length / 2) > MAX_RESPONSE_BYTES / 2) {
      throw new PredictionPriceError('invalid_pyth_update_data');
    }
    return `0x${hex}`;
  });
  return { quote, previousPublishTime, updateData };
}

async function readJson(response: Response): Promise<unknown> {
  if (!response.ok || !response.body) throw new PredictionPriceError('pyth_upstream_unavailable');
  const length = response.headers.get('content-length');
  if (length !== null && Number(length) > MAX_RESPONSE_BYTES) throw new PredictionPriceError('invalid_pyth_response');
  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8', { fatal: true });
  let size = 0, body = '';
  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      size += chunk.value.byteLength;
      if (size > MAX_RESPONSE_BYTES) throw new PredictionPriceError('invalid_pyth_response');
      body += decoder.decode(chunk.value, { stream: true });
    }
    body += decoder.decode();
    return JSON.parse(body);
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
}

/** Incremental SSE parser with bounded frames; comments and CRLF/chunk boundaries follow the SSE grammar. */
export async function consumePythSse(response: Response, accept: (data: unknown) => void, signal: AbortSignal): Promise<void> {
  if (!response.ok || !response.body || !response.headers.get('content-type')?.toLowerCase().startsWith('text/event-stream')) {
    throw new PredictionPriceError('pyth_upstream_unavailable');
  }
  const reader = response.body.getReader(), decoder = new TextDecoder('utf-8', { fatal: true });
  let pending = '', data: string[] = [], frameSize = 0;
  const abort = () => { void reader.cancel().catch(() => {}); };
  signal.addEventListener('abort', abort, { once: true });
  try {
    while (!signal.aborted) {
      const chunk = await reader.read();
      if (chunk.done) break;
      pending += decoder.decode(chunk.value, { stream: true });
      if (pending.length > MAX_RESPONSE_BYTES) throw new PredictionPriceError('invalid_pyth_response');
      while (true) {
        const end = pending.search(/[\r\n]/);
        if (end < 0 || (pending[end] === '\r' && end === pending.length - 1)) break;
        const line = pending.slice(0, end);
        pending = pending.slice(end + (pending[end] === '\r' && pending[end + 1] === '\n' ? 2 : 1));
        if (line === '') {
          if (data.length !== 0) accept(JSON.parse(data.join('\n')));
          data = []; frameSize = 0;
        } else if (line.startsWith('data:')) {
          const text = line.slice(5).replace(/^ /, '');
          frameSize += text.length;
          if (frameSize > MAX_RESPONSE_BYTES) throw new PredictionPriceError('invalid_pyth_response');
          data.push(text);
        }
      }
    }
  } finally {
    signal.removeEventListener('abort', abort);
    await reader.cancel().catch(() => {}); reader.releaseLock();
  }
}

function delay(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise(resolve => {
    if (signal.aborted) { resolve(); return; }
    const finish = () => { clearTimeout(timer); signal.removeEventListener('abort', finish); resolve(); };
    const timer = setTimeout(finish, ms); timer.unref();
    signal.addEventListener('abort', finish, { once: true });
  });
}

/** One demand-driven Hermes stream, regardless of the number of phone clients. No key means no upstream calls. */
export class PythPredictionService implements PredictionPriceService {
  private readonly asset: Readonly<(typeof ASSET_CONFIG)[PredictionAsset]>;
  private readonly apiKey: string | undefined;
  private readonly fetch: Fetch;
  private readonly now: () => number;
  private readonly heartbeatMs: number;
  private readonly reconnectMs: number;
  private readonly idleLeaseMs: number;
  private readonly requestTimeoutMs: number;
  private readonly streamIdleMs: number;
  private readonly listeners = new Set<(snapshot: PredictionSnapshot) => void>();
  private readonly requests = new Set<AbortController>();
  private readonly boundaryCache = new Map<number, PredictionBoundary>();
  private readonly boundaryPending = new Map<number, Promise<PredictionBoundary>>();
  private readonly roundCache = new Map<number, PredictionRound>();
  private readonly roundPending = new Map<number, Promise<PredictionRound>>();
  private boundaryWindow = 0;
  private boundaryCount = 0;
  private quote: PredictionQuote | null = null;
  private available = false;
  private stopped = false;
  private leaseUntil = 0;
  private heartbeat?: ReturnType<typeof setInterval>;
  private stream?: AbortController;
  private streamRunning = false;
  private latestPending?: Promise<void>;
  private lastLatestAttempt = -Infinity;

  constructor(options: { asset?: PredictionAsset; apiKey?: string; fetch?: Fetch; now?: () => number; heartbeatMs?: number;
    reconnectMs?: number; idleLeaseMs?: number; requestTimeoutMs?: number; streamIdleMs?: number } = {}) {
    this.asset = ASSET_CONFIG[options.asset ?? 'eth'];
    // Configuration is intentionally captured once, so adding/rotating a key requires restarting the backend.
    this.apiKey = Object.hasOwn(options, 'apiKey') ? options.apiKey : process.env.PYTH_API_KEY;
    if (this.apiKey !== undefined && (!this.apiKey.length || this.apiKey.length > 2_048 ||
      this.apiKey.trim() !== this.apiKey || /[\u0000-\u001f\u007f]/.test(this.apiKey))) {
      throw new Error('Pyth API key configuration is invalid.');
    }
    this.fetch = options.fetch ?? globalThis.fetch;
    this.now = options.now ?? Date.now;
    this.heartbeatMs = options.heartbeatMs ?? 1_000;
    this.reconnectMs = options.reconnectMs ?? 1_000;
    this.idleLeaseMs = options.idleLeaseMs ?? 15_000;
    this.requestTimeoutMs = options.requestTimeoutMs ?? 5_000;
    this.streamIdleMs = options.streamIdleMs ?? 15_000;
  }

  async snapshot(): Promise<PredictionSnapshot> {
    if (this.stopped || !this.apiKey) return this.current();
    this.leaseUntil = this.now() + this.idleLeaseMs;
    this.start();
    if (this.current().status !== 'live' && this.now() - this.lastLatestAttempt >= 2_000) {
      this.lastLatestAttempt = this.now();
      this.latestPending ??= this.latest().finally(() => { this.latestPending = undefined; });
    }
    if (this.latestPending) await this.latestPending;
    return this.current();
  }

  subscribe(listener: (snapshot: PredictionSnapshot) => void): () => void {
    this.listeners.add(listener);
    try { listener(this.current()); } catch { this.listeners.delete(listener); }
    this.start();
    return () => { this.listeners.delete(listener); this.stopIfIdle(); };
  }

  atTimestamp(targetTimestamp: number): Promise<PredictionBoundary> {
    if (!Number.isSafeInteger(targetTimestamp) || targetTimestamp <= 0 || targetTimestamp > 0xffff_ffff) {
      return Promise.reject(new PredictionPriceError('invalid_timestamp', 400));
    }
    if (!this.apiKey) return Promise.reject(new PredictionPriceError('pyth_unconfigured'));
    if (this.stopped) return Promise.reject(new PredictionPriceError('pyth_unavailable'));
    const now = Math.floor(this.now() / 1_000);
    if (targetTimestamp > now) return Promise.reject(new PredictionPriceError('pyth_target_not_ready'));
    const cached = this.boundaryCache.get(targetTimestamp);
    if (cached) return Promise.resolve(cached);
    const pending = this.boundaryPending.get(targetTimestamp);
    if (pending) return pending;
    if (targetTimestamp < now - 86_400) return Promise.reject(new PredictionPriceError('pyth_target_outside_retention'));
    if (this.boundaryPending.size >= MAX_BOUNDARY_REQUESTS) return Promise.reject(new PredictionPriceError('pyth_busy', 429));
    const window = Math.floor(this.now() / 60_000);
    if (window !== this.boundaryWindow) { this.boundaryWindow = window; this.boundaryCount = 0; }
    if (++this.boundaryCount > 30) return Promise.reject(new PredictionPriceError('pyth_rate_limited', 429));
    const request = this.boundary(targetTimestamp).finally(() => { this.boundaryPending.delete(targetTimestamp); });
    this.boundaryPending.set(targetTimestamp, request);
    return request;
  }

  /** Lock the next range from the three preceding complete 90-second moves. */
  round(poolId: number): Promise<PredictionRound> {
    const start = poolId * ROUND_SECONDS;
    if (!Number.isSafeInteger(poolId) || poolId <= 0 || !Number.isSafeInteger(start) ||
      start <= ROUND_SAMPLE_OFFSETS[0] || start > 0xffff_ffff) {
      return Promise.reject(new PredictionPriceError('invalid_pool_id', 400));
    }
    if (!this.apiKey) return Promise.reject(new PredictionPriceError('pyth_unconfigured'));
    if (this.stopped) return Promise.reject(new PredictionPriceError('pyth_unavailable'));
    const now = Math.floor(this.now() / 1_000);
    if (start > now) return Promise.reject(new PredictionPriceError('pyth_target_not_ready'));
    if (start - ROUND_SAMPLE_OFFSETS[0] < now - 86_400) {
      return Promise.reject(new PredictionPriceError('pyth_target_outside_retention'));
    }
    const cached = this.roundCache.get(poolId);
    if (cached) return Promise.resolve(cached);
    const pending = this.roundPending.get(poolId);
    if (pending) return pending;
    // Three completed pools and this pool's shared opening observation fix all four boundaries.
    // Requests for the same pool share one calculation, including under concurrent app clients.
    if (this.roundPending.size >= 1) return Promise.reject(new PredictionPriceError('pyth_busy', 429));
    const request = this.computeRound(poolId, start).finally(() => { this.roundPending.delete(poolId); });
    this.roundPending.set(poolId, request);
    return request;
  }

  private async computeRound(poolId: number, start: number): Promise<PredictionRound> {
    // Fixed epoch boundaries prevent a late joiner from changing the range.
    const samples = await Promise.all(ROUND_SAMPLE_OFFSETS.map(offset => this.atTimestamp(start - offset)));
    const prices = samples.map(sample => new Decimal(sample.quote.priceUsd));
    const confidences = samples.map(sample => new Decimal(sample.quote.confidenceUsd));
    const moves = prices.slice(1).map((price, index) => price.minus(prices[index]!));
    const absoluteMoves = moves.map(move => move.abs()).sort((a, b) => a.comparedTo(b));
    const typicalMove = absoluteMoves[1]!;
    const recentMove = moves[2]!.abs();
    // Median movement limits one old outlier; the most recent completed round still matters.
    // Three-quarter width targets a range that can be crossed in normal conditions.
    const adaptiveMove = typicalMove.times(TYPICAL_MOVE_WEIGHT).plus(recentMove.times(RECENT_MOVE_WEIGHT));
    const meanMove = Decimal.sum(...moves).div(moves.length);
    const sampleVariance = Decimal.sum(...moves.map(move => move.minus(meanMove).pow(2)))
      .div(moves.length - 1);
    const maxConfidence = Decimal.max(...confidences);
    // Odds uncertainty is separate from the settlement width. It may exceed the band.
    const observedSigma = Decimal.max(prices.at(-1)!.times(MIN_NINETY_SECOND_VOLATILITY_FRACTION),
      typicalMove.div('0.67449'), sampleVariance.sqrt(), maxConfidence.times(3));
    const isBtc = this.asset.feedId === PYTH_BTC_USD_FEED_ID;
    const adaptiveBand = Decimal.max(this.asset.minBandUsd, adaptiveMove.times(RANGE_FRACTION));
    // BTC follows the preceding three round moves, with a cent floor and $100 ceiling.
    // Confidence informs odds, but does not put an otherwise active BTC round in Watch-only mode.
    const requiredBand = isBtc ? Decimal.min(adaptiveBand, this.asset.maxBandUsd)
      : Decimal.max(adaptiveBand, maxConfidence.times(2));
    if (!isBtc && requiredBand.gt(this.asset.maxBandUsd)) throw new PredictionPriceError('pyth_round_unavailable');
    const band = requiredBand.toDecimalPlaces(2, Decimal.ROUND_CEIL);
    // Keep the opening Range quote below one half, including at the width floor.
    const estimatedSigma = Decimal.max(observedSigma, band.times('1.5'));
    // BTC odds retain a bounded sigma input even if an earlier round moved sharply.
    const forecastSigma = isBtc ? Decimal.min(estimatedSigma, this.asset.maxForecastSigmaUsd) : estimatedSigma;
    if (!isBtc && forecastSigma.gt(this.asset.maxForecastSigmaUsd)) throw new PredictionPriceError('pyth_round_unavailable');
    const anchor = samples.at(-1)!;
    const anchorConfidence = new Decimal(anchor.quote.confidenceUsd);
    const anchorPrice = new Decimal(anchor.quote.priceUsd);
    if ((!isBtc && (anchorConfidence.gt(this.asset.maxAnchorConfidenceUsd) || anchorConfidence.times(2).gt(band))) ||
      anchorPrice.lte(band)) {
      throw new PredictionPriceError('pyth_round_unavailable');
    }
    const result: PredictionRound = Object.freeze({ schemaVersion: 1, feedId: this.asset.feedId, poolId,
      bandUsd: band.toFixed(2), forecastSigmaUsd: forecastSigma.toDecimalPlaces(6, Decimal.ROUND_CEIL).toFixed(6),
      quote: anchor.quote });
    if (this.roundCache.size >= MAX_ROUND_CACHE) this.roundCache.delete(this.roundCache.keys().next().value!);
    this.roundCache.set(poolId, result);
    return result;
  }

  close(): void {
    this.stopped = true;
    this.listeners.clear();
    this.stream?.abort();
    for (const controller of this.requests) controller.abort();
    if (this.heartbeat) clearInterval(this.heartbeat);
    this.heartbeat = undefined;
  }

  private current(): PredictionSnapshot {
    const now = this.now();
    const age = this.quote === null ? Infinity : now - this.quote.publishTime * 1_000;
    const status = !this.apiKey ? 'unconfigured' : this.stopped || !this.quote ? 'unavailable'
      : age < 0 || age > PREDICTION_MAX_AGE_MS ? 'stale' : this.available ? 'live' : 'unavailable';
    return Object.freeze({ schemaVersion: 1, status, serverTimeMs: now, feedId: this.asset.feedId, quote: this.quote });
  }

  private emit(): void {
    const snapshot = this.current();
    for (const listener of this.listeners) {
      try { listener(snapshot); } catch { this.listeners.delete(listener); }
    }
  }

  private accept(decoded: DecodedUpdate): boolean {
    if (this.quote !== null && decoded.quote.publishTime < this.quote.publishTime) return false;
    if (this.quote?.publishTime === decoded.quote.publishTime &&
      (this.quote.priceUsd !== decoded.quote.priceUsd || this.quote.confidenceUsd !== decoded.quote.confidenceUsd)) return false;
    this.quote = decoded.quote;
    this.available = true;
    this.emit();
    return true;
  }

  private demanded(): boolean { return !this.stopped && (this.listeners.size > 0 || this.now() < this.leaseUntil); }

  private start(): void {
    if (!this.apiKey || !this.demanded()) return;
    if (!this.heartbeat) {
      this.heartbeat = setInterval(() => { this.emit(); this.stopIfIdle(); }, this.heartbeatMs);
      this.heartbeat.unref();
    }
    if (this.streamRunning) return;
    this.streamRunning = true;
    this.stream = new AbortController();
    void this.streamLoop(this.stream.signal).finally(() => {
      this.streamRunning = false;
      this.stream = undefined;
      if (this.demanded()) this.start();
    });
  }

  private stopIfIdle(): void {
    if (this.demanded()) return;
    this.stream?.abort();
    this.available = false;
    if (this.heartbeat) clearInterval(this.heartbeat);
    this.heartbeat = undefined;
  }

  private url(path: string): string {
    const url = new URL(`${PYTH_HERMES_URL}/v2/updates/price/${path}`);
    url.searchParams.set('ids[]', this.asset.feedId);
    url.searchParams.set('parsed', 'true');
    url.searchParams.set('encoding', 'hex');
    return url.toString();
  }

  private async json(path: string): Promise<unknown> {
    const controller = new AbortController();
    this.requests.add(controller);
    const timer = setTimeout(() => controller.abort(), this.requestTimeoutMs); timer.unref();
    try {
      const response = await this.fetch(this.url(path), { headers: { Authorization: `Bearer ${this.apiKey}`, Accept: 'application/json' },
        redirect: 'error', signal: controller.signal });
      return await readJson(response);
    } catch (failure) {
      // Upstream body, request headers, URLs, and transport error messages never cross this boundary.
      if (failure instanceof PredictionPriceError) throw failure;
      throw new PredictionPriceError('pyth_upstream_unavailable');
    } finally { clearTimeout(timer); this.requests.delete(controller); controller.abort(); }
  }

  private async latest(): Promise<void> {
    try { this.accept(decodePythUpdate(await this.json('latest'), this.now(), false, this.asset.feedId)); }
    catch { if (this.quote === null) this.available = false; this.emit(); }
  }

  private async boundary(targetTimestamp: number): Promise<PredictionBoundary> {
    const parsed = decodePythUpdate(await this.json(String(targetTimestamp)), this.now(), true, this.asset.feedId);
    if (parsed.quote.publishTime < targetTimestamp || parsed.quote.publishTime > targetTimestamp + PREDICTION_BOUNDARY_GAP_SECONDS ||
      parsed.previousPublishTime === undefined || parsed.previousPublishTime >= targetTimestamp) {
      throw new PredictionPriceError('pyth_boundary_unavailable');
    }
    const result: PredictionBoundary = Object.freeze({ schemaVersion: 1, feedId: this.asset.feedId, targetTimestamp,
      quote: parsed.quote, updateData: parsed.updateData! });
    if (this.boundaryCache.size >= MAX_BOUNDARY_CACHE) this.boundaryCache.delete(this.boundaryCache.keys().next().value!);
    this.boundaryCache.set(targetTimestamp, result);
    return result;
  }

  private async streamLoop(signal: AbortSignal): Promise<void> {
    let failures = 0;
    while (this.demanded() && !signal.aborted) {
      const controller = new AbortController();
      const abort = () => controller.abort();
      signal.addEventListener('abort', abort, { once: true });
      let lastValid = this.now();
      const headerTimer = setTimeout(abort, this.requestTimeoutMs); headerTimer.unref();
      const watchdog = setInterval(() => { if (this.now() - lastValid > this.streamIdleMs) controller.abort(); }, Math.min(this.streamIdleMs, 1_000));
      watchdog.unref();
      try {
        const response = await this.fetch(this.url('stream'), { headers: { Authorization: `Bearer ${this.apiKey}`, Accept: 'text/event-stream' },
          redirect: 'error', signal: controller.signal });
        clearTimeout(headerTimer);
        await consumePythSse(response, data => {
          if (this.accept(decodePythUpdate(data, this.now(), false, this.asset.feedId))) { lastValid = this.now(); failures = 0; }
        }, controller.signal);
      } catch { /* The sanitized snapshot communicates outages; reconnect never fabricates a price. */ }
      finally {
        clearTimeout(headerTimer); clearInterval(watchdog); signal.removeEventListener('abort', abort); controller.abort();
      }
      if (!this.demanded() || signal.aborted) break;
      this.available = false; this.emit();
      const wait = Math.min(30_000, this.reconnectMs * 2 ** Math.min(failures++, 5));
      await delay(wait, signal);
    }
  }
}
