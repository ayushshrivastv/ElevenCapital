import test from 'node:test';
import assert from 'node:assert/strict';
import { findShareReference, applyShareReference } from '../src/share-reference.js';
import { initialStatistics } from '../src/statistics.js';
import { backpackActivity, unavailableJupiterActivity } from '../src/activity.js';
import type { Stock } from '../src/schema.js';

const NOW = Date.parse('2026-09-19T07:00:00Z');
const iso = (age = 0) => new Date(NOW - age).toISOString();
function stock(provider: 'backed' | 'backpack'): Stock {
  const backed = provider === 'backed';
  return {
    id: backed ? 'backed:issuer-uuid' : 'backpack:MSFT.US', provider, providerAssetId: backed ? 'issuer-uuid' : 'MSFT.US',
    providerLabel: backed ? 'Backed xStocks' : 'Backpack Securities', symbol: backed ? 'MSFTx' : 'MSFT.US', name: 'Microsoft', logoUrl: null,
    underlying: { symbol: 'MSFT', isin: backed ? 'US5949181045' : null, cusip: null, listingCountry: 'US', currency: 'USD' },
    quote: { price: backed ? null : '497.25', currency: backed ? 'USD' : 'USDC', currencyBasis: backed ? 'quoted_currency' : 'market_symbol',
      changeAmount: backed ? null : '2.25', changePercent: backed ? null : '0.45', asOf: null, receivedAt: backed ? null : iso(),
      basis: backed ? 'onchain_token_market' : 'external_reference_non_executable' },
    activity: backed ? unavailableJupiterActivity('Not received') : backpackActivity('12345.67', iso()),
    volume24h: backed ? null : '12345.67', volume24hBasis: backed ? null : 'quote_currency_turnover',
    statistics: initialStatistics([]), deployments: [], trading: { enabled: false, reason: 'Read-only' },
  };
}
test('labelled reference keeps issuer identity, USDC, source timestamps and token statistics without mutating inputs', () => {
  const backed = stock('backed'); const reference = stock('backpack');
  const originals = structuredClone({ backed, reference });
  assert.equal(findShareReference(backed, [reference]), reference);
  const shown = applyShareReference(backed, reference, NOW);
  assert.equal(shown.id, backed.id); assert.equal(shown.providerAssetId, backed.providerAssetId);
  assert.equal(shown.provider, 'backed'); assert.equal(shown.providerLabel, 'Backed · Share reference');
  assert.equal(shown.quote.currency, 'USDC'); assert.equal(shown.quote.basis, 'underlying_share_reference');
  assert.equal(shown.quote.price, reference.quote.price); assert.equal(shown.quote.receivedAt, reference.quote.receivedAt);
  assert.deepEqual(shown.activity, reference.activity); assert.equal(shown.volume24h, reference.volume24h);
  assert.equal(shown.statistics, backed.statistics); assert.equal(shown.deployments, backed.deployments);
  assert.equal(shown.trading.enabled, false); assert.deepEqual({ backed, reference }, originals);
  assert.notEqual(shown.quote, reference.quote); assert.notEqual(shown.activity, reference.activity);
});
test('fresh native token quote wins; missing, stale and future native quotes can use a fresh reference', () => {
  const reference = stock('backpack');
  const backed = stock('backed'); backed.quote.price = '490'; backed.quote.receivedAt = iso();
  assert.equal(applyShareReference(backed, reference, NOW), backed);
  for (const age of [300_001, -1]) {
    const outdated = structuredClone(backed); outdated.quote.receivedAt = iso(age);
    assert.equal(applyShareReference(outdated, reference, NOW).quote.basis, 'underlying_share_reference');
  }
  const boundary = structuredClone(backed); boundary.quote.receivedAt = iso(300_000);
  assert.equal(applyShareReference(boundary, reference, NOW), boundary);
  assert.equal(applyShareReference(stock('backed'), reference, NOW).quote.basis, 'underlying_share_reference');
});
test('reference needs valid positive price and fresh retrieval plus source timestamp when present', () => {
  const backed = stock('backed');
  for (const age of [300_001, -1]) {
    for (const key of ['receivedAt', 'asOf'] as const) {
      const reference = stock('backpack'); reference.quote[key] = iso(age);
      assert.equal(applyShareReference(backed, reference, NOW), backed);
    }
  }
  for (const price of [null, '0', '-1', '1e-999999999', 'invalid']) {
    const reference = stock('backpack'); reference.quote.price = price;
    assert.equal(applyShareReference(backed, reference, NOW), backed);
  }
  const boundary = stock('backpack'); boundary.quote.receivedAt = iso(300_000);
  assert.equal(applyShareReference(backed, boundary, NOW).quote.basis, 'underlying_share_reference');
  assert.equal(applyShareReference(backed, undefined, NOW), backed);
});
test('known ISIN/CUSIP conflicts fail closed even with matching tickers', () => {
  const backed = stock('backed');
  for (const identity of [{ isin: 'US0378331005', cusip: null }, { isin: null, cusip: '037833100' }, { isin: 'bad', cusip: null }]) {
    const reference = stock('backpack'); Object.assign(reference.underlying, identity);
    assert.equal(findShareReference(backed, [reference]), undefined);
    assert.equal(applyShareReference(backed, reference, NOW), backed);
  }
  const reference = stock('backpack'); reference.underlying.cusip = '594918104';
  assert.equal(findShareReference(backed, [reference]), reference);
  const conflicting = structuredClone(backed); conflicting.underlying.cusip = '037833100';
  assert.equal(findShareReference(conflicting, [reference]), undefined);
});
test('matching display names, foreign symbols, missing metadata, derivatives and ambiguous candidates never select a reference', () => {
  const backed = stock('backed'); const reference = stock('backpack');
  for (const identity of [{ symbol: 'AAPL' }, { listingCountry: 'HK' }, { listingCountry: undefined }, { currency: 'GBP' }, { currency: undefined }]) {
    const candidate = structuredClone(reference); Object.assign(candidate.underlying, identity);
    assert.equal(findShareReference(backed, [candidate]), undefined);
  }
  const foreign = structuredClone(backed); foreign.underlying.listingCountry = 'GB';
  assert.equal(findShareReference(foreign, [reference]), undefined);
  const perp = structuredClone(reference); perp.providerAssetId = 'MSFT-PERP';
  assert.equal(findShareReference(backed, [perp]), undefined);
  assert.equal(findShareReference(backed, [reference, structuredClone(reference)]), undefined);
  assert.equal(findShareReference(backed, [backed]), undefined);
});
test('only the Backpack external-market USDC reference basis is eligible, never another token quote', () => {
  const backed = stock('backed');
  const token = stock('backpack'); token.quote.basis = 'onchain_token_market';
  assert.equal(applyShareReference(backed, token, NOW), backed);
  const currency = stock('backpack'); currency.quote.currency = 'USD';
  assert.equal(applyShareReference(backed, currency, NOW), backed);
  const activity = stock('backpack'); activity.activity = unavailableJupiterActivity('Wrong source');
  assert.equal(applyShareReference(backed, activity, NOW), backed);
});
