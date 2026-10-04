import assert from 'node:assert/strict';
import test from 'node:test';
import bs58 from 'bs58';
import { PantaClient, PantaClientError, createPantaClientFromEnv } from '../src/panta-client.js';

const KEY = 'pk_test_local_fixture_only';
const MARKET = '9xQeWvG816bUx9EPf29DqVU1vDUbtWKVnwG1UxVajZ5J';
const WALLET = 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v';
const SIGNATURE = bs58.encode(Buffer.alloc(64, 1));
const market = (overrides: Record<string, unknown> = {}) => ({
  marketId: MARKET, category: 'crypto', title: 'ETH above 5k?', phase: 'primary',
  images: ['https://example.org/eth.png'], yesPrice: null, noPrice: null, ...overrides,
});
const quote = (overrides: Record<string, unknown> = {}) => ({
  quoteId: 'qt_abc123', marketId: MARKET, side: 'yes', amountUsdc: '20.00',
  shares: '38.42', avgPrice: '0.520800', feeUsdc: '0.40',
  expiresAt: '2026-10-11T12:00:00Z', ...overrides,
});
const build = (overrides: Record<string, unknown> = {}) => ({
  orderId: 'ord_abc123', quoteId: 'qt_abc123', wallet: WALLET, marketId: MARKET,
  side: 'yes', amountUsdc: '20.00', expectedShares: '38.40', feeUsdc: '0.40',
  status: 'built', recentBlockhash: MARKET,
  instructions: [{ programId: '11111111111111111111111111111111', data: 'AQ==',
    accounts: [{ pubkey: WALLET, isSigner: true, isWritable: true }] }],
  ...overrides,
});
const errorCode = (code: PantaClientError['code']) => (error: unknown) =>
  error instanceof PantaClientError && error.code === code;

function fixtureFetch(values: unknown[], inspect?: (url: URL, init: RequestInit) => void): typeof fetch {
  let cursor = 0;
  return (async (input, init) => {
    const url = new URL(String(input));
    inspect?.(url, init ?? {});
    const value = values[cursor++];
    if (value === undefined) assert.fail('Unexpected Panta request');
    return new Response(JSON.stringify(value), { headers: { 'content-type': 'application/json' } });
  }) as typeof fetch;
}

test('credential remains in an official-origin header; missing credential and untrusted origins fail closed', async () => {
  assert.equal(createPantaClientFromEnv({}), null);
  assert.throws(() => new PantaClient({ apiKey: KEY, baseUrl: 'https://other.example/api/v1' }), errorCode('invalid_request'));
  assert.throws(() => new PantaClient({ apiKey: KEY, baseUrl: 'http://live-api.panta.market/api/v1' }), errorCode('invalid_request'));
  assert.throws(() => new PantaClient({ apiKey: 'not-a-key' }), errorCode('invalid_request'));
  let calls = 0;
  const client = new PantaClient({ apiKey: KEY, fetcher: fixtureFetch([{ items: [market()], nextCursor: null }], (url, init) => {
    calls++;
    assert.equal(url.origin, 'https://live-api.panta.market');
    assert.equal(url.pathname, '/api/v1/markets/');
    assert.equal(url.searchParams.get('limit'), '20');
    assert.equal(url.href.includes(KEY), false);
    assert.equal(init.method, 'GET');
    assert.equal(init.redirect, 'error');
    assert.equal(init.cache, 'no-store');
    assert.equal(new Headers(init.headers).get('X-Api-Key'), KEY);
    assert.equal(new Headers(init.headers).has('Authorization'), false);
    assert.ok(init.signal);
  }) });
  const result = await client.listMarkets({ limit: 20 });
  assert.equal(calls, 1);
  assert.equal(result.items[0]?.yesPrice, null);
});

test('market detail and wallet positions bind exact requested identities', async () => {
  const client = new PantaClient({ apiKey: KEY, fetcher: fixtureFetch([
    market({ yesPrice: '0.52', noPrice: '0.48' }),
    { wallet: WALLET, positions: [{ marketId: MARKET, side: 'yes', shares: '38.40',
      phase: 'primary', claimable: false, claimed: false, outcome: null }] },
  ], (url, init) => {
    if (url.pathname.includes('/markets/')) assert.equal(url.pathname, `/api/v1/markets/${MARKET}/`);
    else assert.equal(url.searchParams.get('wallet'), WALLET);
    assert.equal(init.method, 'GET');
  }) });
  const detail = await client.getMarket(MARKET);
  assert.equal(detail.yesPrice, '0.52');
  const positions = await client.positions(WALLET);
  assert.equal(positions.positions[0]?.shares, '38.40');
  await assert.rejects(new PantaClient({ apiKey: KEY, fetcher: fixtureFetch([market({ marketId: WALLET })]) }).getMarket(MARKET),
    errorCode('invalid_response'));
  await assert.rejects(new PantaClient({ apiKey: KEY, fetcher: fixtureFetch([{ wallet: MARKET, positions: [] }]) }).positions(WALLET),
    errorCode('invalid_response'));
});

test('primary buy methods preserve quote, order and signature binding without signing or broadcasting', async () => {
  const expectedPaths = ['/api/v1/primaryorderquote/', '/api/v1/primaryorderbuild/',
    '/api/v1/primaryordersubmit/', '/api/v1/primaryorderverify/'];
  let calls = 0;
  const client = new PantaClient({ apiKey: KEY, fetcher: fixtureFetch([
    quote(), build(), { orderId: 'ord_abc123', status: 'submitted', signature: SIGNATURE },
    { orderId: 'ord_abc123', status: 'confirmed', signature: SIGNATURE, marketId: MARKET, side: 'yes' },
  ], (url, init) => {
    assert.equal(url.pathname, expectedPaths[calls++]);
    assert.equal(init.method, 'POST');
    assert.equal(new Headers(init.headers).get('X-Api-Key'), KEY);
    assert.equal(String(init.body).includes(KEY), false);
  }) });
  const q = await client.quotePrimaryBuy({ wallet: WALLET, marketId: MARKET, side: 'yes', amountUsdc: '20.00' });
  const b = await client.buildPrimaryBuy({ quoteId: q.quoteId, wallet: WALLET, maxSlippageBps: 100 });
  const s = await client.submitPrimaryBuy({ orderId: b.orderId, signature: SIGNATURE, wallet: WALLET });
  const v = await client.verifyPrimaryBuy({ orderId: b.orderId, signature: s.signature, wallet: WALLET });
  assert.equal(calls, 4);
  assert.equal(b.instructions[0]?.programId, '11111111111111111111111111111111');
  assert.equal(v.status, 'confirmed');
});

test('invalid requests and mismatched responses are refused before or after transport as appropriate', async () => {
  const noNetwork = new PantaClient({ apiKey: KEY, fetcher: (async () => assert.fail('No network request expected')) as typeof fetch });
  await assert.rejects(noNetwork.getMarket('../markets'), errorCode('invalid_request'));
  await assert.rejects(noNetwork.positions('not a wallet'), errorCode('invalid_request'));
  await assert.rejects(noNetwork.listMarkets({ limit: 51 }), errorCode('invalid_request'));
  await assert.rejects(noNetwork.quotePrimaryBuy({ wallet: WALLET, marketId: MARKET, side: 'yes', amountUsdc: '0' }),
    errorCode('invalid_request'));
  await assert.rejects(noNetwork.buildPrimaryBuy({ quoteId: 'qt_abc123', wallet: WALLET, maxSlippageBps: 5001 }),
    errorCode('invalid_request'));
  await assert.rejects(noNetwork.submitPrimaryBuy({ orderId: 'ord_abc123', signature: 'invalid' }),
    errorCode('invalid_request'));
  await assert.rejects(new PantaClient({ apiKey: KEY, fetcher: fixtureFetch([quote({ marketId: WALLET })]) })
    .quotePrimaryBuy({ wallet: WALLET, marketId: MARKET, side: 'yes', amountUsdc: '20.00' }), errorCode('invalid_response'));
  await assert.rejects(new PantaClient({ apiKey: KEY, fetcher: fixtureFetch([build({ wallet: MARKET })]) })
    .buildPrimaryBuy({ quoteId: 'qt_abc123', wallet: WALLET }), errorCode('invalid_response'));
});

test('upstream failures, invalid JSON, oversized data and timeouts are sanitized and bounded', async () => {
  const auth = new PantaClient({ apiKey: KEY, fetcher: (async () =>
    new Response('{"detail":"secret diagnostic"}', { status: 401 })) as typeof fetch });
  await assert.rejects(auth.listMarkets(), error => errorCode('unauthorized')(error) &&
    !String(error).includes('secret diagnostic') && !String(error).includes(KEY));
  const stale = new PantaClient({ apiKey: KEY, fetcher: (async () =>
    new Response('{"code":"QUOTE_STALE","message":"sensitive upstream detail"}', { status: 400 })) as typeof fetch });
  await assert.rejects(stale.buildPrimaryBuy({ quoteId: 'qt_abc123', wallet: WALLET }), error =>
    error instanceof PantaClientError && error.code === 'quote_stale' && error.statusCode === 409 &&
    error.upstreamCode === 'QUOTE_STALE' &&
    !String(error).includes('sensitive upstream detail'));
  const rate = new PantaClient({ apiKey: KEY, fetcher: (async () =>
    new Response('{}', { status: 429, headers: { 'retry-after': '8' } })) as typeof fetch });
  await assert.rejects(rate.listMarkets(), error => error instanceof PantaClientError &&
    error.code === 'rate_limited' && error.retryAfterMs === 8_000);
  for (const value of ['{', 'x'.repeat(1_000_001)]) {
    const client = new PantaClient({ apiKey: KEY, fetcher: (async () => new Response(value)) as typeof fetch });
    await assert.rejects(client.listMarkets(), errorCode('invalid_response'));
  }
  const timeout = new PantaClient({ apiKey: KEY, fetcher: (async () => {
    throw new DOMException('timeout', 'TimeoutError');
  }) as typeof fetch });
  await assert.rejects(timeout.listMarkets(), errorCode('timeout'));
});
