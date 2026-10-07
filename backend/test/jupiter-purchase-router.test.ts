import assert from 'node:assert/strict';
import test from 'node:test';
import { JUPITER_PURCHASE_TOOL, JupiterPurchaseRouter } from '../src/jupiter-purchase-router.js';
import { JupiterTradeError, type JupiterOrderQuote } from '../src/jupiter-trade.js';
import { destinationFrom, paymentAssetDefinition, SOL_NATIVE, type JsonRpc } from '../src/purchase-chain.js';
import { PurchaseRouteError, type PurchaseRouteQuoteRequest, type PurchaseRouteStatusRequest } from '../src/purchase-router.js';
import { decodeBase58, encodeBase58 } from '../src/transfer.js';
import { SolanaSwapValidationError, type ValidatedSolanaSwap } from '../src/solana-swap-validation.js';

const WALLET = '9xQeWvG816bUx9EPf29DqVU1vDUbtWKVnwG1UxVajZ5J';
const USDC = 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v';
const MSFT = 'XspzcW1PRtgf6Wj92HCiZdjzKCyFekVD8P5Ueh3dRMX';
const CHAIN = '1151111081099710';
const NOW = Date.parse('2026-09-23T00:00:00Z');
function serialized(signed = false): string {
  return Buffer.concat([Buffer.from([1]), Buffer.alloc(64, signed ? 1 : 0), Buffer.from([1, 0, 0, 1]),
    Buffer.from(decodeBase58(WALLET)), Buffer.alloc(32, 1), Buffer.from([0])]).toString('base64');
}
const signature = encodeBase58(Buffer.alloc(64, 1));
function request(): PurchaseRouteQuoteRequest {
  return { source: paymentAssetDefinition('SOLANA:USDC')!, destination: destinationFrom('SOLANA', MSFT, 'MSFTx', 8),
    amountBaseUnits: '100000000', fromAddress: WALLET, toAddress: WALLET, slippageBps: 50 };
}
function quote(): JupiterOrderQuote {
  return { inputMint: USDC, outputMint: MSFT, inputDecimals: 6, outputDecimals: 8,
    inAmount: '100000000', outAmount: '20000000', minimumOutputAmount: '19900000', inputAmount: '100', outputAmount: '0.2', minimumOutput: '0.199',
    requestId: 'order-one', router: 'metis', mode: 'manual', slippageBps: 50, inputUsdValue: '100', outputUsdValue: '99.9', priceImpactPercent: '0.1',
    feeMint: USDC, feeBps: 10, platformFeeAmount: null, signatureFeeLamports: '5000', prioritizationFeeLamports: '0', rentFeeLamports: '0',
    signatureFeePayer: WALLET, prioritizationFeePayer: WALLET, rentFeePayer: WALLET, taker: WALLET, transactionBase64: serialized(),
    lastValidBlockHeight: '2000', expiresAt: null, gasless: false, quoteOnly: false, executableTransactionAvailable: true,
    safetyValidationRequired: true, buildErrorCode: null, buildErrorReason: null, receivedAt: new Date(NOW).toISOString() };
}
const validated: ValidatedSolanaSwap = {
  transactionBase64: serialized(), messageSha256: 'test-only', sourceTokenAccount: USDC, destinationTokenAccount: MSFT,
  minimumOutputBaseUnits: '19900000', estimatedNetworkFeeLamports: '5000', maximumNativeCostLamports: '2044280',
  simulationSlot: 100, lastValidBlockHeight: '2000',
};
function rpc(value: unknown): JsonRpc {
  return { async call(network, method, params) {
    assert.equal(network, 'SOLANA'); assert.equal(method, 'getTransaction'); assert.equal(params[0], signature); return value;
  } };
}
function statusRequest(): PurchaseRouteStatusRequest {
  return { transactionId: signature, tool: JUPITER_PURCHASE_TOOL, fromChainId: CHAIN, toChainId: CHAIN,
    expectedTransactionBase64: serialized(), destinationAddress: MSFT, recipientAddress: WALLET, minimumReceivedBaseUnits: '19900000' };
}
function receipt(amount = '20000000') {
  return { transaction: [serialized(true), 'base64'], meta: { err: null, fee: 5000,
    preBalances: [100000000], postBalances: [99995000],
    preTokenBalances: [{ accountIndex: 2, mint: MSFT, owner: WALLET, uiTokenAmount: { amount: '40000000' } }],
    postTokenBalances: [{ accountIndex: 2, mint: MSFT, owner: WALLET, uiTokenAmount: { amount: (40000000n + BigInt(amount)).toString() } }],
  } };
}

test('same-wallet Solana quote is independently validated before execution is enabled', async () => {
  let checked = false;
  const router = new JupiterPurchaseRouter(rpc(null), { async quote(input) {
    assert.equal(input.inputMint, USDC); assert.equal(input.outputMint, MSFT); assert.equal(input.taker, WALLET);
    assert.deepEqual(input.excludeRouters, ['jupiterz', 'dflow', 'okx']); return quote();
  } }, async value => { checked = true; assert.equal(value.transactionBase64, serialized()); return validated; }, () => NOW);
  const result = await router.quote(request());
  assert.equal(checked, true); assert.equal(result.executionValidated, true); assert.equal(result.tool, JUPITER_PURCHASE_TOOL);
  assert.equal(result.expiresAt, '2026-09-23T00:00:20.000Z'); assert.equal(result.sourceGasBaseUnits, '2044280');
});

test('native SOL is routed through wrapped SOL while preserving the wallet-facing asset identity', async () => {
  const input = { ...request(), source: paymentAssetDefinition('SOLANA:SOL')! };
  const router = new JupiterPurchaseRouter(rpc(null), { async quote(value) {
    assert.equal(value.inputMint, 'So11111111111111111111111111111111111111112');
    return { ...quote(), inputMint: value.inputMint, inputDecimals: 9 };
  } }, async () => validated);
  assert.equal((await router.quote(input)).fromTokenAddress, SOL_NATIVE);
});

test('mismatched wallet and unsafe or unavailable router responses fail closed', async () => {
  const orders = { async quote() { return quote(); } };
  const denied = new JupiterPurchaseRouter(rpc(null), orders,
    async () => { throw new SolanaSwapValidationError('unsupported_transaction', 'Unreviewed route'); });
  await assert.rejects(denied.quote(request()), error => error instanceof PurchaseRouteError && error.code === 'route_unavailable');
  await assert.rejects(denied.quote({ ...request(), toAddress: MSFT }),
    error => error instanceof PurchaseRouteError && error.code === 'unsafe_router_response');
  const empty = new JupiterPurchaseRouter(rpc(null), { async quote() {
    return { ...quote(), transactionBase64: null, executableTransactionAvailable: false };
  } });
  await assert.rejects(empty.quote(request()), error => error instanceof PurchaseRouteError && error.code === 'route_unavailable');
  const busy = new JupiterPurchaseRouter(rpc(null), { async quote() { throw new JupiterTradeError('rate_limited', 'Busy', 2000); } });
  await assert.rejects(busy.quote(request()), error => error instanceof PurchaseRouteError && error.code === 'router_unavailable');
});

test('status confirms only the exact signed Solana transaction and same-wallet output delta', async () => {
  assert.deepEqual(await new JupiterPurchaseRouter(rpc(receipt())).status(statusRequest()),
    { state: 'DONE', receivedBaseUnits: '20000000', message: null });
  const changed = Buffer.from(serialized(true), 'base64'); changed[changed.length - 2] = 2;
  const spoofed = receipt(); spoofed.transaction = [changed.toString('base64'), 'base64'];
  await assert.rejects(new JupiterPurchaseRouter(rpc(spoofed)).status(statusRequest()),
    error => error instanceof PurchaseRouteError && error.code === 'unsafe_router_response');
});

test('missing evidence stays pending and failed or short receipts are explicit failures', async () => {
  assert.equal((await new JupiterPurchaseRouter(rpc(null)).status(statusRequest())).state, 'PENDING');
  assert.equal((await new JupiterPurchaseRouter({ async call() { throw new Error('offline'); } }).status(statusRequest())).state, 'PENDING');
  const noEvidence = receipt(); noEvidence.meta.postTokenBalances = [];
  assert.equal((await new JupiterPurchaseRouter(rpc(noEvidence)).status(statusRequest())).state, 'FAILED');
  const failed = receipt(); (failed.meta as { err: unknown }).err = { InstructionError: [0, 'error'] };
  assert.equal((await new JupiterPurchaseRouter(rpc(failed)).status(statusRequest())).state, 'FAILED');
  assert.equal((await new JupiterPurchaseRouter(rpc(receipt('19899999'))).status(statusRequest())).state, 'FAILED');
});

test('native SOL receipt includes the confirmed fee and enforces the reviewed minimum', async () => {
  const value = receipt(); value.meta.postBalances[0] = 119995000;
  const requestValue = { ...statusRequest(), destinationAddress: SOL_NATIVE, minimumReceivedBaseUnits: '19900000' };
  assert.deepEqual(await new JupiterPurchaseRouter(rpc(value)).status(requestValue),
    { state: 'DONE', receivedBaseUnits: '20000000', message: null });
  value.meta.postBalances[0] = 119000000;
  assert.equal((await new JupiterPurchaseRouter(rpc(value)).status(requestValue)).state, 'FAILED');
});
