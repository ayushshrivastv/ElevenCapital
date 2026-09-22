import { z } from 'zod';
import { Decimal } from 'decimal.js';
import { AsyncCache } from './cache.js';
import { DecimalString, StatisticsSchema, type Activity, type Metric, type Statistics, type Stock } from './schema.js';
import type { GetJson } from './http.js';
import { jupiterActivity, unavailableJupiterActivity } from './activity.js';

type Deployment = Stock['deployments'][number];
const Fields = {
  marketCapitalization: { field: 'mcap', unit: 'USD', basis: 'token_market_cap', label: 'Market capitalization' },
  liquidity: { field: 'liquidity', unit: 'USD', basis: 'reported_token_liquidity', label: 'Liquidity' },
  holderCount: { field: 'holderCount', unit: 'count', basis: 'token_holders', label: 'Holder count' },
  organicScore: { field: 'organicScore', unit: 'score', basis: 'organic_activity_score', label: 'Organic score' },
} as const;
type FieldName = keyof typeof Fields;
const FieldNames = Object.keys(Fields) as FieldName[];
const Mint = /^[1-9A-HJ-NP-Za-km-z]{32,44}$/;

/** Never look up a ticker. An issuer's one unambiguous Solana deployment is the only lookup identity. */
export function solanaMint(deployments: Deployment[]): string | null {
  const candidates = [...new Set(deployments.filter(item => item.network.trim().toLowerCase() === 'solana').map(item => item.address))];
  return candidates.length === 1 && Mint.test(candidates[0]!) ? candidates[0]! : null;
}

function emptyMetric(name: FieldName, reason: string, source: Metric['source'] = null, receivedAt: string | null = null): Metric {
  const field = Fields[name];
  return { value: null, unit: field.unit, source, status: 'unavailable', basis: field.basis, receivedAt, reason };
}
export function unavailableStatistics(mint: string | null, reason: string, source: Metric['source'] = null,
  receivedAt: string | null = null): Statistics {
  return {
    scope: 'solana_token', network: mint ? 'solana' : null, mint, updatedAt: null,
    marketCapitalization: emptyMetric('marketCapitalization', reason, source, receivedAt),
    liquidity: emptyMetric('liquidity', reason, source, receivedAt),
    holderCount: emptyMetric('holderCount', reason, source, receivedAt),
    organicScore: emptyMetric('organicScore', reason, source, receivedAt),
  };
}
export function initialStatistics(deployments: Deployment[]): Statistics {
  const mint = solanaMint(deployments);
  return unavailableStatistics(mint, mint ? 'Statistics have not been received yet.' : 'No verified Solana deployment is available for this stock.');
}

function normalizedValue(raw: unknown, unit: Metric['unit']): string | null {
  const parsed = DecimalString.safeParse(raw);
  if (!parsed.success) return null;
  const value = new Decimal(parsed.data);
  if (!value.isFinite() || value.isNegative() || value.greaterThan('1e40') || value.decimalPlaces() > 100 ||
    (unit === 'count' && (!value.isInteger() || value.greaterThan('9223372036854775807'))) ||
    (unit === 'score' && value.greaterThan(100))) return null;
  const normalized = value.toFixed();
  return DecimalString.safeParse(normalized).success ? normalized : null;
}

/** Source updatedAt is used only to reject uninitialized/invalid records, never as a market timestamp. */
function hasObservation(raw: unknown, now: number): boolean {
  const parsed = z.iso.datetime().safeParse(raw);
  if (!parsed.success) return false;
  const timestamp = Date.parse(parsed.data);
  return Number.isFinite(timestamp) && timestamp >= Date.UTC(2009, 0, 1) && timestamp <= now + 300_000;
}

/** Activity is a rolling window, so old metadata must not become a fresh 24h volume after a re-fetch. */
export function normalizeJupiterActivity(raw: unknown, requested: readonly string[], fetchedAt: number): Map<string, Activity> {
  const rows = z.array(z.unknown()).max(100).parse(raw);
  const receivedAt = new Date(fetchedAt).toISOString();
  return new Map(requested.map(mint => {
    const matches = rows.filter((row): row is Record<string, unknown> => typeof row === 'object' && row !== null &&
      !Array.isArray(row) && (row as Record<string, unknown>).id === mint);
    const row = matches[0];
    if (matches.length !== 1 || !row || !hasObservation(row.updatedAt, fetchedAt)) {
      return [mint, unavailableJupiterActivity('Jupiter has not reported usable trading activity for this stock’s Solana token.', receivedAt)];
    }
    const updatedAt = new Date(String(row.updatedAt)).toISOString();
    if (Date.parse(updatedAt) < fetchedAt - 300_000 || Date.parse(updatedAt) > fetchedAt) {
      return [mint, unavailableJupiterActivity('Jupiter’s trading activity update is stale or has an invalid future timestamp.', receivedAt, updatedAt)];
    }
    return [mint, jupiterActivity(row.stats24h, receivedAt, updatedAt)];
  }));
}

export function normalizeJupiterStatistics(raw: unknown, requested: readonly string[], fetchedAt: number): Map<string, Statistics> {
  const rows = z.array(z.unknown()).max(100).parse(raw);
  const receivedAt = new Date(fetchedAt).toISOString();
  const requestedSet = new Set(requested);
  const byMint = new Map<string, Record<string, unknown>[]>();
  for (const row of rows) {
    if (typeof row !== 'object' || row === null || Array.isArray(row)) continue;
    const item = row as Record<string, unknown>;
    if (typeof item.id !== 'string' || !requestedSet.has(item.id)) continue;
    byMint.set(item.id, [...(byMint.get(item.id) ?? []), item]);
  }
  return new Map(requested.map(mint => {
    const matches = byMint.get(mint) ?? [];
    if (matches.length !== 1 || !hasObservation(matches[0]?.updatedAt, fetchedAt)) {
      return [mint, unavailableStatistics(mint, 'Jupiter has not reported usable statistics for this stock’s Solana token.', 'Jupiter', receivedAt)];
    }
    const row = matches[0]!;
    const statistics = unavailableStatistics(mint, 'Statistics are unavailable.', 'Jupiter', receivedAt);
    statistics.updatedAt = new Date(String(row.updatedAt)).toISOString();
    for (const name of FieldNames) {
      const field = Fields[name];
      const value = normalizedValue(row[field.field], field.unit);
      statistics[name] = value === null
        ? emptyMetric(name, `${field.label} is not reported for this stock’s Solana token.`, 'Jupiter', receivedAt)
        : { value, unit: field.unit, source: 'Jupiter', status: 'available', basis: field.basis, receivedAt, reason: null };
    }
    return [mint, StatisticsSchema.parse(statistics)];
  }));
}

const QuoteDecimal = Decimal.clone({ precision: 128 });
type EnrichmentBatch = { statistics: Map<string, Statistics>; activity: Map<string, Activity>; quotes: Map<string, Stock['quote']> };
/** Exact-mint observed Solana token USD prices; no underlying/share-price or other issuer substitution. */
export function normalizeJupiterQuotes(raw: unknown, requested: readonly string[], fetchedAt: number): Map<string, Stock['quote']> {
  const rows = z.array(z.unknown()).max(100).parse(raw);
  const quotes = new Map<string, Stock['quote']>();
  for (const mint of requested) {
    const matching = rows.filter((row): row is Record<string, unknown> => typeof row === 'object' && row !== null && !Array.isArray(row) && (row as Record<string, unknown>).id === mint);
    const row = matching[0];
    if (matching.length !== 1 || !row || !hasObservation(row.updatedAt, fetchedAt)) continue;
    const update = Date.parse(String(row.updatedAt));
    if (update > fetchedAt || update < fetchedAt - 300_000) continue;
    const value = normalizedValue(row.usdPrice, 'USD');
    if (value === null || !DecimalString.safeParse(value).success || new Decimal(value).isZero()) continue;
    const change = DecimalString.safeParse((row.stats24h as Record<string, unknown> | undefined)?.priceChange);
    const percent = change.success && new Decimal(change.data).isFinite() && new Decimal(change.data).abs().lte('1e6') ? new Decimal(change.data).toFixed() : null;
    const validPercent = percent !== null && DecimalString.safeParse(percent).success ? percent : null;
    const percentage = validPercent === null ? null : new QuoteDecimal(validPercent);
    const amount = percentage && percentage.greaterThan(-100) ? new QuoteDecimal(value).minus(new QuoteDecimal(value).div(percentage.div(100).plus(1))).toSignificantDigits(40).toFixed() : null;
    quotes.set(mint, { price: value, currency: 'USD', currencyBasis: 'quoted_currency', changeAmount: amount !== null && DecimalString.safeParse(amount).success ? amount : null,
      changePercent: validPercent, asOf: null, receivedAt: new Date(fetchedAt).toISOString(), basis: 'onchain_token_market' });
  }
  return quotes;
}
/** Keyless exact-mint batches have an independent cache and queue; completed batches can publish immediately. */
export class TokenStatisticsService {
  private readonly batches = new Map<string, AsyncCache<EnrichmentBatch>>();
  constructor(private readonly http: GetJson, private readonly now = Date.now, private readonly ttlMs = 60_000) {}
  async enrich(stocks: Stock[]): Promise<Stock[]> { return this.enrichBatches(stocks); }
  async enrichBatches(stocks: Stock[], onBatch?: (updated: Stock[]) => void | Promise<void>): Promise<Stock[]> {
    const mints = [...new Set(stocks.flatMap(stock => { const mint = solanaMint(stock.deployments); return mint ? [mint] : []; }))].sort();
    const updates = new Map<string, Stock>();
    for (let offset = 0; offset < mints.length; offset += 100) {
      const chunk = mints.slice(offset, offset + 100);
      const key = chunk.join(',');
      let cache = this.batches.get(key);
      if (!cache) {
        cache = new AsyncCache(this.ttlMs, 0, this.now);
        this.batches.set(key, cache);
        if (this.batches.size > 128) this.batches.delete(this.batches.keys().next().value!);
      }
      const result = await cache.get(async () => {
        const response = await this.http('jupiter', '/tokens/v2/search', { query: key });
        const fetchedAt = this.now();
        return { statistics: normalizeJupiterStatistics(response, chunk, fetchedAt), activity: normalizeJupiterActivity(response, chunk, fetchedAt), quotes: normalizeJupiterQuotes(response, chunk, fetchedAt) };
      });
      const inChunk = new Set(chunk);
      const changed = stocks.filter(stock => { const mint = solanaMint(stock.deployments); return mint !== null && inChunk.has(mint); }).map(stock => {
        const mint = solanaMint(stock.deployments)!;
        const batch = result.status === 'ok' ? result.value : undefined;
        const statistics = batch?.statistics.get(mint) ?? unavailableStatistics(mint, 'Statistics are temporarily unavailable. Updates resume automatically.', 'Jupiter');
        const exactMintMarket = stock.provider === 'backed' || stock.provider === 'prestocks';
        const activity = exactMintMarket ? batch?.activity.get(mint) ?? unavailableJupiterActivity('Trading activity is temporarily unavailable. Updates resume automatically.') : stock.activity;
        const quote = exactMintMarket ? batch?.quotes.get(mint) ?? stock.quote : stock.quote;
        const updated = { ...stock, statistics, activity, quote };
        updates.set(stock.id, updated); return updated;
      });
      if (onBatch && changed.length) await onBatch(changed);
    }
    return stocks.map(stock => updates.get(stock.id) ?? { ...stock, statistics: initialStatistics(stock.deployments),
      activity: stock.provider === 'backed' || stock.provider === 'prestocks'
        ? unavailableJupiterActivity('No verified Solana deployment is available for this stock.') : stock.activity });
  }
}
