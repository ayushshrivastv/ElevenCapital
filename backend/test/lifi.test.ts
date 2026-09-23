import assert from 'node:assert/strict';
import test from 'node:test';
import { decodeBase58 } from '../src/transfer.js';
import { LiFiClient, LiFiError, type LiFiQuoteRequest } from '../src/lifi.js';
import { destinationFrom, paymentAssetDefinition } from '../src/purchase-chain.js';

const EVM = '0x1111111111111111111111111111111111111111';
const ROUTER = '0x2222222222222222222222222222222222222222';
const XSTOCK = '0xc845b2894dbddd03858fd2d643b4ef725fe0849d';
const SOL = '9xQeWvG816bUx9EPf29DqVU1vDUbtWKVnwG1UxVajZ5J';

function evmRequest(): LiFiQuoteRequest {
  return { source: paymentAssetDefinition('ETHEREUM:ETH')!, destination: destinationFrom('ETHEREUM', XSTOCK, 'NVDAx', 18),
    amountBaseUnits: '100000000000000000', fromAddress: EVM, toAddress: EVM, slippageBps: 50 };
}
function quoteBody(request = evmRequest()) {
  return { id: 'route-one', type: 'swap', tool: 'uniswap',
    action: { fromChainId: Number(request.source.chainId), toChainId: Number(request.destination.chainId),
      fromToken: { address: request.source.address, symbol: request.source.symbol, decimals: request.source.decimals, chainId: Number(request.source.chainId) },
      toToken: { address: request.destination.address, symbol: request.destination.symbol, decimals: request.destination.decimals,
        chainId: Number(request.destination.chainId) }, fromAmount: request.amountBaseUnits,
      fromAddress: request.fromAddress, toAddress: request.toAddress },
    estimate: { fromAmount: request.amountBaseUnits, toAmount: '500000000000000000', toAmountMin: '490000000000000000',
      fromAmountUSD: '75', toAmountUSD: '74.5', approvalAddress: null, priceImpact: '0.001',
      feeCosts: [{ amountUSD: '0.20' }], gasCosts: [{ amountUSD: '0.30', amount: '300000000000000',
        token: { address: request.source.network === 'SOLANA' ? request.source.address : '0x0000000000000000000000000000000000000000',
          symbol: request.source.symbol, decimals: request.source.decimals, chainId: Number(request.source.chainId) } }] },
    transactionRequest: { from: request.fromAddress, to: ROUTER, chainId: Number(request.source.chainId), data: '0x12',
      value: `0x${BigInt(request.amountBaseUnits).toString(16)}`, gasLimit: '0x30d40', gasPrice: '0x3b9aca00' } };
}
function jsonFetcher(body: unknown, inspect?: (url: URL, init?: RequestInit) => void): typeof fetch {
  return (async (input: URL | RequestInfo, init?: RequestInit) => {
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url); inspect?.(url, init);
    return new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } });
  }) as typeof fetch;
}

test('LI.FI adapter uses one fixed HTTPS quote endpoint and validates every reviewed route identity field', async () => {
  const request = evmRequest(); let called = false;
  const client = new LiFiClient(jsonFetcher(quoteBody(request), (url, init) => {
    called = true; assert.equal(url.origin, 'https://li.quest'); assert.equal(url.pathname, '/v1/quote');
    assert.equal(url.searchParams.get('fromToken'), request.source.address); assert.equal(url.searchParams.get('toToken'), XSTOCK);
    assert.equal(url.searchParams.get('fromAddress'), EVM); assert.equal(init?.redirect, 'error');
  }), 'public-test-key');
  const quote = await client.quote(request); assert.equal(called, true); assert.equal(quote.transaction.kind, 'EVM');
  assert.equal(quote.sourceGasBaseUnits, '300000000000000'); assert.equal(quote.feesUsd, '0.5'); assert.equal(quote.priceImpactPercent, '0.1');
  if (quote.transaction.kind !== 'EVM') throw new Error();
  assert.equal(quote.transaction.gas, '0x30d40'); assert.equal(quote.transaction.gasPrice, '0x3b9aca00');
});

test('LI.FI adapter rejects wallet, token, amount, native value, chain, gas and fee tampering before returning an action', async () => {
  const request = evmRequest();
  const cases = [
    { action: { fromAddress: '0x9999999999999999999999999999999999999999' } },
    { action: { fromAmount: '1' } },
    { action: { toToken: { ...quoteBody(request).action.toToken, address: ROUTER } } },
    { transactionRequest: { value: '0x1' } },
    { transactionRequest: { chainId: 42161 } },
    { transactionRequest: { gasLimit: undefined } },
    { transactionRequest: { gasPrice: '0x1000000000000000' } },
  ];
  for (const mutation of cases) {
    const baseline = quoteBody(request); const body = { ...baseline,
      action: { ...baseline.action, ...(mutation.action ?? {}) },
      transactionRequest: { ...baseline.transactionRequest, ...(mutation.transactionRequest ?? {}) } };
    await assert.rejects(new LiFiClient(jsonFetcher(body)).quote(request),
      (error: unknown) => error instanceof LiFiError && error.code === 'unsafe_router_response');
  }
});

function unsignedSolanaTransaction(feePayer: string): string {
  const bytes = Buffer.concat([
    Buffer.from([1]), Buffer.alloc(64), // one empty signature
    Buffer.from([1, 0, 0]), // legacy header: one required signature
    Buffer.from([1]), Buffer.from(decodeBase58(feePayer)), // one static account, fee payer first
    Buffer.alloc(32), Buffer.from([0]), // blockhash and zero instructions
  ]);
  return bytes.toString('base64');
}

test('Solana route parsing requires an unsigned transaction whose first required signer and fee payer is the reviewed Privy wallet', async () => {
  const source = paymentAssetDefinition('SOLANA:SOL')!;
  const request: LiFiQuoteRequest = { source, destination: destinationFrom('ETHEREUM', XSTOCK, 'NVDAx', 18),
    amountBaseUnits: '100000000', fromAddress: SOL, toAddress: EVM, slippageBps: 50 };
  const body = quoteBody(request); body.transactionRequest = { data: unsignedSolanaTransaction(SOL), minContextSlot: 10, lastValidBlockHeight: 20 } as never;
  const quote = await new LiFiClient(jsonFetcher(body)).quote(request); assert.equal(quote.transaction.kind, 'SOLANA');
  const wrong = quoteBody(request); wrong.transactionRequest = { data: unsignedSolanaTransaction('So11111111111111111111111111111111111111112') } as never;
  await assert.rejects(new LiFiClient(jsonFetcher(wrong)).quote(request),
    (error: unknown) => error instanceof LiFiError && error.code === 'unsafe_router_response');
});

test('status transport is fixed-origin and maps only validated LI.FI terminal observations', async () => {
  const client = new LiFiClient(jsonFetcher({ status: 'DONE', receiving: { amount: '490000000000000000' } }, url => {
    assert.equal(url.origin, 'https://li.quest'); assert.equal(url.pathname, '/v1/status'); assert.equal(url.searchParams.get('bridge'), 'relay');
  }));
  assert.deepEqual(await client.status({ transactionId: `0x${'a'.repeat(64)}`, tool: 'relay', fromChainId: '1', toChainId: '42161' }),
    { state: 'DONE', receivedBaseUnits: '490000000000000000', message: null });
});

