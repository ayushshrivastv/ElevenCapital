import assert from 'node:assert/strict';
import test from 'node:test';
import { buildApp } from '../src/app.js';
import { WalletTransactionsRequestSchema, WalletTransactionsService } from '../src/wallet-transactions.js';

const WALLET = '9xQeWvG816bUx9EPf29DqVU1vDUbtWKVnwG1UxVajZ5J';
const PEER = 'Vote111111111111111111111111111111111111111';
const SIGNATURE = '2'.repeat(88);
const TIME = '2026-09-24T10:20:30.000Z';

test('combined wallet history maps Solana activity without changing transaction identity', async () => {
  const service = new WalletTransactionsService({ async activity(address) {
    assert.equal(address, WALLET);
    return { schemaVersion: 1, scope: 'solana-wallet-activity', walletAddress: address, status: 'ok', observedAt: TIME,
      transactions: [{ signature: SIGNATURE, timestamp: TIME, direction: 'RECEIVE', assetSymbol: 'SOL', mint: null,
        amount: '0.25', valueUsd: '50', counterparty: PEER }] };
  } }, () => Date.parse(TIME));
  const result = await service.activity({ wallets: [{ chain: 'SOLANA', address: WALLET }] });
  assert.equal(result.status, 'ok'); assert.equal(result.transactions.length, 1);
  assert.deepEqual(result.transactions[0], { id: `SOLANA:${SIGNATURE}:native:RECEIVE`, chain: 'SOLANA',
    transactionId: SIGNATURE, timestamp: TIME, direction: 'RECEIVE', assetSymbol: 'SOL', amount: '0.25',
    valueUsd: '50', usdBasis: 'current_spot', counterparty: PEER });
});

test('wallet history accepts only unique Solana wallet addresses', () => {
  assert.equal(WalletTransactionsRequestSchema.safeParse({ wallets: [{ chain: 'SOLANA', address: WALLET }] }).success, true);
  for (const body of [
    { wallets: [{ chain: 'BITCOIN', address: 'not-a-solana-address' }] },
    { wallets: [{ chain: 'UNSUPPORTED', address: 'not-a-solana-address' }] },
    { wallets: [{ chain: 'SOLANA', address: WALLET }, { chain: 'SOLANA', address: WALLET }] },
  ]) assert.equal(WalletTransactionsRequestSchema.safeParse(body).success, false);
});

test('transaction endpoint returns Solana history and rejects non-Solana payloads', async () => {
  const transactions = new WalletTransactionsService({ async activity(address) {
    return { schemaVersion: 1, scope: 'solana-wallet-activity', walletAddress: address,
      status: 'ok', observedAt: TIME, transactions: [] };
  } }, () => Date.parse(TIME));
  const app = await buildApp(undefined, false, { transactions });
  const response = await app.inject({ method: 'POST', url: '/v1/wallet/transactions',
    payload: { wallets: [{ chain: 'SOLANA', address: WALLET }] } });
  assert.equal(response.statusCode, 200); assert.equal(response.json().status, 'ok');
  const rejected = await app.inject({ method: 'POST', url: '/v1/wallet/transactions', payload: {
    wallets: [{ chain: 'UNSUPPORTED', address: 'not-a-solana-address' }],
  } });
  assert.equal(rejected.statusCode, 400);
  await app.close();
});
