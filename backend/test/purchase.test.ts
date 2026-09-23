import assert from 'node:assert/strict';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { buildApp } from '../src/app.js';
import { EVM_NATIVE, humanAmount, PAYMENT_ASSETS, PurchaseRpcError, PurchaseRpcUnavailableError,
  type PaymentAssetDefinition, type PurchaseChainReader } from '../src/purchase-chain.js';
import { LiFiError, type LiFiQuoteRequest, type LiFiRouteQuote, type PurchaseRouteStatusRequest, type PurchaseRouter } from '../src/lifi.js';
import { MAX_ACTIVE_PURCHASES_PER_SUBJECT, PurchaseLedger, PurchaseLedgerError } from '../src/purchase-ledger.js';
import { PurchaseError, PurchaseService } from '../src/purchase.js';
import type { PaymentAsset, PurchaseNetwork, PurchaseWallet } from '../src/purchase-schema.js';
import type { PrivyPrincipal } from '../src/privy-auth.js';
import { WalletAuthenticationError } from '../src/privy-auth.js';
import type { Catalog, Stock } from '../src/schema.js';
import { encodeBase58 } from '../src/transfer.js';
import { RELAY_ANTHROPIC_TOOL } from '../src/relay.js';

const NOW = Date.parse('2026-09-22T12:00:00.000Z');
const PRINCIPAL: PrivyPrincipal = { subject: 'did:privy:purchase-user', sessionId: 'session-one' };
const EVM = '0x1111111111111111111111111111111111111111';
const XSTOCK = '0xc845b2894dbddd03858fd2d643b4ef725fe0849d';
const SOL = '9xQeWvG816bUx9EPf29DqVU1vDUbtWKVnwG1UxVajZ5J';
const OPERATION = '5a40fc8d-10d4-4da9-b44f-b4e28ef917cc';
const EVM_TX = `0x${'a'.repeat(64)}`;
const EVM_TX_2 = `0x${'b'.repeat(64)}`;

function stock(provider: Stock['provider'] = 'backed'): Stock {
  return {
    id: `${provider}:${provider === 'backed' ? 'asset-one' : 'NVDA.US'}`, provider,
    providerLabel: provider === 'backed' ? 'Backed xStocks' : 'Backpack Securities', providerAssetId: 'asset-one',
    symbol: 'NVDAx', name: 'NVIDIA xStock', logoUrl: null,
    underlying: { symbol: 'NVDA', isin: null, cusip: null, listingCountry: 'US', currency: 'USD' },
    quote: { price: '150', currency: 'USD', currencyBasis: 'underlying_metadata', changeAmount: null, changePercent: null,
      asOf: null, receivedAt: new Date(NOW).toISOString(), basis: 'provider_indicative_token' },
    volume24h: null, volume24hBasis: null,
    statistics: { scope: 'solana_token', network: null, mint: null, updatedAt: null,
      marketCapitalization: metric(), liquidity: metric(), holderCount: metric('count'), organicScore: metric('score') },
    activity: { currency: 'USD', source: 'Jupiter', scope: 'solana_token', volume24h: null, netVolume24h: null,
      receivedAt: null, updatedAt: null, volumeReason: 'Unavailable', netVolumeReason: 'Unavailable' },
    deployments: provider === 'backed' ? [
      { network: 'Ethereum', address: XSTOCK, decimals: null, depositEnabled: null, withdrawEnabled: null },
      { network: 'Arbitrum', address: XSTOCK, decimals: null, depositEnabled: null, withdrawEnabled: null },
      { network: 'Solana', address: 'Xsc9qvGR1efVDFGLrVsmkzv3qi45LTBjeUKSPmx9qEh', decimals: null, depositEnabled: null, withdrawEnabled: null },
    ] : [],
    trading: { enabled: false, reason: 'Public listing only' },
  };
}
function metric(unit: 'USD' | 'count' | 'score' = 'USD') { return { value: null, unit, source: null, status: 'unavailable' as const,
  basis: unit === 'count' ? 'token_holders' as const : unit === 'score' ? 'organic_activity_score' as const : 'token_market_cap' as const,
  receivedAt: null, reason: 'Unavailable' }; }
function catalog(rows = [stock()]): Catalog { return { schemaVersion: 1, mode: 'live-read-only', receivedAt: new Date(NOW).toISOString(),
  providers: [{ id: 'backed', status: 'ok' }, { id: 'backpack', status: 'ok' }, { id: 'prestocks', status: 'ok' }], stocks: rows }; }
const wallets: PurchaseWallet[] = [{ chain: 'ETHEREUM', address: EVM }, { chain: 'SOLANA', address: SOL }];

class Chains implements PurchaseChainReader {
  balances = new Map(PAYMENT_ASSETS.map(asset => [asset.id, asset.symbol === 'USDC' ? '1000000000' : '1000000000000000000']));
  usdValues = new Map<string, string | null>();
  allowanceValue = '0'; receipt: 'PENDING' | 'SUCCESS' | 'FAILED' = 'PENDING';
  async paymentAssets(connected: readonly PurchaseWallet[]): Promise<PaymentAsset[]> {
    return PAYMENT_ASSETS.filter(asset => connected.some(wallet => wallet.chain === asset.walletChain)).map(asset => ({
      id: asset.id, symbol: asset.symbol, name: asset.name, network: asset.network, chainId: asset.chainId,
      address: asset.address, decimals: asset.decimals, balanceBaseUnits: this.balances.get(asset.id) ?? '0',
      balance: humanAmount(this.balances.get(asset.id) ?? '0', asset.decimals),
      usdValue: this.usdValues.has(asset.id) ? this.usdValues.get(asset.id)! :
        asset.symbol === 'USDC' ? humanAmount(this.balances.get(asset.id) ?? '0', asset.decimals) : null,
      enabled: true,
    }));
  }
  async balance(asset: PaymentAssetDefinition): Promise<string> { return this.balances.get(asset.id) ?? '0'; }
  async tokenDecimals(network: PurchaseNetwork): Promise<number> { return network === 'SOLANA' ? 8 : 18; }
  async allowance(): Promise<string> { return this.allowanceValue; }
  async transactionStatus(): Promise<'PENDING' | 'SUCCESS' | 'FAILED'> { return this.receipt; }
  async prepareApproval() { return { gas: '0xea60', gasPrice: '0x3b9aca00', maxFeePerGas: null, maxPriorityFeePerGas: null }; }
}

class Router implements PurchaseRouter {
  requests: LiFiQuoteRequest[] = []; state: 'PENDING' | 'DONE' | 'FAILED' = 'PENDING';
  async quote(request: LiFiQuoteRequest): Promise<LiFiRouteQuote> {
    this.requests.push(request); const arbitrum = request.destination.network === 'ARBITRUM';
    if (request.destination.network === 'SOLANA') throw new LiFiError('route_unavailable', 'This fixture only has an Ethereum route');
    return { routeId: `route-${request.destination.network}`, tool: 'relay', executionValidated: true, sourceChainId: request.source.chainId,
      destinationChainId: request.destination.chainId, fromTokenAddress: request.source.address,
      toTokenAddress: request.destination.address, fromAddress: request.fromAddress, toAddress: request.toAddress,
      fromAmount: request.amountBaseUnits, toAmount: '500000000000000000', toAmountMin: '490000000000000000', toDecimals: 18,
      fromAmountUsd: '75', toAmountUsd: arbitrum ? '76' : '75', feesUsd: '0.50', priceImpactPercent: '0.1',
      sourceGasBaseUnits: '1000000000000000',
      approvalAddress: request.source.address === EVM_NATIVE ? null : '0x2222222222222222222222222222222222222222',
      transaction: { kind: 'EVM', from: request.fromAddress, to: '0x3333333333333333333333333333333333333333', data: '0x12',
        value: request.source.address === EVM_NATIVE ? request.amountBaseUnits === '1' ? '0x1' : `0x${BigInt(request.amountBaseUnits).toString(16)}` : '0x0',
        gas: '0x30d40', gasPrice: '0x3b9aca00', maxFeePerGas: null, maxPriorityFeePerGas: null, nonce: null } };
  }
  async status() { return { state: this.state, receivedBaseUnits: this.state === 'DONE' ? '500000000000000000' : null,
    message: this.state === 'FAILED' ? 'Route failed' : null }; }
}

function quoteRequest(fromAssetId = 'ETHEREUM:ETH' as const) {
  return { schemaVersion: 1 as const, operationId: OPERATION, stockId: 'backed:asset-one', fromAssetId,
    destinationId: null, amountBaseUnits: fromAssetId.endsWith(':USDC') ? '75000000' : '100000000000000000',
    slippageBps: 50, wallets };
}

test('options expose connected balances and verified Ethereum/Solana deployments, with no provider blanket exclusion', async () => {
  const service = new PurchaseService(async () => catalog([stock(), stock('backpack')]), new Chains(), new Router(), new PurchaseLedger(null, () => NOW), () => NOW, true);
  const result = await service.options(PRINCIPAL, { schemaVersion: 1, stockId: 'backed:asset-one', wallets });
  assert.equal(result.purchasable, true); assert.equal(result.paymentAssets.length, 8);
  assert.equal(result.executionEnabled, true); assert.equal(result.executionReason, null);
  assert.deepEqual(result.destinations.map(value => value.network), ['ETHEREUM', 'SOLANA']);
  assert.deepEqual(result.destinations.map(value => value.decimals), [18, 8]); assert.equal(result.defaultDestinationId, null);
  const unavailable = await service.options(PRINCIPAL, { schemaVersion: 1, stockId: 'backpack:NVDA.US', wallets });
  assert.equal(unavailable.purchasable, false); assert.equal(unavailable.destinations.length, 0);
});

test('options default to the funded payment asset with the largest verified USD value', async () => {
  const chains = new Chains();
  for (const asset of PAYMENT_ASSETS) chains.usdValues.set(asset.id, null);
  chains.usdValues.set('ETHEREUM:ETH', '2200');
  chains.usdValues.set('ARBITRUM:USDC', '1000');
  chains.usdValues.set('SOLANA:SOL', '3500');
  const service = new PurchaseService(async () => catalog(), chains, new Router(), new PurchaseLedger(null, () => NOW), () => NOW, false);
  const options = await service.options(PRINCIPAL, { schemaVersion: 1, stockId: 'backed:asset-one', wallets });
  assert.equal(options.defaultPaymentAssetId, 'SOLANA:SOL');
});

test('equal verified USD values choose the deterministic lexicographically first asset id', async () => {
  const chains = new Chains();
  for (const asset of PAYMENT_ASSETS) chains.usdValues.set(asset.id, null);
  chains.usdValues.set('BASE:ETH', '500');
  chains.usdValues.set('ARBITRUM:USDC', '500');
  const service = new PurchaseService(async () => catalog(), chains, new Router(), new PurchaseLedger(null, () => NOW), () => NOW, false);
  const options = await service.options(PRINCIPAL, { schemaVersion: 1, stockId: 'backed:asset-one', wallets });
  assert.equal(options.defaultPaymentAssetId, 'ARBITRUM:USDC');
});

test('unknown USD values fall back through funded USDC, funded asset, then enabled asset', async () => {
  const optionsFor = async (fundedIds: readonly string[]) => {
    const chains = new Chains();
    for (const asset of PAYMENT_ASSETS) {
      chains.balances.set(asset.id, '0');
      chains.usdValues.set(asset.id, null);
    }
    for (const id of fundedIds) {
      const asset = PAYMENT_ASSETS.find(candidate => candidate.id === id)!;
      chains.balances.set(id, `1${'0'.repeat(asset.decimals)}`);
    }
    const service = new PurchaseService(async () => catalog(), chains, new Router(), new PurchaseLedger(null, () => NOW), () => NOW, false);
    return service.options(PRINCIPAL, { schemaVersion: 1, stockId: 'backed:asset-one', wallets });
  };
  assert.equal((await optionsFor(['ETHEREUM:ETH', 'BASE:USDC', 'ARBITRUM:USDC'])).defaultPaymentAssetId,
    'ARBITRUM:USDC', 'funded USDC wins and is deterministic');
  assert.equal((await optionsFor(['SOLANA:SOL', 'ETHEREUM:ETH'])).defaultPaymentAssetId,
    'ETHEREUM:ETH', 'without funded USDC the deterministic funded asset wins');
  assert.equal((await optionsFor([])).defaultPaymentAssetId, 'ARBITRUM:ETH',
    'without any funded asset the deterministic enabled asset wins');
});

test('native exact-input quote binds the portfolio-indexed destination, commits durably and only advances after route completion', async () => {
  const chains = new Chains(); const router = new Router(); const ledger = new PurchaseLedger(null, () => NOW);
  const service = new PurchaseService(async () => catalog(), chains, router, ledger, () => NOW, true);
  const quote = await service.quote(PRINCIPAL, quoteRequest());
  assert.equal(quote.operationId, OPERATION); assert.ok(quote.destinationId.startsWith('ETHEREUM:'));
  assert.equal(quote.inputAmount, '0.1'); assert.equal(quote.estimatedOutputAmount, '0.5'); assert.equal(quote.walletConfirmations, 1);
  assert.equal(quote.actions[0]?.type, 'EVM_ROUTE'); assert.equal(router.requests.length, 2);
  assert.equal((await service.quote(PRINCIPAL, quoteRequest())).quoteId, quote.quoteId, 'same operation must be idempotent');
  assert.equal(service.commit(PRINCIPAL, quote.quoteId).idempotent, false);
  assert.equal(service.commit(PRINCIPAL, quote.quoteId).idempotent, true);
  assert.equal(service.invoking(PRINCIPAL, quote.quoteId, quote.actions[0]!.id).state, 'INVOKING');
  const submitted = service.submitted(PRINCIPAL, quote.quoteId, quote.actions[0]!.id, { schemaVersion: 1, transactionId: EVM_TX });
  assert.equal(submitted.state, 'EXECUTING'); assert.equal(submitted.step, 0);
  assert.equal((await service.status(PRINCIPAL, quote.quoteId)).step, 0);
  router.state = 'DONE'; const completed = await service.status(PRINCIPAL, quote.quoteId);
  assert.equal(completed.state, 'COMPLETED'); assert.equal(completed.step, 1); assert.equal(completed.receivedAmount, '0.5');
});

test('native Arbitrum Relay purchase has one wallet action and preserves its verified four-minute review window', async () => {
  const mint = 'Pren1FvFX6J3E4kXhJuCiAD5aDmGEb7qJRncwA8Lkhw';
  const listing: Stock = { ...stock(), id: `prestocks:${mint}`, provider: 'prestocks', providerLabel: 'PreStocks · Pre-IPO',
    providerAssetId: mint, symbol: 'ANTHROPIC',
    deployments: [{ network: 'Solana', address: mint, decimals: null, depositEnabled: null, withdrawEnabled: null }] };
  const route: PurchaseRouter = { async quote(request) {
    assert.equal(request.source.id, 'ARBITRUM:ETH');
    assert.equal(request.destination.address, mint);
    return { routeId: '0x' + 'a'.repeat(64), tool: RELAY_ANTHROPIC_TOOL, executionValidated: true,
      expiresAt: new Date(NOW + 240_000).toISOString(), sourceChainId: '42161', destinationChainId: '1151111081099710',
      fromTokenAddress: EVM_NATIVE, toTokenAddress: mint, fromAddress: EVM, toAddress: SOL,
      fromAmount: request.amountBaseUnits, toAmount: '100000000', toAmountMin: '99500000', toDecimals: 8,
      fromAmountUsd: '1.35', toAmountUsd: '1.05', feesUsd: '0.27', priceImpactPercent: null,
      sourceGasBaseUnits: '10000000000000', approvalAddress: null,
      transaction: { kind: 'EVM', from: EVM, to: '0x3333333333333333333333333333333333333333', data: '0x12',
        value: '0x1c6bf52634000', gas: '0x30d40', gasPrice: '0x3b9aca00', maxFeePerGas: null,
        maxPriorityFeePerGas: null, nonce: null } };
  }, async status() { return { state: 'PENDING', receivedBaseUnits: null, message: null }; } };
  const service = new PurchaseService(async () => catalog([listing]), new Chains(), route,
    new PurchaseLedger(null, () => NOW), () => NOW, true);
  const quote = await service.quote(PRINCIPAL, { ...quoteRequest('ARBITRUM:ETH'), stockId: listing.id,
    amountBaseUnits: '500000000000000' });
  assert.equal(quote.executionEnabled, true);
  assert.equal(quote.walletConfirmations, 1);
  assert.deepEqual(quote.actions.map(action => action.type), ['EVM_ROUTE']);
  assert.equal(quote.expiresAt, new Date(NOW + 240_000).toISOString());
});

test('ERC-20 route grants only the reviewed amount and blocks route signing until approval receipt and allowance are confirmed', async () => {
  const chains = new Chains(); const router = new Router(); const service = new PurchaseService(async () => catalog(), chains, router,
    new PurchaseLedger(null, () => NOW), () => NOW, true);
  const quote = await service.quote(PRINCIPAL, quoteRequest('ETHEREUM:USDC'));
  assert.deepEqual(quote.actions.map(value => value.type), ['EVM_APPROVAL', 'EVM_ROUTE']);
  const approval = quote.actions[0]!; const route = quote.actions[1]!;
  assert.equal(approval.type, 'EVM_APPROVAL');
  if (approval.type !== 'EVM_APPROVAL') throw new Error();
  assert.equal(BigInt(`0x${approval.transaction.data.slice(-64)}`).toString(), quote.inputBaseUnits);
  assert.equal(approval.transaction.gas, '0xea60'); assert.equal(approval.transaction.gasPrice, '0x3b9aca00');
  service.commit(PRINCIPAL, quote.quoteId);
  service.invoking(PRINCIPAL, quote.quoteId, approval.id);
  assert.equal(service.submitted(PRINCIPAL, quote.quoteId, approval.id, { schemaVersion: 1, transactionId: EVM_TX }).step, 0);
  assert.throws(() => service.invoking(PRINCIPAL, quote.quoteId, route.id),
    (error: unknown) => error instanceof PurchaseLedgerError && error.code === 'action_out_of_order');
  chains.receipt = 'SUCCESS'; chains.allowanceValue = quote.inputBaseUnits;
  const approved = await service.status(PRINCIPAL, quote.quoteId); assert.equal(approved.step, 1); assert.equal(approved.state, 'EXECUTING');
  service.invoking(PRINCIPAL, quote.quoteId, route.id);
  assert.equal(service.submitted(PRINCIPAL, quote.quoteId, route.id, { schemaVersion: 1, transactionId: EVM_TX_2 }).step, 1);
  router.state = 'DONE'; const completed = await service.status(PRINCIPAL, quote.quoteId);
  assert.equal(completed.state, 'COMPLETED'); assert.equal(completed.step, 2);
});

test('quote fails before commit when source gas balance cannot cover the route', async () => {
  const chains = new Chains(); chains.balances.set('ETHEREUM:ETH', '1');
  const service = new PurchaseService(async () => catalog(), chains, new Router(), new PurchaseLedger(null, () => NOW), () => NOW, true);
  await assert.rejects(service.quote(PRINCIPAL, quoteRequest('ETHEREUM:USDC')), /needs more ETH/);
});

test('default-safe preview mode returns a live review but refuses commit before any wallet invocation', async () => {
  const service = new PurchaseService(async () => catalog(), new Chains(), new Router(), new PurchaseLedger(null, () => NOW), () => NOW, false);
  const options = await service.options(PRINCIPAL, { schemaVersion: 1, stockId: 'backed:asset-one', wallets });
  assert.equal(options.purchasable, true); assert.equal(options.executionEnabled, false); assert.ok(options.executionReason);
  const quote = await service.quote(PRINCIPAL, quoteRequest());
  assert.equal(quote.executionEnabled, false); assert.ok(quote.executionReason);
  assert.throws(() => service.commit(PRINCIPAL, quote.quoteId), /disabled/i);
  await assert.rejects(service.status(PRINCIPAL, quote.quoteId), /Commit the reviewed purchase/);
});

test('durable invoking reservation is session-bound, releasable only before provider use, and blocks a second active quote', async () => {
  const filename = join(mkdtempSync(join(tmpdir(), 'eleven-purchase-')), 'ledger.json');
  const chains = new Chains(); const router = new Router(); const ledger = new PurchaseLedger(filename, () => NOW);
  const service = new PurchaseService(async () => catalog(), chains, router, ledger, () => NOW, true);
  const first = await service.quote(PRINCIPAL, quoteRequest()); service.commit(PRINCIPAL, first.quoteId);
  const secondRequest = { ...quoteRequest(), operationId: '37a9b840-3e9d-4ed5-a22f-c886327d0920' };
  const second = await service.quote(PRINCIPAL, secondRequest);
  assert.throws(() => service.commit(PRINCIPAL, second.quoteId),
    (error: unknown) => error instanceof PurchaseLedgerError && error.code === 'quote_conflict');
  const action = first.actions[0]!;
  assert.equal(service.invoking(PRINCIPAL, first.quoteId, action.id).idempotent, false);
  assert.equal(service.invoking(PRINCIPAL, first.quoteId, action.id).idempotent, true);
  const otherSession = { ...PRINCIPAL, sessionId: 'another-device' };
  assert.throws(() => service.invoking(otherSession, first.quoteId, action.id),
    (error: unknown) => error instanceof PurchaseLedgerError && error.code === 'quote_conflict');
  assert.ok(new PurchaseLedger(filename, () => NOW + 1).findByQuote(PRINCIPAL, first.quoteId).invoking,
    'invoking phase must survive process restart');
  assert.throws(() => service.releaseInvocation(otherSession, first.quoteId, action.id), PurchaseLedgerError);
  assert.equal(service.releaseInvocation(PRINCIPAL, first.quoteId, action.id).state, 'COMMITTED');
});

test('an expired committed quote with no wallet reservation releases its source-wallet lock, but an invoking quote never auto-clears', async () => {
  let now = NOW;
  const ledger = new PurchaseLedger(null, () => now);
  const service = new PurchaseService(async () => catalog(), new Chains(), new Router(), ledger, () => now, true);
  const first = await service.quote(PRINCIPAL, quoteRequest()); service.commit(PRINCIPAL, first.quoteId);
  now += 90_001;
  const second = await service.quote(PRINCIPAL, { ...quoteRequest(), operationId: '5d4777ad-f77a-41a6-964f-31954780e2c5' });
  assert.equal(service.commit(PRINCIPAL, second.quoteId).state, 'COMMITTED');
  assert.equal(ledger.findByQuote(PRINCIPAL, first.quoteId).state, 'EXPIRED');

  const secondAction = second.actions[0]!; service.invoking(PRINCIPAL, second.quoteId, secondAction.id);
  now += 90_001;
  const third = await service.quote(PRINCIPAL, { ...quoteRequest(), operationId: 'fcd7409d-104b-461b-8cac-b66c85da2d82' });
  assert.throws(() => service.commit(PRINCIPAL, third.quoteId),
    (error: unknown) => error instanceof PurchaseLedgerError && error.code === 'quote_conflict');
  assert.equal(ledger.findByQuote(PRINCIPAL, second.quoteId).state, 'COMMITTED');
  assert.equal(ledger.findByQuote(PRINCIPAL, second.quoteId).invoking?.actionId, secondAction.id);
});

test('preview quote storage is bounded per subject and expired reviews are pruned on mutation and restart', async () => {
  let now = NOW;
  const filename = join(mkdtempSync(join(tmpdir(), 'eleven-preview-quota-')), 'ledger.json');
  const ledger = new PurchaseLedger(filename, () => now);
  const service = new PurchaseService(async () => catalog(), new Chains(), new Router(), ledger, () => now, false);
  const operationId = (index: number) => `00000000-0000-4000-8000-${index.toString().padStart(12, '0')}`;
  let firstQuoteId = '';
  for (let index = 0; index < MAX_ACTIVE_PURCHASES_PER_SUBJECT; index++) {
    const quote = await service.quote(PRINCIPAL, { ...quoteRequest(), operationId: operationId(index) });
    if (index === 0) firstQuoteId = quote.quoteId;
  }
  assert.equal(ledger.snapshot().length, MAX_ACTIVE_PURCHASES_PER_SUBJECT);
  assert.equal((await service.quote(PRINCIPAL, { ...quoteRequest(), operationId: operationId(0) })).quoteId, firstQuoteId,
    'an idempotent operation remains readable at the quota boundary');
  await assert.rejects(service.quote(PRINCIPAL, { ...quoteRequest(), operationId: operationId(MAX_ACTIVE_PURCHASES_PER_SUBJECT) }),
    (error: unknown) => error instanceof PurchaseLedgerError && error.code === 'quote_capacity' && error.statusCode === 429);

  now += 90_001;
  const recovered = await service.quote(PRINCIPAL, { ...quoteRequest(), operationId: operationId(MAX_ACTIVE_PURCHASES_PER_SUBJECT + 1) });
  assert.ok(recovered.quoteId); assert.equal(ledger.snapshot().length, 1,
    'the next mutation prunes every expired unstarted preview quote');
  now += 90_001;
  assert.equal(new PurchaseLedger(filename, () => now).snapshot().length, 0,
    'restart load prunes an expired preview quote from the durable file');
});

test('all purchase routes authenticate before work and enforce strict bodies', async () => {
  let calls = 0;
  const purchase = { options: async () => { calls++; return { schemaVersion: 1 as const, stockId: 'backed:asset-one', purchasable: false,
    reason: 'Unavailable', executionEnabled: false, executionReason: 'Disabled', paymentAssets: [], destinations: [], defaultPaymentAssetId: null, defaultDestinationId: null }; },
    quote: async () => { throw new Error(); }, commit: () => { throw new Error(); }, invoking: () => { throw new Error(); },
    releaseInvocation: () => { throw new Error(); }, submitted: () => { throw new Error(); }, status: async () => { throw new Error(); } };
  const auth = { verifyAuthorization: async (value: string | undefined) => {
    if (value !== 'Bearer valid') throw new WalletAuthenticationError('authentication_required', 401, 'Missing'); return PRINCIPAL;
  } };
  const app = await buildApp(undefined, false, { purchase, accessTokenVerifier: auth });
  const payload = { schemaVersion: 1, stockId: 'backed:asset-one', wallets };
  assert.equal((await app.inject({ method: 'POST', url: '/v1/purchases/options', payload })).statusCode, 401); assert.equal(calls, 0);
  const quoteId = '37a9b840-3e9d-4ed5-a22f-c886327d0920'; const actionId = '43cc5960-d013-4bd7-bf55-0afb57e6cb51';
  for (const request of [
    { method: 'POST', url: '/v1/purchases/quote', payload: quoteRequest() },
    { method: 'POST', url: `/v1/purchases/${quoteId}/commit`, payload: { schemaVersion: 1 } },
    { method: 'POST', url: `/v1/purchases/${quoteId}/actions/${actionId}/invoking`, payload: { schemaVersion: 1 } },
    { method: 'POST', url: `/v1/purchases/${quoteId}/actions/${actionId}/submitted`, payload: { schemaVersion: 1, transactionId: EVM_TX } },
    { method: 'POST', url: `/v1/purchases/${quoteId}/actions/${actionId}/release`, payload: { schemaVersion: 1, reason: 'provider_definitely_not_invoked' } },
    { method: 'GET', url: `/v1/purchases/${quoteId}/status` },
  ]) assert.equal((await app.inject(request)).statusCode, 401, request.url);
  assert.equal(calls, 0);
  const good = await app.inject({ method: 'POST', url: '/v1/purchases/options', headers: { authorization: 'Bearer valid' }, payload });
  assert.equal(good.statusCode, 200); assert.equal(calls, 1); assert.equal(good.headers['cache-control'], 'no-store');
  assert.equal((await app.inject({ method: 'POST', url: '/v1/purchases/options', headers: { authorization: 'Bearer valid' },
    payload: { ...payload, rpcUrl: 'https://evil.example' } })).statusCode, 400);
  await app.close();
});

const STOCK_MINT = 'Xsc9qvGR1efVDFGLrVsmkzv3qi45LTBjeUKSPmx9qEh';
function solanaStock(provider: Stock['provider']): Stock {
  return { ...stock(provider), id: `${provider}:MSFT.US`, symbol: provider === 'backpack' ? 'MSFT.US' : 'MSFTx',
    deployments: [{ network: 'Solana', address: STOCK_MINT, decimals: 8, depositEnabled: null, withdrawEnabled: null }] };
}
class SolanaRouter implements PurchaseRouter {
  requests: LiFiQuoteRequest[] = [];
  validated = true;
  state: 'PENDING' | 'DONE' = 'PENDING';
  expiresAt = new Date(NOW + 30_000).toISOString();
  async quote(request: LiFiQuoteRequest): Promise<LiFiRouteQuote> {
    this.requests.push(request);
    return { routeId: 'verified-solana-route', tool: 'eleven-jupiter-metis', sourceChainId: request.source.chainId,
      destinationChainId: request.destination.chainId, fromTokenAddress: request.source.address,
      toTokenAddress: request.destination.address, fromAddress: request.fromAddress, toAddress: request.toAddress,
      fromAmount: request.amountBaseUnits, toAmount: '100000000', toAmountMin: '99000000', toDecimals: request.destination.decimals,
      fromAmountUsd: '100', toAmountUsd: '99.5', feesUsd: null, priceImpactPercent: '0.1', sourceGasBaseUnits: '5000000',
      executionValidated: this.validated, expiresAt: this.expiresAt, approvalAddress: null,
      transaction: { kind: 'SOLANA', transactionBase64: Buffer.alloc(200).toString('base64'), minContextSlot: '100', lastValidBlockHeight: '200' } };
  }
  async status() { return { state: this.state, receivedBaseUnits: this.state === 'DONE' ? '100000000' : null, message: null }; }
}

test('only a completed verified Solana purchase exposes its final transaction signature', async () => {
  const row = solanaStock('prestocks'); const router = new SolanaRouter();
  const ledger = new PurchaseLedger(null, () => NOW);
  const service = new PurchaseService(async () => catalog([row]), new Chains(), router, ledger, () => NOW, true);
  const quote = await service.quote(PRINCIPAL, { ...quoteRequest(), stockId: row.id,
    fromAssetId: 'SOLANA:USDC', amountBaseUnits: '100000000' });
  const action = quote.actions[0]!;
  const signature = encodeBase58(Uint8Array.from({ length: 64 }, (_, index) => index + 1));
  service.commit(PRINCIPAL, quote.quoteId);
  service.invoking(PRINCIPAL, quote.quoteId, action.id);
  const submitted = service.submitted(PRINCIPAL, quote.quoteId, action.id, { schemaVersion: 1, transactionId: signature });
  assert.equal(submitted.solanaTransactionSignature, null);
  assert.equal((await service.status(PRINCIPAL, quote.quoteId)).solanaTransactionSignature, null);
  router.state = 'DONE';
  const completed = await service.status(PRINCIPAL, quote.quoteId);
  assert.equal(completed.state, 'COMPLETED');
  assert.equal(completed.solanaTransactionSignature, signature);
  assert.equal(ledger.findByQuote(PRINCIPAL, quote.quoteId).solanaTransactionSignature, signature);
});

test('a Solana-destination EVM route waits for its destination signature and passes exact settlement details', async () => {
  const row = solanaStock('prestocks'); const signature = encodeBase58(Uint8Array.from({ length: 64 }, (_, index) => index + 1));
  let destinationTransactionId: string | null = null;
  let statusRequest: PurchaseRouteStatusRequest | null = null;
  const router: PurchaseRouter = {
    async quote(request) {
      return { routeId: 'relay-request-1', tool: 'relay-v2', executionValidated: true,
        sourceChainId: request.source.chainId, destinationChainId: request.destination.chainId,
        fromTokenAddress: request.source.address, toTokenAddress: request.destination.address,
        fromAddress: request.fromAddress, toAddress: request.toAddress, fromAmount: request.amountBaseUnits,
        toAmount: '100000000', toAmountMin: '99000000', toDecimals: request.destination.decimals,
        fromAmountUsd: '3', toAmountUsd: '2.9', feesUsd: '0.1', priceImpactPercent: '0.1', sourceGasBaseUnits: '1',
        approvalAddress: null, transaction: { kind: 'EVM', from: request.fromAddress,
          to: '0x3333333333333333333333333333333333333333', data: '0x12',
          value: `0x${BigInt(request.amountBaseUnits).toString(16)}`, gas: '0x30d40', gasPrice: '0x3b9aca00',
          maxFeePerGas: null, maxPriorityFeePerGas: null, nonce: null } };
    },
    async status(request) {
      statusRequest = request;
      return { state: 'DONE', receivedBaseUnits: '100000000', message: null, destinationTransactionId };
    },
  };
  const service = new PurchaseService(async () => catalog([row]), new Chains(), router,
    new PurchaseLedger(null, () => NOW), () => NOW, true);
  const quote = await service.quote(PRINCIPAL, { ...quoteRequest(), stockId: row.id, fromAssetId: 'ARBITRUM:ETH',
    amountBaseUnits: '1000000000000000' });
  const action = quote.actions[0]!;
  service.commit(PRINCIPAL, quote.quoteId);
  service.invoking(PRINCIPAL, quote.quoteId, action.id);
  service.submitted(PRINCIPAL, quote.quoteId, action.id, { schemaVersion: 1, transactionId: EVM_TX });
  const pending = await service.status(PRINCIPAL, quote.quoteId);
  assert.equal(pending.state, 'EXECUTING'); assert.equal(pending.solanaTransactionSignature, null);
  assert.equal((statusRequest as PurchaseRouteStatusRequest | null)?.destinationAddress, STOCK_MINT);
  assert.equal((statusRequest as PurchaseRouteStatusRequest | null)?.recipientAddress, SOL);
  assert.equal((statusRequest as PurchaseRouteStatusRequest | null)?.minimumReceivedBaseUnits, quote.minimumReceivedBaseUnits);
  assert.equal((statusRequest as PurchaseRouteStatusRequest & { routeId?: string } | null)?.routeId, 'relay-request-1');
  destinationTransactionId = signature;
  const completed = await service.status(PRINCIPAL, quote.quoteId);
  assert.equal(completed.state, 'COMPLETED'); assert.equal(completed.solanaTransactionSignature, signature);
});

test('each provider uses its exact verified Solana deployment for buy and owned tokens for sell', async () => {
  for (const provider of ['backed', 'backpack', 'prestocks'] as const) {
    const row = solanaStock(provider); const chains = new Chains(); const router = new SolanaRouter();
    const sourceId = `SOLANA:${STOCK_MINT}`;
    chains.balances.set(sourceId, '100000000');
    const service = new PurchaseService(async () => catalog([row]), chains, router, new PurchaseLedger(null, () => NOW), () => NOW, true);
    const options = await service.options(PRINCIPAL, { schemaVersion: 1, stockId: row.id, wallets });
    assert.equal(options.purchasable, true);
    assert.equal(options.destinations[0]?.address, STOCK_MINT);
    const buy = await service.quote(PRINCIPAL, { ...quoteRequest(), stockId: row.id, fromAssetId: 'SOLANA:USDC', amountBaseUnits: '100000000' });
    assert.equal(buy.executionEnabled, true);
    assert.equal(buy.actions[0]?.type, 'SOLANA_ROUTE');
    assert.equal(buy.expiresAt, router.expiresAt);
    const sellOptions = await service.options(PRINCIPAL, { schemaVersion: 1, stockId: row.id, side: 'SELL', wallets });
    assert.equal(sellOptions.purchasable, true);
    assert.equal(sellOptions.paymentAssets[0]?.id, sourceId);
    assert.equal(sellOptions.paymentAssets[0]?.balance, '1');
    assert.equal(sellOptions.paymentAssets[0]?.symbol, row.symbol);
    assert.deepEqual(sellOptions.destinations.map(d => d.symbol), ['USDC', 'SOL']);
    const sell = await service.quote(PRINCIPAL, { ...quoteRequest(), operationId: 'f7d5d22e-7ab3-46e6-81de-68081b6e916e',
      stockId: row.id, side: 'SELL', fromAssetId: sourceId, amountBaseUnits: '50000000', destinationId: sellOptions.destinations[0]!.id });
    assert.equal(sell.inputAmount, '0.5');
    assert.equal(sell.executionEnabled, true);
    assert.equal(router.requests.at(-1)?.source.address, STOCK_MINT);
    assert.equal(router.requests.at(-1)?.destination.symbol, 'USDC');
    await assert.rejects(service.quote(PRINCIPAL, { ...quoteRequest(), operationId: 'c6605ca0-df5f-4261-82e7-f0887648e6e9',
      stockId: row.id, side: 'SELL', fromAssetId: 'SOLANA:USDC', amountBaseUnits: '1' }), /not supported/);
  }
});

test('an unfunded stock holding cannot be sold and an unvalidated quote cannot be committed', async () => {
  const row = solanaStock('backed'); const chains = new Chains(); const router = new SolanaRouter(); router.validated = false;
  const service = new PurchaseService(async () => catalog([row]), chains, router, new PurchaseLedger(null, () => NOW), () => NOW, true);
  const options = await service.options(PRINCIPAL, { schemaVersion: 1, stockId: row.id, side: 'SELL', wallets });
  assert.equal(options.purchasable, false); assert.match(options.reason!, /No MSFTx tokens/);
  await assert.rejects(service.quote(PRINCIPAL, { ...quoteRequest(), stockId: row.id, side: 'SELL',
    fromAssetId: `SOLANA:${STOCK_MINT}`, amountBaseUnits: '1' }), /does not hold enough/);
  const buy = await service.quote(PRINCIPAL, { ...quoteRequest(), stockId: row.id, fromAssetId: 'SOLANA:USDC', amountBaseUnits: '1000000' });
  assert.equal(buy.executionEnabled, false);
  assert.throws(() => service.commit(PRINCIPAL, buy.quoteId), /preview/);
  await assert.rejects(service.quote(PRINCIPAL, { ...quoteRequest(), stockId: row.id, side: 'SELL',
    fromAssetId: 'SOLANA:USDC', amountBaseUnits: '1000000' }), /different details/);
});

test('issuer and RPC decimal disagreement excludes the exact deployment', async () => {
  const row = solanaStock('prestocks'); row.deployments[0]!.decimals = 6;
  const service = new PurchaseService(async () => catalog([row]), new Chains(), new SolanaRouter(), new PurchaseLedger(null, () => NOW), () => NOW, true);
  const options = await service.options(PRINCIPAL, { schemaVersion: 1, stockId: row.id, wallets });
  assert.equal(options.destinations.length, 0);
  assert.equal(options.purchasable, false);

  class InvalidMetadataChains extends Chains {
    async tokenDecimals(): Promise<number> { throw new PurchaseRpcError(); }
  }
  const invalid = new PurchaseService(async () => catalog([solanaStock('backed')]), new InvalidMetadataChains(), new SolanaRouter(),
    new PurchaseLedger(null, () => NOW), () => NOW, true);
  const invalidOptions = await invalid.options(PRINCIPAL, { schemaVersion: 1, stockId: 'backed:MSFT.US', wallets });
  assert.equal(invalidOptions.purchasable, false);
  assert.equal(invalidOptions.destinations.length, 0);
});

test('temporary deployment metadata outages remain retryable while healthy exact deployments stay available', async () => {
  class MetadataOutageChains extends Chains {
    async tokenDecimals(network: PurchaseNetwork): Promise<number> {
      if (network === 'SOLANA') throw new PurchaseRpcUnavailableError();
      return 18;
    }
  }
  const chains = new MetadataOutageChains();
  const unavailable = new PurchaseService(async () => catalog([solanaStock('backed')]), chains, new SolanaRouter(),
    new PurchaseLedger(null, () => NOW), () => NOW, true);
  await assert.rejects(unavailable.options(PRINCIPAL, { schemaVersion: 1, stockId: 'backed:MSFT.US', wallets }),
    error => error instanceof PurchaseError && error.code === 'router_unavailable' && error.statusCode === 503);

  const partial = new PurchaseService(async () => catalog(), chains, new Router(),
    new PurchaseLedger(null, () => NOW), () => NOW, true);
  const options = await partial.options(PRINCIPAL, { schemaVersion: 1, stockId: 'backed:asset-one', wallets });
  assert.deepEqual(options.destinations.map(destination => destination.network), ['ETHEREUM']);
  assert.equal(options.purchasable, true);
  await assert.rejects(partial.options(PRINCIPAL, { schemaVersion: 1, stockId: 'backed:asset-one', wallets: [wallets[1]!] }),
    error => error instanceof PurchaseError && error.code === 'router_unavailable' && error.statusCode === 503);
  await assert.rejects(partial.options(PRINCIPAL, { schemaVersion: 1, stockId: 'backed:asset-one', side: 'SELL', wallets }),
    error => error instanceof PurchaseError && error.code === 'router_unavailable' && error.statusCode === 503);
  await assert.rejects(partial.quote(PRINCIPAL, { ...quoteRequest('SOLANA:USDC'),
    operationId: 'bd3839ce-d9ba-40ea-ae60-274ec788797e', destinationId: `SOLANA:${STOCK_MINT}` }),
  error => error instanceof PurchaseError && error.code === 'router_unavailable' && error.statusCode === 503);
});

test('scaled xStock quotes and owned balances use chain UI quantities while raw transaction units remain exact', async () => {
  class ScaledChains extends Chains {
    async tokenUiMultiplier() { return '1.0059033904787456'; }
  }
  const row = solanaStock('backed'); const chains = new ScaledChains(); const router = new SolanaRouter();
  chains.balances.set(`SOLANA:${STOCK_MINT}`, '100000000');
  const service = new PurchaseService(async () => catalog([row]), chains, router, new PurchaseLedger(null, () => NOW), () => NOW, true);
  const buy = await service.quote(PRINCIPAL, { ...quoteRequest(), stockId: row.id, fromAssetId: 'SOLANA:USDC', amountBaseUnits: '100000000' });
  assert.equal(buy.estimatedOutputAmount, '1.00590339');
  assert.equal(buy.estimatedOutputBaseUnits, '100000000');
  assert.equal(buy.minimumReceived, '0.99584435');
  assert.equal(buy.outputDecimals, 8);
  assert.equal(buy.outputUiMultiplier, '1.0059033904787456');
  const options = await service.options(PRINCIPAL, { schemaVersion: 1, stockId: row.id, side: 'SELL', wallets });
  assert.equal(options.paymentAssets[0]?.balance, '1.00590339');
  assert.equal(options.paymentAssets[0]?.balanceBaseUnits, '100000000');
  const sell = await service.quote(PRINCIPAL, { ...quoteRequest(), operationId: 'ebdf1172-09d6-4b79-ab1f-c106a0f3c3a8', stockId: row.id,
    side: 'SELL', fromAssetId: `SOLANA:${STOCK_MINT}`, amountBaseUnits: '50000000', destinationId: options.destinations[0]!.id });
  assert.equal(sell.inputAmount, '0.50295169');
  assert.equal(sell.inputBaseUnits, '50000000');
  assert.equal(router.requests.at(-1)?.source.uiMultiplier, '1.0059033904787456');
});

test('automatic route selection compares scaled UI output when router USD values are unavailable', async () => {
  const alternateMint = 'PresTj4Yc2bAR197Er7wz4UUKSfqt6FryBEdAriBoQB';
  const row = solanaStock('backed');
  row.deployments.push({ network: 'Solana', address: alternateMint, decimals: 8, depositEnabled: null, withdrawEnabled: null });
  class ScaledChains extends Chains {
    async tokenUiMultiplier(_network: PurchaseNetwork, address: string) { return address === STOCK_MINT ? '2' : '1'; }
  }
  class UnpricedRouter extends SolanaRouter {
    override async quote(request: LiFiQuoteRequest): Promise<LiFiRouteQuote> {
      const route = await super.quote(request);
      const scaled = request.destination.address === STOCK_MINT;
      return { ...route, toAmount: scaled ? '60000000' : '100000000',
        toAmountMin: scaled ? '59000000' : '99000000', toAmountUsd: null };
    }
  }
  const service = new PurchaseService(async () => catalog([row]), new ScaledChains(), new UnpricedRouter(),
    new PurchaseLedger(null, () => NOW), () => NOW, true);
  const quote = await service.quote(PRINCIPAL, { ...quoteRequest(), stockId: row.id,
    fromAssetId: 'SOLANA:USDC', amountBaseUnits: '100000000' });
  assert.equal(quote.destinationId, `SOLANA:${STOCK_MINT}`);
  assert.equal(quote.estimatedOutputAmount, '1.2');
  assert.equal(quote.executableUnitPriceUsd, null);
});
