import assert from 'node:assert/strict';
import test from 'node:test';
import { buildApp } from '../src/app.js';
import { PurchaseLedger, PurchaseLedgerError } from '../src/purchase-ledger.js';
import { PurchaseError, PurchaseService } from '../src/purchase.js';
import { PurchaseOptionsRequestSchema, PurchaseQuoteRequestSchema, PurchaseSubmittedRequestSchema,
  type PaymentAsset, type PurchaseWallet } from '../src/purchase-schema.js';
import type { PaymentAssetDefinition, PurchaseChainReader } from '../src/purchase-chain.js';
import type { PurchaseRouteQuoteRequest, PurchaseRouter, PurchaseRouteStatusRequest } from '../src/purchase-router.js';
import { encodeBase58 } from '../src/transfer.js';
import type { Catalog, Stock } from '../src/schema.js';
import { WalletAuthenticationError } from '../src/privy-auth.js';

const NOW = Date.parse('2026-09-23T12:00:00.000Z');
const PRINCIPAL = { subject: 'did:privy:test-user', sessionId: 'test-session' };
const WALLET = '9xQeWvG816bUx9EPf29DqVU1vDUbtWKVnwG1UxVajZ5J';
const MINT = 'XspzcW1PRtgf6Wj92HCiZdjzKCyFekVD8P5Ueh3dRMX';
const USDC = 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v';
const SOL_NATIVE = '11111111111111111111111111111111';
const wallets: PurchaseWallet[] = [{ chain: 'SOLANA', address: WALLET }];
const signature = encodeBase58(Uint8Array.from({ length: 64 }, (_, index) => index + 1));
const stock = { id: 'backed:asset-one', provider: 'backed', symbol: 'MSFTx', name: 'Microsoft xStock',
  deployments: [{ network: 'Solana', address: MINT, decimals: 8 }] } as unknown as Stock;
function catalog(rows = [stock]): Catalog {
  return { schemaVersion: 1, mode: 'live-read-only', receivedAt: new Date(NOW).toISOString(),
    providers: [{ id: 'backed', status: 'ok' }], stocks: rows };
}
function paymentAssets(): PaymentAsset[] {
  return [
    { id: 'SOLANA:SOL', symbol: 'SOL', name: 'Solana', network: 'SOLANA', chainId: '1151111081099710',
      address: SOL_NATIVE, decimals: 9, balanceBaseUnits: '1000000000', balance: '1', usdValue: '150', enabled: true },
    { id: 'SOLANA:USDC', symbol: 'USDC', name: 'USD Coin on Solana', network: 'SOLANA', chainId: '1151111081099710',
      address: USDC, decimals: 6, balanceBaseUnits: '100000000', balance: '100', usdValue: '100', enabled: true },
  ];
}
class Chains implements PurchaseChainReader {
  balances = new Map([['SOLANA:SOL', '1000000000'], ['SOLANA:USDC', '100000000'], [`SOLANA:${MINT}`, '100000000']]);
  async paymentAssets() { return paymentAssets(); }
  async balance(asset: PaymentAssetDefinition) { return this.balances.get(asset.id) ?? '0'; }
  async tokenDecimals() { return 8; }
  async tokenUiMultiplier() { return '1'; }
}
class Router implements PurchaseRouter {
  requests: PurchaseRouteQuoteRequest[] = [];
  state: 'PENDING' | 'DONE' | 'FAILED' = 'PENDING';
  async quote(request: PurchaseRouteQuoteRequest) {
    this.requests.push(request);
    return { routeId: 'solana-route', tool: 'eleven-jupiter-metis', sourceChainId: '1151111081099710',
      destinationChainId: '1151111081099710', executionValidated: true,
      expiresAt: new Date(NOW + 30_000).toISOString(), fromTokenAddress: request.source.address,
      toTokenAddress: request.destination.address, fromAddress: request.fromAddress, toAddress: request.toAddress,
      fromAmount: request.amountBaseUnits, toAmount: '200000000', toAmountMin: '198000000', toDecimals: 8,
      fromAmountUsd: '100', toAmountUsd: '99', feesUsd: null, priceImpactPercent: '0.1', sourceGasBaseUnits: '5000',
      approvalAddress: null, transaction: { kind: 'SOLANA' as const, transactionBase64: Buffer.alloc(200).toString('base64'),
        minContextSlot: '100', lastValidBlockHeight: '200' } };
  }
  async status(_request: PurchaseRouteStatusRequest) {
    return { state: this.state, receivedBaseUnits: this.state === 'DONE' ? '200000000' : null,
      message: this.state === 'FAILED' ? 'Swap failed.' : null };
  }
}
function quoteRequest(overrides: Record<string, unknown> = {}) {
  return { schemaVersion: 1 as const, operationId: '5a40fc8d-10d4-4da9-b44f-b4e28ef917cc', stockId: stock.id,
    fromAssetId: 'SOLANA:USDC', destinationId: null, amountBaseUnits: '1000000', slippageBps: 50, wallets, ...overrides };
}

test('purchase request schemas accept only a connected Solana wallet and Solana asset identifiers', () => {
  assert.equal(PurchaseOptionsRequestSchema.safeParse({ schemaVersion: 1, stockId: stock.id, wallets }).success, true);
  assert.equal(PurchaseQuoteRequestSchema.safeParse(quoteRequest()).success, true);
  for (const value of [
    { schemaVersion: 1, stockId: stock.id, wallets: [{ chain: 'UNSUPPORTED', address: 'not-a-solana-address' }] },
    { ...quoteRequest(), fromAssetId: 'UNSUPPORTED:USDC' }, { ...quoteRequest(), destinationId: 'UNSUPPORTED:not-a-solana-mint' },
  ]) assert.equal((value as { fromAssetId?: string }).fromAssetId ? PurchaseQuoteRequestSchema.safeParse(value).success : PurchaseOptionsRequestSchema.safeParse(value).success, false);
  assert.equal(PurchaseSubmittedRequestSchema.safeParse({ schemaVersion: 1, transactionId: signature }).success, true);
  assert.equal(PurchaseSubmittedRequestSchema.safeParse({ schemaVersion: 1, transactionId: `0x${'a'.repeat(64)}` }).success, false);
});

test('options expose only Solana funding assets and a verified Solana destination', async () => {
  const service = new PurchaseService(async () => catalog(), new Chains(), new Router(), new PurchaseLedger(null, () => NOW), () => NOW, true);
  const result = await service.options(PRINCIPAL, { schemaVersion: 1, stockId: stock.id, wallets });
  assert.equal(result.purchasable, true); assert.equal(result.executionEnabled, true);
  assert.deepEqual(result.paymentAssets.map(asset => asset.id), ['SOLANA:SOL', 'SOLANA:USDC']);
  assert.deepEqual(result.destinations.map(destination => destination.id), [`SOLANA:${MINT}`]);
  assert.equal(result.defaultPaymentAssetId, 'SOLANA:SOL');
});

test('quote produces one reviewed Solana action and binds the same wallet on both sides', async () => {
  const router = new Router();
  const service = new PurchaseService(async () => catalog(), new Chains(), router, new PurchaseLedger(null, () => NOW), () => NOW, true);
  const quote = await service.quote(PRINCIPAL, quoteRequest());
  assert.equal(quote.executionEnabled, true); assert.equal(quote.actions.length, 1); assert.equal(quote.actions[0]?.type, 'SOLANA_ROUTE');
  assert.equal(quote.destinationId, `SOLANA:${MINT}`); assert.equal(quote.minimumReceived, '1.98');
  assert.equal(router.requests[0]?.fromAddress, WALLET); assert.equal(router.requests[0]?.toAddress, WALLET);
});

test('quote fails closed when the selected asset or Solana fee balance is insufficient', async () => {
  const chains = new Chains(); chains.balances.set('SOLANA:USDC', '1');
  const service = new PurchaseService(async () => catalog(), chains, new Router(), new PurchaseLedger(null, () => NOW), () => NOW, true);
  await assert.rejects(service.quote(PRINCIPAL, quoteRequest()),
    error => error instanceof PurchaseError && error.code === 'insufficient_balance');
  chains.balances.set('SOLANA:USDC', '100000000'); chains.balances.set('SOLANA:SOL', '1');
  await assert.rejects(service.quote(PRINCIPAL, quoteRequest()), /needs more SOL/);
});

test('committed Solana purchase completes only after its reviewed signature settles', async () => {
  const router = new Router(); const ledger = new PurchaseLedger(null, () => NOW);
  const service = new PurchaseService(async () => catalog(), new Chains(), router, ledger, () => NOW, true);
  const quote = await service.quote(PRINCIPAL, quoteRequest()); const action = quote.actions[0]!;
  service.commit(PRINCIPAL, quote.quoteId); service.invoking(PRINCIPAL, quote.quoteId, action.id);
  const submitted = service.submitted(PRINCIPAL, quote.quoteId, action.id, { schemaVersion: 1, transactionId: signature });
  assert.equal(submitted.state, 'EXECUTING'); assert.equal(submitted.solanaTransactionSignature, null);
  router.state = 'DONE'; const completed = await service.status(PRINCIPAL, quote.quoteId);
  assert.equal(completed.state, 'COMPLETED'); assert.equal(completed.solanaTransactionSignature, signature);
  assert.equal(service.activity(PRINCIPAL).transactions[0]?.transactionId, signature);
});

test('preview mode returns a review but refuses wallet invocation', async () => {
  const service = new PurchaseService(async () => catalog(), new Chains(), new Router(), new PurchaseLedger(null, () => NOW), () => NOW, false);
  const quote = await service.quote(PRINCIPAL, quoteRequest());
  assert.equal(quote.executionEnabled, false); assert.throws(() => service.commit(PRINCIPAL, quote.quoteId), /disabled/i);
});

test('operation IDs are idempotent only for identical quote details', async () => {
  const service = new PurchaseService(async () => catalog(), new Chains(), new Router(), new PurchaseLedger(null, () => NOW), () => NOW, false);
  const first = await service.quote(PRINCIPAL, quoteRequest());
  assert.equal((await service.quote(PRINCIPAL, quoteRequest())).quoteId, first.quoteId);
  await assert.rejects(service.quote(PRINCIPAL, quoteRequest({ amountBaseUnits: '2000000' })),
    error => error instanceof PurchaseLedgerError && error.code === 'quote_conflict');
});

test('purchase HTTP routes authenticate before service work and reject extra fields', async () => {
  let calls = 0;
  const purchase = { options: async () => { calls++; return { schemaVersion: 1 as const, stockId: stock.id, purchasable: false,
    reason: 'Unavailable', executionEnabled: false, executionReason: 'Disabled', paymentAssets: [], destinations: [],
    defaultPaymentAssetId: null, defaultDestinationId: null }; } } as never;
  const auth = { async verifyAuthorization(value: string | undefined) {
    if (value !== 'Bearer valid') throw new WalletAuthenticationError('authentication_required', 401, 'Missing');
    return PRINCIPAL;
  } };
  const app = await buildApp(undefined, false, { purchase, accessTokenVerifier: auth });
  const payload = { schemaVersion: 1, stockId: stock.id, wallets };
  assert.equal((await app.inject({ method: 'POST', url: '/v1/purchases/options', payload })).statusCode, 401); assert.equal(calls, 0);
  assert.equal((await app.inject({ method: 'POST', url: '/v1/purchases/options', headers: { authorization: 'Bearer valid' }, payload })).statusCode, 200);
  assert.equal((await app.inject({ method: 'POST', url: '/v1/purchases/options', headers: { authorization: 'Bearer valid' },
    payload: { ...payload, rpcUrl: 'https://evil.example' } })).statusCode, 400);
  await app.close();
});
