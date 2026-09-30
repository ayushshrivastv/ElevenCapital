import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { Connection, Keypair, PublicKey, SystemProgram, Transaction, TransactionInstruction } from '@solana/web3.js';
import { NATIVE_MINT, TOKEN_PROGRAM_ID } from '@solana/spl-token';
import BN from 'bn.js';
import { ActivationType, BaseFeeMode, CollectFeeMode, DammV2BaseFeeMode, DammV2DynamicFeeMode,
  DYNAMIC_BONDING_CURVE_PROGRAM_ID, MigrationFeeOption, MigrationOption, MigratedCollectFeeMode,
  TokenAuthorityOption, TokenDecimal, TokenType, DynamicBondingCurveClient, deriveDbcEventAuthority,
  deriveDbcPoolAddress, deriveDbcPoolAuthority, deriveDbcTokenVaultAddress, deriveMintMetadata,
  type PoolConfig, type VirtualPool,
} from '@meteora-ag/dynamic-bonding-curve-sdk';
import bs58 from 'bs58';
import { buildApp } from '../src/app.js';
import { matchesMeteoraLaunchState, MeteoraTokenLaunchChain, reviewedCurve, SOLANA_MAINNET_GENESIS_HASH,
  type SignedLaunchTransaction, type TokenLaunchChain,
  type TokenLaunchChainObservation } from '../src/token-launch-chain.js';
import { TokenLaunchLedger, TokenLaunchLedgerError } from '../src/token-launch-ledger.js';
import { TokenLaunchPrepareRequestSchema, type TokenLaunchPreparation, type TokenLaunchPrepareRequest } from '../src/token-launch-schema.js';
import { TokenLaunchService } from '../src/token-launch.js';
import { TokenMetadataPublicationSchema } from '../src/token-metadata-schema.js';
import { WalletAuthenticationError, type PrivyPrincipal } from '../src/privy-auth.js';

const NOW = Date.parse('2026-10-07T04:00:00.000Z');
const OPERATION = '5a40fc8d-10d4-4da9-b44f-b4e28ef917cc';
const SECOND_OPERATION = '37a9b840-3e9d-4ed5-a22f-c886327d0920';
const PRINCIPAL: PrivyPrincipal = { subject: 'did:privy:token-launch-user', sessionId: 'launch-session' };

function material(operationId = OPERATION) {
  const wallet = Keypair.generate(); const mint = Keypair.generate(); const config = Keypair.generate();
  const quoteMint = NATIVE_MINT.toBase58();
  const pool = deriveDbcPoolAddress(NATIVE_MINT, mint.publicKey, config.publicKey);
  const client = new DynamicBondingCurveClient(new Connection('https://api.mainnet.solana.com'), 'confirmed');
  const coder = client.state.getProgram().coder.instruction;
  const economics = { totalTokenSupply: '1000000000', feeBps: 100, migrationThresholdSol: '1',
    percentageSupplyOnMigration: 20 };
  const program = DYNAMIC_BONDING_CURVE_PROGRAM_ID;
  const event = deriveDbcEventAuthority();
  const k = (pubkey: PublicKey, isSigner = false, isWritable = false) =>
    ({ pubkey, isSigner, isWritable });
  const configInstruction = new TransactionInstruction({ programId: program,
    data: coder.encode('createConfig', { configParameters: reviewedCurve(economics) }),
    keys: [k(config.publicKey, true, true), k(wallet.publicKey), k(wallet.publicKey), k(NATIVE_MINT),
      k(wallet.publicKey, true, true), k(SystemProgram.programId), k(event), k(program)] });
  const poolInstruction = new TransactionInstruction({ programId: program,
    data: coder.encode('initializeVirtualPoolWithSplToken', {
      params: { name: 'Launch Test', symbol: 'TEST', uri: 'https://metadata.example/token.json' },
    }), keys: [k(config.publicKey), k(deriveDbcPoolAuthority()), k(wallet.publicKey, true),
      k(mint.publicKey, true, true), k(NATIVE_MINT), k(pool, false, true),
      k(deriveDbcTokenVaultAddress(pool, mint.publicKey), false, true),
      k(deriveDbcTokenVaultAddress(pool, NATIVE_MINT), false, true),
      k(deriveMintMetadata(mint.publicKey), false, true),
      k(new PublicKey('metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s')),
      k(wallet.publicKey, true, true), k(TOKEN_PROGRAM_ID),
      k(TOKEN_PROGRAM_ID),
      k(SystemProgram.programId), k(event), k(program)] });
  const transaction = new Transaction({ feePayer: wallet.publicKey,
    blockhash: Keypair.generate().publicKey.toBase58(), lastValidBlockHeight: 1_000 });
  transaction.add(configInstruction, poolInstruction); transaction.partialSign(mint, config);
  const partialBytes = transaction.serialize({ requireAllSignatures: false, verifySignatures: true });
  const walletCopy = Transaction.from(partialBytes); walletCopy.partialSign(wallet);
  const walletSignature = walletCopy.signatures.find(value => value.publicKey.equals(wallet.publicKey))?.signature;
  assert.ok(walletSignature);
  const privyBytes = Buffer.concat([Buffer.from([1]), walletSignature, walletCopy.serializeMessage()]);
  const preparation: TokenLaunchPreparation = {
    schemaVersion: 1, operationId, network: 'SOLANA_MAINNET', walletId: 'privy-wallet-one',
    walletAddress: wallet.publicKey.toBase58(), name: 'Launch Test', symbol: 'TEST',
    metadataUri: 'https://metadata.example/token.json', programId: DYNAMIC_BONDING_CURVE_PROGRAM_ID.toBase58(),
    quoteMint, mintAddress: mint.publicKey.toBase58(),
    configAddress: config.publicKey.toBase58(), poolAddress: pool.toBase58(), totalTokenSupply: '1000000000',
    tokenDecimals: 6, feeBps: 100, migrationThresholdSol: '1', percentageSupplyOnMigration: 20,
    migrationTarget: 'METEORA_DAMM_V2', tokenAuthority: 'IMMUTABLE', dynamicFeeEnabled: false,
    creatorPermanentLockedLiquidityPercentage: 100, estimatedFeeLamports: '10000', maximumWalletDebitLamports: '30000000',
    balanceLamports: '100000000', simulationUnitsConsumed: 200000, observedSlot: '100',
    recentBlockhash: transaction.recentBlockhash!, lastValidBlockHeight: '1000',
    preparedAt: new Date(NOW).toISOString(), expiresAt: new Date(NOW + 75_000).toISOString(),
    transactionSha256: createHash('sha256').update(partialBytes).digest('hex'),
    transactionBase64: partialBytes.toString('base64'), encoding: 'base64-legacy-partially-signed',
  };
  return { wallet, mint, config, preparation, walletSignedTransactionBase64: privyBytes.toString('base64') };
}

function request(value = material()): TokenLaunchPrepareRequest {
  return { schemaVersion: 1, operationId: value.preparation.operationId, network: 'SOLANA_MAINNET',
    walletId: value.preparation.walletId, walletAddress: value.preparation.walletAddress,
    name: value.preparation.name, symbol: value.preparation.symbol, metadataUri: value.preparation.metadataUri,
    totalTokenSupply: value.preparation.totalTokenSupply, feeBps: value.preparation.feeBps,
    migrationThresholdSol: value.preparation.migrationThresholdSol,
    percentageSupplyOnMigration: value.preparation.percentageSupplyOnMigration };
}
function identity(preparation: TokenLaunchPreparation) {
  return { schemaVersion: 1 as const, operationId: preparation.operationId, walletId: preparation.walletId,
    walletAddress: preparation.walletAddress, transactionSha256: preparation.transactionSha256 };
}

class FakeChain implements TokenLaunchChain {
  preflightCalls = 0; broadcastCalls = 0; observed: TokenLaunchChainObservation = {
    state: 'confirmed', isFinalized: true, message: 'Token launch finalized on Solana mainnet.',
  };
  constructor(readonly launch = material(), private readonly assembler = new MeteoraTokenLaunchChain(undefined, () => NOW)) {}
  async prepare(input: TokenLaunchPrepareRequest) { return { ...this.launch.preparation, operationId: input.operationId,
    walletId: input.walletId, walletAddress: input.walletAddress, name: input.name, symbol: input.symbol,
    metadataUri: input.metadataUri, totalTokenSupply: input.totalTokenSupply, feeBps: input.feeBps,
    migrationThresholdSol: input.migrationThresholdSol, percentageSupplyOnMigration: input.percentageSupplyOnMigration }; }
  assembleSigned(preparation: TokenLaunchPreparation, value: string): SignedLaunchTransaction {
    return this.assembler.assembleSigned(preparation, value);
  }
  async preflight() { this.preflightCalls++; }
  async broadcast() { this.broadcastCalls++; return true; }
  async observe() { return this.observed; }
}

test('launch schema is mainnet-only and bounds supply, fees, metadata and untrusted RPC fields', () => {
  const launch = material(); const valid = request(launch);
  assert.equal(TokenLaunchPrepareRequestSchema.safeParse(valid).success, true);
  for (const invalid of [
    { ...valid, network: 'SOLANA_DEVNET' }, { ...valid, totalTokenSupply: '1000000000001' },
    { ...valid, feeBps: 24 }, { ...valid, metadataUri: 'content://local/image' },
    { ...valid, metadataUri: 'https://localhost/token.json' }, { ...valid, metadataUri: 'https://8.8.8.8/token.json' },
    { ...valid, metadataUri: 'https://[2606:4700:4700::1111]/token.json' },
    { ...valid, metadataUri: 'https://metadata.example:443/token.json' },
    { ...valid, metadataUri: 'https://meta\tdata.example/token.json' }, { ...valid, rpcUrl: 'https://evil.example' },
  ]) assert.equal(TokenLaunchPrepareRequestSchema.safeParse(invalid).success, false);
});

test('metadata publication and launch preparation share exact public DNS HTTPS rules', () => {
  const valid = request(); const hash = 'a'.repeat(64);
  const publication = (metadataUri: string) => ({ metadataUri, imageUri: 'https://metadata.example/image.png', contentHash: hash });
  assert.equal(TokenMetadataPublicationSchema.safeParse(publication(valid.metadataUri)).success, true);
  assert.equal(TokenLaunchPrepareRequestSchema.safeParse(valid).success, true);
  for (const metadataUri of [
    'https://8.8.8.8/token.json', 'https://[2606:4700:4700::1111]/token.json',
    'https://metadata.example:443/token.json', 'https://meta\tdata.example/token.json',
  ]) {
    assert.equal(TokenMetadataPublicationSchema.safeParse(publication(metadataUri)).success, false);
    assert.equal(TokenLaunchPrepareRequestSchema.safeParse({ ...valid, metadataUri }).success, false);
  }
});

test('Privy one-signature wire is verified and reconstructed with persisted mint/config signatures', () => {
  const launch = material(); const chain = new MeteoraTokenLaunchChain(undefined, () => NOW);
  const signed = chain.assembleSigned(launch.preparation, launch.walletSignedTransactionBase64);
  const transaction = Transaction.from(signed.bytes);
  assert.equal(transaction.verifySignatures(true), true);
  const signers = transaction.signatures.map(value => value.publicKey.toBase58());
  assert.equal(signers[0], launch.wallet.publicKey.toBase58());
  assert.deepEqual(new Set(signers.slice(1)), new Set([launch.mint.publicKey.toBase58(), launch.config.publicKey.toBase58()]));
  assert.equal(signed.transactionId, transaction.signature && bs58.encode(transaction.signature));
});

test('wallet signing cannot change the message, backend signature slots, or use another wallet', () => {
  const launch = material(); const chain = new MeteoraTokenLaunchChain(undefined, () => NOW);
  const changed = Buffer.from(launch.walletSignedTransactionBase64, 'base64'); changed[changed.length - 1] ^= 1;
  assert.throws(() => chain.assembleSigned(launch.preparation, changed.toString('base64')), /does not match/);
  const normal = Transaction.from(Buffer.from(launch.preparation.transactionBase64, 'base64'));
  normal.partialSign(launch.wallet);
  const full = normal.serialize({ requireAllSignatures: true, verifySignatures: true });
  assert.equal(chain.assembleSigned(launch.preparation, full.toString('base64')).transactionId, bs58.encode(normal.signature!));
  const changedSlot = Buffer.from(full); changedSlot[70] ^= 1;
  assert.throws(() => chain.assembleSigned(launch.preparation, changedSlot.toString('base64')), /signer slots/);
  const other = material();
  assert.throws(() => chain.assembleSigned(launch.preparation, other.walletSignedTransactionBase64), /does not match|invalid/);
});

test('launch rejects tampered config economics or pool metadata before accepting a wallet signature', () => {
  const launch = material(); const chain = new MeteoraTokenLaunchChain(undefined, () => NOW);
  for (const instructionIndex of [0, 1]) {
    const changed = Transaction.from(Buffer.from(launch.preparation.transactionBase64, 'base64'));
    changed.instructions[instructionIndex]!.data = Buffer.from(changed.instructions[instructionIndex]!.data);
    changed.instructions[instructionIndex]!.data[changed.instructions[instructionIndex]!.data.length - 1] ^= 1;
    changed.partialSign(launch.mint, launch.config);
    const bytes = changed.serialize({ requireAllSignatures: false, verifySignatures: true });
    assert.throws(() => chain.assembleSigned({ ...launch.preparation, transactionBase64: bytes.toString('base64'),
      transactionSha256: createHash('sha256').update(bytes).digest('hex') }, launch.walletSignedTransactionBase64),
    /reviewed economics/);
  }
});

test('mainnet launch rejects wrong genesis and signed cost above reviewed debit', async () => {
  const launch = material(); const chain = new MeteoraTokenLaunchChain('https://rpc.example.test', () => NOW);
  Object.defineProperty(chain, 'connection', { value: { async getGenesisHash() { return 'wrong-cluster'; } } });
  await assert.rejects(chain.prepare(request(launch)), /not mainnet/);
  const reviewed = new MeteoraTokenLaunchChain('https://rpc.example.test', () => NOW);
  const signed = reviewed.assembleSigned(launch.preparation, launch.walletSignedTransactionBase64);
  let debit = 30_000_001;
  Object.defineProperty(reviewed, 'connection', { value: {
    async getGenesisHash() { return SOLANA_MAINNET_GENESIS_HASH; },
    async getBlockHeight() { return 900; }, async getBalance() { return 100_000_000; },
    async simulateTransaction() { return { value: { err: null, accounts: [{ lamports: 100_000_000 - debit }] } }; },
  } });
  await assert.rejects(reviewed.preflight(launch.preparation, signed), /maximum wallet debit/);
  debit = 30_000_000;
  await assert.doesNotReject(reviewed.preflight(launch.preparation, signed));
});

test('launch status awaits finality and does not expire a bound signature on unindexed history', async () => {
  const launch = material(); let clock = NOW; let confirmationStatus = 'confirmed'; let historyCovered = false;
  let recovered = false;
  const chain = new MeteoraTokenLaunchChain('https://rpc.example.test', () => clock);
  const signed = chain.assembleSigned(launch.preparation, launch.walletSignedTransactionBase64);
  const ledger = new TokenLaunchLedger(null, () => clock); const id = identity(launch.preparation);
  ledger.registerPrepared(PRINCIPAL, request(launch), launch.preparation);
  ledger.commit(PRINCIPAL, id); ledger.bindBroadcast(PRINCIPAL, id, signed.transactionId, signed.base64);
  Object.defineProperty(chain, 'verifyCreatedState', { value: async () => 'valid' });
  Object.defineProperty(chain, 'connection', { value: {
    async getGenesisHash() { return SOLANA_MAINNET_GENESIS_HASH; },
    async getBlockHeight() { return 1_200; },
    async getSignatureStatuses() { return { value: [confirmationStatus === 'missing'
      ? null : { confirmationStatus, err: null }] }; },
    async getParsedTransaction() { return recovered ? { meta: { err: null } } : null; },
    async getSignaturesForAddress() { return historyCovered
      ? [{ signature: 'older-finalized-wallet-transaction', slot: 99, err: null }] : []; },
  } });
  assert.equal((await chain.observe(ledger.current(PRINCIPAL, id))).state, 'broadcast');
  confirmationStatus = 'finalized';
  const finalized = await chain.observe(ledger.current(PRINCIPAL, id));
  assert.equal(finalized.state, 'confirmed'); assert.equal(finalized.isFinalized, true);
  confirmationStatus = 'missing'; clock = NOW + 75_000 + 5 * 60_000 + 1;
  assert.equal((await chain.observe(ledger.current(PRINCIPAL, id))).state, 'unknown');
  historyCovered = true;
  assert.equal((await chain.observe(ledger.current(PRINCIPAL, id))).state, 'expired');
  recovered = true;
  assert.equal((await chain.observe(ledger.current(PRINCIPAL, id))).state, 'confirmed');
});

test('durable launch intent commits, reconstructs, binds before broadcast, and confirms idempotently', async () => {
  const fake = new FakeChain(); const ledger = new TokenLaunchLedger(null, () => NOW);
  const service = new TokenLaunchService(fake, ledger, () => NOW);
  const prepared = await service.prepare(PRINCIPAL, request(fake.launch)); const bound = identity(prepared);
  assert.equal(service.commit(PRINCIPAL, bound).state, 'committed');
  const first = await service.submit(PRINCIPAL, { ...bound, walletSignedTransactionBase64: fake.launch.walletSignedTransactionBase64 });
  assert.equal(first.state, 'broadcast'); assert.equal(first.idempotent, false); assert.equal(fake.preflightCalls, 1);
  const second = await service.submit(PRINCIPAL, { ...bound, walletSignedTransactionBase64: fake.launch.walletSignedTransactionBase64 });
  assert.equal(second.idempotent, true); assert.equal(fake.preflightCalls, 1); assert.equal(fake.broadcastCalls, 1);
  const status = await service.status(PRINCIPAL, bound);
  assert.equal(status.state, 'confirmed'); assert.equal(status.isFinalized, true);
  assert.match(status.explorerUrl, new RegExp(prepared.mintAddress));
  assert.throws(() => service.release(PRINCIPAL, { ...bound, reason: 'provider_definitely_not_invoked' }), TokenLaunchLedgerError);
  assert.equal(JSON.stringify(ledger.snapshot()).includes('secretKey'), false);
});

test('one unresolved launch per user and wallet is released only before a signature is bound', async () => {
  const fake = new FakeChain(); const ledger = new TokenLaunchLedger(null, () => NOW);
  const service = new TokenLaunchService(fake, ledger, () => NOW);
  const prepared = await service.prepare(PRINCIPAL, request(fake.launch));
  const secondRequest = { ...request(fake.launch), operationId: SECOND_OPERATION };
  await assert.rejects(service.prepare(PRINCIPAL, secondRequest),
    error => error instanceof TokenLaunchLedgerError && error.code === 'unresolved_launch');
  const released = service.release(PRINCIPAL, { ...identity(prepared), reason: 'provider_definitely_not_invoked' });
  assert.equal(released.state, 'released');
  assert.equal((await service.prepare(PRINCIPAL, secondRequest)).operationId, SECOND_OPERATION);
});

test('an orphaned prepared launch is durably pruned after expiry and no longer blocks the wallet', () => {
  let clock = NOW; const filename = join(mkdtempSync(join(tmpdir(), 'eleven-launch-')), 'ledger.json');
  const first = material(); const ledger = new TokenLaunchLedger(filename, () => clock);
  ledger.registerPrepared(PRINCIPAL, request(first), first.preparation);
  clock = NOW + 75_001;
  const nextMaterial = material(SECOND_OPERATION); const nextRequest = { ...request(nextMaterial),
    walletId: first.preparation.walletId, walletAddress: first.preparation.walletAddress };
  const restarted = new TokenLaunchLedger(filename, () => clock);
  assert.equal(restarted.findPreparation(PRINCIPAL, nextRequest), null);
  assert.equal(new TokenLaunchLedger(filename, () => clock).snapshot().length, 0);
});

test('confirmed DBC state must preserve every reviewed fee, supply, authority, migration and pool invariant', () => {
  const launch = material(); const p = launch.preparation; const wallet = launch.wallet.publicKey;
  const config = {
    quoteMint: { equals: (value: unknown) => String(value) === 'So11111111111111111111111111111111111111112' },
    feeClaimer: wallet, leftoverReceiver: wallet, collectFeeMode: CollectFeeMode.QuoteToken,
    migrationOption: MigrationOption.MET_DAMM_V2, activationType: ActivationType.Timestamp,
    tokenDecimal: TokenDecimal.SIX, tokenType: TokenType.SPLToken, quoteTokenFlag: TokenType.SPLToken,
    partnerPermanentLockedLiquidityPercentage: 0, partnerLiquidityPercentage: 0,
    creatorPermanentLockedLiquidityPercentage: 100, creatorLiquidityPercentage: 0,
    migrationFeeOption: MigrationFeeOption.Customizable, fixedTokenSupplyFlag: 1, creatorTradingFeePercentage: 100,
    tokenUpdateAuthority: TokenAuthorityOption.Immutable, migrationFeePercentage: 0, creatorMigrationFeePercentage: 0,
    migrationQuoteThreshold: new BN('1000000000'), preMigrationTokenSupply: new BN('1000000000000000'),
    postMigrationTokenSupply: new BN('1000000000000000'), migratedCollectFeeMode: MigratedCollectFeeMode.QuoteToken,
    migratedDynamicFee: DammV2DynamicFeeMode.Disabled, migratedPoolFeeBps: 100,
    migratedPoolBaseFeeMode: DammV2BaseFeeMode.FeeTimeSchedulerLinear, enableFirstSwapWithMinFee: 0,
    migratedCompoundingFeeBps: 0, poolCreationFee: new BN(0),
    poolFees: { baseFee: { baseFeeMode: BaseFeeMode.FeeSchedulerLinear, cliffFeeNumerator: new BN('10000000'),
      firstFactor: 0, secondFactor: new BN(0), thirdFactor: new BN(0) }, dynamicFee: { initialized: 0 } },
  } as unknown as PoolConfig;
  const pool = { poolState: { config: launch.config.publicKey, creator: wallet, baseMint: launch.mint.publicKey,
    poolType: TokenType.SPLToken, isMigrated: 0 } } as unknown as VirtualPool;
  assert.equal(matchesMeteoraLaunchState(p, config, pool), true);
  assert.equal(matchesMeteoraLaunchState(p, config, { poolState: { ...pool.poolState, isMigrated: 1 } } as VirtualPool), true);
  for (const changed of [
    { ...config, creatorPermanentLockedLiquidityPercentage: 99 }, { ...config, migratedDynamicFee: DammV2DynamicFeeMode.Enabled },
    { ...config, migratedPoolFeeBps: 101 }, { ...config, feeClaimer: Keypair.generate().publicKey },
    { ...config, migrationQuoteThreshold: new BN('999999999') },
  ]) assert.equal(matchesMeteoraLaunchState(p, changed as PoolConfig, pool), false);
  const wrongPool = { poolState: { ...pool.poolState, baseMint: Keypair.generate().publicKey } } as VirtualPool;
  assert.equal(matchesMeteoraLaunchState(p, config, wrongPool), false);
});

test('HTTP token launch routes authenticate first and reject extra fields', async () => {
  const fake = new FakeChain(); const launchService = new TokenLaunchService(fake, new TokenLaunchLedger(null, () => NOW), () => NOW);
  const verifier = { async verifyAuthorization(value: string | undefined) {
    if (value !== 'Bearer valid') throw new WalletAuthenticationError('authentication_required', 401, 'Missing');
    return PRINCIPAL;
  } };
  const app = await buildApp(undefined, false, { tokenLaunch: launchService, accessTokenVerifier: verifier });
  const payload = request(fake.launch);
  assert.equal((await app.inject({ method: 'POST', url: '/v1/token-launches/prepare', payload })).statusCode, 401);
  assert.equal((await app.inject({ method: 'POST', url: '/v1/token-launches/prepare', headers: { authorization: 'Bearer valid' },
    payload: { ...payload, rpcUrl: 'https://evil.example' } })).statusCode, 400);
  const response = await app.inject({ method: 'POST', url: '/v1/token-launches/prepare',
    headers: { authorization: 'Bearer valid' }, payload });
  assert.equal(response.statusCode, 200); assert.equal(response.json().network, 'SOLANA_MAINNET');
  await app.close();
});
