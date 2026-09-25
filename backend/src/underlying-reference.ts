import { Decimal } from 'decimal.js';
import { DecimalString, type Activity, type Chart, type ChartRange, type Stock } from './schema.js';
import { nonnegativeAmount } from './activity.js';

const Money = Decimal.clone({ precision: 128 });
const ExchangeByCountry: Record<string, Set<string>> = {
  US: new Set(['NMS', 'NYQ', 'NGM', 'NCM', 'ASE', 'PCX', 'BTS']),
  GB: new Set(['LSE']), HK: new Set(['HKG']), DE: new Set(['GER']), ES: new Set(['MCE']),
};
const YahooSymbol = /^[A-Za-z0-9.^=_-]{1,40}$/;

export type UnderlyingReference = {
  symbol: string;
  fetchedAt: string;
  sourceAt: string | null;
  localPrice: string;
  changePercent: string | null;
  turnoverLocal: string | null;
  points: Chart['points'];
  historyWindowMs?: number;
};

/** Resolve an exact ISIN search to one listing in the issuer-declared country. */
export function resolveUnderlyingSymbol(raw: unknown, stock: Stock): string | null {
  const country = stock.underlying.listingCountry ?? '';
  const allowed = ExchangeByCountry[country];
  if (!allowed || !stock.underlying.isin) return null;
  const response = typeof raw === 'object' && raw !== null && !Array.isArray(raw) ? raw as Record<string, unknown> : {};
  const rows = Array.isArray(response.quotes) ? response.quotes : [];
  const matches = rows.filter((value): value is Record<string, unknown> => {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) return false;
    const row = value as Record<string, unknown>;
    return typeof row.symbol === 'string' && YahooSymbol.test(row.symbol) && typeof row.exchange === 'string' &&
      allowed.has(row.exchange) && (row.quoteType === 'EQUITY' || row.quoteType === 'ETF') && row.isYahooFinance === true;
  });
  return matches.length === 1 ? String(matches[0]!.symbol) : null;
}

function finiteDecimal(raw: unknown, signed = false): string | null {
  if (typeof raw !== 'string' && typeof raw !== 'number') return null;
  const text = String(raw);
  if (!DecimalString.safeParse(text).success) return null;
  const value = new Money(text);
  if (!value.isFinite() || (!signed && !value.greaterThan(0)) || value.abs().greaterThan('1e40')) return null;
  return value.toFixed();
}

/** lossless-json represents provider integer literals as strings. */
function safeInteger(raw: unknown): number | null {
  if (typeof raw !== 'number' && typeof raw !== 'string') return null;
  const text = String(raw);
  if (!/^-?\d+$/.test(text)) return null;
  const value = Number(text);
  return Number.isSafeInteger(value) ? value : null;
}

/** Normalize one public 5-minute underlying-share chart; no ticker fallback or cross-listing substitution. */
export function normalizeUnderlyingReference(raw: unknown, expectedSymbol: string, fetchedAt: number,
  historyWindowMs = 86_400_000): UnderlyingReference | null {
  if (!YahooSymbol.test(expectedSymbol) || !Number.isFinite(fetchedAt) || !Number.isFinite(historyWindowMs) ||
    historyWindowMs <= 0 || historyWindowMs > 366 * 86_400_000) return null;
  const root = typeof raw === 'object' && raw !== null && !Array.isArray(raw) ? raw as Record<string, unknown> : {};
  const chart = typeof root.chart === 'object' && root.chart !== null && !Array.isArray(root.chart) ? root.chart as Record<string, unknown> : {};
  const results = Array.isArray(chart.result) ? chart.result : [];
  if (results.length !== 1 || typeof results[0] !== 'object' || results[0] === null || Array.isArray(results[0])) return null;
  const result = results[0] as Record<string, unknown>;
  const meta = typeof result.meta === 'object' && result.meta !== null && !Array.isArray(result.meta) ? result.meta as Record<string, unknown> : {};
  if (meta.symbol !== expectedSymbol || meta.currency !== 'USD' || !['EQUITY', 'ETF'].includes(String(meta.instrumentType))) return null;
  const localPrice = finiteDecimal(meta.regularMarketPrice ?? meta.fulldayPrice);
  if (localPrice === null) return null;
  const percent = finiteDecimal(meta.regularMarketChangePercent ?? meta.fulldayChangePercent, true);
  const sourceSeconds = safeInteger(meta.regularMarketTime);
  const sourceMs = sourceSeconds === null ? null : sourceSeconds * 1000;
  const sourceAt = sourceMs !== null && sourceMs <= fetchedAt + 300_000 && sourceMs >= fetchedAt - 86_400_000
    ? new Date(sourceMs).toISOString() : null;
  const timestamps = Array.isArray(result.timestamp) ? result.timestamp : [];
  const indicators = typeof result.indicators === 'object' && result.indicators !== null && !Array.isArray(result.indicators) ? result.indicators as Record<string, unknown> : {};
  const quotes = Array.isArray(indicators.quote) ? indicators.quote : [];
  const quote = typeof quotes[0] === 'object' && quotes[0] !== null && !Array.isArray(quotes[0]) ? quotes[0] as Record<string, unknown> : {};
  const closes = Array.isArray(quote.close) ? quote.close : [];
  const volumes = Array.isArray(quote.volume) ? quote.volume : [];
  const points: Chart['points'] = [];
  let turnover = new Money(0), hasTurnover = false;
  for (let index = 0; index < timestamps.length && index < closes.length; index++) {
    const seconds = safeInteger(timestamps[index]), price = finiteDecimal(closes[index]);
    if (seconds === null || price === null) continue;
    const at = seconds * 1000;
    if (at > fetchedAt + 300_000 || at < fetchedAt - historyWindowMs) continue;
    points.push({ timestamp: new Date(at).toISOString(), price });
    const rawVolume = volumes[index];
    const volume = rawVolume === null || rawVolume === undefined ? null : nonnegativeAmount(String(rawVolume));
    if (volume !== null) { turnover = turnover.plus(new Money(volume).times(price)); hasTurnover = true; }
  }
  points.sort((a, b) => a.timestamp.localeCompare(b.timestamp));
  const unique = points.filter((point, index) => index === 0 || point.timestamp !== points[index - 1]!.timestamp);
  return { symbol: expectedSymbol, fetchedAt: new Date(fetchedAt).toISOString(), sourceAt, localPrice,
    changePercent: percent, turnoverLocal: hasTurnover && turnover.lte('1e40') ? turnover.toFixed() : null,
    points: unique, historyWindowMs };
}

/** Anchor the underlying path to the issuer's USD price while retaining explicit reference provenance. */
export function applyUnderlyingReference(stock: Stock, reference: UnderlyingReference | undefined, now: number): Stock {
  if (!reference || stock.provider !== 'backed' || stock.quote.price === null || stock.quote.receivedAt === null ||
    stock.quote.basis === 'onchain_token_market' || stock.underlying.listingCountry !== 'US' ||
    stock.underlying.currency !== 'USD' || reference.sourceAt === null ||
    now - Date.parse(reference.fetchedAt) > 300_000) return stock;
  const usd = new Money(stock.quote.price), local = new Money(reference.localPrice);
  if (!usd.greaterThan(0) || !local.greaterThan(0)) return stock;
  const ratio = usd.div(local);
  const percent = reference.changePercent;
  const changeAmount = percent !== null && new Money(percent).greaterThan(-100)
    ? usd.minus(usd.div(new Money(percent).div(100).plus(1))).toSignificantDigits(40).toFixed() : null;
  const volume = reference.turnoverLocal === null ? null : new Money(reference.turnoverLocal).times(ratio).toSignificantDigits(40).toFixed();
  const activity: Activity = { currency: 'USD', source: 'Yahoo', scope: 'underlying_share', volume24h: volume,
    netVolume24h: null, receivedAt: volume === null ? null : reference.fetchedAt, updatedAt: reference.sourceAt,
    volumeReason: volume === null ? 'The underlying-share reference did not publish usable 24h turnover.' : null,
    netVolumeReason: 'The underlying-share reference does not publish a 24h buy/sell volume breakdown.' };
  return { ...stock, providerLabel: 'Backed · Share reference',
    quote: { price: usd.toFixed(), currency: 'USD', currencyBasis: 'underlying_metadata', changeAmount,
      changePercent: percent, asOf: reference.sourceAt, receivedAt: reference.fetchedAt, basis: 'underlying_share_reference' },
    activity, volume24h: volume, volume24hBasis: volume === null ? null : 'quote_currency_turnover' };
}

export function anchoredReferencePoints(stock: Stock, reference: UnderlyingReference): Chart['points'] {
  if (stock.quote.price === null) return [];
  const ratio = new Money(stock.quote.price).div(reference.localPrice);
  return reference.points.map(point => ({ timestamp: point.timestamp, price: new Money(point.price).times(ratio).toSignificantDigits(40).toFixed() }));
}

/** A share reference may be used only within the exact history window requested upstream. */
export function underlyingReferenceChartPoints(stock: Stock, reference: UnderlyingReference, range: ChartRange,
  now: number): Chart['points'] | null {
  if (!Number.isFinite(now)) return null;
  const duration = range === 'ONE_HOUR' ? 3_600_000 : range === 'ONE_DAY' ? 86_400_000 :
    range === 'ONE_WEEK' ? 604_800_000 : range === 'ONE_MONTH' ? 2_592_000_000 :
    now - Date.UTC(new Date(now).getUTCFullYear(), 0, 1);
  if ((reference.historyWindowMs ?? 86_400_000) < duration) return null;
  const since = now - duration;
  const points = anchoredReferencePoints(stock, reference).filter(point => Date.parse(point.timestamp) >= since);
  // A long-range request can still return only recent rows for a newly listed or
  // temporarily incomplete symbol. Do not stretch that fragment across the range.
  if ((range === 'ONE_WEEK' || range === 'ONE_MONTH' || range === 'YEAR_TO_DATE') &&
    (points.length < 3 || Date.parse(points[0]!.timestamp) > since + duration * 0.25)) return null;
  return points;
}
