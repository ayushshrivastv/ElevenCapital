import assert from 'node:assert/strict';
import test from 'node:test';
import { JupiterSwapV2, JupiterTradeError, type JupiterOrderRequest, type JupiterTradeErrorCode } from '../src/jupiter-trade.js';
import { decodeBase58 } from '../src/transfer.js';

const USDC = 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v';
const MSFT = 'XspzcW1PRtgf6Wj92HCiZdjzKCyFekVD8P5Ueh3dRMX';
const TAKER = '9xQeWvG816bUx9EPf29DqVU1vDUbtWKVnwG1UxVajZ5J';
const NOW = Date.parse('2026-09-23T00:00:00Z');
function request(): JupiterOrderRequest { return { inputMint: USDC, outputMint: MSFT, amountBaseUnits: '100000000', inputDecimals: 6, outputDecimals: 8, slippageBps: 50 }; }
// Shape captured by the read-only, keyless /order probe; amounts are deterministic fixtures.
function body() { return { inputMint: USDC, outputMint: MSFT, inAmount: '100000000', outAmount: '19906365',
  otherAmountThreshold: '19806833', swapMode: 'ExactIn', slippageBps: 50, requestId: '01a0ca85-5639-759e-97c0-78a5c8eb87fc',
  router: 'metis', mode: 'manual', transaction: null as string | null, taker: null as string | null,
  feeMint: USDC, feeBps: 10, platformFee: { feeBps: 10, feeMint: USDC },
  signatureFeeLamports: 0, prioritizationFeeLamports: 0, rentFeeLamports: 0,
  inUsdValue: 100, outUsdValue: 99.89, priceImpact: -0.10676248325429821, gasless: false }; }
function fetcher(value: unknown, inspect?: (url: URL, init?: RequestInit) => void, status = 200, headers: Record<string, string> = {}): typeof fetch {
  return (async (input, init) => {
    const url = new URL(input instanceof URL ? input.href : typeof input === 'string' ? input : input.url);
    inspect?.(url, init);
    return new Response(typeof value === 'string' ? value : JSON.stringify(value), { status, headers: { 'content-type': 'application/json', ...headers } });
  }) as typeof fetch;
}
function errorCode(code: JupiterTradeErrorCode) { return (error: unknown) => error instanceof JupiterTradeError && error.code === code; }
function transaction(taker = TAKER): string {
  return Buffer.concat([Buffer.from([1]), Buffer.alloc(64), Buffer.from([0x80, 1, 0, 0, 1]),
    Buffer.from(decodeBase58(taker)), Buffer.alloc(32, 1), Buffer.from([0, 0])]).toString('base64');
}

test('keyless read-only order binds exact mints, amount and precision without constructing or broadcasting a transaction', async () => {
  let calls = 0;
  const client = new JupiterSwapV2(fetcher(body(), (url, init) => {
    calls++; assert.equal(url.origin, 'https://api.jup.ag'); assert.equal(url.pathname, '/swap/v2/order');
    assert.equal(url.searchParams.get('inputMint'), USDC); assert.equal(url.searchParams.get('outputMint'), MSFT);
    assert.equal(url.searchParams.get('amount'), '100000000'); assert.equal(url.searchParams.get('taker'), null);
    assert.equal(url.searchParams.get('slippageBps'), '50'); assert.equal(url.searchParams.get('swapMode'), 'ExactIn');
    assert.equal(init?.method, 'GET'); assert.equal(init?.redirect, 'error'); assert.equal(new Headers(init?.headers).has('x-api-key'), false);
  }), undefined, () => NOW);
  assert.equal(client.readiness().authentication, 'keyless'); assert.equal(client.readiness().signsOrBroadcasts, false);
  const q = await client.quote(request()); assert.equal(calls, 1); assert.equal(q.quoteOnly, true);
  assert.equal(q.transactionBase64, null); assert.equal(q.executableTransactionAvailable, false);
  assert.equal(q.inputAmount, '100'); assert.equal(q.outputAmount, '0.19906365'); assert.equal(q.minimumOutput, '0.19806833');
  assert.equal(q.priceImpactPercent, '-0.10676248325429821'); assert.equal(q.safetyValidationRequired, true);
});

test('API key is sent only in the fixed-origin header, and a requested Metis-only route excludes all other engines', async () => {
  const client = new JupiterSwapV2(fetcher(body(), (url, init) => {
    assert.equal(url.searchParams.get('excludeRouters'), 'jupiterz,dflow,okx');
    assert.equal(new Headers(init?.headers).get('x-api-key'), 'test-public-fixture');
    assert.equal(url.href.includes('test-public-fixture'), false);
  }), 'test-public-fixture');
  assert.equal(client.readiness().authentication, 'api-key');
  await client.quote({ ...request(), excludeRouters: ['jupiterz', 'dflow', 'okx'] });
  await assert.rejects(new JupiterSwapV2(fetcher({ ...body(), router: 'jupiterz' })).quote({ ...request(), excludeRouters: ['jupiterz'] }), errorCode('unsafe_router_response'));
});

test('chain-verified scaled share factors affect display amounts only and are never sent as router input', async () => {
  const client = new JupiterSwapV2(fetcher(body(), url => {
    assert.equal(url.searchParams.has('inputUiMultiplier'), false); assert.equal(url.searchParams.has('outputUiMultiplier'), false);
    assert.equal(url.searchParams.get('amount'), '100000000');
  }));
  const q = await client.quote({ ...request(), outputUiMultiplier: '1.0059033904787456' });
  assert.equal(q.outAmount, '19906365'); assert.equal(q.outputUiMultiplier, '1.0059033904787456');
  assert.equal(q.outputAmount, '0.2002388'); assert.equal(q.minimumOutput, '0.1992376');
  for (const factor of ['0', '-1', 'Infinity', '1000000000001']) await assert.rejects(client.quote({ ...request(), outputUiMultiplier: factor }), errorCode('invalid_request'));
});

test('response binding rejects a different mint, wallet, amount, precision, minimum, slippage, fee token or request ID', async () => {
  const bad = [{ inputMint: MSFT }, { outputMint: USDC }, { inAmount: '100000001' }, { taker: TAKER },
    { inputDecimals: 9 }, { outputDecimals: 6 }, { otherAmountThreshold: '1' }, { otherAmountThreshold: '19906366' },
    { slippageBps: 51 }, { feeMint: TAKER }, { feeBps: -1 }, { requestId: '../execute' }, { outAmount: '0' },
    { platformFee: { feeBps: 11, feeMint: USDC } }, { platformFee: { feeBps: 10, feeMint: MSFT } },
    { priceImpact: '1e99999999' }, { inputUsdValue: -1, inUsdValue: -1 }];
  for (const mutation of bad) await assert.rejects(new JupiterSwapV2(fetcher({ ...body(), ...mutation })).quote(request()), errorCode('unsafe_router_response'));
});

test('invalid requests fail before contacting Jupiter', async () => {
  const client = new JupiterSwapV2((async () => { assert.fail('network called'); }) as typeof fetch);
  for (const mutation of [{ amountBaseUnits: '0' }, { amountBaseUnits: '01' }, { amountBaseUnits: '1.5' },
    { amountBaseUnits: '18446744073709551616' }, { inputMint: 'bad' }, { outputMint: USDC },
    { taker: '11111111111111111111111111111111111' }, { inputDecimals: 19 }, { slippageBps: -1 }]) {
    await assert.rejects(client.quote({ ...request(), ...mutation }), errorCode('invalid_request'));
  }
});

test('an order with a taker preserves transaction bytes and binds an unsigned required signer, but never marks safety verified', async () => {
  const q = await new JupiterSwapV2(fetcher({ ...body(), taker: TAKER, transaction: transaction(), lastValidBlockHeight: '99999999' }), undefined, () => NOW)
    .quote({ ...request(), taker: TAKER });
  assert.equal(q.quoteOnly, false); assert.equal(q.transactionBase64, transaction());
  assert.equal(q.executableTransactionAvailable, true); assert.equal(q.safetyValidationRequired, true);
  assert.equal(q.lastValidBlockHeight, '99999999');
});

test('malformed, mismatched, already signed and missing-expiry transactions are rejected', async () => {
  const signed = Buffer.from(transaction(), 'base64'); signed[1] = 1;
  const valid = { ...body(), taker: TAKER, transaction: transaction(), lastValidBlockHeight: '99999999' };
  for (const mutation of [{ transaction: 'garbage' }, { transaction: transaction(USDC) }, { transaction: signed.toString('base64') },
    { transaction: Buffer.alloc(1233).toString('base64') }, { lastValidBlockHeight: undefined }, { errorCode: 1 },
    { transaction: null }, { transaction: '' }]) {
    await assert.rejects(new JupiterSwapV2(fetcher({ ...valid, ...mutation }), undefined, () => NOW).quote({ ...request(), taker: TAKER }), errorCode('unsafe_router_response'));
  }
  await assert.rejects(new JupiterSwapV2(fetcher({ ...body(), transaction: transaction() })).quote(request()), errorCode('unsafe_router_response'));
});

test('empty transaction with documented build error is a quote and never actionable', async () => {
  const q = await new JupiterSwapV2(fetcher({ ...body(), taker: TAKER, transaction: '', errorCode: 2,
    errorMessage: 'UNTRUSTED provider instructions ignored' })).quote({ ...request(), taker: TAKER });
  assert.equal(q.executableTransactionAvailable, false); assert.equal(q.transactionBase64, null);
  assert.equal(q.buildErrorCode, 2); assert.equal(q.buildErrorReason, 'The wallet needs SOL for network fees.');
});

test('RFQ orders use their expiry timestamp while expired or unreasonable timestamps fail closed', async () => {
  const rfq = { ...body(), router: 'jupiterz', taker: TAKER, transaction: transaction(), expireAt: '2026-09-23T00:00:30Z' };
  const q = await new JupiterSwapV2(fetcher(rfq), undefined, () => NOW).quote({ ...request(), taker: TAKER });
  assert.equal(q.expiresAt, '2026-09-23T00:00:30.000Z');
  await assert.rejects(new JupiterSwapV2(fetcher({ ...rfq, expireAt: '2026-09-22T23:59:59Z' }), undefined, () => NOW).quote({ ...request(), taker: TAKER }), errorCode('quote_expired'));
  for (const expireAt of [undefined, 'invalid', '2027-01-01T00:00:00Z']) await assert.rejects(new JupiterSwapV2(fetcher({ ...rfq, expireAt }), undefined, () => NOW).quote({ ...request(), taker: TAKER }), errorCode('unsafe_router_response'));
});

test('transport errors distinguish no liquidity, access failure and throttling with bounded retry delay', async () => {
  for (const [status, code] of [[400, 'route_unavailable'], [404, 'route_unavailable'], [401, 'authentication_required'],
    [403, 'authentication_required'], [500, 'router_unavailable']] as const) {
    await assert.rejects(new JupiterSwapV2(fetcher({}, undefined, status)).quote(request()), errorCode(code));
  }
  await assert.rejects(new JupiterSwapV2(fetcher({}, undefined, 429, { 'x-ratelimit-reset': String(NOW / 1000 + 15) }), undefined, () => NOW).quote(request()),
    (error: unknown) => error instanceof JupiterTradeError && error.code === 'rate_limited' && error.retryAfterMs === 15000);
  await assert.rejects(new JupiterSwapV2((async () => { throw new Error('network timeout'); }) as typeof fetch).quote(request()), errorCode('router_unavailable'));
});

test('invalid JSON, oversized body and unsafe numeric precision fail instead of producing trade details', async () => {
  for (const invalid of ['{', ' '.repeat(1_000_001), { ...body(), signatureFeeLamports: Number.MAX_SAFE_INTEGER + 1 }]) {
    await assert.rejects(new JupiterSwapV2(fetcher(invalid)).quote(request()), errorCode('unsafe_router_response'));
  }
});

test('automatic slippage and legacy price-impact ratio keep provider semantics', async () => {
  const input = request(); delete input.slippageBps;
  const q = await new JupiterSwapV2(fetcher({ ...body(), priceImpact: undefined, priceImpactPct: '-0.001', slippageBps: 40,
    otherAmountThreshold: '19826739' }, url => { assert.equal(url.searchParams.has('slippageBps'), false); })).quote(input);
  assert.equal(q.slippageBps, 40); assert.equal(q.priceImpactPercent, '-0.1');
});
