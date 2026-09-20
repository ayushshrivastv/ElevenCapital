import assert from 'node:assert/strict';
import test from 'node:test';
import { EvmActivityService, WalletTransactionsService, type BlockscoutReader } from '../src/wallet-transactions.js';
import { completedPurchaseActivity } from '../src/purchase-activity.js';
import type { StoredPurchase } from '../src/purchase-ledger.js';
import { buildApp } from '../src/app.js';
import { WalletAuthenticationError } from '../src/privy-auth.js';

const wallet = '0x1234567890abcdef1234567890abcdef12345678';
const peer = '0xabcdefabcdefabcdefabcdefabcdefabcdefabcd';
const tx = `0x${'a'.repeat(64)}`;
const tokenTx = `0x${'b'.repeat(64)}`;
const time = '2026-09-24T10:20:30.000Z';
const wrapper = (hash: string) => ({ hash });
const page = (items: unknown[]) => ({ items, next_page_params: null });

test('indexed Arbitrum activity shows confirmed ETH and ERC-20 flows with explicit spot basis', async () => {
  const reader: BlockscoutReader = { async read(chain, address, kind) {
    assert.equal(chain, 'ARBITRUM'); assert.equal(address, wallet);
    if (kind === 'transactions') return page([
      { hash: tx, status: 'ok', timestamp: time, from: wrapper(peer), to: wrapper(wallet),
        value: '1000000000000000', exchange_rate: '3000' },
      { hash: `0x${'c'.repeat(64)}`, status: 'error', timestamp: time, from: wrapper(wallet),
        to: wrapper(peer), value: '2000000000000000', exchange_rate: '3000' },
    ]);
    if (kind === 'token-transfers') return page([
      { transaction_hash: tokenTx, log_index: '2', token_type: 'ERC-20', timestamp: time,
        from: wrapper(wallet), to: wrapper(peer), total: { value: '2500000', decimals: '6' },
        token: { address_hash: '0xabcdefabcdefabcdefabcdefabcdefabcdefabce', symbol: 'USDC',
          reputation: 'ok', exchange_rate: '0.999' } },
      { transaction_hash: tokenTx, log_index: '3', token_type: 'ERC-721', timestamp: time,
        from: wrapper(peer), to: wrapper(wallet), total: { value: '1', decimals: '0' },
        token: { address_hash: '0xabcdefabcdefabcdefabcdefabcdefabcdefabce', symbol: 'NFT' } },
    ]);
    return page([]);
  } };
  const result = await new EvmActivityService('ARBITRUM', reader, () => Date.parse(time)).activity(wallet);
  assert.equal(result.status, 'ok');
  assert.equal(result.transactions.length, 2);
  const native = result.transactions.find(row => row.assetSymbol === 'ETH')!;
  assert.equal(native.direction, 'RECEIVE'); assert.equal(native.amount, '0.001');
  assert.equal(native.valueUsd, '3'); assert.equal(native.usdBasis, 'current_spot');
  const token = result.transactions.find(row => row.assetSymbol === 'USDC')!;
  assert.equal(token.direction, 'SEND'); assert.equal(token.amount, '2.5');
  assert.equal(token.valueUsd, '2.4975');
});

test('partial explorer reads never claim a complete history', async () => {
  const reader: BlockscoutReader = { async read(_chain, _address, kind) {
    if (kind === 'transactions') return page([]);
    throw new Error('unavailable');
  } };
  const result = await new EvmActivityService('ETHEREUM', reader).activity(wallet);
  assert.equal(result.status, 'partial'); assert.deepEqual(result.transactions, []);
});

test('combined feed binds exact wallets and never lets one unavailable chain hide another', async () => {
  const solana = '11111111111111111111111111111111';
  const service = new WalletTransactionsService(
    { async activity(address) { assert.equal(address, solana); return { status: 'ok', transactions: [] } as never; } },
    { async activity() { return { status: 'unavailable', transactions: [] }; } },
    { async activity() { return { status: 'ok', transactions: [] }; } },
    () => Date.parse(time));
  const response = await service.activity({ wallets: [{ chain: 'SOLANA', address: solana },
    { chain: 'ARBITRUM', address: wallet.toUpperCase().replace(/^0X/, '0x') },
    { chain: 'ETHEREUM', address: wallet }] });
  assert.equal(response.status, 'partial');
  assert.equal(response.wallets[1]?.address, wallet);
});

test('completed purchase history is principal-scoped and retains unknown legacy side', () => {
  const principal = { subject: 'did:privy:owner', sessionId: 'session' };
  const row = { subject: principal.subject, state: 'COMPLETED', side: null, operationId: '123e4567-e89b-42d3-a456-426614174000',
    updatedAt: time, solanaTransactionSignature: '4'.repeat(87), receivedAmount: '0.5',
    submitted: [{ transactionId: tx }], quote: { actions: [{}], stockId: 'prestocks:spcx',
      fromAssetId: 'ARBITRUM:USDC', inputAmount: '1', executableUnitPriceUsd: '2' } } as StoredPurchase;
  const response = completedPurchaseActivity(principal, [row, { ...row, subject: 'did:privy:someone-else' }], Date.parse(time));
  assert.equal(response.transactions.length, 1);
  assert.equal(response.transactions[0]?.side, null);
  assert.equal(response.transactions[0]?.valueUsd, '1');
  assert.deepEqual(response.transactions[0]?.relatedTransactionIds, [tx, '4'.repeat(87)]);
  assert.equal(completedPurchaseActivity({ subject: 'did:privy:third', sessionId: 'other' }, [row], Date.parse(time)).transactions.length, 0);
});

test('transaction route binds response wallets and purchase route requires a verified Privy subject', async () => {
  const principal = { subject: 'did:privy:owner', sessionId: 'session' };
  const app = await buildApp(undefined, false, {
    accessTokenVerifier: { async verifyAuthorization(header) {
      if (header !== 'Bearer valid') throw new WalletAuthenticationError('authentication_required', 401, 'Missing');
      return principal;
    } },
    transactions: { async activity(input) { return { schemaVersion: 1, scope: 'wallet-transactions',
      wallets: input.wallets, status: 'ok', observedAt: time, transactions: [] }; } },
    purchase: { activity() { return { schemaVersion: 1, scope: 'completed-purchases',
      observedAt: time, hasMore: false, transactions: [] }; } } as never,
  });
  const publicResponse = await app.inject({ method: 'POST', url: '/v1/wallet/transactions', payload: {
    wallets: [{ chain: 'ARBITRUM', address: wallet }] } });
  assert.equal(publicResponse.statusCode, 200);
  assert.equal(publicResponse.json().wallets[0].address, wallet);
  assert.equal((await app.inject({ method: 'GET', url: '/v1/purchase/activity' })).statusCode, 401);
  const privateResponse = await app.inject({ method: 'GET', url: '/v1/purchase/activity',
    headers: { authorization: 'Bearer valid' } });
  assert.equal(privateResponse.statusCode, 200);
  assert.equal(privateResponse.json().scope, 'completed-purchases');
  await app.close();
});
