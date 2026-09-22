import assert from 'node:assert/strict';
import test from 'node:test';
import { createGetJson, type GetJson } from '../src/http.js';
import { geckoRangeRequest, normalizeGeckoOhlcv, selectGeckoPool, TokenHistoryService, type GeckoPool } from '../src/token-history.js';

const NOW = Date.parse('2026-09-22T20:00:00.000Z');
const MINT = 'XspzcW1PRtgf6Wj92HCiZdjzKCyFekVD8P5Ueh3dRMX';
const OTHER = 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v';
const WRONG = 'So11111111111111111111111111111111111111112';
const POOL = 'D6bRhQUcR9B7bPbbqgxpE17MjyUjBtr8hHQCcJoHrrv1';

function poolRow(address = POOL, base = MINT, quote = OTHER, createdAt?: string) {
  return { id: `solana_${address}`, type: 'pool', attributes: { address, reserve_in_usd: '296774.9285', volume_usd: { h24: '1203379.20' },
    ...(createdAt ? { pool_created_at: createdAt } : {}) },
    relationships: { base_token: { data: { id: `solana_${base}`, type: 'token' } },
      quote_token: { data: { id: `solana_${quote}`, type: 'token' } } } };
}

function candleResponse(pool: GeckoPool, times: number[]) {
  return { data: { id: 'request', type: 'ohlcv_request_response', attributes: { ohlcv_list: times.map((timestamp, index) =>
    [String(timestamp), `${100 + index}.1`, `${101 + index}.1`, `${99 + index}.1`, `${100 + index}.123456789012345678`, `${1000 + index}`]) } },
    meta: { base: { address: pool.baseAddress }, quote: { address: pool.quoteAddress } } };
}

test('pool selection preserves provider rank and proves the verified mint is in an active Solana pair', () => {
  const selection = selectGeckoPool({ data: [poolRow(POOL, WRONG, OTHER), poolRow()] }, MINT);
  assert.deepEqual(selection, { address: POOL, baseAddress: MINT, quoteAddress: OTHER, createdAt: null });
  assert.equal(selectGeckoPool({ data: [poolRow(POOL, WRONG, OTHER)] }, MINT), null);
  assert.equal(selectGeckoPool({ data: [{ ...poolRow(), attributes: { ...poolRow().attributes, reserve_in_usd: '0' } }] }, MINT), null);
  assert.throws(() => selectGeckoPool({ data: Array.from({ length: 21 }, () => poolRow()) }, MINT), /pool response/);
});

test('OHLCV normalization verifies pool token metadata and keeps exact USD closes without float rounding', () => {
  const pool = { address: POOL, baseAddress: MINT, quoteAddress: OTHER, createdAt: null };
  const seconds = Math.floor(NOW / 1000);
  const page = normalizeGeckoOhlcv(candleResponse(pool, [seconds - 60, seconds - 180, seconds - 120]), pool, NOW - 3_600_000, NOW);
  assert.deepEqual(page.points.map(point => point.timestamp), [seconds - 180, seconds - 120, seconds - 60].map(value => new Date(value * 1000).toISOString()));
  assert.equal(page.points[0]!.price, '101.123456789012345678');
  assert.throws(() => normalizeGeckoOhlcv({ ...candleResponse(pool, [seconds - 60]),
    meta: { base: { address: WRONG }, quote: { address: OTHER } } }, pool, NOW - 3_600_000, NOW), /identity mismatch/);
  const conflict = candleResponse(pool, [seconds - 60, seconds - 60]);
  assert.throws(() => normalizeGeckoOhlcv(conflict, pool, NOW - 3_600_000, NOW), /Conflicting/);
});

test('range plans fit public response bounds and cap year-to-date pagination', () => {
  assert.deepEqual(geckoRangeRequest('ONE_DAY', NOW), { timeframe: 'minute', aggregate: '15', limit: 96, since: NOW - 86_400_000, pages: 1 });
  assert.deepEqual(geckoRangeRequest('ONE_WEEK', NOW), { timeframe: 'hour', aggregate: '4', limit: 42, since: NOW - 604_800_000, pages: 1 });
  const ytd = geckoRangeRequest('YEAR_TO_DATE', NOW);
  assert.equal(ytd.since, Date.UTC(2026, 0, 1)); assert.equal(ytd.limit, 100); assert.equal(ytd.pages, 4);
});

test('detail history uses exact mint orientation and caches pool discovery plus candles', async () => {
  const calls: { provider: string; path: string; query?: Record<string, string> }[] = [];
  const pool = { address: POOL, baseAddress: MINT, quoteAddress: OTHER, createdAt: null };
  const seconds = Math.floor(NOW / 1000);
  const http: GetJson = async (provider, path, query) => {
    calls.push({ provider, path, query });
    assert.equal(provider, 'geckoterminal');
    if (path.endsWith('/pools')) return { data: [poolRow()] };
    assert.equal(path, `/api/v2/networks/solana/pools/${POOL}/ohlcv/minute`);
    assert.equal(query!.token, MINT); assert.equal(query!.currency, 'usd'); assert.equal(query!.aggregate, '15');
    return candleResponse(pool, [seconds - 2700, seconds - 1800, seconds - 900, seconds]);
  };
  const service = new TokenHistoryService(http, () => NOW);
  const first = await service.chart('backed:one', MINT, 'ONE_DAY');
  const second = await service.chart('prestocks:one', MINT, 'ONE_DAY');
  assert.equal(first.status, 'ok'); assert.equal(first.basis, 'onchain_token_market'); assert.equal(first.currency, 'USD');
  assert.equal(first.points.length, 4); assert.match(first.statusReason!, /GeckoTerminal exact-mint/);
  assert.equal(second.stockId, 'prestocks:one'); assert.deepEqual(second.points, first.points);
  assert.equal(calls.length, 2, 'same exact mint and range share both caches');
});

test('one or two exact-mint candles are explicitly insufficient rather than presented as full history', async () => {
  const pool = { address: POOL, baseAddress: MINT, quoteAddress: OTHER, createdAt: null };
  const seconds = Math.floor(NOW / 1000);
  const http: GetJson = async (_provider, path) => path.endsWith('/pools') ? { data: [poolRow()] } : candleResponse(pool, [seconds - 60, seconds]);
  const chart = await new TokenHistoryService(http, () => NOW).chart('prestocks:one', MINT, 'ONE_DAY');
  assert.equal(chart.status, 'unavailable'); assert.deepEqual(chart.points, []); assert.match(chart.statusReason!, /only 2 exact-mint candles/);
});

test('a transient empty pool page is retried after series expiry instead of being cached as no pool', async () => {
  let now = NOW; let poolCalls = 0; let candleCalls = 0;
  const pool = { address: POOL, baseAddress: MINT, quoteAddress: OTHER, createdAt: null };
  const http: GetJson = async (_provider, path) => {
    if (path.endsWith('/pools')) return ++poolCalls === 1 ? { data: [] } : { data: [poolRow()] };
    candleCalls++;
    const seconds = Math.floor(now / 1000);
    return candleResponse(pool, [seconds - 2700, seconds - 1800, seconds - 900, seconds]);
  };
  const service = new TokenHistoryService(http, () => now);
  const empty = await service.chart('prestocks:one', MINT, 'ONE_DAY');
  assert.equal(empty.status, 'unavailable'); assert.equal(poolCalls, 1); assert.equal(candleCalls, 0);
  now += 59_000;
  const cached = await service.chart('prestocks:one', MINT, 'ONE_DAY');
  assert.equal(cached.status, 'unavailable'); assert.equal(poolCalls, 1);
  now += 2_000;
  const recovered = await service.chart('prestocks:one', MINT, 'ONE_DAY');
  assert.equal(recovered.status, 'ok'); assert.equal(recovered.points.length, 4);
  assert.equal(poolCalls, 2); assert.equal(candleCalls, 1);
});

test('year-to-date history fails closed when public candles stop after a pool that predates the requested window', async () => {
  const pool = { address: POOL, baseAddress: MINT, quoteAddress: OTHER, createdAt: Date.parse('2025-01-01T00:00:00Z') };
  const times = ['2026-06-01T00:00:00Z', '2026-07-01T00:00:00Z', '2026-08-01T00:00:00Z'].map(value => Date.parse(value) / 1000);
  const row = poolRow(POOL, MINT, OTHER, '2025-01-01T00:00:00Z');
  const http: GetJson = async (_provider, path) => path.endsWith('/pools') ? { data: [row] } : candleResponse(pool, times);
  const chart = await new TokenHistoryService(http, () => NOW).chart('backed:one', MINT, 'YEAR_TO_DATE');
  assert.equal(chart.status, 'unavailable'); assert.deepEqual(chart.points, []); assert.match(chart.statusReason!, /did not cover the requested year-to-date window/);
});

test('HTTP transport confines public history to allowlisted GeckoTerminal paths and queries', async () => {
  const originalFetch = globalThis.fetch;
  const requested: string[] = [];
  try {
    globalThis.fetch = (async input => { requested.push(String(input)); return new Response('{"data":[]}', { status: 200 }); }) as typeof fetch;
    const http = createGetJson();
    await http('geckoterminal', `/api/v2/networks/solana/tokens/${MINT}/pools`, { include: 'base_token,quote_token', page: '1' });
    assert.equal(requested[0], `https://api.geckoterminal.com/api/v2/networks/solana/tokens/${MINT}/pools?include=base_token%2Cquote_token&page=1`);
    await assert.rejects(http('geckoterminal', '/api/v2/networks/eth/pools'), /Invalid GeckoTerminal request/);
    await assert.rejects(http('geckoterminal', `/api/v2/networks/solana/tokens/${MINT}/pools`, { include: 'base_token,quote_token', page: '1', url: 'https://evil.example' }), /Invalid GeckoTerminal request/);
  } finally { globalThis.fetch = originalFetch; }
});
