import { Decimal } from 'decimal.js';
import { AsyncCache } from './cache.js';
import type { GetJson } from './http.js';
import { ChartSchema, DecimalString, type Chart, type ChartRange } from './schema.js';

const SolanaAddress = /^[1-9A-HJ-NP-Za-km-z]{32,44}$/;
const Money = Decimal.clone({ precision: 128 });
const MIN_USEFUL_POINTS = 3;
const MAX_YTD_PAGES = 4;

export type GeckoPool = {
  address: string;
  baseAddress: string;
  quoteAddress: string;
  createdAt: number | null;
};

type Series = {
  status: 'ok' | 'unavailable';
  points: Chart['points'];
  receivedAt: string;
  reason: string;
};

type CandlePage = {
  points: Chart['points'];
  oldestTimestamp: number | null;
  count: number;
};

type RangeRequest = {
  timeframe: 'minute' | 'hour' | 'day';
  aggregate: '1' | '4' | '12' | '15';
  limit: number;
  since: number;
  pages: number;
};

function record(raw: unknown): Record<string, unknown> | null {
  return typeof raw === 'object' && raw !== null && !Array.isArray(raw) ? raw as Record<string, unknown> : null;
}

function relationshipAddress(raw: unknown): string | null {
  const relationship = record(raw), data = record(relationship?.data);
  const id = data?.id;
  if (typeof id !== 'string' || !id.startsWith('solana_')) return null;
  const address = id.slice('solana_'.length);
  return SolanaAddress.test(address) ? address : null;
}

function positiveDecimal(raw: unknown, allowZero = false): string | null {
  if (typeof raw !== 'string' && typeof raw !== 'number') return null;
  const text = String(raw);
  const parsed = DecimalString.safeParse(text);
  if (!parsed.success) return null;
  const value = new Money(parsed.data);
  if (!value.isFinite() || value.greaterThan('1e40') || (allowZero ? value.isNegative() : !value.greaterThan(0)) || value.decimalPlaces() > 100) return null;
  const normalized = value.toFixed();
  return DecimalString.safeParse(normalized).success ? normalized : null;
}

/** Select GeckoTerminal's first provider-ranked active pool while proving that it contains the verified mint. */
export function selectGeckoPool(raw: unknown, mint: string): GeckoPool | null {
  if (!SolanaAddress.test(mint)) return null;
  const root = record(raw), rows = root?.data;
  if (!Array.isArray(rows) || rows.length > 20) throw new Error('Invalid GeckoTerminal pool response');
  for (const rawRow of rows) {
    const row = record(rawRow), attributes = record(row?.attributes), relationships = record(row?.relationships);
    const address = attributes?.address;
    if (row?.type !== 'pool' || typeof address !== 'string' || !SolanaAddress.test(address) || row.id !== `solana_${address}`) continue;
    const baseAddress = relationshipAddress(relationships?.base_token);
    const quoteAddress = relationshipAddress(relationships?.quote_token);
    const volume = record(attributes?.volume_usd)?.h24;
    if (!baseAddress || !quoteAddress || baseAddress === quoteAddress || (baseAddress !== mint && quoteAddress !== mint) ||
      positiveDecimal(attributes?.reserve_in_usd) === null || positiveDecimal(volume, true) === null) continue;
    const createdAt = typeof attributes?.pool_created_at === 'string' ? Date.parse(attributes.pool_created_at) : NaN;
    return { address, baseAddress, quoteAddress,
      createdAt: Number.isFinite(createdAt) && createdAt >= Date.UTC(2020, 0, 1) ? createdAt : null };
  }
  return null;
}

function safeEpochSeconds(raw: unknown): number | null {
  if (typeof raw !== 'string' && typeof raw !== 'number') return null;
  const text = String(raw);
  if (!/^\d{1,12}$/.test(text)) return null;
  const value = Number(text);
  return Number.isSafeInteger(value) ? value : null;
}

/** Normalize USD close candles only after the response proves the selected pool's exact token pair. */
export function normalizeGeckoOhlcv(raw: unknown, pool: GeckoPool, since: number, now: number): CandlePage {
  if (!Number.isFinite(since) || !Number.isFinite(now) || since > now) throw new Error('Invalid chart window');
  const root = record(raw), data = record(root?.data), attributes = record(data?.attributes), meta = record(root?.meta);
  const base = record(meta?.base)?.address, quote = record(meta?.quote)?.address;
  if (data?.type !== 'ohlcv_request_response' || base !== pool.baseAddress || quote !== pool.quoteAddress) {
    throw new Error('GeckoTerminal OHLCV identity mismatch');
  }
  const candles = attributes?.ohlcv_list;
  if (!Array.isArray(candles) || candles.length > 100) throw new Error('Invalid GeckoTerminal OHLCV response');
  const byTimestamp = new Map<string, string>();
  let oldestTimestamp: number | null = null;
  for (const rawCandle of candles) {
    if (!Array.isArray(rawCandle) || rawCandle.length !== 6) throw new Error('Invalid GeckoTerminal candle');
    const seconds = safeEpochSeconds(rawCandle[0]);
    const price = positiveDecimal(rawCandle[4]);
    if (seconds === null || price === null || seconds * 1000 > now + 300_000 || seconds * 1000 < Date.UTC(2020, 0, 1)) {
      throw new Error('Invalid GeckoTerminal candle');
    }
    oldestTimestamp = oldestTimestamp === null ? seconds : Math.min(oldestTimestamp, seconds);
    if (seconds * 1000 < since || seconds * 1000 > now) continue;
    const timestamp = new Date(seconds * 1000).toISOString();
    const previous = byTimestamp.get(timestamp);
    if (previous !== undefined && previous !== price) throw new Error('Conflicting GeckoTerminal candles');
    byTimestamp.set(timestamp, price);
  }
  return { points: [...byTimestamp].sort(([a], [b]) => a.localeCompare(b)).map(([timestamp, price]) => ({ timestamp, price })),
    oldestTimestamp, count: candles.length };
}

/** Bounded request shapes deliberately cap each public response at 100 candles. */
export function geckoRangeRequest(range: ChartRange, now: number): RangeRequest {
  const startOfYear = Date.UTC(new Date(now).getUTCFullYear(), 0, 1);
  switch (range) {
    case 'ONE_HOUR': return { timeframe: 'minute', aggregate: '1', limit: 60, since: now - 3_600_000, pages: 1 };
    case 'ONE_DAY': return { timeframe: 'minute', aggregate: '15', limit: 96, since: now - 86_400_000, pages: 1 };
    case 'ONE_WEEK': return { timeframe: 'hour', aggregate: '4', limit: 42, since: now - 604_800_000, pages: 1 };
    case 'ONE_MONTH': return { timeframe: 'hour', aggregate: '12', limit: 60, since: now - 2_592_000_000, pages: 1 };
    case 'YEAR_TO_DATE': return { timeframe: 'day', aggregate: '1', limit: 100, since: startOfYear, pages: MAX_YTD_PAGES };
  }
}

/** Detail-only, exact-mint GeckoTerminal history with bounded single-flight caches. */
export class TokenHistoryService {
  private readonly pools = new Map<string, AsyncCache<GeckoPool>>();
  private readonly series = new Map<string, AsyncCache<Series>>();

  constructor(private readonly http: GetJson, private readonly now = Date.now) {}

  private poolCache(mint: string) {
    let cache = this.pools.get(mint);
    if (!cache) {
      cache = new AsyncCache<GeckoPool>(900_000, 86_400_000, this.now, 30_000);
      if (this.pools.size >= 128) this.pools.delete(this.pools.keys().next().value!);
      this.pools.set(mint, cache);
    }
    return cache;
  }

  private seriesCache(key: string) {
    let cache = this.series.get(key);
    if (!cache) {
      cache = new AsyncCache<Series>(60_000, 900_000, this.now, 30_000);
      if (this.series.size >= 128) this.series.delete(this.series.keys().next().value!);
      this.series.set(key, cache);
    }
    return cache;
  }

  private async load(mint: string, range: ChartRange): Promise<Series> {
    const requestedAt = this.now();
    const poolResult = await this.poolCache(mint).get(async () => {
      const pool = selectGeckoPool(await this.http('geckoterminal',
        `/api/v2/networks/solana/tokens/${mint}/pools`, { include: 'base_token,quote_token', page: '1' }), mint);
      if (!pool) throw new Error('No verified exact-mint GeckoTerminal pool is currently available');
      return pool;
    });
    const pool = poolResult.value;
    if (!pool) return { status: 'unavailable', points: [], receivedAt: new Date(this.now()).toISOString(),
      reason: 'GeckoTerminal did not return a verified active Solana pool for this exact token mint.' };

    const request = geckoRangeRequest(range, requestedAt);
    const points = new Map<string, string>();
    let before: number | undefined;
    let coveredStart = request.pages === 1;
    for (let page = 0; page < request.pages; page++) {
      const query: Record<string, string> = { aggregate: request.aggregate, limit: String(request.limit), currency: 'usd',
        token: mint, include_empty_intervals: 'false' };
      if (before !== undefined) query.before_timestamp = String(before);
      const raw = await this.http('geckoterminal', `/api/v2/networks/solana/pools/${pool.address}/ohlcv/${request.timeframe}`, query);
      const normalized = normalizeGeckoOhlcv(raw, pool, request.since, this.now());
      for (const point of normalized.points) points.set(point.timestamp, point.price);
      if (normalized.oldestTimestamp !== null && normalized.oldestTimestamp * 1000 <= request.since) {
        coveredStart = true; break;
      }
      if (normalized.oldestTimestamp === null || normalized.count < request.limit) {
        // Empty intervals are omitted. Treat exhaustion as complete YTD only when
        // the selected pool itself did not exist at the beginning of the window.
        if (request.pages > 1) coveredStart = pool.createdAt !== null && pool.createdAt >= request.since;
        break;
      }
      const next = normalized.oldestTimestamp - 1;
      if (before !== undefined && next >= before) throw new Error('GeckoTerminal pagination did not advance');
      before = next;
    }
    const ordered = [...points].sort(([a], [b]) => a.localeCompare(b)).map(([timestamp, price]) => ({ timestamp, price }));
    const receivedAt = new Date(this.now()).toISOString();
    if (!coveredStart) return { status: 'unavailable', points: [], receivedAt,
      reason: 'The bounded public GeckoTerminal history did not cover the requested year-to-date window.' };
    if (ordered.length < MIN_USEFUL_POINTS) return { status: 'unavailable', points: [], receivedAt,
      reason: `GeckoTerminal returned only ${ordered.length} exact-mint candle${ordered.length === 1 ? '' : 's'}; at least ${MIN_USEFUL_POINTS} are required for a useful market graph.` };
    return { status: 'ok', points: ordered, receivedAt,
      reason: 'GeckoTerminal exact-mint Solana pool OHLCV from its provider-ranked active pool; USD closes are on-chain trade history, not an executable quote.' };
  }

  async chart(stockId: string, mint: string, range: ChartRange): Promise<Chart> {
    const receivedAt = new Date(this.now()).toISOString();
    if (!SolanaAddress.test(mint)) return ChartSchema.parse({ stockId, range, status: 'unavailable', basis: 'onchain_token_market',
      currency: 'USD', receivedAt, points: [], statusReason: 'No single verified Solana mint is available for token-market history.' });
    const result = await this.seriesCache(`${mint}:${range}`).get(() => this.load(mint, range));
    if (!result.value) return ChartSchema.parse({ stockId, range, status: 'unavailable', basis: 'onchain_token_market',
      currency: 'USD', receivedAt, points: [], statusReason: 'GeckoTerminal exact-mint history is temporarily unavailable.' });
    const series = result.value;
    return ChartSchema.parse({ stockId, range, status: series.status, basis: 'onchain_token_market', currency: 'USD',
      receivedAt: series.receivedAt, points: series.points,
      statusReason: result.status === 'stale'
        ? `${series.reason} Refresh is delayed; a cached verified response is shown.` : series.reason });
  }
}
