import assert from 'node:assert/strict';
import test from 'node:test';
import { buildApp } from '../src/app.js';
import { PantaClientError } from '../src/panta-client.js';
import type { PantaApi } from '../src/panta-routes.js';
import { WalletAuthenticationError } from '../src/privy-auth.js';

const MARKET = '11111111111111111111111111111111';
const WALLET = 'So11111111111111111111111111111111111111112';
const auth = { async verifyAuthorization(value: string | undefined) {
  if (value === 'Bearer valid') return { subject: 'did:privy:panta-user', sessionId: 'session' };
  if (value === 'Bearer other') return { subject: 'did:privy:another-user', sessionId: 'another-session' };
  throw new WalletAuthenticationError('authentication_required', 401, 'A verified Privy session is required.');
} };

test('Panta routes require Privy, validate inputs, and do not use the practice ledger', async t => {
  const calls: string[] = [];
  const panta = {
    async listMarkets(input: unknown) { calls.push(`list:${JSON.stringify(input)}`); return { items: [], nextCursor: null }; },
    async getMarket(id: string) { calls.push(`market:${id}`); return { marketId: id, title: 'Panta event' }; },
    async positions(wallet: string) { calls.push(`positions:${wallet}`); return { wallet, positions: [] }; },
    async quotePrimaryBuy(input: unknown) { calls.push(`quote:${JSON.stringify(input)}`); return { quoteId: 'qt_1' }; },
    async buildPrimaryBuy(input: unknown) { calls.push(`build:${JSON.stringify(input)}`); return { orderId: 'ord_1' }; },
    async submitPrimaryBuy(input: unknown) { calls.push(`submit:${JSON.stringify(input)}`); return { status: 'submitted' }; },
    async verifyPrimaryBuy(input: unknown) { calls.push(`verify:${JSON.stringify(input)}`); return { status: 'confirmed' }; },
  } as unknown as PantaApi;
  const app = await buildApp(undefined, false, { panta, accessTokenVerifier: auth });
  t.after(() => app.close());
  const headers = { authorization: 'Bearer valid' };

  assert.equal((await app.inject({ method: 'GET', url: '/v1/panta/markets' })).statusCode, 401);
  assert.equal((await app.inject({ method: 'GET', url: '/v1/panta/markets?unexpected=1', headers })).statusCode, 400);
  assert.equal((await app.inject({ method: 'GET', url: '/v1/panta/markets?limit=20&status=primary', headers })).statusCode, 200);
  assert.equal((await app.inject({ method: 'GET', url: `/v1/panta/markets/${MARKET}`, headers })).statusCode, 200);
  assert.equal((await app.inject({ method: 'GET', url: `/v1/panta/positions?wallet=${WALLET}`, headers })).statusCode, 200);
  const quote = await app.inject({ method: 'POST', url: '/v1/panta/orders/quote', headers,
    payload: { wallet: WALLET, marketId: MARKET, side: 'yes', amountUsdc: '20.00' } });
  assert.equal(quote.statusCode, 200);
  assert.deepEqual(quote.json(), { quoteId: 'qt_1' });
  assert.equal(quote.headers['cache-control'], 'no-store');
  assert.equal((await app.inject({ method: 'POST', url: '/v1/panta/orders/quote', headers,
    payload: { wallet: WALLET, marketId: MARKET, side: 'maybe', amountUsdc: '20.00' } })).statusCode, 400);
  assert.equal((await app.inject({ method: 'POST', url: '/v1/panta/orders/build', headers,
    payload: { quoteId: 'qt_1', wallet: WALLET } })).statusCode, 200);
  assert.equal((await app.inject({ method: 'POST', url: '/v1/panta/orders/build',
    headers: { authorization: 'Bearer other' }, payload: { quoteId: 'qt_1', wallet: WALLET } })).statusCode, 409);
  assert.equal((await app.inject({ method: 'POST', url: '/v1/panta/orders/submit', headers,
    payload: { orderId: 'ord_1', signature: '1'.repeat(88) } })).statusCode, 400);
  assert.equal((await app.inject({ method: 'POST', url: '/v1/panta/orders/verify',
    headers: { authorization: 'Bearer other' }, payload: { orderId: 'ord_1', wallet: WALLET } })).statusCode, 409);
  assert.equal((await app.inject({ method: 'POST', url: '/v1/panta/orders/verify', headers,
    payload: { orderId: 'ord_1', wallet: WALLET } })).statusCode, 200);
  assert.deepEqual(calls, [
    'list:{"limit":20,"status":"primary"}', `market:${MARKET}`, `positions:${WALLET}`,
    `quote:{"wallet":"${WALLET}","marketId":"${MARKET}","side":"yes","amountUsdc":"20.00"}`,
    `build:{"quoteId":"qt_1","wallet":"${WALLET}"}`,
    `verify:{"orderId":"ord_1","wallet":"${WALLET}"}`,
  ]);
});

test('Panta endpoints fail closed without a backend key and sanitize upstream failures', async t => {
  const unavailable = await buildApp(undefined, false, { panta: null, accessTokenVerifier: auth });
  t.after(() => unavailable.close());
  const noKey = await unavailable.inject({ method: 'GET', url: '/v1/panta/markets',
    headers: { authorization: 'Bearer valid' } });
  assert.equal(noKey.statusCode, 503);
  assert.equal(noKey.json().error, 'panta_unavailable');

  const broken = await buildApp(undefined, false, { accessTokenVerifier: auth, panta: {
    async listMarkets() { throw new PantaClientError('unauthorized', 503, 'Panta access is unavailable.'); },
  } as unknown as PantaApi });
  t.after(() => broken.close());
  const failure = await broken.inject({ method: 'GET', url: '/v1/panta/markets',
    headers: { authorization: 'Bearer valid' } });
  assert.equal(failure.statusCode, 503);
  assert.ok(!failure.body.includes('pk_live_'));
});
