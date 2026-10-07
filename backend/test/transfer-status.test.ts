import assert from 'node:assert/strict';
import test from 'node:test';
import { buildApp } from '../src/app.js';
import { MAINNET_GENESIS } from '../src/portfolio-upstream.js';
import { TransferStatusRequestSchema, TransferStatusSchema } from '../src/transfer-status-schema.js';
import { decodeSignature, TransferStatusService } from '../src/transfer-status.js';
import { encodeBase58, TransferPreparationError, TransferRateLimiter } from '../src/transfer.js';
import type { TransferRpc } from '../src/transfer-rpc.js';

const NOW = Date.parse('2026-09-22T12:00:00.000Z');
const OPERATION = '5a40fc8d-10d4-4da9-b44f-b4e28ef917cc';
const SENDER = '4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi';
const RECIPIENT = '8qbHbw2BbbTHBW1sbeqakYXVKRQM8Ne7pLK7m6CVfeR';
const OTHER = 'CktRuQ2mttgRGhkvLSEgGkeRmW7hZcmKfP6Z4Z7Zc5Xj';
const SYSTEM_PROGRAM = '11111111111111111111111111111111';
const signatureBytes = new Uint8Array(64); signatureBytes.fill(5);
const SIGNATURE = encodeBase58(signatureBytes);
const request = {
  schemaVersion: 1 as const, operationId: OPERATION, walletId: 'wallet-solana', chain: 'SOLANA' as const,
  sender: SENDER, recipient: RECIPIENT, assetId: 'SOLANA:native' as const,
  baseUnits: '1000000000', transactionId: SIGNATURE,
};
type Mode = 'unknown' | 'processed' | 'confirmed' | 'finalized' | 'failedConfirmed' | 'failedFinalized' |
  'mismatchSender' | 'wrongRecipient' | 'wrongLamports' | 'nullTransaction';

function rpc(mode: Mode): TransferRpc {
  return { async call(_chain, method) {
    if (method === 'getGenesisHash') return MAINNET_GENESIS;
    if (method === 'getSignatureStatuses') {
      if (mode === 'unknown') return { context: { slot: '1000' }, value: [null] };
      const confirmationStatus = mode === 'processed' ? 'processed' :
        mode === 'finalized' || mode === 'failedFinalized' ? 'finalized' : 'confirmed';
      return { context: { slot: '1000' }, value: [{ slot: '990', confirmations: confirmationStatus === 'finalized' ? null : '10',
        err: mode.startsWith('failed') ? { InstructionError: [0, 'Custom'] } : null, confirmationStatus }] };
    }
    if (method === 'getTransaction') {
      if (mode === 'nullTransaction') return null;
      const failed = mode.startsWith('failed');
      const source = mode === 'mismatchSender' ? OTHER : SENDER;
      const destination = mode === 'wrongRecipient' ? OTHER : RECIPIENT;
      const lamports = mode === 'wrongLamports' ? '1000000001' : request.baseUnits;
      return { slot: '990', blockTime: String(Math.floor((NOW - 30_000) / 1000)),
        meta: { err: failed ? { InstructionError: [0, 'Custom'] } : null },
        transaction: { signatures: [SIGNATURE], message: {
          accountKeys: [
            { pubkey: source, signer: true, writable: true },
            { pubkey: destination, signer: false, writable: true },
            { pubkey: SYSTEM_PROGRAM, signer: false, writable: false },
          ],
          instructions: [{ program: 'system', programId: SYSTEM_PROGRAM,
            parsed: { type: 'transfer', info: { source, destination, lamports } } }],
        } } };
    }
    throw new Error(`Unexpected ${method}`);
  } };
}

test('Solana status verifies exact System Program transfer semantics for each confirmation state', async () => {
  const expected = { processed: 'pending', confirmed: 'confirmed', finalized: 'finalized' } as const;
  for (const [mode, state] of Object.entries(expected) as [keyof typeof expected, string][]) {
    const result = await new TransferStatusService(rpc(mode), () => NOW).status(request);
    assert.equal(result.status, state); assert.equal(result.senderVerified, true);
    assert.equal(result.isFinalized, mode === 'finalized');
  }
});

test('unknown and missing transaction observations remain unverified', async () => {
  for (const mode of ['unknown', 'nullTransaction'] as const) {
    const result = await new TransferStatusService(rpc(mode), () => NOW).status(request);
    assert.equal(result.status, 'unknown'); assert.equal(result.senderVerified, null); assert.equal(result.slot, null);
  }
});

test('verified Solana failures are explicit and finality remains accurate', async () => {
  const confirmed = await new TransferStatusService(rpc('failedConfirmed'), () => NOW).status(request);
  assert.equal(confirmed.status, 'failed'); assert.equal(confirmed.isFinalized, false);
  const finalized = await new TransferStatusService(rpc('failedFinalized'), () => NOW).status(request);
  assert.equal(finalized.status, 'failed'); assert.equal(finalized.isFinalized, true);
});

test('wrong fee payer, recipient or amount is rejected as a transaction mismatch', async () => {
  for (const mode of ['mismatchSender', 'wrongRecipient', 'wrongLamports'] as const) {
    await assert.rejects(new TransferStatusService(rpc(mode), () => NOW).status(request),
      error => error instanceof TransferPreparationError && error.code === 'transaction_mismatch');
  }
});

test('status request accepts only a Solana signature bound to Solana transfer identity', () => {
  assert.equal(decodeSignature(SIGNATURE).length, 64);
  assert.equal(TransferStatusRequestSchema.safeParse(request).success, true);
  for (const value of [
    { ...request, chain: 'ETHEREUM' }, { ...request, transactionId: `0x${'a'.repeat(64)}` },
    { ...request, assetId: 'ETHEREUM:native' }, { ...request, baseUnits: '01' }, { ...request, rpcUrl: 'https://evil.example' },
  ]) assert.equal(TransferStatusRequestSchema.safeParse(value).success, false);
});

test('status response schema rejects unverified confirmation and contradictory finality', () => {
  const valid = awaitStatus('confirmed');
  assert.equal(TransferStatusSchema.safeParse(valid).success, true);
  assert.equal(TransferStatusSchema.safeParse({ ...valid, senderVerified: null }).success, false);
  assert.equal(TransferStatusSchema.safeParse({ ...valid, status: 'finalized', isFinalized: false }).success, false);
});

function awaitStatus(status: 'confirmed') {
  return { schemaVersion: 1, operationId: OPERATION, chain: 'SOLANA',
    caip2: 'solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp', transactionId: SIGNATURE,
    sender: SENDER, senderVerified: true, status, confirmations: '10', isFinalized: false,
    observedSlot: '1000', slot: '990', blockTime: new Date(NOW - 30_000).toISOString(),
    confirmationStatus: 'confirmed', failureCode: null, observedAt: new Date(NOW).toISOString() };
}

test('HTTP status endpoint is no-store and independently rate-limited', async () => {
  const auth = { verifyAuthorization: async () => ({ subject: 'did:privy:test-user', sessionId: 'session-one' }) };
  const ledger = { registerPrepared() {}, commit() { throw new Error(); }, release() { throw new Error(); },
    recordTransaction() {}, recordObservation() {} };
  const app = await buildApp(undefined, false, { transferStatus: new TransferStatusService(rpc('confirmed'), () => NOW),
    transferStatusRateLimiter: new TransferRateLimiter(() => NOW, 1), accessTokenVerifier: auth, transferIntentLedger: ledger });
  const first = await app.inject({ method: 'POST', url: '/v1/wallet/transfer/status',
    headers: { authorization: 'Bearer test' }, payload: request });
  assert.equal(first.statusCode, 200); assert.equal(first.headers['cache-control'], 'no-store');
  const second = await app.inject({ method: 'POST', url: '/v1/wallet/transfer/status', headers: { authorization: 'Bearer test' },
    payload: { ...request, operationId: '37a9b840-3e9d-4ed5-a22f-c886327d0920' } });
  assert.equal(second.statusCode, 429);
  await app.close();
});
