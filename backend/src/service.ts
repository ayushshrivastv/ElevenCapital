import { z } from 'zod';
import { Decimal } from 'decimal.js';
import { AsyncCache, type CacheResult } from './cache.js';
import { getJson, type GetJson } from './http.js';
import { loadBacked, loadBackpack, loadPreStocks, type ProviderSnapshot } from './providers.js';
import { CatalogSchema, ChartSchema, DecimalString, type Catalog, type Chart, type ChartRange, type Provider, type Stock } from './schema.js';
import { TokenStatisticsService } from './statistics.js';

export class UnknownStockError extends Error {}
export function candleRequest(range: ChartRange, now: number): { interval: string; startTime: string; endTime: string } {
  const durations: Record<Exclude<ChartRange, 'YEAR_TO_DATE'>, [number, string]> = {
    ONE_HOUR: [3_600_000, '1m'], ONE_DAY: [86_400_000, '5m'],
    ONE_WEEK: [604_800_000, '1h'], ONE_MONTH: [2_592_000_000, '4h'],
  };
  const selection = range === 'YEAR_TO_DATE'
    ? [now - Date.UTC(new Date(now).getUTCFullYear(), 0, 1), '1d'] as const : durations[range];
  return { interval: selection[1], startTime: String(Math.floor((now - selection[0]) / 1000)), endTime: String(Math.floor(now / 1000)) };
}
const Candle = z.object({ start: z.string(), close: DecimalString });
export function normalizeCandles(raw: unknown): Chart['points'] {
  return z.array(Candle).max(10_000).parse(raw).map(candle => {
    if (!/^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d$/.test(candle.start)) throw new Error('Invalid candle timestamp');
    const instant = new Date(candle.start.replace(' ', 'T') + 'Z');
    if (!Number.isFinite(instant.getTime()) || new Decimal(candle.close).isNegative()) throw new Error('Invalid candle');
    return { timestamp: instant.toISOString(), price: candle.close };
  }).sort((a, b) => a.timestamp.localeCompare(b.timestamp));
}
export class MarketDataService {
  private readonly backedCache: AsyncCache<ProviderSnapshot>;
  private readonly backpackCache: AsyncCache<ProviderSnapshot>;
  private readonly preStocksCache: AsyncCache<ProviderSnapshot>;
  private readonly charts = new Map<string, AsyncCache<Chart>>();
  private readonly statistics: TokenStatisticsService;
  private readonly previous = new Map<Provider, ProviderSnapshot>();
  constructor(private readonly http: GetJson = getJson, private readonly now = Date.now, ttlMs = 15_000) {
    this.backedCache = new AsyncCache(ttlMs, 300_000, now);
    this.backpackCache = new AsyncCache(ttlMs, 300_000, now);
    this.preStocksCache = new AsyncCache(ttlMs, 300_000, now);
    // Quote refreshes do not increase the rate of the separately cached Jupiter analytics feed.
    this.statistics = new TokenStatisticsService(http, now, 60_000);
  }
  async catalog(): Promise<Catalog> {
    const snapshots = await Promise.all([
      this.backedCache.get(() => this.loadProvider('backed')),
      this.backpackCache.get(() => this.loadProvider('backpack')),
      this.preStocksCache.get(() => this.loadProvider('prestocks')),
    ]);
    const providerStatus = (id: Provider, snapshot: CacheResult<ProviderSnapshot>): Catalog['providers'][number] => {
      if (snapshot.status === 'stale') return { id, status: 'stale', message: 'Provider refresh failed. Showing previously received data, at most five minutes old.' };
      if (snapshot.status === 'unavailable') return { id, status: 'unavailable', message: 'Public provider is unavailable. No sample values are substituted.' };
      if (snapshot.value?.retainedQuotes) return { id, status: 'stale', message: 'Some quotes are refreshing automatically. Retained observations keep their original timestamps and expire after five minutes.' };
      if (snapshot.value?.partial) return { id, status: 'unavailable', message: 'Some public quotes are unavailable. Missing values are left empty.' };
      return { id, status: 'ok' };
    };
    // Timestamp the completed snapshot after enrichment: metric fetches may finish after quote fetches.
    const stocks = await this.statistics.enrich(snapshots.flatMap(snapshot => snapshot.value?.stocks ?? []));
    return CatalogSchema.parse({
      schemaVersion: 1, mode: 'live-read-only', receivedAt: new Date(this.now()).toISOString(),
      providers: [providerStatus('backed', snapshots[0]), providerStatus('backpack', snapshots[1]), providerStatus('prestocks', snapshots[2])],
      stocks,
    });
  }
  private async loadProvider(id: Provider): Promise<ProviderSnapshot> {
    const latest = await (id === 'backed' ? loadBacked(this.http, this.now) : id === 'backpack'
      ? loadBackpack(this.http, this.now) : loadPreStocks(this.http, this.now));
    const previous = this.previous.get(id);
    let retainedQuotes = false;
    const stocks = latest.stocks.map(stock => {
      if (stock.quote.price !== null) return stock;
      const old = previous?.stocks.find(item => item.id === stock.id && item.providerAssetId === stock.providerAssetId &&
        item.underlying.isin === stock.underlying.isin && item.quote.currency === stock.quote.currency);
      const age = old?.quote.receivedAt ? this.now() - Date.parse(old.quote.receivedAt) : NaN;
      if (!old || old.quote.price === null || !Number.isFinite(age) || age < 0 || age > 300_000) return stock;
      retainedQuotes = true;
      return { ...stock, quote: old.quote };
    });
    const result = { ...latest, stocks, retainedQuotes };
    this.previous.set(id, result);
    return result;
  }
  async chart(id: string, range: ChartRange): Promise<Chart> {
    const stock = (await this.catalog()).stocks.find(item => item.id === id);
    if (!stock) throw new UnknownStockError('Unknown verified stock');
    const empty = (status: 'unsupported' | 'unavailable'): Chart => ({
      stockId: id, range, status, basis: stock.quote.basis, currency: stock.quote.currency,
      receivedAt: new Date(this.now()).toISOString(), points: [],
    });
    if (stock.provider === 'backed' || stock.provider === 'prestocks') return empty('unsupported');
    const key = `${id}:${range}`;
    let cache = this.charts.get(key);
    if (!cache) {
      cache = new AsyncCache<Chart>(60_000, 60_000, this.now);
      this.charts.set(key, cache);
    }
    const result = await cache.get(() => this.loadChart(stock, range));
    // Never label stale charts as current; the contract has no ambiguous stale chart status.
    return result.status === 'ok' && result.value ? result.value : empty('unavailable');
  }
  private async loadChart(stock: Stock, range: ChartRange): Promise<Chart> {
    const response = await this.http('backpack', '/api/v1/klines', {
      source: 'External', symbol: `${stock.providerAssetId}_USDC`, ...candleRequest(range, this.now()),
    });
    const points = normalizeCandles(response);
    return ChartSchema.parse({ stockId: stock.id, range, status: points.length ? 'ok' : 'unavailable',
      basis: stock.quote.basis, currency: stock.quote.currency, receivedAt: new Date(this.now()).toISOString(), points });
  }
}
