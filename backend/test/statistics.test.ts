import assert from 'node:assert/strict';
import test from 'node:test';
import { minimumRequestSpacing, parseProviderJson, type GetJson } from '../src/http.js';
import { MetricSchema, type Stock } from '../src/schema.js';
import { initialStatistics, normalizeJupiterStatistics, solanaMint, TokenStatisticsService } from '../src/statistics.js';
import { unavailableJupiterActivity } from '../src/activity.js';

const NOW = Date.parse('2026-09-18T12:00:00.000Z');
const MINT_A = 'XspzcW1PRtgf6Wj92HCiZdjzKCyFekVD8P5Ueh3dRMX';
const MINT_B = 'So11111111111111111111111111111111111111112';
const deployment = (address: string, network = 'Solana'): Stock['deployments'][number] => ({
  address, network, decimals: 8, depositEnabled: false, withdrawEnabled: false,
});
function stock(mint = MINT_A, id = 'backed:asset-a'): Stock {
  const deployments = [deployment(mint)];
  return {
    id, provider: id.startsWith('backed:') ? 'backed' : 'backpack', providerLabel: 'Provider', providerAssetId: id,
    symbol: 'MSFTx', name: 'Microsoft', logoUrl: null,
    underlying: { symbol: 'MSFT', isin: 'US5949181045', cusip: '594918104' },
    quote: { price: '497.25', currency: 'USD', currencyBasis: 'underlying_metadata', changeAmount: null,
      changePercent: null, asOf: null, receivedAt: new Date(NOW).toISOString(), basis: 'provider_indicative_token' },
    volume24h: null, volume24hBasis: null, deployments, statistics: initialStatistics(deployments),
    activity: unavailableJupiterActivity('Not received'),
    trading: { enabled: false, reason: 'Not connected' },
  };
}
function observation(id = MINT_A) {
  return { id, mcap: '52563755.03160228', liquidity: '563042.8596252465', holderCount: '23412',
    organicScore: '77.48139464970512', updatedAt: '2026-09-18T11:59:59.557677389Z' };
}
const metricNames = ['marketCapitalization', 'liquidity', 'holderCount', 'organicScore'] as const;

test('only one exact issuer Solana mint can be used; ambiguous, unknown-chain and malformed deployments fail closed', () => {
  assert.equal(solanaMint([deployment(MINT_A, 'solana')]), MINT_A);
  assert.equal(solanaMint([deployment(MINT_A), deployment(MINT_A)]), MINT_A);
  assert.equal(solanaMint([deployment(MINT_A), deployment(MINT_B)]), null);
  assert.equal(solanaMint([deployment(MINT_A, 'Ethereum')]), null);
  assert.equal(solanaMint([deployment('https://example.com/attack')]), null);
});

test('all four metrics preserve raw decimal precision, token scope and separate provider/fetch timestamps', () => {
  const raw = parseProviderJson('[{"id":"' + MINT_A + '","mcap":52563755.0316022800001,"liquidity":563042.8596252465,"holderCount":23412,"organicScore":77.48139464970512,"updatedAt":"2026-09-18T11:59:59.557677389Z"}]');
  const metrics = normalizeJupiterStatistics(raw, [MINT_A], NOW).get(MINT_A)!;
  assert.equal(metrics.scope, 'solana_token');
  assert.equal(metrics.network, 'solana');
  assert.equal(metrics.mint, MINT_A);
  assert.equal(metrics.updatedAt, '2026-09-18T11:59:59.557Z');
  assert.equal(metrics.marketCapitalization.value, '52563755.0316022800001');
  assert.equal(metrics.liquidity.basis, 'reported_token_liquidity');
  assert.equal(metrics.holderCount.value, '23412');
  assert.equal(metrics.organicScore.value, '77.48139464970512');
  for (const name of metricNames) {
    assert.equal(metrics[name].source, 'Jupiter');
    assert.equal(metrics[name].status, 'available');
    assert.equal(metrics[name].receivedAt, '2026-09-18T12:00:00.000Z');
    assert.equal(metrics[name].reason, null);
  }
});

test('unknown records and same-symbol different mints never enrich a provider product', async () => {
  const requests: string[] = [];
  const http: GetJson = async (provider, path, query) => {
    assert.equal(provider, 'jupiter'); assert.equal(path, '/tokens/v2/search');
    requests.push(query!.query!);
    return [{ ...observation(MINT_A), symbol: 'MSFT.US' }];
  };
  const result = await new TokenStatisticsService(http, () => NOW).enrich([stock(), stock(MINT_B, 'backpack:MSFT.US')]);
  assert.equal(requests.length, 1);
  assert.deepEqual(requests[0]!.split(',').sort(), [MINT_A, MINT_B].sort());
  assert.equal(result[0]!.statistics.marketCapitalization.value, observation().mcap);
  assert.equal(result[1]!.statistics.marketCapitalization.value, null);
  assert.equal(result[1]!.statistics.mint, MINT_B);
});

test('missing market cap never borrows FDV, company capitalization, total supply or another field', () => {
  const row = { ...observation(), mcap: null, fdv: '9999999', companyMarketCap: '3000000000000', totalSupply: '10000' };
  const metrics = normalizeJupiterStatistics([row], [MINT_A], NOW).get(MINT_A)!;
  assert.equal(metrics.marketCapitalization.value, null);
  assert.equal(metrics.marketCapitalization.status, 'unavailable');
  assert.equal(metrics.liquidity.value, observation().liquidity);
  assert.equal(metrics.holderCount.value, observation().holderCount);
});

test('real observed zero is retained; negative, fractional counts and out-of-range scores are unavailable', () => {
  const zero = normalizeJupiterStatistics([{ ...observation(), mcap: '0', liquidity: '0', holderCount: '0', organicScore: '0' }], [MINT_A], NOW).get(MINT_A)!;
  for (const name of metricNames) { assert.equal(zero[name].value, '0'); assert.equal(zero[name].status, 'available'); }
  const invalid = normalizeJupiterStatistics([{ ...observation(), mcap: '-1', liquidity: '1e99999999', holderCount: '23.5', organicScore: '100.01' }], [MINT_A], NOW).get(MINT_A)!;
  for (const name of metricNames) { assert.equal(invalid[name].value, null); assert.equal(invalid[name].status, 'unavailable'); }
});

test('metadata-only year-0001 records cannot turn organicScore zero into a measured observation', () => {
  for (const updatedAt of ['0001-01-01T00:00:00Z', 'bad-date', undefined, '2026-09-19T12:00:00Z']) {
    const metrics = normalizeJupiterStatistics([{ id: MINT_A, organicScore: '0', updatedAt }], [MINT_A], NOW).get(MINT_A)!;
    assert.equal(metrics.updatedAt, null);
    for (const name of metricNames) { assert.equal(metrics[name].value, null); assert.equal(metrics[name].status, 'unavailable'); }
  }
});

test('duplicate exact-mint records are ambiguous and become unavailable without selecting an arbitrary value', () => {
  const metrics = normalizeJupiterStatistics([observation(), { ...observation(), mcap: '1' }], [MINT_A], NOW).get(MINT_A)!;
  assert.equal(metrics.marketCapitalization.value, null);
  assert.equal(metrics.updatedAt, null);
});

test('statistics cache deduplicates concurrent requests, preserves fetch time, and clears values after refresh failure', async () => {
  let clock = NOW; let calls = 0; let fail = false;
  const http: GetJson = async () => {
    calls++; await Promise.resolve();
    if (fail) throw new Error('SECRET_PROVIDER_FAILURE');
    return [observation()];
  };
  const service = new TokenStatisticsService(http, () => clock);
  const [first, same] = await Promise.all([service.enrich([stock()]), service.enrich([stock()])]);
  assert.equal(calls, 1);
  assert.deepEqual(first, same);
  clock += 59_000;
  const cached = await service.enrich([stock()]);
  assert.equal(calls, 1);
  assert.equal(cached[0]!.statistics.holderCount.receivedAt, first[0]!.statistics.holderCount.receivedAt);
  clock += 2_000; fail = true;
  const failed = await service.enrich([stock()]);
  assert.equal(calls, 2);
  assert.equal(failed[0]!.statistics.holderCount.value, null);
  assert.equal(failed[0]!.statistics.holderCount.receivedAt, null);
  assert.equal(failed[0]!.statistics.updatedAt, null);
  assert.equal(failed[0]!.quote.price, stock().quote.price);
  assert.ok(!JSON.stringify(failed).includes('SECRET'));
  await service.enrich([stock()]); assert.equal(calls, 2, 'outage backoff prevents a retry burst');
});

test('no Solana deployment makes no statistics request and retains the quote', async () => {
  let calls = 0;
  const source = { ...stock(), deployments: [deployment('0xabc', 'Ethereum')] };
  const [result] = await new TokenStatisticsService(async () => { calls++; return []; }, () => NOW).enrich([source]);
  assert.equal(calls, 0);
  assert.equal(result!.quote.price, source.quote.price);
  assert.equal(result!.statistics.mint, null);
  for (const name of metricNames) assert.equal(result!.statistics[name].value, null);
});

test('changing issuer deployment invalidates identity cache and fetches the new exact mint', async () => {
  const queried: string[] = [];
  const service = new TokenStatisticsService(async (_provider, _path, query) => {
    queried.push(query!.query!); return [observation(query!.query!)];
  }, () => NOW);
  const first = await service.enrich([stock()]);
  const second = await service.enrich([stock(MINT_B)]);
  assert.deepEqual(queried, [MINT_A, MINT_B]);
  assert.notEqual(first[0]!.statistics.mint, second[0]!.statistics.mint);
});

test('requests batch at most 100 exact mint addresses without dropping later stocks', async () => {
  const alphabet = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
  const stocks = Array.from({ length: 101 }, (_, index) => stock('1'.repeat(30) + alphabet[Math.floor(index / 58)]! + alphabet[index % 58]!, `backed:${index}`));
  const sizes: number[] = [];
  const service = new TokenStatisticsService(async (_provider, _path, query) => {
    const mints = query!.query!.split(','); sizes.push(mints.length); return mints.map(observation);
  }, () => NOW);
  const result = await service.enrich(stocks);
  assert.deepEqual(sizes, [100, 1]);
  assert.equal(result.length, 101);
  assert.ok(result.every(item => item.statistics.marketCapitalization.value !== null));
});

test('minimum request spacing serializes concurrent callers and allows an immediate call after an idle window', async () => {
  let now = 1000;
  const waits: number[] = [];
  const limiter = minimumRequestSpacing(2050, () => now, async ms => { waits.push(ms); now += ms; });
  await Promise.all([limiter(), limiter(), limiter()]);
  assert.deepEqual(waits, [2050, 2050]);
  now += 5000; await limiter(); assert.equal(waits.length, 2);
});

test('contract rejects invalid status/value combinations and out-of-domain metric values', () => {
  const metric = normalizeJupiterStatistics([observation()], [MINT_A], NOW).get(MINT_A)!.holderCount;
  assert.equal(MetricSchema.safeParse({ ...metric, value: '1.5' }).success, false);
  assert.equal(MetricSchema.safeParse({ ...metric, value: '-1' }).success, false);
  assert.equal(MetricSchema.safeParse({ ...metric, value: '9223372036854775808' }).success, false);
  assert.equal(MetricSchema.safeParse({ ...metric, status: 'unavailable' }).success, false);
  assert.equal(MetricSchema.safeParse({ ...metric, source: null }).success, false);
});
