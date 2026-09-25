import assert from 'node:assert/strict';
import test from 'node:test';
import { JupiterPurchaseRouter, JUPITER_PURCHASE_TOOL } from '../src/jupiter-purchase-router.js';
import { JupiterTradeError, type JupiterOrderQuote } from '../src/jupiter-trade.js';
import { LiFiError, type LiFiQuoteRequest, type PurchaseRouter, type PurchaseRouteStatusRequest } from '../src/lifi.js';
import { destinationFrom, paymentAssetDefinition, SOL_NATIVE, type JsonRpc } from '../src/purchase-chain.js';
import { decodeBase58, encodeBase58 } from '../src/transfer.js';
import { SolanaSwapValidationError, type ValidatedSolanaSwap } from '../src/solana-swap-validation.js';
import { RELAY_ANTHROPIC_MINT, RELAY_ANTHROPIC_TOOL } from '../src/relay.js';

const WALLET = '9xQeWvG816bUx9EPf29DqVU1vDUbtWKVnwG1UxVajZ5J';
const USDC = 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v';
const MSFT = 'XspzcW1PRtgf6Wj92HCiZdjzKCyFekVD8P5Ueh3dRMX';
const CHAIN = '1151111081099710'; const NOW = Date.parse('2026-09-23T00:00:00Z');
function serialized(signed = false): string { return Buffer.concat([Buffer.from([1]), Buffer.alloc(64, signed ? 1 : 0), Buffer.from([1, 0, 0, 1]),
  Buffer.from(decodeBase58(WALLET)), Buffer.alloc(32, 1), Buffer.from([0])]).toString('base64'); }
const signature = encodeBase58(Buffer.alloc(64, 1));
function request(): LiFiQuoteRequest { return { source: paymentAssetDefinition('SOLANA:USDC')!, destination: destinationFrom('SOLANA', MSFT, 'MSFTx', 8),
  amountBaseUnits: '100000000', fromAddress: WALLET, toAddress: WALLET, slippageBps: 50 }; }
function quote(): JupiterOrderQuote { return { inputMint: USDC, outputMint: MSFT, inputDecimals: 6, outputDecimals: 8,
  inAmount: '100000000', outAmount: '20000000', minimumOutputAmount: '19900000', inputAmount: '100', outputAmount: '0.2', minimumOutput: '0.199',
  requestId: 'order-one', router: 'metis', mode: 'manual', slippageBps: 50, inputUsdValue: '100', outputUsdValue: '99.9', priceImpactPercent: '0.1',
  feeMint: USDC, feeBps: 10, platformFeeAmount: null, signatureFeeLamports: '5000', prioritizationFeeLamports: '0', rentFeeLamports: '0',
  signatureFeePayer: WALLET, prioritizationFeePayer: WALLET, rentFeePayer: WALLET, taker: WALLET, transactionBase64: serialized(),
  lastValidBlockHeight: '2000', expiresAt: null, gasless: false, quoteOnly: false, executableTransactionAvailable: true,
  safetyValidationRequired: true, buildErrorCode: null, buildErrorReason: null, receivedAt: new Date(NOW).toISOString() }; }
const validated: ValidatedSolanaSwap = { transactionBase64: serialized(), messageSha256: 'test-only', sourceTokenAccount: USDC, destinationTokenAccount: MSFT,
  minimumOutputBaseUnits: '19900000', estimatedNetworkFeeLamports: '5000', maximumNativeCostLamports: '2044280', simulationSlot: 100,
  lastValidBlockHeight: '2000' };
function fallback(): PurchaseRouter { return { async quote() { throw new Error('Unexpected fallback'); }, async status() { throw new Error('Unexpected fallback'); } }; }
function rpc(value: unknown): JsonRpc { return { async call(network, method, params) {
  assert.equal(network, 'SOLANA'); assert.equal(method, 'getTransaction'); assert.equal(params[0], signature);
  assert.deepEqual(params[1], { commitment: 'confirmed', encoding: 'base64', maxSupportedTransactionVersion: 0 }); return value;
} }; }
function statusRequest(): PurchaseRouteStatusRequest { return { transactionId: signature, tool: JUPITER_PURCHASE_TOOL, fromChainId: CHAIN, toChainId: CHAIN,
  expectedTransactionBase64: serialized(), destinationAddress: MSFT, recipientAddress: WALLET, minimumReceivedBaseUnits: '19900000' }; }
function receipt(amount = '20000000') { return { transaction: [serialized(true), 'base64'], meta: { err: null, fee: 5000,
  preBalances: [100000000], postBalances: [99995000],
  preTokenBalances: [{ accountIndex: 2, mint: MSFT, owner: WALLET, uiTokenAmount: { amount: '40000000' } }],
  postTokenBalances: [{ accountIndex: 2, mint: MSFT, owner: WALLET, uiTokenAmount: { amount: (40000000n + BigInt(amount)).toString() } }] } }; }

test('same-chain route uses native Privy wallet identity and Metis-only independent validation before enabling execution', async () => {
  let checked = false;
  const router = new JupiterPurchaseRouter(rpc(null), { async quote(input) {
    assert.equal(input.inputMint, USDC); assert.equal(input.outputMint, MSFT); assert.equal(input.taker, WALLET);
    assert.deepEqual(input.excludeRouters, ['jupiterz', 'dflow', 'okx']); return quote();
  } }, fallback(), async q => { checked = true; assert.equal(q.transactionBase64, serialized()); return validated; }, () => NOW);
  const result = await router.quote(request()); assert.equal(checked, true); assert.equal(result.executionValidated, true);
  assert.equal(result.tool, JUPITER_PURCHASE_TOOL); assert.equal(result.sourceGasBaseUnits, '2044280');
  assert.equal(result.expiresAt, '2026-09-23T00:00:20.000Z'); assert.equal(result.feesUsd, null);
});

test('native SOL uses wrapped mint for routing but preserves the application native-asset identity', async () => {
  const input = { ...request(), source: paymentAssetDefinition('SOLANA:SOL')! };
  const router = new JupiterPurchaseRouter(rpc(null), { async quote(req) {
    assert.equal(req.inputMint, 'So11111111111111111111111111111111111111112'); return { ...quote(), inputMint: req.inputMint, inputDecimals: 9 };
  } }, fallback(), async () => validated);
  assert.equal((await router.quote(input)).fromTokenAddress, SOL_NATIVE);
});

test('cross-chain delegation stays preview-only even if the fallback claims execution validation', async () => {
  const input = { ...request(), destination: destinationFrom('ETHEREUM', '0x1111111111111111111111111111111111111111', 'MSFTx', 18) };
  let delegated = false;
  const preview = { executionValidated: true, routeId: 'preview' } as never;
  const router = new JupiterPurchaseRouter(rpc(null), { async quote() { assert.fail(); } }, { async quote(q) { delegated = true; assert.equal(q, input); return preview; }, async status() { assert.fail(); } });
  assert.equal((await router.quote(input)).executionValidated, false); assert.equal(delegated, true);
});

test('only pinned Ethereum and Arbitrum assets to Anthropic Solana delegate to validated Relay execution', async () => {
  const fromAddress = '0x1111111111111111111111111111111111111111';
  const input: LiFiQuoteRequest = { source: paymentAssetDefinition('ARBITRUM:USDC')!,
    destination: destinationFrom('SOLANA', RELAY_ANTHROPIC_MINT, 'ANTHROPIC', 9),
    amountBaseUnits: '1250000', fromAddress, toAddress: WALLET, slippageBps: 50 };
  let quoted = false;
  let tracked = false;
  const router = new JupiterPurchaseRouter(rpc(null), { async quote() { assert.fail(); } }, fallback(),
    async () => { assert.fail(); }, () => NOW, {
      async quote(value) {
        quoted = true;
        assert.equal(value.fromAddress, fromAddress);
        assert.equal(value.recipientAddress, WALLET);
        assert.equal(value.slippageBps, 50);
        if (value.sourceAssetId === 'ARBITRUM:USDC') assert.equal(value.amountBaseUnits, '1250000');
        else { assert.ok(value.sourceAssetId === 'ARBITRUM:ETH' || value.sourceAssetId === 'ETHEREUM:ETH');
          assert.equal(value.amountBaseUnits, '500000000000000'); }
        return { executionValidated: true, tool: RELAY_ANTHROPIC_TOOL, routeId: '0x' + 'a'.repeat(64) } as never;
      },
      async status(value) {
        tracked = true;
        assert.deepEqual(value, { sourceChainId: '42161', requestId: '0x' + 'a'.repeat(64), transactionId: '0x' + 'b'.repeat(64),
          recipientAddress: WALLET, destinationMint: RELAY_ANTHROPIC_MINT, minimumReceivedBaseUnits: '950000' });
        return { state: 'DONE', receivedBaseUnits: '960000', message: null, destinationTransactionId: signature };
      },
    });
  assert.equal((await router.quote(input)).executionValidated, true);
  assert.equal(quoted, true);
  const status = await router.status({ tool: RELAY_ANTHROPIC_TOOL, routeId: '0x' + 'a'.repeat(64),
    transactionId: '0x' + 'b'.repeat(64), fromChainId: '42161', toChainId: CHAIN,
    destinationAddress: RELAY_ANTHROPIC_MINT, recipientAddress: WALLET, minimumReceivedBaseUnits: '950000' });
  assert.equal(status.destinationTransactionId, signature);
  assert.equal(tracked, true);
  const eth = await router.quote({ ...input, source: paymentAssetDefinition('ARBITRUM:ETH')!, amountBaseUnits: '500000000000000' });
  assert.equal(eth.executionValidated, true);
  const ethereumEth = await router.quote({ ...input, source: paymentAssetDefinition('ETHEREUM:ETH')!, amountBaseUnits: '500000000000000' });
  assert.equal(ethereumEth.executionValidated, true);
  await assert.rejects(router.status({ tool: RELAY_ANTHROPIC_TOOL, routeId: '0x' + 'a'.repeat(64),
    transactionId: '0x' + 'b'.repeat(64), fromChainId: '42161', toChainId: CHAIN,
    destinationAddress: USDC, recipientAddress: WALLET, minimumReceivedBaseUnits: '950000' }),
    error => error instanceof LiFiError && error.code === 'unsafe_router_response');
});

test('Ethereum Relay status remains bound to mainnet and the pinned Anthropic recipient', async () => {
  const router = new JupiterPurchaseRouter(rpc(null), { async quote() { assert.fail(); } }, fallback(),
    async () => { assert.fail(); }, () => NOW, {
      async quote() { assert.fail(); },
      async status(value) {
        assert.deepEqual(value, { sourceChainId: '1', requestId: '0x' + 'a'.repeat(64),
          transactionId: '0x' + 'b'.repeat(64), recipientAddress: WALLET,
          destinationMint: RELAY_ANTHROPIC_MINT, minimumReceivedBaseUnits: '950000' });
        return { state: 'DONE', receivedBaseUnits: '960000', message: null, destinationTransactionId: signature };
      },
    });
  const request: PurchaseRouteStatusRequest = { tool: RELAY_ANTHROPIC_TOOL, routeId: '0x' + 'a'.repeat(64),
    transactionId: '0x' + 'b'.repeat(64), fromChainId: '1', toChainId: CHAIN,
    destinationAddress: RELAY_ANTHROPIC_MINT, recipientAddress: WALLET, minimumReceivedBaseUnits: '950000' };
  assert.equal((await router.status(request)).destinationTransactionId, signature);
  await assert.rejects(router.status({ ...request, fromChainId: '8453' }),
    error => error instanceof LiFiError && error.code === 'unsafe_router_response');
  await assert.rejects(router.status({ ...request, destinationAddress: USDC }),
    error => error instanceof LiFiError && error.code === 'unsafe_router_response');
});

test('unsupported validators, missing transactions and mismatched wallets cannot become executable', async () => {
  const orders = { async quote() { return quote(); } };
  const denied = new JupiterPurchaseRouter(rpc(null), orders, fallback(), async () => { throw new SolanaSwapValidationError('unsupported_transaction', 'Unreviewed route'); });
  await assert.rejects(denied.quote(request()), e => e instanceof LiFiError && e.code === 'route_unavailable');
  await assert.rejects(denied.quote({ ...request(), toAddress: MSFT }), e => e instanceof LiFiError && e.code === 'unsafe_router_response');
  const empty = new JupiterPurchaseRouter(rpc(null), { async quote() { return { ...quote(), transactionBase64: null, executableTransactionAvailable: false }; } });
  await assert.rejects(empty.quote(request()), e => e instanceof LiFiError && e.code === 'route_unavailable');
  const busy = new JupiterPurchaseRouter(rpc(null), { async quote() { throw new JupiterTradeError('rate_limited', 'Busy', 2000); } });
  await assert.rejects(busy.quote(request()), e => e instanceof LiFiError && e.code === 'router_unavailable');
});

test('status confirms only the exact signed transaction and same-wallet output delta', async () => {
  const result = await new JupiterPurchaseRouter(rpc(receipt())).status(statusRequest());
  assert.deepEqual(result, { state: 'DONE', receivedBaseUnits: '20000000', message: null });
});

test('status refuses a spoofed signature, replaced message or recipient', async () => {
  const changed = Buffer.from(serialized(true), 'base64'); changed[changed.length - 2] = 2;
  const wrongSig = Buffer.from(serialized(true), 'base64'); wrongSig[1] = 2;
  for (const tx of [changed, wrongSig]) { const value = receipt(); value.transaction = [tx.toString('base64'), 'base64'];
    await assert.rejects(new JupiterPurchaseRouter(rpc(value)).status(statusRequest()), e => e instanceof LiFiError && e.code === 'unsafe_router_response'); }
  await assert.rejects(new JupiterPurchaseRouter(rpc(receipt())).status({ ...statusRequest(), recipientAddress: USDC }), e => e instanceof LiFiError && e.code === 'unsafe_router_response');
});

test('missing confirmation or balance evidence remains pending; a reverted or below-minimum receipt is failed', async () => {
  assert.equal((await new JupiterPurchaseRouter(rpc(null)).status(statusRequest())).state, 'PENDING');
  assert.equal((await new JupiterPurchaseRouter({ async call() { throw new Error('offline'); } }).status(statusRequest())).state, 'PENDING');
  const missing = receipt(); missing.meta.postTokenBalances = []; assert.equal((await new JupiterPurchaseRouter(rpc(missing)).status(statusRequest())).state, 'FAILED');
  const noEvidence = { ...receipt(), meta: { err: null } }; assert.equal((await new JupiterPurchaseRouter(rpc(noEvidence)).status(statusRequest())).state, 'PENDING');
  const failed = receipt(); (failed.meta as { err: unknown }).err = { InstructionError: [0, 'error'] };
  assert.equal((await new JupiterPurchaseRouter(rpc(failed)).status(statusRequest())).state, 'FAILED');
  assert.equal((await new JupiterPurchaseRouter(rpc(receipt('19899999'))).status(statusRequest())).state, 'FAILED');
});

test('token receipts aggregate owned accounts and never count movements between owned accounts as purchases', async () => {
  const value = receipt(); value.meta.postTokenBalances[0]!.uiTokenAmount.amount = '0';
  value.meta.postTokenBalances.push({ accountIndex: 3, mint: MSFT, owner: WALLET, uiTokenAmount: { amount: '40000000' } });
  assert.equal((await new JupiterPurchaseRouter(rpc(value)).status(statusRequest())).state, 'FAILED');
  value.meta.postTokenBalances.push({ ...value.meta.postTokenBalances[0]! });
  await assert.rejects(new JupiterPurchaseRouter(rpc(value)).status(statusRequest()), e => e instanceof LiFiError && e.code === 'unsafe_router_response');
});

test('native SOL receipts add back only the confirmed fee and require the minimum actual received', async () => {
  const value = receipt(); value.meta.postBalances[0] = 119995000;
  const request = { ...statusRequest(), destinationAddress: SOL_NATIVE, minimumReceivedBaseUnits: '19900000' };
  assert.deepEqual(await new JupiterPurchaseRouter(rpc(value)).status(request), { state: 'DONE', receivedBaseUnits: '20000000', message: null });
  value.meta.postBalances[0] = 119000000;
  assert.equal((await new JupiterPurchaseRouter(rpc(value)).status(request)).state, 'FAILED');
});
