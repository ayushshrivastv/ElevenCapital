import { AsyncCache } from './cache.js';
import { performance } from 'node:perf_hooks';
import { getJson, type GetJson } from './http.js';
import { loadBacked, loadBackpack, loadPreStocks, type ProviderSnapshot } from './providers.js';
import { solanaMint, TokenStatisticsService } from './statistics.js';
import { ProviderIds, StockSchema, ChartSchema, type Catalog, type Chart, type ChartRange, type Provider, type Stock } from './schema.js';
import { candleRequest, normalizeCandles, UnknownStockError } from './service.js';
import { applyExternalTicker, BackpackLive } from './backpack-live.js';
import { backpackActivity, nonnegativeAmount } from './activity.js';
import { Decimal } from 'decimal.js';
import { applyShareReference, findShareReference } from './share-reference.js';
import { applyUnderlyingReference, normalizeUnderlyingReference, resolveUnderlyingSymbol, underlyingReferenceChartPoints,
  type UnderlyingReference } from './underlying-reference.js';
import type { CatalogMutation } from './market-update.js';
import { TokenHistoryService } from './token-history.js';
const Money = Decimal.clone({ precision: 128 });
const PUBLICATION_INTERVAL_MS = 16;
const PUBLICATION_LATENCY_SAMPLE_LIMIT = 256;
const BACKGROUND_ANALYTICS_LIMIT = 20;
const MIN_LOCAL_HISTORY_COVERAGE = 0.9;

/** Server-observed quotes are history only after they cover the selected time window. */
export function locallyObservedHistoryCoversRange(points: Chart['points'], range: ChartRange, now: number): boolean {
  if (points.length < 3 || !Number.isFinite(now)) return false;
  const since = Number(candleRequest(range, now).startTime) * 1000;
  const first = Date.parse(points[0]!.timestamp), last = Date.parse(points.at(-1)!.timestamp);
  return Number.isFinite(first) && Number.isFinite(last) && first >= since && last <= now + 5_000 &&
    last - first >= (now - since) * MIN_LOCAL_HISTORY_COVERAGE;
}

/** Select one bounded, rotating exact-mint background slice. UI interests use the separate priority worker. */
export function rotatingAnalyticsTargets(stocks: readonly Stock[], interests: ReadonlySet<string>, cursor: number,
  limit = BACKGROUND_ANALYTICS_LIMIT): { targets: Stock[]; nextCursor: number } {
  const eligible = stocks.filter(stock => !interests.has(stock.id) && solanaMint(stock.deployments) !== null)
    .sort((a, b) => a.id.localeCompare(b.id));
  if (!eligible.length || !Number.isSafeInteger(limit) || limit <= 0) return { targets: [], nextCursor: 0 };
  const start = Number.isSafeInteger(cursor) && cursor >= 0 ? cursor % eligible.length : 0;
  const count = Math.min(limit, eligible.length);
  const targets = Array.from({ length: count }, (_, index) => eligible[(start + index) % eligible.length]!);
  return { targets, nextCursor: (start + count) % eligible.length };
}
function sameInstrument(a: Stock, b: Stock): boolean {
  const first = a.underlying, second = b.underlying;
  return a.providerAssetId === b.providerAssetId && a.symbol === b.symbol &&
    first.symbol === second.symbol && first.isin === second.isin && first.cusip === second.cusip &&
    (first.listingCountry ?? null) === (second.listingCountry ?? null) && (first.currency ?? null) === (second.currency ?? null) &&
    JSON.stringify(a.deployments) === JSON.stringify(b.deployments);
}
function freshObservation(stock: Stock, now: number): boolean {
  if (stock.quote.price === null || stock.quote.receivedAt === null) return false;
  const valid = (timestamp: string) => { const age = now - Date.parse(timestamp); return Number.isFinite(age) && age >= 0 && age <= 300_000; };
  return valid(stock.quote.receivedAt) && (stock.quote.asOf === null || valid(stock.quote.asOf));
}

/** Normalize the issuer's public indicative xStock price without borrowing another instrument's fields. */
export function applyBackedIndicativePrice(stock: Stock, raw: unknown, received: number): Stock | null {
  if (stock.provider !== 'backed' || !Number.isFinite(received)) return null;
  const response = typeof raw === 'object' && raw !== null && !Array.isArray(raw) ? raw as Record<string, unknown> : {};
  const price = nonnegativeAmount(response.quote);
  if (price === null || new Money(price).lte(0)) return null;
  return { ...stock, quote: { ...stock.quote, price, currency: 'USD', changeAmount: null, changePercent: null,
    asOf: null, receivedAt: new Date(received).toISOString(), basis: 'provider_indicative_token' } };
}

/** Instrument identity, streaming quotes and slower token analytics have independent lifecycles. */
export class LiveMarketService {
  private readonly stocks = new Map<string, Stock>();
  private readonly nativeBacked = new Map<string, Stock>();
  private readonly referenceIds = new Map<string, string>();
  private readonly underlyingReferences = new Map<string, UnderlyingReference>();
  private readonly underlyingSymbols = new Map<string, string>();
  private readonly unresolvedUnderlyingSymbols = new Set<string>();
  private readonly backpackByUnderlying = new Map<string, Stock>();
  private readonly source = new Map<Provider, ProviderSnapshot>();
  private readonly sourceFailed = new Set<Provider>();
  private readonly listeners = new Set<(catalog: Catalog, mutation: CatalogMutation) => void>();
  private readonly metadataPending = new Map<Provider, Promise<void>>();
  private readonly history = new Map<string, Chart['points']>();
  private readonly wsSourceTimes = new Map<string, string>();
  private readonly chartCaches = new Map<string, AsyncCache<Chart>>();
  private readonly underlyingHistoryCaches = new Map<string, AsyncCache<UnderlyingReference | null>>();
  private readonly interests = new Set<string>();
  private readonly statistics: TokenStatisticsService;
  private readonly visibleStatistics: TokenStatisticsService;
  private readonly tokenHistory: TokenHistoryService;
  private readonly upstream: BackpackLive;
  private metadataTimer?: ReturnType<typeof setInterval>;
  private preStocksTimer?: ReturnType<typeof setInterval>;
  private metadataRetryTimer?: ReturnType<typeof setInterval>;
  private quoteTimer?: ReturnType<typeof setInterval>;
  private analyticsTimer?: ReturnType<typeof setInterval>;
  private publishTimer?: ReturnType<typeof setTimeout>;
  private readonly changedIds = new Set<string>();
  private readonly removedIds = new Set<string>();
  private membershipChanged = false;
  private publishRequestedAt?: number;
  private lastPublishedAt = Number.NEGATIVE_INFINITY;
  private readonly publicationLatencySamples: number[] = [];
  private quotePending = false;
  private backedQuotePending = false;
  private backedQuoteRequested = false;
  private underlyingReferencePending = false;
  private underlyingReferenceRequested = false;
  private underlyingReferenceCursor = 0;
  private backedQuoteCursor = 0;
  private analyticsCursor = 0;
  private analyticsPending = false;
  private visiblePending = false;
  private preStocksMetadataRequestedAt = Number.NEGATIVE_INFINITY;
  private running = false;
  readonly metrics = { metadataRefreshes: 0, quoteRefreshes: 0, quoteErrors: 0, analyticsErrors: 0, published: 0,
    invalidUpdates: 0, lastInvalidFields: [] as string[], backedQuoteRefreshes: 0, backedQuoteErrors: 0,
    lastBackedQuoteErrors: [] as string[],
    lastQuoteLatencyMs: null as number | null, lastPublicationLatencyMs: null as number | null,
    maxPublicationLatencyMs: null as number | null, p95PublicationLatencyMs: null as number | null,
    p99PublicationLatencyMs: null as number | null,
    publicationLatencySamples: 0 };
  constructor(private readonly http: GetJson = getJson, private readonly now = Date.now,
    private readonly monotonicNow = () => performance.now()) {
    this.statistics = new TokenStatisticsService(http, now, 60_000);
    this.visibleStatistics = new TokenStatisticsService(http, now, 5_000);
    this.tokenHistory = new TokenHistoryService(http, now);
    this.upstream = new BackpackLive((symbol, raw, received) => {
      const id = `backpack:${symbol.replace(/_USDC$/, '')}`;
      const stock = this.stocks.get(id);
      const watermark = this.wsSourceTimes.get(id);
      const next = stock && applyExternalTicker(watermark ? { ...stock, quote: { ...stock.quote, asOf: watermark } } : stock, raw, received);
      if (next) { this.wsSourceTimes.set(id, next.quote.asOf!); this.store(next); this.publish(); }
    }, now);
  }
  start() {
    if (this.running) return;
    this.running = true;
    for (const id of ProviderIds) void this.refreshMetadata(id);
    this.metadataTimer = setInterval(() => { for (const id of ProviderIds) void this.refreshMetadata(id); }, 300_000);
    // PreStocks has one bounded catalog/price resource and no public socket. Poll it
    // independently while exact-mint Jupiter observations remain on the fast path.
    this.preStocksTimer = setInterval(() => { void this.refreshMetadata('prestocks'); }, 30_000);
    this.metadataRetryTimer = setInterval(() => { void this.retryFailedMetadata(); }, 60_000);
    this.quoteTimer = setInterval(() => { void this.refreshQuotes(); void this.refreshBackedQuotes(); void this.refreshUnderlyingReferences(); }, 5_000);
    this.analyticsTimer = setInterval(() => { void this.refreshVisible(); void this.refreshAnalytics(); }, 5_000);
    this.metadataTimer.unref(); this.preStocksTimer.unref(); this.metadataRetryTimer.unref(); this.quoteTimer.unref(); this.analyticsTimer.unref();
  }
  stop() {
    this.running = false;
    for (const timer of [this.metadataTimer, this.preStocksTimer, this.metadataRetryTimer, this.quoteTimer, this.analyticsTimer]) if (timer) clearInterval(timer);
    if (this.publishTimer) clearTimeout(this.publishTimer);
    this.publishTimer = undefined; this.publishRequestedAt = undefined; this.lastPublishedAt = Number.NEGATIVE_INFINITY;
    this.changedIds.clear(); this.removedIds.clear(); this.membershipChanged = false;
    this.upstream.stop(); this.listeners.clear();
  }
  onUpdate(listener: (catalog: Catalog, mutation: CatalogMutation) => void) { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; }
  async catalog(): Promise<Catalog> { this.start(); return this.snapshot(); }
  snapshot(): Catalog {
    const stocks = [...this.stocks.values()];
    const providers: Catalog['providers'] = ProviderIds.map(id => {
      const rows = stocks.filter(stock => stock.provider === id);
      const available = rows.filter(stock => stock.quote.price !== null);
      const fresh = available.filter(stock => freshObservation(stock, this.now()));
      const stale = available.length - fresh.length;
      const unresolved = this.source.get(id)?.reconciliation?.unresolved.length ?? 0;
      const references = fresh.filter(stock => stock.quote.basis === 'underlying_share_reference').length;
      const coverage = id === 'backed'
        ? `${fresh.length - references} fresh token quotes; ${references} labelled share references; ${rows.length - available.length} prices unavailable.`
        : id === 'prestocks'
          ? `${fresh.filter(stock => stock.quote.basis === 'onchain_token_market').length} exact-mint market quotes; ${fresh.filter(stock => stock.quote.basis === 'provider_indicative_token').length} fresh provider-indicative token prices; ${rows.length - available.length} prices unavailable.`
          : `${fresh.length} of ${rows.length} fresh external-market references; not executable quotes.`;
      return { id, status: !rows.length ? 'unavailable' : this.sourceFailed.has(id) || stale > 0 || unresolved > 0 ? 'stale' : fresh.length ? 'ok' : 'unavailable',
        message: !rows.length ? this.sourceFailed.has(id) ? 'Catalog source unavailable; retrying automatically.' : 'Loading complete provider catalog.' :
          `${this.sourceFailed.has(id) ? 'Catalog refresh delayed; retained data is shown and retries are automatic. ' : ''}${unresolved ? `${unresolved} provider rows could not be verified and remain hidden. ` : ''}${stale ? `${stale} cached quotes are stale; updates resume automatically. ` : ''}${coverage}` };
    });
    return { schemaVersion: 1, mode: 'live-read-only', receivedAt: new Date(this.now()).toISOString(), providers, stocks };
  }
  setInterest(ids: Iterable<string>) {
    this.interests.clear();
    for (const id of ids) if (this.stocks.has(id)) this.interests.add(id);
    // A viewport change may arrive for every scroll step. Keep PreStocks metadata
    // on its documented 30-second cadence while exact-mint Jupiter enrichment
    // remains on the five-second visible path below.
    if ([...this.interests].some(id => id.startsWith('prestocks:')) &&
      this.now() - this.preStocksMetadataRequestedAt >= 30_000) void this.refreshMetadata('prestocks');
    this.syncUpstream();
    // Viewport work moves to the front of the next analytics batch. It never creates another worker.
    void this.refreshVisible();
    this.backedQuoteRequested = true;
    void this.refreshBackedQuotes();
    this.underlyingReferenceRequested = true;
    void this.refreshUnderlyingReferences();
  }
  diagnostics() {
    return { checkedAt: new Date(this.now()).toISOString(), appCount: this.stocks.size,
      sources: [...this.source].map(([provider, snapshot]) => ({ provider, ...snapshot.reconciliation,
        appCount: [...this.stocks.values()].filter(stock => stock.provider === provider).length,
        stale: this.sourceFailed.has(provider) })), metrics: { ...this.metrics, upstream: this.upstream.metrics,
        visibleSubscriptions: this.interests.size, visibleInterestIds: [...this.interests].sort() }, delivery: {
        scope: 'Catalog publication request after provider validation through WebSocket enqueue.',
        publishWindowMs: PUBLICATION_INTERVAL_MS, targetP95Ms: 50, targetP99Ms: 100,
      }, limits: {
        backed: 'Public exact-token analytics plus exact-mint GeckoTerminal pool OHLCV when available; labelled share history remains a fallback.',
        backpack: 'External reference ticker stream. RFQ-only products have no public spot order book.',
        prestocks: 'Public product snapshots, exact-mint analytics and exact-mint GeckoTerminal pool OHLCV when available; no provider WebSocket.',
      } };
  }
  private syncUpstream() {
    const ids = new Set([...this.interests].flatMap(id => id.startsWith('backpack:') ? [id] :
      this.stocks.get(id)?.quote.basis === 'underlying_share_reference' && this.referenceIds.has(id) ? [this.referenceIds.get(id)!] : []));
    this.upstream.setSymbols([...ids].map(id => `${id.slice(9)}_USDC`));
  }
  private async retryFailedMetadata(): Promise<void> {
    if (!this.running) return;
    await Promise.all([...this.sourceFailed].map(id => this.refreshMetadata(id)));
  }
  private async refreshMetadata(id: Provider): Promise<void> {
    const active = this.metadataPending.get(id); if (active) return active;
    if (id === 'prestocks') this.preStocksMetadataRequestedAt = this.now();
    const task = (async () => {
      try {
        const latest = await (id === 'backed' ? loadBacked(this.http, this.now, { metadataOnly: true, refreshClassification: true }) :
          id === 'backpack' ? loadBackpack(this.http, this.now, { refreshClassification: true, metadataTtlMs: 300_000 }) :
            loadPreStocks(this.http, this.now));
        if (!this.running) return;
        const identities = new Set(latest.stocks.map(stock => stock.id));
        for (const [key, old] of this.stocks) if (old.provider === id && !identities.has(key)) {
          this.removeFromCatalog(key); this.history.delete(key); this.wsSourceTimes.delete(key);
          this.nativeBacked.delete(key); this.referenceIds.delete(key);
          this.underlyingReferences.delete(key); this.underlyingSymbols.delete(key); this.unresolvedUnderlyingSymbols.delete(key);
          if (old.provider === 'backpack') this.backpackByUnderlying.delete(old.underlying.symbol);
          for (const cacheKey of this.chartCaches.keys()) if (cacheKey.startsWith(`${key}:`)) this.chartCaches.delete(cacheKey);
        }
        for (const item of latest.stocks) {
          const previous = this.nativeBacked.get(item.id) ?? this.stocks.get(item.id);
          const same = previous && sameInstrument(previous, item);
          if (previous && !same) {
            this.history.delete(item.id); this.wsSourceTimes.delete(item.id);
            if (previous.provider === 'backpack') this.backpackByUnderlying.delete(previous.underlying.symbol);
            for (const cacheKey of this.chartCaches.keys()) if (cacheKey.startsWith(`${item.id}:`)) this.chartCaches.delete(cacheKey);
          }
          const keepPreviousQuote = previous && previous.quote.price !== null &&
            (id !== 'prestocks' || (previous.quote.basis === 'onchain_token_market' && freshObservation(previous, this.now())));
          const merged = same ? { ...item, statistics: previous.statistics,
            quote: keepPreviousQuote ? previous.quote : item.quote,
            activity: previous.activity.volume24h !== null ? previous.activity : item.activity,
            volume24h: previous.volume24h ?? item.volume24h, volume24hBasis: previous.volume24hBasis ?? item.volume24hBasis } : item;
          this.store(merged);
        }
        this.source.set(id, latest); this.sourceFailed.delete(id); this.metrics.metadataRefreshes++;
        if (id === 'backpack') for (const native of this.nativeBacked.values()) this.store(native);
        this.publish(); void this.refreshVisible(); void this.refreshAnalytics();
        if (id === 'backed') {
          this.unresolvedUnderlyingSymbols.clear();
          void this.refreshBackedQuotes(); void this.refreshUnderlyingReferences();
        }
        if (id === 'backpack') void this.refreshQuotes();
      } catch { if (this.running) { this.sourceFailed.add(id); this.publish(); } }
    })().finally(() => this.metadataPending.delete(id));
    this.metadataPending.set(id, task); return task;
  }
  private async refreshQuotes() {
    if (!this.running || this.quotePending || !this.source.has('backpack')) return;
    this.quotePending = true;
    const started = this.now();
    try {
      const raw = await this.http('backpack', '/api/v1/tickers', { source: 'External', interval: '1d' });
      if (!Array.isArray(raw) || raw.length > 10_000) throw new Error('Invalid ticker batch');
      const duplicate = new Set<string>(); const rows = new Map<string, Record<string, unknown>>();
      for (const value of raw) if (typeof value === 'object' && value && typeof value.symbol === 'string') {
        if (rows.has(value.symbol)) duplicate.add(value.symbol);
        rows.set(value.symbol, value);
      }
      if (!this.running) return;
      const receivedAt = new Date(this.now()).toISOString();
      for (const stock of this.stocks.values()) {
        if (stock.provider !== 'backpack' || (stock.quote.receivedAt && Date.parse(stock.quote.receivedAt) >= started)) continue;
        const symbol = `${stock.providerAssetId}_USDC`, row = rows.get(symbol);
        if (!row || duplicate.has(symbol)) continue;
        const price = nonnegativeAmount(row.lastPrice);
        if (price === null || new Money(price).lte(0)) continue;
        const signed = (value: unknown) => typeof value === 'string' && /^-?\d+(?:\.\d+)?$/.test(value) && value.length < 100 ? value : null;
        const change = signed(row.priceChange), percent = signed(row.priceChangePercent);
        const activity = backpackActivity(row.quoteVolume, receivedAt);
        this.store({ ...stock, quote: { ...stock.quote, price, changeAmount: change,
          changePercent: percent === null ? null : new Money(percent).times(100).toFixed(), asOf: null, receivedAt },
          volume24h: activity.volume24h, volume24hBasis: activity.volume24h === null ? null : 'quote_currency_turnover', activity });
      }
      this.metrics.quoteRefreshes++; this.metrics.lastQuoteLatencyMs = this.now() - started; this.publish();
    } catch { this.metrics.quoteErrors++; this.publish(); } finally { this.quotePending = false; }
  }
  /**
   * xStocks exposes one cached public price endpoint per asset. Visible rows go first and one
   * rotating bounded batch refreshes the rest, so every phone shares one upstream worker.
   */
  private async refreshBackedQuotes() {
    if (!this.running || this.backedQuotePending || !this.source.has('backed')) return;
    this.backedQuoteRequested = false;
    const candidates = [...this.nativeBacked.values()].filter(stock => {
      const shown = this.stocks.get(stock.id);
      return !shown || !freshObservation(shown, this.now()) || shown.quote.basis === 'provider_indicative_token';
    }).sort((a, b) => {
      // Cold/missing rows are completed before already visible indicative quotes.
      const missingA = this.stocks.get(a.id)?.quote.price === null ? 0 : 1;
      const missingB = this.stocks.get(b.id)?.quote.price === null ? 0 : 1;
      return missingA - missingB || a.id.localeCompare(b.id);
    });
    if (!candidates.length) return;
    const visible = candidates.filter(stock => this.interests.has(stock.id));
    const background = candidates.filter(stock => !this.interests.has(stock.id));
    // Keep the queue short so a viewport change never sits behind a large cold-start batch.
    const capacity = Math.max(0, 12 - visible.length);
    const rotated = background.length ? Array.from({ length: Math.min(capacity, background.length) }, (_, index) =>
      background[(this.backedQuoteCursor + index) % background.length]!) : [];
    this.backedQuoteCursor = background.length ? (this.backedQuoteCursor + rotated.length) % background.length : 0;
    const targets = [...visible, ...rotated].slice(0, 100);
    this.backedQuotePending = true;
    try {
      let changed = 0;
      await Promise.allSettled(targets.map(async stock => {
        try {
          const raw = await this.http('backed', `/api/v2/public/assets/${encodeURIComponent(stock.symbol)}/price-data`);
          const next = applyBackedIndicativePrice(this.nativeBacked.get(stock.id) ?? stock, raw, this.now());
          if (!this.running || !next) return;
          this.store(next);
          changed++;
          // Do not make a fast quote wait for the slowest member of its batch. The
          // publisher coalesces these calls into bounded socket deltas.
          if (changed === 1 || changed % 8 === 0) this.publish();
        } catch (failure) {
          this.metrics.backedQuoteErrors++;
          const reason = failure instanceof Error ? `${failure.name}:${failure.message}` : 'UnknownError';
          this.metrics.lastBackedQuoteErrors = [...this.metrics.lastBackedQuoteErrors, `${stock.symbol}:${reason}`].slice(-8);
        }
      }));
      if (!this.running) return;
      this.metrics.backedQuoteRefreshes++;
      if (changed > 0) this.publish();
    } finally {
      this.backedQuotePending = false;
      if (this.backedQuoteRequested) {
        this.backedQuoteRequested = false;
        void this.refreshBackedQuotes();
      }
    }
  }
  private async refreshAnalytics() {
    if (!this.running || this.analyticsPending || !this.stocks.size) return;
    const canonical = [...this.stocks.values()].map(stock => this.nativeBacked.get(stock.id) ?? stock);
    const selected = rotatingAnalyticsTargets(canonical, this.interests, this.analyticsCursor);
    this.analyticsCursor = selected.nextCursor;
    if (!selected.targets.length) return;
    this.analyticsPending = true;
    try {
      await this.statistics.enrichBatches(selected.targets, batch => this.acceptAnalytics(batch));
    } catch { this.metrics.analyticsErrors++; } finally { this.analyticsPending = false; }
  }
  /**
   * The issuer publishes a current USD price but no percent, turnover or candles.
   * For rows without token-market analytics or a Backpack reference, resolve the exact
   * ISIN and attach a clearly labelled underlying-share reference from a separate feed.
   */
  private async refreshUnderlyingReferences() {
    if (!this.running || this.underlyingReferencePending || !this.source.has('backed')) return;
    this.underlyingReferenceRequested = false;
    const candidates = [...this.nativeBacked.values()].filter(stock =>
      stock.quote.basis !== 'onchain_token_market' && !this.referenceIds.has(stock.id) &&
      stock.underlying.listingCountry === 'US' && stock.underlying.currency === 'USD' &&
      !!stock.underlying.isin && !this.unresolvedUnderlyingSymbols.has(stock.id),
    ).sort((a, b) => {
      const staleA = !this.underlyingReferences.has(a.id) || this.now() - Date.parse(this.underlyingReferences.get(a.id)!.fetchedAt) > 60_000 ? 0 : 1;
      const staleB = !this.underlyingReferences.has(b.id) || this.now() - Date.parse(this.underlyingReferences.get(b.id)!.fetchedAt) > 60_000 ? 0 : 1;
      return staleA - staleB || a.id.localeCompare(b.id);
    });
    if (!candidates.length) return;
    const visible = candidates.filter(stock => this.interests.has(stock.id));
    const background = candidates.filter(stock => !this.interests.has(stock.id));
    const capacity = Math.max(0, 12 - visible.length);
    const rotated = background.length ? Array.from({ length: Math.min(capacity, background.length) }, (_, index) =>
      background[(this.underlyingReferenceCursor + index) % background.length]!) : [];
    this.underlyingReferenceCursor = background.length ? (this.underlyingReferenceCursor + rotated.length) % background.length : 0;
    const targets = [...visible, ...rotated].slice(0, 24);
    this.underlyingReferencePending = true;
    try {
      await Promise.allSettled(targets.map(async stock => {
        let symbol = this.underlyingSymbols.get(stock.id);
        if (!symbol) {
          const raw = await this.http('yahoo', '/v1/finance/search', { q: stock.underlying.isin!, quotesCount: '8', newsCount: '0' });
          symbol = resolveUnderlyingSymbol(raw, stock) ?? undefined;
          if (!symbol) { this.unresolvedUnderlyingSymbols.add(stock.id); return; }
          this.underlyingSymbols.set(stock.id, symbol);
        }
        const raw = await this.http('yahoo', `/v8/finance/chart/${encodeURIComponent(symbol)}`,
          { interval: '5m', range: '1d', includePrePost: 'true' });
        const reference = normalizeUnderlyingReference(raw, symbol, this.now());
        if (!this.running || !reference) return;
        this.underlyingReferences.set(stock.id, reference);
        const current = this.nativeBacked.get(stock.id);
        if (current) this.store(current);
        this.publish();
      }));
    } finally {
      this.underlyingReferencePending = false;
      if (this.underlyingReferenceRequested) {
        this.underlyingReferenceRequested = false;
        void this.refreshUnderlyingReferences();
      }
    }
  }
  private async refreshVisible() {
    if (!this.running || this.visiblePending || !this.interests.size) return;
    this.visiblePending = true;
    try {
      const stocks = [...this.interests].flatMap(id => this.stocks.has(id) ? [this.nativeBacked.get(id) ?? this.stocks.get(id)!] : []);
      await this.visibleStatistics.enrichBatches(stocks, batch => this.acceptAnalytics(batch));
    } catch { this.metrics.analyticsErrors++; } finally { this.visiblePending = false; }
  }
  private acceptAnalytics(batch: Stock[]) {
        if (!this.running) return;
        for (const update of batch) {
          const current = this.nativeBacked.get(update.id) ?? this.stocks.get(update.id);
          if (!current || !sameInstrument(current, update)) continue;
          const newerQuote = Date.parse(update.quote.receivedAt ?? '') >= Date.parse(current.quote.receivedAt ?? '') || current.quote.price === null;
          const newerStats = Date.parse(update.statistics.marketCapitalization.receivedAt ?? '') >=
            Date.parse(current.statistics.marketCapitalization.receivedAt ?? '') || current.statistics.marketCapitalization.receivedAt === null;
          if (!newerStats) continue;
          this.store({ ...current, statistics: update.statistics,
            ...(current.provider === 'backed' || current.provider === 'prestocks' ? { quote: newerQuote ? update.quote : current.quote, activity: update.activity,
              volume24h: update.activity.volume24h, volume24hBasis: update.activity.volume24h === null ? null : 'quote_currency_turnover' as const } : {}) });
        }
        this.publish();
  }
  private store(stock: Stock) {
    const input = StockSchema.safeParse(stock);
    if (!input.success) { this.metrics.invalidUpdates++; this.metrics.lastInvalidFields = input.error.issues.slice(0, 4).map(issue => `${stock.id}:${issue.path.join('.')}`); return; }
    if (stock.provider === 'backed') {
      this.nativeBacked.set(stock.id, stock);
      const candidate = this.backpackByUnderlying.get(stock.underlying.symbol);
      const reference = findShareReference(stock, candidate ? [candidate] : []);
      if (reference) this.referenceIds.set(stock.id, reference.id); else this.referenceIds.delete(stock.id);
      const projected = reference ? applyShareReference(stock, reference, this.now()) :
        applyUnderlyingReference(stock, this.underlyingReferences.get(stock.id), this.now());
      this.commit(projected);
    } else if (stock.provider === 'backpack') {
      this.commit(stock); this.backpackByUnderlying.set(stock.underlying.symbol, stock);
      for (const native of this.nativeBacked.values()) if (native.underlying.symbol === stock.underlying.symbol) this.store(native);
    } else this.commit(stock);
  }
  private removeFromCatalog(id: string): boolean {
    if (!this.stocks.delete(id)) return false;
    this.changedIds.delete(id);
    this.removedIds.add(id);
    this.membershipChanged = true;
    return true;
  }
  private commit(stock: Stock) {
    const parsed = StockSchema.safeParse(stock);
    if (!parsed.success) { this.metrics.invalidUpdates++; this.metrics.lastInvalidFields = parsed.error.issues.slice(0, 4).map(issue => `${stock.id}:${issue.path.join('.')}`); return; }
    const previous = this.stocks.get(stock.id);
    if (previous === stock || (previous !== undefined && JSON.stringify(previous) === JSON.stringify(stock))) return;
    if (previous && (previous.quote.basis !== stock.quote.basis || previous.quote.currency !== stock.quote.currency)) {
      this.history.delete(stock.id);
      for (const key of this.chartCaches.keys()) if (key.startsWith(`${stock.id}:`)) this.chartCaches.delete(key);
    }
    if (!previous) this.membershipChanged = true;
    this.removedIds.delete(stock.id);
    this.changedIds.add(stock.id);
    this.stocks.set(stock.id, stock);
    if (stock.quote.price === null || !stock.quote.receivedAt) return;
    const points = this.history.get(stock.id) ?? [];
    const previousPoint = points.at(-1);
    const timestamp = stock.quote.asOf ?? stock.quote.receivedAt;
    const elapsed = previousPoint ? Date.parse(timestamp) - Date.parse(previousPoint.timestamp) : Number.POSITIVE_INFINITY;
    if ((!previousPoint || timestamp > previousPoint.timestamp) &&
      (!previousPoint || stock.quote.price !== previousPoint.price || elapsed >= 15_000)) {
      points.push({ timestamp, price: stock.quote.price });
      if (points.length > 512) points.shift();
      this.history.set(stock.id, points);
    }
  }
  private publish() {
    if (!this.running) return;
    const requestedAt = this.monotonicNow();
    if (this.publishRequestedAt === undefined) this.publishRequestedAt = requestedAt;
    if (this.publishTimer) return;
    const remaining = PUBLICATION_INTERVAL_MS - (requestedAt - this.lastPublishedAt);
    if (remaining <= 0) { this.flushPublication(); return; }
    this.publishTimer = setTimeout(() => {
      this.publishTimer = undefined;
      this.flushPublication();
    }, Math.ceil(remaining));
    this.publishTimer.unref();
  }
  private flushPublication() {
    if (!this.running) return;
    const requestedAt = this.publishRequestedAt ?? this.monotonicNow();
    this.publishRequestedAt = undefined;
    const mutation: CatalogMutation = {
      changedIds: [...this.changedIds], removedIds: [...this.removedIds],
      membershipChanged: this.membershipChanged, requestedAt,
    };
    this.changedIds.clear(); this.removedIds.clear(); this.membershipChanged = false;
    this.syncUpstream();
    const value = this.snapshot();
    const publishedAt = this.monotonicNow();
    this.lastPublishedAt = publishedAt;
    const latency = Math.max(0, publishedAt - requestedAt);
    this.publicationLatencySamples.push(latency);
    if (this.publicationLatencySamples.length > PUBLICATION_LATENCY_SAMPLE_LIMIT) this.publicationLatencySamples.shift();
    const sorted = [...this.publicationLatencySamples].sort((a, b) => a - b);
    this.metrics.lastPublicationLatencyMs = latency;
    this.metrics.maxPublicationLatencyMs = sorted.at(-1) ?? null;
    this.metrics.p95PublicationLatencyMs = sorted[Math.max(0, Math.ceil(sorted.length * 0.95) - 1)] ?? null;
    this.metrics.p99PublicationLatencyMs = sorted[Math.max(0, Math.ceil(sorted.length * 0.99) - 1)] ?? null;
    this.metrics.publicationLatencySamples = sorted.length;
    this.metrics.published++;
    for (const listener of this.listeners) try { listener(value, mutation); } catch { this.listeners.delete(listener); }
  }
  private async backpackReferenceChart(id: string, range: ChartRange, reference: Stock): Promise<Chart | null> {
    const key = `${id}:${range}:underlying_share_reference:${reference.id}`;
    let cache = this.chartCaches.get(key);
    if (!cache) {
      cache = new AsyncCache<Chart>(30_000, 120_000, this.now);
      if (this.chartCaches.size >= 128) this.chartCaches.delete(this.chartCaches.keys().next().value!);
      this.chartCaches.set(key, cache);
    }
    const result = await cache.get(async () => {
      const raw = await this.http('backpack', '/api/v1/klines', { source: 'External', symbol: `${reference.providerAssetId}_USDC`,
        ...candleRequest(range, this.now()) });
      const points = normalizeCandles(raw);
      const useful = points.length >= 3;
      return ChartSchema.parse({ stockId: id, range, status: useful ? 'ok' : 'unavailable', points: useful ? points : [],
        basis: 'underlying_share_reference', currency: 'USDC', receivedAt: new Date(this.now()).toISOString(),
        statusReason: useful
          ? 'Verified same-share Backpack external reference history (USDC); not xStock token trades or an executable quote.'
          : `The verified Backpack share reference returned only ${points.length} point${points.length === 1 ? '' : 's'}; at least 3 are required for a useful graph.` });
    });
    if (!result.value || result.value.status !== 'ok') return null;
    return result.status === 'stale' ? { ...result.value,
      statusReason: `${result.value.statusReason ?? 'Verified share reference history.'} Refresh is delayed; cached reference history is shown.` } : result.value;
  }

  /** Token-priced listings can still need share history when no exact-token pool has candles.
   * Resolve the issuer ISIN, never a guessed ticker or a conflicting provider identity. */
  private async underlyingHistoryReference(stock: Stock, range: ChartRange): Promise<UnderlyingReference | null> {
    if (stock.provider !== 'backed' ||
      stock.underlying.listingCountry !== 'US' || stock.underlying.currency !== 'USD' || !stock.underlying.isin) return null;
    const now = this.now();
    const request = range === 'ONE_HOUR' ? { interval: '5m', span: '1d', historyWindowMs: 86_400_000 } :
      range === 'ONE_DAY' ? { interval: '5m', span: '5d', historyWindowMs: 5 * 86_400_000 } :
      range === 'ONE_WEEK' ? { interval: '1h', span: '1mo', historyWindowMs: 31 * 86_400_000 } :
      range === 'ONE_MONTH' ? { interval: '1d', span: '1mo', historyWindowMs: 31 * 86_400_000 } :
      { interval: '1d', span: 'ytd', historyWindowMs: now - Date.UTC(new Date(now).getUTCFullYear(), 0, 1) + 86_400_000 };
    const key = `${stock.id}:${stock.underlying.isin}:${range}`;
    let cache = this.underlyingHistoryCaches.get(key);
    if (!cache) {
      cache = new AsyncCache<UnderlyingReference | null>(60_000, 300_000, this.now, 30_000);
      if (this.underlyingHistoryCaches.size >= 128) this.underlyingHistoryCaches.delete(this.underlyingHistoryCaches.keys().next().value!);
      this.underlyingHistoryCaches.set(key, cache);
    }
    const result = await cache.get(async () => {
      const search = await this.http('yahoo', '/v1/finance/search', { q: stock.underlying.isin!, quotesCount: '8', newsCount: '0' });
      const symbol = resolveUnderlyingSymbol(search, stock);
      if (!symbol) return null;
      const raw = await this.http('yahoo', `/v8/finance/chart/${encodeURIComponent(symbol)}`,
        { interval: request.interval, range: request.span, includePrePost: 'true' });
      return normalizeUnderlyingReference(raw, symbol, this.now(), request.historyWindowMs);
    });
    return result.value ?? null;
  }

  async chart(id: string, range: ChartRange): Promise<Chart> {
    const stock = this.stocks.get(id);
    if (!stock) throw new UnknownStockError('Unknown current stock');
    const base: Chart = { stockId: id, range, status: 'unavailable', currency: stock.quote.currency,
      basis: stock.quote.basis, receivedAt: new Date(this.now()).toISOString(), points: [] };

    if (stock.provider === 'backed' || stock.provider === 'prestocks') {
      const mint = solanaMint(stock.deployments);
      const native = await this.tokenHistory.chart(id, mint ?? '', range);
      if (native.status === 'ok') return this.withCurrent(native);
      const since = Number(candleRequest(range, this.now()).startTime) * 1000;
      const points = (this.history.get(id) ?? []).filter(point => Date.parse(point.timestamp) >= since);
      if (stock.provider === 'backed') {
        const share = this.stocks.get(this.referenceIds.get(id) ?? '');
        if (share) {
          const referenceChart = await this.backpackReferenceChart(id, range, share);
          if (referenceChart) return this.withCurrent(referenceChart);
        }
        const observed = range === 'ONE_HOUR' || range === 'ONE_DAY' ? this.underlyingReferences.get(id) : undefined;
        const reference = observed && this.now() - Date.parse(observed.fetchedAt) <= 300_000
          ? observed : await this.underlyingHistoryReference(stock, range);
        if (reference) {
          const referencePoints = stock.quote.basis === 'underlying_share_reference'
            ? underlyingReferenceChartPoints(stock, reference, range, this.now())
            : underlyingReferenceChartPoints({ ...stock, quote: { ...stock.quote, price: reference.localPrice } }, reference, range, this.now());
          if (referencePoints && referencePoints.length >= 3) return this.withCurrent(ChartSchema.parse({ stockId: id, range,
            status: 'ok', basis: 'underlying_share_reference', currency: 'USD', receivedAt: reference.fetchedAt,
            points: referencePoints,
            statusReason: 'Exact Yahoo US/USD underlying-share history; not xStock token trades or an executable quote.' }));
        }
      }

      // Prefer complete, clearly labelled share history over a handful of points
      // collected since this development server started.
      if (stock.quote.basis !== 'underlying_share_reference' && locallyObservedHistoryCoversRange(points, range, this.now())) {
        return this.withCurrent({ ...base, points, status: 'ok',
          statusReason: `${native.statusReason ?? 'Exact-mint pool history is unavailable.'} Showing ${points.length} exact-token price observations retained by this server.` });
      }

      const observedSpan = points.length > 1 ? Math.max(0, Date.parse(points.at(-1)!.timestamp) - Date.parse(points[0]!.timestamp)) : 0;
      return { ...base, statusReason: `${native.statusReason ?? 'Exact-mint token history is unavailable.'} ` +
        `${points.length} locally observed price point${points.length === 1 ? ' is' : 's are'} insufficient: ` +
        `observations over ${Math.round(observedSpan / 60_000)} minutes do not cover the selected ${range} window.` };
    }

    const reference = stock.quote.basis === 'underlying_share_reference' ? this.stocks.get(this.referenceIds.get(id) ?? '') : undefined;
    if (stock.quote.basis === 'underlying_share_reference' && !reference) return base;
    const key = `${id}:${range}:${stock.quote.basis}:${stock.quote.currency}`;
    let cache = this.chartCaches.get(key);
    if (!cache) {
      cache = new AsyncCache<Chart>(30_000, 120_000, this.now);
      if (this.chartCaches.size >= 128) this.chartCaches.delete(this.chartCaches.keys().next().value!);
      this.chartCaches.set(key, cache);
    }
    const result = await cache.get(async () => {
      const raw = await this.http('backpack', '/api/v1/klines', { source: 'External', symbol: `${(reference ?? stock).providerAssetId}_USDC`, ...candleRequest(range, this.now()) });
      const points = normalizeCandles(raw);
      return ChartSchema.parse({ ...base, status: points.length ? 'ok' : 'unavailable', points, receivedAt: new Date(this.now()).toISOString(),
        ...(reference ? { statusReason: 'Underlying-share reference price and volume (USDC); not the xStock token market or an executable quote.' } : {}) });
    });
    return this.withCurrent(result.value ? { ...result.value, ...(result.status === 'stale' ? { statusReason: 'History refresh delayed; current reference quote shown separately at the chart edge.' } : {}) } : base);
  }
  withCurrent(chart: Chart): Chart {
    const stock = this.stocks.get(chart.stockId);
    // A current quote is a chart tail, never a replacement for missing history.
    if (chart.status !== 'ok' || chart.points.length < 3 || !stock || stock.quote.price === null || !stock.quote.receivedAt ||
      stock.quote.currency !== chart.currency || stock.quote.basis !== chart.basis ||
      this.now() - Date.parse(stock.quote.receivedAt) > 300_000 || this.now() < Date.parse(stock.quote.receivedAt)) return chart;
    const at = stock.quote.asOf ?? stock.quote.receivedAt;
    if (chart.points.at(-1)?.timestamp && at < chart.points.at(-1)!.timestamp) return chart;
    const points = chart.points.filter(point => point.timestamp < at);
    points.push({ timestamp: at, price: stock.quote.price });
    return { ...chart, points, status: 'ok', receivedAt: new Date(this.now()).toISOString() };
  }
}
