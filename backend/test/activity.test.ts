import assert from 'node:assert/strict';
import test from 'node:test';
import { backpackActivity, jupiterActivity, nonnegativeAmount, unavailableJupiterActivity } from '../src/activity.js';
import { parseProviderJson, type GetJson } from '../src/http.js';
import { loadBackpack } from '../src/providers.js';
import { Registry } from '../src/registry.js';
import { ActivitySchema, type Stock } from '../src/schema.js';
import { initialStatistics, normalizeJupiterActivity, TokenStatisticsService } from '../src/statistics.js';

const NOW = Date.parse('2026-09-18T12:00:00.000Z');
const FETCHED = new Date(NOW).toISOString();
const UPDATED = '2026-09-18T11:59:59.557Z';
const MINT_A = 'XspzcW1PRtgf6Wj92HCiZdjzKCyFekVD8P5Ueh3dRMX';
const MINT_B = 'So11111111111111111111111111111111111111112';
const stats24h = { buyVolume: '100.25', sellVolume: '80.75' };
const observation = (id = MINT_A) => ({ id, updatedAt: UPDATED, stats24h, mcap: '1000', liquidity: '25', holderCount: '10', organicScore: '40' });
function stock(provider: 'backed' | 'backpack' = 'backed', mint = MINT_A): Stock {
  const deployments = [{ network: 'Solana', address: mint, decimals: 8, depositEnabled: false, withdrawEnabled: false }];
  return {
    id: `${provider}:MSFT`, provider, providerLabel: provider, providerAssetId: 'MSFT', symbol: 'MSFT', name: 'Microsoft',
    logoUrl: null, underlying: { symbol: 'MSFT', isin: null, cusip: null },
    quote: { price: '497.200000000000000001', currency: provider === 'backed' ? 'USD' : 'USDC',
      currencyBasis: provider === 'backed' ? 'underlying_metadata' : 'market_symbol', changeAmount: null, changePercent: null,
      receivedAt: FETCHED, asOf: null, basis: provider === 'backed' ? 'provider_indicative_token' : 'external_reference_non_executable' },
    volume24h: provider === 'backpack' ? '900' : null, volume24hBasis: provider === 'backpack' ? 'quote_currency_turnover' : null,
    activity: provider === 'backpack' ? backpackActivity('900', FETCHED) : unavailableJupiterActivity('Not received'),
    statistics: initialStatistics(deployments), deployments, trading: { enabled: false, reason: 'Not connected' },
  };
}

test('24h turnover and signed net preserve decimal precision without using organic volume or other intervals', () => {
  const raw = parseProviderJson('{"buyVolume":100000000000000000000.123456789012345678901,"sellVolume":100000000000000000000.123456789012345678899,"buyOrganicVolume":9,"sellOrganicVolume":8}');
  const value = jupiterActivity(raw, FETCHED, UPDATED);
  assert.equal(value.volume24h, '200000000000000000000.2469135780246913578');
  assert.equal(value.netVolume24h, '0.000000000000000000002');
  assert.equal(value.currency, 'USD');
  assert.equal(value.source, 'Jupiter');
  assert.equal(value.scope, 'solana_token');
  assert.equal(value.receivedAt, FETCHED);
  assert.equal(value.updatedAt, UPDATED);
  assert.equal(value.volumeReason, null);
  const normalized = normalizeJupiterActivity([{ ...observation(), stats5m: { buyVolume: '100000', sellVolume: '1' } }], [MINT_A], NOW).get(MINT_A)!;
  assert.equal(normalized.volume24h, '181');
  assert.equal(normalized.netVolume24h, '19.5');
});

test('negative directional net and observed zero turnover are real values', () => {
  assert.equal(jupiterActivity({ buyVolume: '2.1', sellVolume: '10.7' }, FETCHED, UPDATED).netVolume24h, '-8.6');
  const zero = jupiterActivity({ buyVolume: '0', sellVolume: '0' }, FETCHED, UPDATED);
  assert.equal(zero.volume24h, '0');
  assert.equal(zero.netVolume24h, '0');
  assert.equal(zero.netVolumeReason, null);
  assert.equal(backpackActivity('0', FETCHED).volume24h, '0');
});

test('a missing or invalid side cannot fabricate total turnover or directional net', () => {
  for (const raw of [null, {}, { buyVolume: '2' }, { sellVolume: '2' }, { buyVolume: '2', sellVolume: null },
    { buyVolume: '-2', sellVolume: '1' }, { buyVolume: 'NaN', sellVolume: '1' }, { buyVolume: '1e999999', sellVolume: '1' },
    { buyVolume: '1e-101', sellVolume: '1' }, { buyVolume: '1e40', sellVolume: '1' }]) {
    const value = jupiterActivity(raw, FETCHED, UPDATED);
    assert.equal(value.volume24h, null);
    assert.equal(value.netVolume24h, null);
    assert.ok(value.volumeReason?.trim());
    assert.ok(value.netVolumeReason?.trim());
  }
  assert.equal(nonnegativeAmount('1e3'), '1000');
  assert.equal(nonnegativeAmount('Infinity'), null);
});

test('exact mint is required; same-symbol, ambiguous and missing records remain unavailable', () => {
  for (const rows of [[], [{ ...observation(MINT_B), symbol: 'MSFT' }], [observation(), observation()]]) {
    const value = normalizeJupiterActivity(rows, [MINT_A], NOW).get(MINT_A)!;
    assert.equal(value.volume24h, null);
    assert.equal(value.netVolume24h, null);
  }
});

test('rolling activity rejects uninitialized, old and future source timestamps and preserves its source time', () => {
  for (const updatedAt of [undefined, 'bad-time', '0001-01-01T00:00:00Z',
    '2026-09-18T11:54:59.999Z', '2026-09-18T12:00:00.001Z']) {
    assert.equal(normalizeJupiterActivity([{ ...observation(), updatedAt }], [MINT_A], NOW).get(MINT_A)!.volume24h, null);
  }
  const edge = normalizeJupiterActivity([{ ...observation(), updatedAt: '2026-09-18T11:55:00Z' }], [MINT_A], NOW).get(MINT_A)!;
  assert.equal(edge.volume24h, '181');
  assert.equal(edge.updatedAt, '2026-09-18T11:55:00.000Z');
});

test('Backpack turnover stays USDC and missing buy/sell breakdown is explicit, never borrowed from xStocks', async () => {
  const service = new TokenStatisticsService(async () => [observation(), { ...observation(MINT_B), stats24h: { buyVolume: '9999', sellVolume: '1' } }], () => NOW);
  const [backed, backpack] = await service.enrich([stock(), stock('backpack', MINT_B)]);
  assert.equal(backed!.activity.volume24h, '181');
  assert.equal(backed!.activity.netVolume24h, '19.5');
  assert.equal(backed!.volume24h, null, 'legacy provider turnover contract stays unchanged');
  assert.equal(backpack!.activity.volume24h, '900');
  assert.equal(backpack!.activity.netVolume24h, null);
  assert.equal(backpack!.activity.currency, 'USDC');
  assert.equal(backpack!.activity.updatedAt, null);
  assert.match(backpack!.activity.netVolumeReason!, /buy\/sell/);
  assert.deepEqual(backed!.quote, stock().quote);
  assert.deepEqual(backpack!.quote, stock('backpack', MINT_B).quote);
});

test('activity shares one statistics fetch and cached source/fetch times, then clears independently on outage', async () => {
  let clock = NOW; let calls = 0; let failed = false;
  const service = new TokenStatisticsService(async () => { calls++; if (failed) throw new Error('PRIVATE_DETAILS'); return [observation()]; }, () => clock);
  const [first, same] = await Promise.all([service.enrich([stock()]), service.enrich([stock()])]);
  assert.equal(calls, 1);
  assert.deepEqual(first, same);
  clock += 59_000;
  assert.deepEqual((await service.enrich([stock()]))[0]!.activity, first[0]!.activity);
  clock += 2_000; failed = true;
  const value = (await service.enrich([stock()]))[0]!;
  assert.equal(calls, 2);
  assert.equal(value.activity.volume24h, null);
  assert.equal(value.activity.netVolume24h, null);
  assert.equal(value.activity.receivedAt, null);
  assert.equal(value.activity.updatedAt, null);
  assert.equal(value.quote.price, stock().quote.price);
  assert.ok(!JSON.stringify(value).includes('PRIVATE_DETAILS'));
});

test('re-fetching old provider metadata cannot reset the age of a rolling 24h observation', async () => {
  let clock = NOW;
  const service = new TokenStatisticsService(async () => [observation()], () => clock);
  assert.equal((await service.enrich([stock()]))[0]!.activity.volume24h, '181');
  clock += 301_000;
  assert.equal((await service.enrich([stock()]))[0]!.activity.volume24h, null);
});

test('absence of an unambiguous Solana deployment cannot make an activity request or change a valid price', async () => {
  let calls = 0;
  const input = { ...stock(), deployments: [] };
  const value = (await new TokenStatisticsService(async () => { calls++; return []; }, () => NOW).enrich([input]))[0]!;
  assert.equal(calls, 0);
  assert.equal(value.activity.volume24h, null);
  assert.equal(value.quote.price, input.quote.price);
  assert.match(value.activity.volumeReason!, /No verified Solana/);
});

test('contract rejects inconsistent units, impossible net, missing provenance and blank unavailable reasons', () => {
  const valid = jupiterActivity(stats24h, FETCHED, UPDATED);
  for (const patch of [{ currency: 'USDC' }, { scope: 'external_market' }, { volume24h: '-1' },
    { netVolume24h: '181.01' }, { netVolume24h: '-181.01' }, { receivedAt: null }, { updatedAt: null },
    { volume24h: null, volumeReason: 'Not reported' }, { volumeReason: ' ' },
    { volume24h: null, netVolume24h: null, volumeReason: ' ', netVolumeReason: 'Unavailable' }]) {
    assert.equal(ActivitySchema.safeParse({ ...valid, ...patch }).success, false, JSON.stringify(patch));
  }
  assert.equal(ActivitySchema.safeParse({ ...valid, netVolume24h: '-181' }).success, true);
  assert.equal(ActivitySchema.safeParse(backpackActivity(null, FETCHED)).success, true);
});

const msft = Registry.find(entry => entry.symbol === 'MSFT')!;
function backpackHttp(tickers: unknown[]): GetJson {
  return async (_provider, path) => {
    if (path.endsWith('/securities')) return [{ asset: 'MSFT.US', cusip: msft.cusip, name: 'Microsoft' }];
    if (path.endsWith('/assets')) return [];
    if (path.endsWith('/tickers')) return tickers;
    throw new Error('Unexpected path');
  };
}
const ticker = { symbol: 'MSFT.US_USDC', lastPrice: '497.25', priceChange: '1.2', priceChangePercent: '0.01', quoteVolume: '550.75', volume: '999999' };
test('provider normalization keeps a valid price when turnover is missing or invalid', async () => {
  for (const quoteVolume of [undefined, null, '-1', 'Infinity', '1e41']) {
    const result = await loadBackpack(backpackHttp([{ ...ticker, quoteVolume }]), () => NOW);
    const value = result.stocks[0]!;
    assert.equal(result.partial, false);
    assert.equal(value.quote.price, '497.25');
    assert.equal(value.volume24h, null);
    assert.equal(value.volume24hBasis, null);
    assert.equal(value.activity.volume24h, null);
    assert.ok(value.activity.volumeReason);
  }
});

test('zero/invalid prices and ambiguous tickers fail closed without guessing another source or hiding valid turnover', async () => {
  for (const lastPrice of ['0', '-1', 'NaN', '1e41']) {
    const result = await loadBackpack(backpackHttp([{ ...ticker, lastPrice }]), () => NOW);
    assert.equal(result.partial, true);
    assert.equal(result.stocks[0]!.quote.price, null);
    assert.equal(result.stocks[0]!.quote.changePercent, null);
    assert.equal(result.stocks[0]!.activity.volume24h, '550.75');
  }
  const ambiguous = await loadBackpack(backpackHttp([ticker, { ...ticker, lastPrice: '1' }]), () => NOW);
  assert.equal(ambiguous.stocks[0]!.quote.price, null);
  assert.equal(ambiguous.stocks[0]!.activity.volume24h, null);
});
