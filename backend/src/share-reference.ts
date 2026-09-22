import { Decimal } from 'decimal.js';
import { DecimalString, type Stock } from './schema.js';

const MAX_AGE_MS = 300_000;
const US_TICKER = /^[A-Z][A-Z0-9.-]{0,15}$/;
const ISIN = /^[A-Z]{2}[A-Z0-9]{9}\d$/;
const CUSIP = /^[A-Z0-9]{9}$/;

function validIdentity(stock: Stock): boolean {
  const { isin, cusip } = stock.underlying;
  if (isin !== null && !ISIN.test(isin)) return false;
  if (cusip !== null && !CUSIP.test(cusip)) return false;
  return !(isin?.startsWith('US') && cusip !== null && isin.slice(2, 11) !== cusip);
}

function matchesUnderlying(backed: Stock, reference: Stock): boolean {
  const a = backed.underlying;
  const b = reference.underlying;
  if (backed.provider !== 'backed' || reference.provider !== 'backpack' ||
    a.listingCountry !== 'US' || b.listingCountry !== 'US' || a.currency !== 'USD' || b.currency !== 'USD' ||
    !US_TICKER.test(a.symbol) || a.symbol !== b.symbol || reference.providerAssetId !== `${a.symbol}.US` ||
    reference.id !== `backpack:${reference.providerAssetId}` || !validIdentity(backed) || !validIdentity(reference)) return false;
  if (a.isin !== null && b.isin !== null && a.isin !== b.isin) return false;
  if (a.cusip !== null && b.cusip !== null && a.cusip !== b.cusip) return false;
  if (a.isin?.startsWith('US') && b.cusip !== null && a.isin.slice(2, 11) !== b.cusip) return false;
  if (b.isin?.startsWith('US') && a.cusip !== null && b.isin.slice(2, 11) !== a.cusip) return false;
  return true;
}

/** Find one exact US share identity. Names, token symbols, and other-country ticker collisions are never used. */
export function findShareReference(backed: Stock, candidates: Iterable<Stock>): Stock | undefined {
  let match: Stock | undefined;
  for (const candidate of candidates) {
    if (!matchesUnderlying(backed, candidate)) continue;
    if (match) return undefined; // Ambiguous observations must not depend on array order.
    match = candidate;
  }
  return match;
}

function freshTimestamp(timestamp: string | null, now: number): boolean {
  if (timestamp === null) return false;
  const observed = Date.parse(timestamp);
  return Number.isFinite(observed) && observed <= now && now - observed <= MAX_AGE_MS;
}

function freshQuote(stock: Stock, now: number): boolean {
  const quote = stock.quote;
  const parsed = DecimalString.safeParse(quote.price);
  if (!parsed.success) return false;
  const price = new Decimal(parsed.data);
  if (!price.isFinite() || !price.greaterThan(0) || price.greaterThan('1e40') || price.decimalPlaces() > 100) return false;
  return freshTimestamp(quote.receivedAt, now) && (quote.asOf === null || freshTimestamp(quote.asOf, now));
}

/**
 * Market-display projection only. Keep canonical token observations and wallet valuation inputs separate.
 * A share reference is neither a token execution quote nor a value for the user's token balance.
 */
export function applyShareReference(backed: Stock, reference: Stock | undefined, now: number): Stock {
  if (!Number.isFinite(now) || backed.provider !== 'backed') return backed;
  // A measured token market is the preferred display. The issuer's one-field
  // indicative price has no change, turnover or history, so a verified underlying
  // reference is the more complete labelled market view when available.
  const native = backed.quote.basis === 'onchain_token_market';
  if (native && freshQuote(backed, now)) return backed;
  if (!reference || !matchesUnderlying(backed, reference) || !freshQuote(reference, now) ||
    reference.quote.basis !== 'external_reference_non_executable' || reference.quote.currency !== 'USDC' ||
    reference.quote.currencyBasis !== 'market_symbol' || reference.activity.source !== 'Backpack' ||
    reference.activity.scope !== 'external_market' || reference.activity.currency !== 'USDC') return backed;
  return {
    ...backed,
    providerLabel: 'Backed · Share reference',
    quote: { ...reference.quote, basis: 'underlying_share_reference' },
    activity: { ...reference.activity },
    volume24h: reference.volume24h,
    volume24hBasis: reference.volume24hBasis,
  };
}
