import assert from 'node:assert/strict';
import { generateKeyPairSync, sign } from 'node:crypto';
import { chmodSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { buildApp, WALLET_LOG_REDACTION_PATHS } from '../src/app.js';
import { PrivyAccessTokenVerifier, SubjectRateLimiter, SubjectRateLimitError, WalletAuthenticationError, type PrivyPrincipal } from '../src/privy-auth.js';
import { TransferIntentError, TransferIntentLedger } from '../src/transfer-intent-ledger.js';
import type { TransferPreparation, TransferRequest } from '../src/transfer-schema.js';

const NOW_MS = Date.parse('2026-09-22T12:00:00.000Z');
const NOW = Math.floor(NOW_MS / 1_000);
const APP_ID = 'cmu6x993600x00cldzp9bnejk';
const SUBJECT = 'did:privy:test-user-one';
const PRINCIPAL: PrivyPrincipal = { subject: SUBJECT, sessionId: 'session-one' };
const OPERATION = '5a40fc8d-10d4-4da9-b44f-b4e28ef917cc';
const SECOND_OPERATION = '37a9b840-3e9d-4ed5-a22f-c886327d0920';
const WALLET = 'wallet-ethereum-one';
const SENDER = '0x1111111111111111111111111111111111111111';
const RECIPIENT = '0x2222222222222222222222222222222222222222';

const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
const jwk = publicKey.export({ format: 'jwk' });
const verifier = new PrivyAccessTokenVerifier(APP_ID, { keys: async () => [{ ...jwk, use: 'sig', alg: 'ES256' }] }, () => NOW);

function token(overrides: Record<string, unknown> = {}, header: Record<string, unknown> = {}): string {
  const encodedHeader = Buffer.from(JSON.stringify({ alg: 'ES256', typ: 'JWT', kid: 'key-one', ...header })).toString('base64url');
  const encodedClaims = Buffer.from(JSON.stringify({ iss: 'privy.io', aud: APP_ID, sub: SUBJECT, sid: 'session-one',
    iat: NOW - 10, exp: NOW + 3_500, ...overrides })).toString('base64url');
  const signature = sign('sha256', Buffer.from(`${encodedHeader}.${encodedClaims}`), { key: privateKey, dsaEncoding: 'ieee-p1363' });
  return `${encodedHeader}.${encodedClaims}.${signature.toString('base64url')}`;
}

function request(operationId = OPERATION): TransferRequest {
  return { schemaVersion: 1, operationId, walletId: WALLET, chain: 'ETHEREUM', sender: SENDER,
    recipient: RECIPIENT, assetId: 'ETHEREUM:native', amount: '0.1' };
}
function prepared(operationId = OPERATION): TransferPreparation {
  return {
    schemaVersion: 1, operationId, chain: 'ETHEREUM', caip2: 'eip155:1',
    asset: { id: 'ETHEREUM:native', symbol: 'ETH', kind: 'native', address: null, decimals: 18 },
    sender: SENDER, recipient: RECIPIENT, amount: '0.1', baseUnits: '100000000000000000',
    balanceBaseUnits: '1000000000000000000', estimatedFeeBaseUnits: '1', maxFeeBaseUnits: '2',
    preparedAt: new Date(NOW_MS).toISOString(), expiresAt: new Date(NOW_MS + 120_000).toISOString(), observedBlock: '1',
    transaction: { chainId: '0x1', from: SENDER, to: RECIPIENT, nonce: '0x0', gas: '0x5208',
      value: '0x16345785d8a0000', data: '0x', type: '0x2', maxFeePerGas: '0x1', maxPriorityFeePerGas: '0x1' },
  };
}
function identity(operationId = OPERATION) {
  return { schemaVersion: 1 as const, operationId, walletId: WALLET, chain: 'ETHEREUM' as const, sender: SENDER,
    recipient: RECIPIENT, assetId: 'ETHEREUM:native' as const, baseUnits: '100000000000000000' };
}

test('Privy bearer verifier accepts exact ES256 claims and rejects missing, invalid, expired and other-audience tokens', async () => {
  assert.ok(WALLET_LOG_REDACTION_PATHS.includes('req.headers.authorization'));
  assert.deepEqual(await verifier.verifyAuthorization(`Bearer ${token()}`), PRINCIPAL);
  await assert.rejects(verifier.verifyAuthorization(undefined), (error: unknown) => error instanceof WalletAuthenticationError && error.statusCode === 401);
  await assert.rejects(verifier.verifyAuthorization(`Bearer ${token({ aud: 'another-app' })}`), (error: unknown) => error instanceof WalletAuthenticationError && error.code === 'invalid_access_token');
  await assert.rejects(verifier.verifyAuthorization(`Bearer ${token({ exp: NOW })}`), (error: unknown) => error instanceof WalletAuthenticationError);
  const tampered = token().replace(/.$/, value => value === 'A' ? 'B' : 'A');
  await assert.rejects(verifier.verifyAuthorization(`Bearer ${tampered}`), (error: unknown) => error instanceof WalletAuthenticationError);
});

test('subject quotas are isolated and authentication failures do not consume another subject quota', async () => {
  const limiter = new SubjectRateLimiter(() => NOW_MS, 1);
  limiter.take('did:privy:user-a'); limiter.take('did:privy:user-b');
  assert.throws(() => limiter.take('did:privy:user-a'), SubjectRateLimitError);
  await assert.rejects(verifier.verifyAuthorization('Bearer invalid'), WalletAuthenticationError);
  assert.throws(() => limiter.take('did:privy:user-b'), SubjectRateLimitError);
});

test('durable commit is idempotent and blocks a second device after process restart', () => {
  const filename = join(mkdtempSync(join(tmpdir(), 'eleven-intent-')), 'private.json');
  const first = new TransferIntentLedger(filename, () => NOW_MS);
  first.registerPrepared(PRINCIPAL, request(), prepared());
  assert.deepEqual(first.commit(PRINCIPAL, identity()), { schemaVersion: 1, operationId: OPERATION, state: 'committed', idempotent: false });
  assert.equal(first.commit(PRINCIPAL, identity()).idempotent, true);

  const restarted = new TransferIntentLedger(filename, () => NOW_MS + 1_000);
  restarted.registerPrepared(PRINCIPAL, request(SECOND_OPERATION), prepared(SECOND_OPERATION));
  assert.throws(() => restarted.commit(PRINCIPAL, identity(SECOND_OPERATION)),
    (error: unknown) => error instanceof TransferIntentError && error.code === 'unresolved_transfer');
  assert.equal((restarted.snapshot()[0] as { subject: string }).subject, SUBJECT);
  assert.ok(!JSON.stringify(restarted.snapshot()).includes(token()), 'ledger must never contain a bearer token');
});

test('definite-not-broadcast release is exact, idempotent and permits the next operation', () => {
  const ledger = new TransferIntentLedger(null, () => NOW_MS);
  ledger.registerPrepared(PRINCIPAL, request(), prepared()); ledger.commit(PRINCIPAL, identity());
  const release = { ...identity(), reason: 'provider_definitely_not_broadcast' as const };
  assert.equal(ledger.release(PRINCIPAL, release).idempotent, false);
  assert.equal(ledger.release(PRINCIPAL, release).idempotent, true);
  ledger.registerPrepared(PRINCIPAL, request(SECOND_OPERATION), prepared(SECOND_OPERATION));
  assert.equal(ledger.commit(PRINCIPAL, identity(SECOND_OPERATION)).state, 'committed');
});

test('a locally journaled pre-commit operation can release a prepared or committed crash-window lock', () => {
  const preparedLedger = new TransferIntentLedger(null, () => NOW_MS);
  preparedLedger.registerPrepared(PRINCIPAL, request(), prepared());
  const release = { ...identity(), reason: 'provider_definitely_not_broadcast' as const };
  assert.deepEqual(preparedLedger.release(PRINCIPAL, release), {
    schemaVersion: 1, operationId: OPERATION, state: 'released', idempotent: false,
  });

  const committedLedger = new TransferIntentLedger(null, () => NOW_MS);
  committedLedger.registerPrepared(PRINCIPAL, request(), prepared());
  committedLedger.commit(PRINCIPAL, identity());
  assert.equal(committedLedger.release(PRINCIPAL, release).state, 'released');
});

test('persistence errors fail closed before commit success', () => {
  const directory = mkdtempSync(join(tmpdir(), 'eleven-intent-fail-'));
  const parentFile = join(directory, 'not-a-directory'); writeFileSync(parentFile, 'x'); chmodSync(parentFile, 0o600);
  const ledger = new TransferIntentLedger(join(parentFile, 'ledger.json'), () => NOW_MS);
  assert.throws(() => ledger.registerPrepared(PRINCIPAL, request(), prepared()),
    (error: unknown) => error instanceof TransferIntentError && error.code === 'intent_storage_unavailable');
  assert.throws(() => ledger.registerPrepared(PRINCIPAL, request(), prepared()), TransferIntentError);
});

test('wallet transfer routes require a valid bearer before rate limiting or transfer work', async () => {
  let calls = 0;
  const transfer = { prepare: async () => { calls++; return prepared(); } };
  const ledger = new TransferIntentLedger(null, () => NOW_MS);
  const app = await buildApp(undefined, false, { transfer, accessTokenVerifier: verifier, transferIntentLedger: ledger,
    transferSubjectRateLimiter: new SubjectRateLimiter(() => NOW_MS, 1) });
  const missing = await app.inject({ method: 'POST', url: '/v1/wallet/transfer/prepare', payload: request() });
  assert.equal(missing.statusCode, 401); assert.equal(calls, 0);
  const wrongAudience = await app.inject({ method: 'POST', url: '/v1/wallet/transfer/prepare',
    headers: { authorization: `Bearer ${token({ aud: 'another-app' })}` }, payload: request() });
  assert.equal(wrongAudience.statusCode, 401); assert.equal(calls, 0);
  const valid = await app.inject({ method: 'POST', url: '/v1/wallet/transfer/prepare',
    headers: { authorization: `Bearer ${token()}` }, payload: request() });
  assert.equal(valid.statusCode, 200); assert.equal(calls, 1);
  await app.close();
});
