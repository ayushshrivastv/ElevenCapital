import assert from 'node:assert/strict';
import test from 'node:test';
import { TOKEN_2022_PROGRAM_ID, TOKEN_PROGRAM_ID } from '@solana/spl-token';
import { buildApp } from '../src/app.js';
import { CommunityMintValidationError, CommunityMintValidationService } from '../src/community-mint-validation.js';
import { WalletAuthenticationError, SubjectRateLimiter } from '../src/privy-auth.js';
import { MAINNET_GENESIS } from '../src/portfolio-upstream.js';
import type { TransferRpc } from '../src/transfer-rpc.js';

const MINT = 'So11111111111111111111111111111111111111112';
const SYSTEM_PROGRAM = '11111111111111111111111111111111';
function mintData(): Buffer {
  const data = Buffer.alloc(82);
  data.writeBigUInt64LE(1_000_000n, 36);
  data[44] = 9;
  data[45] = 1;
  return data;
}
function account(owner: string, data: Buffer, executable = false): unknown {
  return { context: { slot: 123 }, value: { owner, executable, data: [data.toString('base64'), 'base64'], lamports: 1000 } };
}
function rpc(value: unknown, genesis: unknown = MAINNET_GENESIS): { source: TransferRpc; methods: string[] } {
  const methods: string[] = [];
  return { methods, source: { async call(chain, method, params) {
    assert.equal(chain, 'SOLANA');
    methods.push(method);
    if (method === 'getGenesisHash') { assert.deepEqual(params, []); return genesis; }
    if (method === 'getAccountInfo') {
      assert.deepEqual(params, [MINT, { encoding: 'base64', commitment: 'confirmed' }]);
      return value;
    }
    throw new Error(`Unexpected RPC method: ${method}`);
  } } };
}
function code(value: unknown, expected: string): boolean {
  return value instanceof CommunityMintValidationError && value.code === expected;
}

test('mainnet classic and extended Token-2022 mint layouts validate without any write RPC', async () => {
  const classic = rpc(account(TOKEN_PROGRAM_ID.toBase58(), mintData()));
  assert.deepEqual(await new CommunityMintValidationService(classic.source).validate(MINT),
    { schemaVersion: 1, network: 'solana-mainnet', mintAddress: MINT, valid: true });
  assert.deepEqual(classic.methods, ['getGenesisHash', 'getAccountInfo']);

  const extended = Buffer.alloc(202);
  mintData().copy(extended);
  extended[165] = 1; // Token-2022 AccountType.Mint
  extended.writeUInt16LE(3, 166); // MintCloseAuthority extension
  extended.writeUInt16LE(32, 168);
  const token2022 = rpc(account(TOKEN_2022_PROGRAM_ID.toBase58(), extended));
  assert.equal((await new CommunityMintValidationService(token2022.source).validate(MINT)).valid, true);
  assert.deepEqual(token2022.methods, ['getGenesisHash', 'getAccountInfo']);
});

test('missing, wrong-owner, executable, token-account and uninitialized addresses fail closed', async () => {
  const absent = rpc({ context: { slot: 123 }, value: null });
  await assert.rejects(new CommunityMintValidationService(absent.source).validate(MINT), error => code(error, 'mint_not_found'));
  const invalid = [
    account(SYSTEM_PROGRAM, mintData()),
    account(TOKEN_PROGRAM_ID.toBase58(), mintData(), true),
    account(TOKEN_PROGRAM_ID.toBase58(), Buffer.alloc(165)),
    account(TOKEN_PROGRAM_ID.toBase58(), Buffer.alloc(82)),
  ];
  for (const value of invalid) await assert.rejects(new CommunityMintValidationService(rpc(value).source).validate(MINT),
    error => code(error, 'not_token_mint'));
});

test('Token-2022 extended accounts require the mint discriminator and bounded TLV records', async () => {
  const wrongType = Buffer.alloc(202); mintData().copy(wrongType); wrongType[165] = 2;
  const overrun = Buffer.alloc(202); mintData().copy(overrun); overrun[165] = 1;
  overrun.writeUInt16LE(3, 166); overrun.writeUInt16LE(64, 168);
  for (const data of [wrongType, overrun]) await assert.rejects(
    new CommunityMintValidationService(rpc(account(TOKEN_2022_PROGRAM_ID.toBase58(), data)).source).validate(MINT),
    error => code(error, 'not_token_mint'));
});

test('malformed mint, wrong genesis, broken RPC and malformed response never validate', async () => {
  const read = rpc(account(TOKEN_PROGRAM_ID.toBase58(), mintData()));
  await assert.rejects(new CommunityMintValidationService(read.source).validate(SYSTEM_PROGRAM + '!'),
    error => code(error, 'invalid_mint'));
  assert.deepEqual(read.methods, []);
  const devnet = rpc(account(TOKEN_PROGRAM_ID.toBase58(), mintData()), 'devnet');
  await assert.rejects(new CommunityMintValidationService(devnet.source).validate(MINT),
    error => code(error, 'mainnet_rpc_unavailable'));
  assert.deepEqual(devnet.methods, ['getGenesisHash']);
  const down: TransferRpc = { async call() { throw new Error('offline'); } };
  await assert.rejects(new CommunityMintValidationService(down).validate(MINT),
    error => code(error, 'mainnet_rpc_unavailable'));
  await assert.rejects(new CommunityMintValidationService(rpc({ context: {}, value: { owner: TOKEN_PROGRAM_ID.toBase58() } }).source)
    .validate(MINT), error => code(error, 'mainnet_rpc_unavailable'));
});

test('HTTP mint validation authenticates, limits reads, is no-store and rejects extra query inputs', async t => {
  const read = rpc(account(TOKEN_PROGRAM_ID.toBase58(), mintData()));
  const app = await buildApp(undefined, false, {
    communityMintValidation: new CommunityMintValidationService(read.source),
    communityMintValidationRateLimiter: new SubjectRateLimiter(() => 1_000, 1),
    accessTokenVerifier: { async verifyAuthorization(value) {
      if (value === 'Bearer alice') return { subject: 'did:privy:alice', sessionId: 'session-1' };
      throw new WalletAuthenticationError('authentication_required', 401, 'A verified Privy session is required.');
    } },
  });
  t.after(() => app.close());
  const url = `/v1/community-tokens/${MINT}/validate`;
  assert.equal((await app.inject({ method: 'GET', url })).statusCode, 401);
  const valid = await app.inject({ method: 'GET', url, headers: { authorization: 'Bearer alice' } });
  assert.equal(valid.statusCode, 200);
  assert.equal(valid.headers['cache-control'], 'no-store');
  assert.deepEqual(valid.json(), { schemaVersion: 1, network: 'solana-mainnet', mintAddress: MINT, valid: true });
  assert.equal((await app.inject({ method: 'GET', url, headers: { authorization: 'Bearer alice' } })).statusCode, 429);
  assert.equal((await app.inject({ method: 'GET', url: `${url}?rpc=https://evil.example`,
    headers: { authorization: 'Bearer alice' } })).statusCode, 400);
  assert.deepEqual(read.methods, ['getGenesisHash', 'getAccountInfo']);
});
