import assert from 'node:assert/strict';
import test from 'node:test';
import { MAINNET_GENESIS } from '../src/portfolio-upstream.js';
import { buildSolanaTransfer, decodeSolanaTransfer, exactBaseUnits, TransferPreparationError,
  TransferPreparationService, TransferRateLimiter } from '../src/transfer.js';
import type { TransferRpc } from '../src/transfer-rpc.js';
import type { TransferRequest } from '../src/transfer-schema.js';

const SENDER = '9xQeWvG816bUx9EPf29DqVU1vDUbtWKVnwG1UxVajZ5J';
const RECIPIENT = 'Vote111111111111111111111111111111111111111';
const BLOCKHASH = 'SysvarRent111111111111111111111111111111111';
const OPERATION = '5a40fc8d-10d4-4da9-b44f-b4e28ef917cc';
function request(overrides: Partial<TransferRequest> = {}): TransferRequest {
  return { schemaVersion: 1, operationId: OPERATION, walletId: 'wallet-solana', chain: 'SOLANA',
    sender: SENDER, recipient: RECIPIENT, assetId: 'SOLANA:native', amount: '0.1', ...overrides };
}
function rpc(overrides: Partial<Record<string, unknown>> = {}): TransferRpc {
  let accountReads = 0;
  return { async call(_chain, method) {
    if (method in overrides) return overrides[method];
    if (method === 'getGenesisHash') return MAINNET_GENESIS;
    if (method === 'getBalance') return { context: { slot: '1000' }, value: '1000000000' };
    if (method === 'getAccountInfo') {
      const seen = accountReads++;
      return { context: { slot: '1000' }, value: seen % 2 === 0
        ? { executable: false, owner: '11111111111111111111111111111111', data: ['', 'base64'] } : null };
    }
    if (method === 'getLatestBlockhash') return { context: { slot: '1000' }, value: { blockhash: BLOCKHASH, lastValidBlockHeight: '2000' } };
    if (method === 'getFeeForMessage') return { context: { slot: '1000' }, value: '5000' };
    if (method === 'simulateTransaction') return { context: { slot: '1000' }, value: { err: null } };
    throw new Error(`Unexpected ${method}`);
  } };
}

test('plain decimal conversion is exact and rejects zero, exponent and excess precision', () => {
  assert.equal(exactBaseUnits('0.000000001', 9, 1_000_000_000n), '1');
  assert.equal(exactBaseUnits('1.25', 9, 2_000_000_000n), '1250000000');
  for (const amount of ['0', '1e-3', '-1', '0.0000000001']) assert.throws(() => exactBaseUnits(amount, 9, 2_000_000_000n));
});

test('Solana transfer serialization binds exact parties, blockhash and lamports', () => {
  const built = buildSolanaTransfer(SENDER, RECIPIENT, BLOCKHASH, 100_000_000n);
  assert.deepEqual(decodeSolanaTransfer(built.transaction), {
    sender: SENDER, recipient: RECIPIENT, recentBlockhash: BLOCKHASH, lamports: '100000000',
  });
  assert.ok(built.transaction.length > built.message.length);
});

test('native SOL preparation validates mainnet, balance, fee and simulation', async () => {
  const result = await new TransferPreparationService(rpc(), () => Date.parse('2026-09-22T12:00:00Z')).prepare(request());
  assert.equal(result.chain, 'SOLANA'); assert.equal(result.asset.id, 'SOLANA:native');
  assert.equal(result.baseUnits, '100000000'); assert.equal(result.estimatedFeeBaseUnits, '5000');
  assert.deepEqual(decodeSolanaTransfer(Buffer.from(result.transactionBase64, 'base64')), {
    sender: SENDER, recipient: RECIPIENT, recentBlockhash: BLOCKHASH, lamports: '100000000',
  });
});

test('unsupported token transfers and wrong-network observations fail without creating a transaction', async () => {
  await assert.rejects(new TransferPreparationService(rpc()).prepare(request({ assetId: 'SOLANA:USDC' })),
    error => error instanceof TransferPreparationError && error.code === 'unsupported_asset');
  await assert.rejects(new TransferPreparationService(rpc({ getGenesisHash: 'devnet' })).prepare(request()),
    error => error instanceof TransferPreparationError && error.code === 'wrong_network');
  await assert.rejects(new TransferPreparationService(rpc()).prepare(request({ amount: '2' })),
    error => error instanceof TransferPreparationError && error.code === 'insufficient_asset_balance');
});

test('unsafe Solana recipients are rejected before RPC work', async () => {
  for (const recipient of [SENDER, '11111111111111111111111111111111']) {
    await assert.rejects(new TransferPreparationService(rpc()).prepare(request({ recipient })),
      error => error instanceof TransferPreparationError && error.code === 'unsafe_recipient');
  }
});

test('transfer rate limiter resets only after its fixed window', () => {
  let now = 1_000; const limiter = new TransferRateLimiter(() => now, 2, 60_000);
  limiter.take(); limiter.take();
  assert.throws(() => limiter.take(), error => error instanceof TransferPreparationError && error.code === 'rate_limited');
  now += 60_000; assert.doesNotThrow(() => limiter.take());
});
