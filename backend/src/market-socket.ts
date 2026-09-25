import { randomUUID } from 'node:crypto';
import type { Server } from 'node:http';
import type { Duplex } from 'node:stream';
import WebSocket, { WebSocketServer } from 'ws';
import { z } from 'zod';
import { Range, type Catalog, type Chart, type ChartRange, type Stock } from './schema.js';
import { RealtimeCatalog } from './realtime.js';
import type { CatalogPatch } from './market-update.js';

const Id = z.string().min(3).max(120).regex(/^(backed|backpack|prestocks):[A-Za-z0-9.\-]+$/);
const Subscription = z.object({ type: z.literal('subscribe'), ids: z.array(Id).max(100),
  detail: z.object({ id: Id, range: Range }).strict().nullable().optional() }).strict();
const LIMIT = 16 * 1024 * 1024;
type Detail = { id: string; range: ChartRange };
type Client = { socket: WebSocket; ids: Set<string>; detail?: Detail; chart?: Chart; chartPending: boolean;
  chartGeneration: number; chartLoadedAt: number; alive: boolean; hasSnapshot: boolean };
export interface SocketMarketService {
  chart(id: string, range: ChartRange): Promise<Chart>;
  setInterest?(ids: Iterable<string>): void;
  withCurrent?(chart: Chart): Chart;
}

/** A token-priced listing may carry verified token history or an explicitly labeled share reference. */
export function chartMatchesStock(chart: Chart, stock: Stock): boolean {
  if (chart.stockId !== stock.id) return false;
  if (chart.basis === stock.quote.basis && chart.currency === stock.quote.currency) return true;
  if ((stock.provider === 'backed' || stock.provider === 'prestocks') && chart.basis === 'onchain_token_market' &&
    chart.currency === 'USD' && stock.deployments.some(deployment => deployment.network.toLowerCase() === 'solana')) return true;
  return stock.provider === 'backed' && chart.basis === 'underlying_share_reference' &&
    (chart.currency === 'USD' || chart.currency === 'USDC') &&
    stock.underlying.listingCountry === 'US' && stock.underlying.currency === 'USD' && !!stock.underlying.isin;
}

/** One ordered catalog and one connection per app; no socket or request per row. */
export class MarketSocket {
  readonly sessionId = randomUUID();
  private revision = 0;
  private latest?: Catalog;
  private readonly latestById = new Map<string, Stock>();
  private readonly clients = new Set<Client>();
  private readonly latencySamples: number[] = [];
  private readonly server = new WebSocketServer({ noServer: true, maxPayload: 32_768,
    perMessageDeflate: { threshold: 2_048, serverNoContextTakeover: true, clientNoContextTakeover: true, concurrencyLimit: 2 } });
  private readonly unsubscribe: () => void;
  private readonly heartbeat: ReturnType<typeof setInterval>;
  readonly metrics = { connections: 0, messages: 0, rejectedMessages: 0, deltas: 0, snapshots: 0,
    publicationToWsEnqueueLastMs: null as number | null, publicationToWsEnqueueMaxMs: null as number | null,
    publicationToWsEnqueueP95Ms: null as number | null, publicationToWsEnqueueP99Ms: null as number | null,
    publicationToWsEnqueueSamples: 0 };
  constructor(private readonly httpServer: Server, private readonly feed: RealtimeCatalog,
    private readonly service: SocketMarketService, private readonly now = Date.now,
    private readonly monotonicNow = () => performance.now()) {
    httpServer.on('upgrade', this.upgrade);
    this.unsubscribe = feed.subscribe(event => {
      if (event.event === 'status') { for (const client of this.clients) this.send(client, { type: 'status', ...event.data }); return; }
      const patch = event.patch;
      // A full copy is only needed for an unannotated authoritative refresh.
      // Incremental publications consult the existing index directly.
      const previous = patch ? undefined : new Map(this.latestById);
      this.latest = event.data; this.revision++;
      const membershipChanged = !patch || patch.membershipChanged ||
        patch.removedIds.some(id => this.latestById.has(id)) || patch.stocks.some(stock => !this.latestById.has(stock.id));
      if (membershipChanged) {
        this.reindex(event.data);
        for (const client of this.clients) {
          const detailChanged = this.detailChanged(client, previous, this.latestById, patch);
          this.snapshot(client, patch?.requestedAt);
          if (detailChanged) this.updateChart(client, true);
        }
        this.interests();
        return;
      }
      this.applyPatch(patch);
      const detailChanges = new Set([...patch.changedIds, ...patch.removedIds]);
      for (const client of this.clients) {
        if (!client.hasSnapshot) { this.snapshot(client); continue; }
        // Every connected catalog receives every accepted changed row immediately.
        // Viewport IDs prioritize upstream collection; they never delay delivery of
        // a change that the backend has already accepted.
        this.delta(client, patch.stocks, patch.removedIds, patch.requestedAt);
        if (client.detail && detailChanges.has(client.detail.id)) this.updateChart(client, true);
      }
    });
    this.heartbeat = setInterval(() => {
      for (const client of this.clients) {
        if (!client.alive) { client.socket.terminate(); continue; }
        client.alive = false; client.socket.ping();
        this.send(client, { type: 'heartbeat', serverTime: new Date(this.now()).toISOString() });
      }
    }, 10_000);
    this.heartbeat.unref();
  }
  private upgrade = (request: import('node:http').IncomingMessage, socket: Duplex, head: Buffer) => {
    if (request.url !== '/v1/market/ws') { socket.destroy(); return; }
    if (this.clients.size >= 32) { socket.end('HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n'); return; }
    this.server.handleUpgrade(request, socket, head, connection => this.attach(connection));
  };
  private attach(socket: WebSocket) {
    const client: Client = { socket, ids: new Set(), chartPending: false, chartGeneration: 0, chartLoadedAt: 0,
      alive: true, hasSnapshot: false };
    this.clients.add(client); this.metrics.connections = this.clients.size;
    socket.on('pong', () => { client.alive = true; });
    socket.on('error', () => {});
    socket.on('close', () => { this.clients.delete(client); this.metrics.connections = this.clients.size; this.interests(); });
    socket.on('message', (data, binary) => {
      try {
        if (binary) throw new Error('Text only');
        const message = Subscription.parse(JSON.parse(data.toString()));
        this.metrics.messages++;
        client.ids = new Set(message.ids);
        const detail = message.detail ?? undefined;
        const detailSelectionChanged = JSON.stringify(detail) !== JSON.stringify(client.detail);
        if (detailSelectionChanged) {
          client.chartGeneration++; client.chart = undefined; client.chartLoadedAt = 0; client.chartPending = false;
        }
        client.detail = detail;
        if (detail) client.ids.add(detail.id);
        this.interests(); this.revision++;
        const current = [...client.ids].flatMap(id => this.latestById.get(id) ?? []);
        this.delta(client, current, []);
        if (detail && (detailSelectionChanged || (!client.chart && !client.chartPending))) {
          this.updateChart(client, detailSelectionChanged);
        }
      } catch { this.metrics.rejectedMessages++; socket.close(1008, 'Invalid market subscription'); }
    });
    this.feed.start();
    if (this.latest) this.snapshot(client); else this.send(client, { type: 'status', state: 'loading', message: 'Loading provider catalogs.' });
  }
  private send(client: Client, value: unknown, smallDelta = false): boolean {
    if (client.socket.readyState !== WebSocket.OPEN) return false;
    const text = JSON.stringify(value), bytes = Buffer.byteLength(text);
    if (bytes > LIMIT || client.socket.bufferedAmount + bytes > LIMIT) {
      client.socket.close(1013, 'Slow market consumer; reconnect for snapshot'); return false;
    }
    // Deflate bookkeeping costs more than it saves for small, high-rate deltas.
    // Large deltas and snapshots retain negotiated compression.
    if (smallDelta && bytes < 2_048) client.socket.send(text, { compress: false });
    else client.socket.send(text);
    return true;
  }
  private snapshot(client: Client, requestedAt?: number) {
    if (!this.latest) return;
    client.hasSnapshot = true;
    this.metrics.snapshots++;
    const enqueued = this.send(client, { type: 'snapshot', sessionId: this.sessionId, revision: this.revision, catalog: this.latest });
    if (enqueued && requestedAt !== undefined) this.recordLatency(requestedAt);
  }
  private delta(client: Client, stocks: readonly Stock[], removedIds: readonly string[], requestedAt?: number) {
    if (!this.latest) return;
    // Provider state and timestamps can change even when this viewport has no price tick.
    this.metrics.deltas++;
    const enqueued = this.send(client, { type: 'delta', sessionId: this.sessionId, revision: this.revision,
      receivedAt: this.latest.receivedAt, stocks, providers: this.latest.providers, removedIds }, true);
    if (enqueued && requestedAt !== undefined && (stocks.length > 0 || removedIds.length > 0)) this.recordLatency(requestedAt);
  }
  private reindex(catalog: Catalog) {
    this.latestById.clear();
    for (const stock of catalog.stocks) this.latestById.set(stock.id, stock);
  }
  private applyPatch(patch: CatalogPatch) {
    for (const id of patch.removedIds) this.latestById.delete(id);
    for (const stock of patch.stocks) this.latestById.set(stock.id, stock);
  }
  private detailChanged(client: Client, before: ReadonlyMap<string, Stock> | undefined, after: ReadonlyMap<string, Stock>,
    patch?: CatalogPatch): boolean {
    const id = client.detail?.id;
    if (!id) return false;
    if (patch) return patch.changedIds.includes(id) || patch.removedIds.includes(id);
    return JSON.stringify(before?.get(id)) !== JSON.stringify(after.get(id));
  }
  private recordLatency(requestedAt: number) {
    const elapsed = this.monotonicNow() - requestedAt;
    if (!Number.isFinite(elapsed) || elapsed < 0) return;
    const value = Math.round(elapsed * 1_000) / 1_000;
    this.latencySamples.push(value);
    if (this.latencySamples.length > 512) this.latencySamples.shift();
    const ordered = [...this.latencySamples].sort((a, b) => a - b);
    const p95 = ordered[Math.max(0, Math.ceil(ordered.length * 0.95) - 1)]!;
    const p99 = ordered[Math.max(0, Math.ceil(ordered.length * 0.99) - 1)]!;
    this.metrics.publicationToWsEnqueueLastMs = value;
    this.metrics.publicationToWsEnqueueMaxMs = this.metrics.publicationToWsEnqueueMaxMs === null
      ? value : Math.max(this.metrics.publicationToWsEnqueueMaxMs, value);
    this.metrics.publicationToWsEnqueueP95Ms = p95;
    this.metrics.publicationToWsEnqueueP99Ms = p99;
    this.metrics.publicationToWsEnqueueSamples++;
  }
  private interests() {
    const ids = new Set<string>();
    for (const client of this.clients) for (const id of client.ids) ids.add(id);
    this.service.setInterest?.(ids);
  }
  private updateChart(client: Client, detailChanged: boolean) {
    const detail = client.detail; if (!detail) return;
    const stock = this.latestById.get(detail.id);
    if (!stock) {
      client.chartGeneration++; client.chart = undefined; client.chartLoadedAt = 0; client.chartPending = false;
      return;
    }
    if (client.chart && !chartMatchesStock(client.chart, stock)) {
      client.chartGeneration++; client.chart = undefined; client.chartLoadedAt = 0; client.chartPending = false;
    }
    if (client.chart && detailChanged) this.sendChart(client, client.chart);
    if (client.chartPending || (client.chartLoadedAt && this.now() - client.chartLoadedAt < 30_000)) return;
    client.chartPending = true;
    const generation = client.chartGeneration;
    void this.service.chart(detail.id, detail.range).then(chart => {
      if (!this.clients.has(client) || client.chartGeneration !== generation) return;
      client.chart = chart; client.chartLoadedAt = this.now(); this.revision++; this.sendChart(client, chart);
    }).catch(() => {
      if (this.clients.has(client) && client.chartGeneration === generation) this.send(client, { type: 'status', state: 'reconnecting', message: 'Price history is refreshing automatically.' });
    }).finally(() => { if (client.chartGeneration === generation) client.chartPending = false; });
  }
  private sendChart(client: Client, chart: Chart) {
    this.send(client, { type: 'chart', sessionId: this.sessionId, revision: this.revision,
      chart: this.service.withCurrent?.(chart) ?? chart });
  }
  close() {
    this.unsubscribe(); clearInterval(this.heartbeat);
    this.httpServer.removeListener('upgrade', this.upgrade);
    for (const client of this.clients) client.socket.terminate();
    this.clients.clear(); this.service.setInterest?.([]); this.server.close();
  }
}
