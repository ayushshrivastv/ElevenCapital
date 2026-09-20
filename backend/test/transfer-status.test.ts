import assert from 'node:assert/strict';
import test from 'node:test';
import { buildApp } from '../src/app.js';
import { ETH_USDC, MAINNET_GENESIS } from '../src/portfolio-upstream.js';
import { TransferStatusRequestSchema, TransferStatusSchema } from '../src/transfer-status-schema.js';
import { decodeSignature, TransferStatusService } from '../src/transfer-status.js';
import { encodeBase58, TransferPreparationError, TransferRateLimiter } from '../src/transfer.js';
import type { TransferRpc } from '../src/transfer-rpc.js';

const NOW = Date.parse('2026-09-22T12:00:00.000Z');
const OPERATION = '5a40fc8d-10d4-4da9-b44f-b4e28ef917cc';
const WALLET_ID = 'wallet-test-main';
const HASH = `0x${'ab'.repeat(32)}`;
const BLOCK_HASH = `0x${'cd'.repeat(32)}`;
const ETH_SENDER = '0x1111111111111111111111111111111111111111';
const ETH_RECIPIENT = '0x2222222222222222222222222222222222222222';
const ETH_OTHER = '0x3333333333333333333333333333333333333333';
const SOL_SENDER = '4vJ9JU1bJJE96FWSJKvHsmmFADCg4gpZQff4P3bkLKi';
const SOL_RECIPIENT = '8qbHbw2BbbTHBW1sbeqakYXVKRQM8Ne7pLK7m6CVfeR';
const SOL_OTHER = 'CktRuQ2mttgRGhkvLSEgGkeRmW7hZcmKfP6Z4Z7Zc5Xj';
const SYSTEM_PROGRAM = '11111111111111111111111111111111';
const signatureBytes = new Uint8Array(64); signatureBytes.fill(5);
const SIGNATURE = encodeBase58(signatureBytes);

const evmRequest = {
  schemaVersion: 1 as const, operationId: OPERATION, walletId: WALLET_ID, chain: 'ETHEREUM' as const, sender: ETH_SENDER,
  recipient: ETH_RECIPIENT, assetId: 'ETHEREUM:native' as const, baseUnits: '100000000000000000', transactionId: HASH,
};
const usdcRequest = { ...evmRequest, assetId: 'ETHEREUM:USDC' as const, baseUnits: '1234567' };

type EvmMode = 'unknown' | 'pending' | 'confirmed' | 'finalized' | 'failedConfirmed' | 'failedFinalized' |
  'mismatchSender' | 'wrongRecipient' | 'wrongValue' | 'wrongCalldata' | 'missingTransactionWithReceipt';

function ethereumRpc(mode: EvmMode, request = evmRequest): TransferRpc {
  const included = ['confirmed', 'finalized', 'failedConfirmed', 'failedFinalized', 'missingTransactionWithReceipt'].includes(mode);
  const blockNumber = mode === 'confirmed' || mode === 'failedConfirmed' ? '0x69' : '0x60';
  const expectedTo = request.assetId === 'ETHEREUM:USDC' ? ETH_USDC : request.recipient;
  const expectedValue = request.assetId === 'ETHEREUM:USDC' ? '0x0' : `0x${BigInt(request.baseUnits).toString(16)}`;
  const expectedInput = request.assetId === 'ETHEREUM:USDC'
    ? `0xa9059cbb${request.recipient.slice(2).padStart(64, '0')}${BigInt(request.baseUnits).toString(16).padStart(64, '0')}`
    : '0x';
  return { async call(_chain, method, params) {
    if (method === 'eth_chainId') return '0x1';
    if (method === 'eth_getTransactionByHash') {
      if (mode === 'unknown' || mode === 'missingTransactionWithReceipt') return null;
      return {
        hash: HASH,
        from: mode === 'mismatchSender' ? ETH_OTHER : ETH_SENDER,
        to: mode === 'wrongRecipient' ? ETH_OTHER : expectedTo,
        value: mode === 'wrongValue' ? `0x${(BigInt(request.baseUnits) + 1n).toString(16)}` : expectedValue,
        input: mode === 'wrongCalldata' ? '0x00' : expectedInput,
        chainId: '0x1',
        blockNumber: included ? blockNumber : null,
      };
    }
    if (method === 'eth_getTransactionReceipt') {
      if (!included) return null;
      return { transactionHash: HASH, from: ETH_SENDER, to: expectedTo, blockNumber, blockHash: BLOCK_HASH,
        status: mode.startsWith('failed') ? '0x0' : '0x1' };
    }
    if (method === 'eth_getBlockByNumber') {
      if (params[0] === 'latest') return { number: '0x70', hash: `0x${'aa'.repeat(32)}`, timestamp: `0x${Math.floor(NOW / 1000).toString(16)}` };
      if (params[0] === 'finalized') return { number: '0x64', hash: `0x${'ef'.repeat(32)}`, timestamp: `0x${Math.floor(NOW / 1000).toString(16)}` };
      return { number: params[0], hash: BLOCK_HASH, timestamp: `0x${Math.floor((NOW - 60_000) / 1000).toString(16)}` };
    }
    throw new Error(`Unexpected ${method}`);
  } };
}

test('Ethereum status verifies the exact reviewed native transfer before reporting it seen', async () => {
  const unknown = await new TransferStatusService(ethereumRpc('unknown'), () => NOW).status(evmRequest);
  assert.equal(unknown.status, 'unknown'); assert.equal(unknown.senderVerified, null);
  const pending = await new TransferStatusService(ethereumRpc('pending'), () => NOW).status(evmRequest);
  assert.equal(pending.status, 'pending'); assert.equal(pending.senderVerified, true); assert.equal(pending.confirmations, '0');
  const confirmed = await new TransferStatusService(ethereumRpc('confirmed'), () => NOW).status(evmRequest);
  assert.equal(confirmed.status, 'confirmed'); assert.equal(confirmed.senderVerified, true); assert.equal(confirmed.confirmations, '8');
  const finalized = await new TransferStatusService(ethereumRpc('finalized'), () => NOW).status(evmRequest);
  assert.equal(finalized.status, 'finalized'); assert.equal(finalized.isFinalized, true);
});

test('Ethereum execution failures remain pollable until their block is finalized', async () => {
  const nonfinal = await new TransferStatusService(ethereumRpc('failedConfirmed'), () => NOW).status(evmRequest);
  assert.equal(nonfinal.status, 'failed'); assert.equal(nonfinal.senderVerified, true); assert.equal(nonfinal.isFinalized, false);
  const finalized = await new TransferStatusService(ethereumRpc('failedFinalized'), () => NOW).status(evmRequest);
  assert.equal(finalized.status, 'failed'); assert.equal(finalized.failureCode, 'execution_reverted'); assert.equal(finalized.isFinalized, true);
});

test('Ethereum status rejects wrong sender, recipient, value and calldata', async () => {
  for (const mode of ['mismatchSender', 'wrongRecipient', 'wrongValue', 'wrongCalldata'] as const) {
    await assert.rejects(new TransferStatusService(ethereumRpc(mode), () => NOW).status(evmRequest),
      (error: unknown) => error instanceof TransferPreparationError && error.code === 'transaction_mismatch');
  }
});

test('Ethereum status verifies canonical USDC contract, zero value and exact transfer calldata', async () => {
  const valid = await new TransferStatusService(ethereumRpc('pending', usdcRequest), () => NOW).status(usdcRequest);
  assert.equal(valid.status, 'pending'); assert.equal(valid.senderVerified, true);
  for (const mode of ['wrongRecipient', 'wrongValue', 'wrongCalldata'] as const) {
    await assert.rejects(new TransferStatusService(ethereumRpc(mode, usdcRequest), () => NOW).status(usdcRequest),
      (error: unknown) => error instanceof TransferPreparationError && error.code === 'transaction_mismatch');
  }
});

test('Ethereum receipt without the transaction record cannot be confirmed', async () => {
  const result = await new TransferStatusService(ethereumRpc('missingTransactionWithReceipt'), () => NOW).status(evmRequest);
  assert.equal(result.status, 'unknown'); assert.equal(result.senderVerified, null); assert.equal(result.isFinalized, false);
});

const solRequest = {
  schemaVersion: 1 as const, operationId: OPERATION, walletId: WALLET_ID, chain: 'SOLANA' as const, sender: SOL_SENDER,
  recipient: SOL_RECIPIENT, assetId: 'SOLANA:native' as const, baseUnits: '1000000000', transactionId: SIGNATURE,
};
type SolMode = 'unknown' | 'processed' | 'confirmed' | 'finalized' | 'failedConfirmed' | 'failedFinalized' |
  'mismatchSender' | 'wrongRecipient' | 'wrongLamports' | 'nullTransaction';

function solanaRpc(mode: SolMode): TransferRpc {
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
      const source = mode === 'mismatchSender' ? SOL_OTHER : SOL_SENDER;
      const destination = mode === 'wrongRecipient' ? SOL_OTHER : SOL_RECIPIENT;
      const lamports = mode === 'wrongLamports' ? '1000000001' : solRequest.baseUnits;
      return { slot: '990', blockTime: String(Math.floor((NOW - 30_000) / 1000)), meta: { err: failed ? { InstructionError: [0, 'Custom'] } : null },
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

test('Solana status verifies exact System Program transfer semantics for every visible state', async () => {
  const expected = { processed: 'pending', confirmed: 'confirmed', finalized: 'finalized' } as const;
  for (const [mode, state] of Object.entries(expected) as [keyof typeof expected, string][]) {
    const result = await new TransferStatusService(solanaRpc(mode), () => NOW).status(solRequest);
    assert.equal(result.status, state); assert.equal(result.senderVerified, true); assert.equal(result.isFinalized, mode === 'finalized');
  }
});

test('Solana failures are verified and remain nonterminal until finalized', async () => {
  const nonfinal = await new TransferStatusService(solanaRpc('failedConfirmed'), () => NOW).status(solRequest);
  assert.equal(nonfinal.status, 'failed'); assert.equal(nonfinal.senderVerified, true); assert.equal(nonfinal.isFinalized, false);
  const finalized = await new TransferStatusService(solanaRpc('failedFinalized'), () => NOW).status(solRequest);
  assert.equal(finalized.status, 'failed'); assert.equal(finalized.senderVerified, true); assert.equal(finalized.isFinalized, true);
});

test('Solana status rejects wrong fee payer, recipient and lamports', async () => {
  for (const mode of ['mismatchSender', 'wrongRecipient', 'wrongLamports'] as const) {
    await assert.rejects(new TransferStatusService(solanaRpc(mode), () => NOW).status(solRequest),
      (error: unknown) => error instanceof TransferPreparationError && error.code === 'transaction_mismatch');
  }
});

test('Solana missing parsed transaction remains unknown even when signature status is confirmed', async () => {
  const result = await new TransferStatusService(solanaRpc('nullTransaction'), () => NOW).status(solRequest);
  assert.equal(result.status, 'unknown'); assert.equal(result.senderVerified, null); assert.equal(result.slot, null);
});

test('status request binds strict reviewed transfer identity and chain-specific transaction IDs', () => {
  assert.equal(decodeSignature(SIGNATURE).length, 64);
  assert.equal(TransferStatusRequestSchema.safeParse(evmRequest).success, true);
  assert.equal(TransferStatusRequestSchema.safeParse(solRequest).success, true);
  for (const value of [
    { ...evmRequest, transactionId: SIGNATURE }, { ...solRequest, transactionId: HASH },
    { ...evmRequest, assetId: 'SOLANA:native' }, { ...evmRequest, baseUnits: '01' },
    { ...evmRequest, recipient: undefined }, { ...evmRequest, rpcUrl: 'https://evil.example' },
  ]) assert.equal(TransferStatusRequestSchema.safeParse(value).success, false);
});

test('HTTP status endpoint is no-store, bounded, typed and independently rate-limited', async () => {
  const service = new TransferStatusService(ethereumRpc('confirmed'), () => NOW);
  const auth = { verifyAuthorization: async () => ({ subject: 'did:privy:test-user', sessionId: 'session-one' }) };
  const ledger = { registerPrepared() {}, commit() { throw new Error(); }, release() { throw new Error(); },
    recordTransaction() {}, recordObservation() {} };
  const app = await buildApp(undefined, false, { transferStatus: service, transferStatusRateLimiter: new TransferRateLimiter(() => NOW, 1),
    accessTokenVerifier: auth, transferIntentLedger: ledger });
  const first = await app.inject({ method: 'POST', url: '/v1/wallet/transfer/status', headers: { authorization: 'Bearer test' }, payload: evmRequest });
  assert.equal(first.statusCode, 200); assert.equal(first.headers['cache-control'], 'no-store'); assert.equal(first.json().status, 'confirmed');
  const second = await app.inject({ method: 'POST', url: '/v1/wallet/transfer/status', headers: { authorization: 'Bearer test' }, payload: { ...evmRequest, operationId: '37a9b840-3e9d-4ed5-a22f-c886327d0920' } });
  assert.equal(second.statusCode, 429); assert.equal(second.json().error, 'rate_limited');
  assert.equal((await app.inject({ method: 'GET', url: '/v1/wallet/transfer/status' })).statusCode, 404);
  assert.equal((await app.inject({ method: 'POST', url: '/v1/wallet/transfer/status', payload: { ...evmRequest, padding: 'x'.repeat(2_000) } })).statusCode, 413);
  await app.close();
});

test('status response schema rejects unverified confirmation and contradictory finality metadata', () => {
  const base = { schemaVersion: 1, operationId: OPERATION, chain: 'ETHEREUM', caip2: 'eip155:1', transactionId: HASH,
    sender: ETH_SENDER, senderVerified: true, status: 'confirmed', confirmations: '8', isFinalized: false, observedAt: new Date(NOW).toISOString(),
    blockNumber: '105', blockHash: BLOCK_HASH, blockTime: new Date(NOW - 60_000).toISOString(), finalizedBlockNumber: '100', failureCode: null };
  assert.equal(TransferStatusSchema.safeParse(base).success, true);
  for (const patch of [{ senderVerified: null }, { status: 'finalized', isFinalized: false }, { status: 'failed', failureCode: null }, { blockNumber: null }]) {
    assert.equal(TransferStatusSchema.safeParse({ ...base, ...patch }).success, false);
  }
});
