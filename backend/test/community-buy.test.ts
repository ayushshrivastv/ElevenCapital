import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { NATIVE_MINT, TOKEN_PROGRAM_ID,
  createAssociatedTokenAccountIdempotentInstruction, createCloseAccountInstruction, createSyncNativeInstruction,
  getAssociatedTokenAddressSync } from '@solana/spl-token';
import { Keypair, PublicKey, SystemProgram, Transaction, TransactionInstruction } from '@solana/web3.js';
import { DYNAMIC_BONDING_CURVE_PROGRAM_ID, deriveDbcEventAuthority, deriveDbcPoolAuthority,
  deriveDbcTokenVaultAddress } from '@meteora-ag/dynamic-bonding-curve-sdk';
import { buildApp } from '../src/app.js';
import { MeteoraCommunityBuyChain, verifiedBuyTokenDelta, type CommunityBuyChain, type CommunityBuyObservation,
  type SignedCommunityBuy } from '../src/community-buy-chain.js';
import { SOLANA_MAINNET_GENESIS_HASH } from '../src/token-launch-chain.js';
import { CommunityBuyLedger, CommunityBuyLedgerError } from '../src/community-buy-ledger.js';
import { CommunityBuyPrepareRequestSchema, type CommunityBuyPreparation,
  type CommunityBuyPrepareRequest } from '../src/community-buy-schema.js';
import { CommunityBuyService } from '../src/community-buy.js';
import type { PrivyPrincipal } from '../src/privy-auth.js';

const NOW = Date.parse('2026-10-08T10:00:00.000Z');
const OPERATION = '5a40fc8d-10d4-4da9-b44f-b4e28ef917cc';
const PRINCIPAL: PrivyPrincipal = { subject: 'did:privy:community-buyer', sessionId: 'buy-session' };

function fixture(operationId = OPERATION) {
  const wallet = Keypair.generate(); const mint = Keypair.generate().publicKey;
  const config = Keypair.generate().publicKey; const pool = Keypair.generate().publicKey;
  const wsolAta = getAssociatedTokenAddressSync(NATIVE_MINT, wallet.publicKey);
  const tokenAta = getAssociatedTokenAddressSync(mint, wallet.publicKey);
  const amountInLamports = '10000000'; const minimumAmountOut = '1000000';
  const swapData = Buffer.alloc(25);
  Buffer.from([65, 75, 63, 76, 235, 91, 91, 136]).copy(swapData);
  swapData.writeBigUInt64LE(BigInt(amountInLamports), 8); swapData.writeBigUInt64LE(BigInt(minimumAmountOut), 16);
  const swap = new TransactionInstruction({ programId: DYNAMIC_BONDING_CURVE_PROGRAM_ID, data: swapData,
    keys: [
      { pubkey: deriveDbcPoolAuthority(), isSigner: false, isWritable: false },
      { pubkey: config, isSigner: false, isWritable: false },
      { pubkey: pool, isSigner: false, isWritable: true },
      { pubkey: wsolAta, isSigner: false, isWritable: true },
      { pubkey: tokenAta, isSigner: false, isWritable: true },
      { pubkey: deriveDbcTokenVaultAddress(pool, mint), isSigner: false, isWritable: true },
      { pubkey: deriveDbcTokenVaultAddress(pool, NATIVE_MINT), isSigner: false, isWritable: true },
      { pubkey: mint, isSigner: false, isWritable: false },
      { pubkey: NATIVE_MINT, isSigner: false, isWritable: false },
      { pubkey: wallet.publicKey, isSigner: true, isWritable: false },
      { pubkey: TOKEN_PROGRAM_ID, isSigner: false, isWritable: false },
      { pubkey: TOKEN_PROGRAM_ID, isSigner: false, isWritable: false },
      { pubkey: DYNAMIC_BONDING_CURVE_PROGRAM_ID, isSigner: false, isWritable: false },
      { pubkey: deriveDbcEventAuthority(), isSigner: false, isWritable: false },
      { pubkey: DYNAMIC_BONDING_CURVE_PROGRAM_ID, isSigner: false, isWritable: false },
    ] });
  const transaction = new Transaction({ feePayer: wallet.publicKey,
    blockhash: Keypair.generate().publicKey.toBase58(), lastValidBlockHeight: 1_000 });
  transaction.add(
    createAssociatedTokenAccountIdempotentInstruction(wallet.publicKey, wsolAta, wallet.publicKey, NATIVE_MINT),
    createAssociatedTokenAccountIdempotentInstruction(wallet.publicKey, tokenAta, wallet.publicKey, mint),
    SystemProgram.transfer({ fromPubkey: wallet.publicKey, toPubkey: wsolAta, lamports: BigInt(amountInLamports) }),
    createSyncNativeInstruction(wsolAta), swap,
    createCloseAccountInstruction(wsolAta, wallet.publicKey, wallet.publicKey),
  );
  const unsigned = transaction.serialize({ requireAllSignatures: false, verifySignatures: false });
  const signedTransaction = Transaction.from(unsigned); signedTransaction.partialSign(wallet);
  const preparation: CommunityBuyPreparation = {
    schemaVersion: 1, operationId, network: 'SOLANA_MAINNET', walletId: 'privy-wallet-one',
    walletAddress: wallet.publicKey.toBase58(), mintAddress: mint.toBase58(),
    amountInLamports, slippageBps: 100,
    programId: DYNAMIC_BONDING_CURVE_PROGRAM_ID.toBase58(), quoteMint: NATIVE_MINT.toBase58(),
    poolAddress: pool.toBase58(), configAddress: config.toBase58(), tokenDecimals: 6,
    expectedAmountOut: '1100000', minimumAmountOut, estimatedFeeLamports: '5000',
    maximumWalletDebitLamports: '12000000', balanceLamports: '100000000', simulationUnitsConsumed: 100_000,
    observedSlot: '100', recentBlockhash: transaction.recentBlockhash!, lastValidBlockHeight: '1000',
    preparedAt: new Date(NOW).toISOString(), expiresAt: new Date(NOW + 75_000).toISOString(),
    transactionSha256: createHash('sha256').update(unsigned).digest('hex'),
    transactionBase64: Buffer.from(unsigned).toString('base64'), encoding: 'base64-legacy-unsigned',
  };
  return { wallet, transaction, preparation,
    walletSignedTransactionBase64: signedTransaction.serialize().toString('base64') };
}
function request(value = fixture()): CommunityBuyPrepareRequest {
  const p = value.preparation;
  return { schemaVersion: 1, operationId: p.operationId, network: 'SOLANA_MAINNET', walletId: p.walletId,
    walletAddress: p.walletAddress, mintAddress: p.mintAddress,
    amountInLamports: p.amountInLamports, slippageBps: p.slippageBps };
}
function identity(preparation: CommunityBuyPreparation) {
  return { schemaVersion: 1 as const, operationId: preparation.operationId, walletId: preparation.walletId,
    walletAddress: preparation.walletAddress, transactionSha256: preparation.transactionSha256 };
}

class FakeChain implements CommunityBuyChain {
  prepareCalls = 0; preflightCalls = 0; broadcastCalls = 0;
  observed: CommunityBuyObservation = { state: 'confirmed', isFinalized: true, message: 'Confirmed.',
    tokenAmountReceived: '1100000', walletTokenBalanceRaw: '1100000' };
  constructor(readonly buy = fixture(), private readonly assembler = new MeteoraCommunityBuyChain(undefined, () => NOW)) {}
  async prepare(input: CommunityBuyPrepareRequest) { this.prepareCalls++; return { ...this.buy.preparation,
    operationId: input.operationId, walletId: input.walletId, walletAddress: input.walletAddress,
    mintAddress: input.mintAddress, amountInLamports: input.amountInLamports, slippageBps: input.slippageBps }; }
  assembleSigned(preparation: CommunityBuyPreparation, value: string): SignedCommunityBuy {
    return this.assembler.assembleSigned(preparation, value);
  }
  async preflight() { this.preflightCalls++; }
  async broadcast() { this.broadcastCalls++; return true; }
  async observe() { return this.observed; }
}

test('community buy schema allows bounded mainnet SOL buys only', () => {
  const valid = request(); assert.equal(CommunityBuyPrepareRequestSchema.safeParse(valid).success, true);
  for (const invalid of [
    { ...valid, network: 'SOLANA_DEVNET' }, { ...valid, amountInLamports: '1' },
    { ...valid, amountInLamports: '1000000001' }, { ...valid, slippageBps: 501 },
    { ...valid, rpcUrl: 'https://evil.example' },
  ]) assert.equal(CommunityBuyPrepareRequestSchema.safeParse(invalid).success, false);
});

test('mainnet buy rejects a wrong-cluster RPC before pool lookup', async () => {
  const chain = new MeteoraCommunityBuyChain('https://rpc.example.test', () => NOW);
  Object.defineProperty(chain, 'connection', { value: { async getGenesisHash() { return 'wrong-cluster'; } } });
  await assert.rejects(chain.prepare(request()), /not mainnet/);
});

test('Privy one-signature buy is bound to the exact reviewed instructions, accounts and amount', () => {
  const buy = fixture(); const chain = new MeteoraCommunityBuyChain(undefined, () => NOW);
  const signed = chain.assembleSigned(buy.preparation, buy.walletSignedTransactionBase64);
  assert.equal(Transaction.from(signed.bytes).verifySignatures(true), true);
  const tampered = Buffer.from(buy.walletSignedTransactionBase64, 'base64'); tampered[tampered.length - 1] ^= 1;
  assert.throws(() => chain.assembleSigned(buy.preparation, tampered.toString('base64')), /changed/);
  const extra = Transaction.from(Buffer.from(buy.preparation.transactionBase64, 'base64'));
  extra.add(new TransactionInstruction({ programId: TOKEN_PROGRAM_ID, keys: [], data: Buffer.from([4]) }));
  const extraBytes = extra.serialize({ requireAllSignatures: false, verifySignatures: false });
  assert.throws(() => chain.assembleSigned({ ...buy.preparation,
    transactionBase64: extraBytes.toString('base64'), transactionSha256: createHash('sha256').update(extraBytes).digest('hex') },
  buy.walletSignedTransactionBase64), /unrelated|changed/);
});

test('swap validation rejects altered vault, referral, event authority and account permissions', () => {
  const buy = fixture(); const chain = new MeteoraCommunityBuyChain(undefined, () => NOW);
  const cases: Array<(swap: TransactionInstruction) => void> = [
    swap => { swap.keys[5]!.pubkey = Keypair.generate().publicKey; },
    swap => { swap.keys[12]!.pubkey = Keypair.generate().publicKey; },
    swap => { swap.keys[13]!.pubkey = Keypair.generate().publicKey; },
    swap => { swap.keys[1]!.isWritable = true; },
  ];
  for (const change of cases) {
    const transaction = Transaction.from(Buffer.from(buy.preparation.transactionBase64, 'base64'));
    const swap = transaction.instructions.find(ix => ix.programId.equals(DYNAMIC_BONDING_CURVE_PROGRAM_ID));
    assert.ok(swap); change(swap);
    const unsigned = transaction.serialize({ requireAllSignatures: false, verifySignatures: false });
    const preparation = { ...buy.preparation, transactionBase64: unsigned.toString('base64'),
      transactionSha256: createHash('sha256').update(unsigned).digest('hex') };
    transaction.partialSign(buy.wallet);
    assert.throws(() => chain.assembleSigned(preparation, transaction.serialize().toString('base64')),
      /account order or permissions/);
  }
});

test('token delivery proof rejects prior holdings, wrong owner, wrong mint and missing pre-state', () => {
  const mint = Keypair.generate().publicKey.toBase58(); const wallet = Keypair.generate().publicKey.toBase58();
  const row = (amount: string, owner = wallet, targetMint = mint) => ({
    accountIndex: 2, mint: targetMint, owner, uiTokenAmount: { amount },
  });
  assert.equal(verifiedBuyTokenDelta([row('100')], [row('150')], 2, mint, wallet, false, '40'), 50n);
  assert.equal(verifiedBuyTokenDelta([row('100')], [row('150')], 2, mint, wallet, false, '60'), null);
  assert.equal(verifiedBuyTokenDelta([row('100')], [row('90')], 2, mint, wallet, false, '1'), null);
  assert.equal(verifiedBuyTokenDelta([row('100')], [row('150', 'another-owner')], 2, mint, wallet, false, '1'), null);
  assert.equal(verifiedBuyTokenDelta([row('100')], [row('150', wallet, Keypair.generate().publicKey.toBase58())],
    2, mint, wallet, false, '1'), null);
  assert.equal(verifiedBuyTokenDelta([], [row('150')], 2, mint, wallet, false, '1'), null);
  assert.equal(verifiedBuyTokenDelta([], [row('150')], 2, mint, wallet, true, '1'), 150n);
});

test('signed preflight rejects one lamport above reviewed maximum even below the general safety cap', async () => {
  const buy = fixture(); const chain = new MeteoraCommunityBuyChain('https://rpc.example.test', () => NOW);
  const signed = chain.assembleSigned(buy.preparation, buy.walletSignedTransactionBase64);
  let postLamports = 100_000_000 - 12_000_001;
  Object.defineProperty(chain, 'connection', { value: {
    async getGenesisHash() { return SOLANA_MAINNET_GENESIS_HASH; },
    async getBlockHeight() { return 900; },
    async getBalance() { return 100_000_000; },
    async simulateTransaction() { return { value: { err: null, accounts: [{ lamports: postLamports }] } }; },
  } });
  await assert.rejects(chain.preflight(buy.preparation, signed), /maximum wallet debit/);
  postLamports = 100_000_000 - 12_000_000;
  await assert.doesNotReject(chain.preflight(buy.preparation, signed));
});

test('finalized token delivery remains confirmed after an emptied ATA or excess actual debit', async () => {
  const buy = fixture(); const chain = new MeteoraCommunityBuyChain('https://rpc.example.test', () => NOW);
  const signed = chain.assembleSigned(buy.preparation, buy.walletSignedTransactionBase64);
  const ledger = new CommunityBuyLedger(null, () => NOW); const id = identity(buy.preparation);
  ledger.registerPrepared(PRINCIPAL, request(buy), buy.preparation);
  ledger.commit(PRINCIPAL, id); ledger.bindBroadcast(PRINCIPAL, id, signed.transactionId, signed.base64);
  const tokenAta = getAssociatedTokenAddressSync(new PublicKey(buy.preparation.mintAddress),
    buy.wallet.publicKey).toBase58();
  let closed = true; let walletPostLamports = 88_000_000;
  Object.defineProperty(chain, 'connection', { value: {
    async getGenesisHash() { return SOLANA_MAINNET_GENESIS_HASH; },
    async getBlockHeight() { return 900; },
    async getSignatureStatuses() { return { value: [{ err: null, confirmationStatus: 'finalized' }] }; },
    async getParsedTransaction() { return { transaction: { message: { accountKeys: [
      { pubkey: buy.wallet.publicKey }, { pubkey: new PublicKey(tokenAta) },
    ] } }, meta: { err: null, preTokenBalances: [], postTokenBalances: [{
      accountIndex: 1, mint: buy.preparation.mintAddress, owner: buy.preparation.walletAddress,
      uiTokenAmount: { amount: '1100000' },
    }], preBalances: [100_000_000, 0], postBalances: [walletPostLamports, 2_039_280] } }; },
    async getAccountInfo() { if (closed) return null; throw new Error('RPC unavailable'); },
  } });
  const afterClose = await chain.observe(ledger.current(PRINCIPAL, id));
  assert.equal(afterClose.state, 'confirmed'); assert.equal(afterClose.isFinalized, true);
  assert.equal(afterClose.tokenAmountReceived, '1100000'); assert.equal(afterClose.walletTokenBalanceRaw, '0');
  walletPostLamports = 87_999_999;
  const excessDebit = await chain.observe(ledger.current(PRINCIPAL, id));
  assert.equal(excessDebit.state, 'confirmed'); assert.equal(excessDebit.isFinalized, true);
  assert.equal(excessDebit.tokenAmountReceived, '1100000');
  assert.match(excessDebit.message, /debit exceeded the reviewed maximum/);
  closed = false;
  const unknownBalance = await chain.observe(ledger.current(PRINCIPAL, id));
  assert.equal(unknownBalance.state, 'confirmed'); assert.equal(unknownBalance.isFinalized, true);
  assert.equal(unknownBalance.walletTokenBalanceRaw, null);
});

test('missing signature status waits through grace and requires bracketed wallet history', async () => {
  const buy = fixture(); let clock = NOW; let recovered = false; let historyCalls = 0; let covered = false;
  const chain = new MeteoraCommunityBuyChain('https://rpc.example.test', () => clock);
  const signed = chain.assembleSigned(buy.preparation, buy.walletSignedTransactionBase64);
  const ledger = new CommunityBuyLedger(null, () => clock); const id = identity(buy.preparation);
  ledger.registerPrepared(PRINCIPAL, request(buy), buy.preparation);
  ledger.commit(PRINCIPAL, id); ledger.bindBroadcast(PRINCIPAL, id, signed.transactionId, signed.base64);
  clock = NOW + 80_000;
  const tokenAta = getAssociatedTokenAddressSync(new PublicKey(buy.preparation.mintAddress), buy.wallet.publicKey);
  Object.defineProperty(chain, 'connection', { value: {
    async getGenesisHash() { return SOLANA_MAINNET_GENESIS_HASH; },
    async getBlockHeight() { return 1_200; },
    async getSignatureStatuses() { return { value: [null] }; },
    async getParsedTransaction() { return recovered ? { transaction: { message: { accountKeys: [
      { pubkey: buy.wallet.publicKey }, { pubkey: tokenAta },
    ] } }, meta: { err: null, preTokenBalances: [], postTokenBalances: [{
      accountIndex: 1, mint: buy.preparation.mintAddress, owner: buy.preparation.walletAddress,
      uiTokenAmount: { amount: '1100000' },
    }], preBalances: [100_000_000, 0], postBalances: [88_000_000, 2_039_280] } } : null; },
    async getSignaturesForAddress() { historyCalls++; return covered
      ? [{ signature: 'older-finalized-wallet-transaction', slot: 99, err: null }] : []; },
    async getAccountInfo() { return null; },
  } });
  assert.equal((await chain.observe(ledger.current(PRINCIPAL, id))).state, 'unknown');
  assert.equal(historyCalls, 0);
  clock = NOW + 75_000 + 5 * 60_000 + 1;
  assert.equal((await chain.observe(ledger.current(PRINCIPAL, id))).state, 'unknown');
  assert.equal(historyCalls, 1);
  covered = true;
  assert.equal((await chain.observe(ledger.current(PRINCIPAL, id))).state, 'expired');
  recovered = true;
  const historical = await chain.observe(ledger.current(PRINCIPAL, id));
  assert.equal(historical.state, 'confirmed'); assert.equal(historical.isFinalized, true);
  assert.equal(historical.tokenAmountReceived, '1100000');
});

test('durable buy commits, signs, broadcasts once and returns purchase proof', async () => {
  const fake = new FakeChain(); const ledger = new CommunityBuyLedger(null, () => NOW);
  const service = new CommunityBuyService(fake, ledger, () => NOW);
  const prepared = await service.prepare(PRINCIPAL, request(fake.buy)); const id = identity(prepared);
  assert.equal(service.commit(PRINCIPAL, id).state, 'committed');
  const first = await service.submit(PRINCIPAL, { ...id, walletSignedTransactionBase64: fake.buy.walletSignedTransactionBase64 });
  assert.equal(first.state, 'broadcast'); assert.equal(first.idempotent, false);
  const second = await service.submit(PRINCIPAL, { ...id, walletSignedTransactionBase64: fake.buy.walletSignedTransactionBase64 });
  assert.equal(second.idempotent, true); assert.equal(fake.preflightCalls, 1); assert.equal(fake.broadcastCalls, 1);
  const status = await service.status(PRINCIPAL, id);
  assert.equal(status.state, 'confirmed'); assert.equal(status.tokenAmountReceived, '1100000');
  assert.equal(status.walletTokenBalanceRaw, '1100000');
  assert.equal(status.explorerUrl, `https://solscan.io/token/${prepared.mintAddress}`);
  assert.throws(() => service.release(PRINCIPAL, { ...id, reason: 'provider_definitely_not_invoked' }), CommunityBuyLedgerError);
});

test('an unresolved buy blocks a second wallet operation and reloads from private disk', async () => {
  const buy = fixture(); const filename = join(mkdtempSync(join(tmpdir(), 'eleven-buy-')), 'ledger.json');
  const ledger = new CommunityBuyLedger(filename, () => NOW); const fake = new FakeChain(buy);
  const service = new CommunityBuyService(fake, ledger, () => NOW);
  const prepared = await service.prepare(PRINCIPAL, request(buy));
  const reloaded = new CommunityBuyLedger(filename, () => NOW);
  assert.equal(reloaded.current(PRINCIPAL, identity(prepared)).preparation.transactionSha256, prepared.transactionSha256);
  const second = { ...request(buy), operationId: '37a9b840-3e9d-4ed5-a22f-c886327d0920' };
  await assert.rejects(service.prepare(PRINCIPAL, second), CommunityBuyLedgerError);
  service.release(PRINCIPAL, { ...identity(prepared), reason: 'provider_definitely_not_invoked' });
  assert.equal((await service.prepare(PRINCIPAL, second)).operationId, second.operationId);
});

test('community buy API is authenticated and rejects malformed request before signing', async () => {
  const fake = new FakeChain(); const service = new CommunityBuyService(fake, new CommunityBuyLedger(null, () => NOW), () => NOW);
  const app = await buildApp(undefined, false, { communityBuy: service, autoRefresh: false,
    accessTokenVerifier: { async verifyAuthorization(value) {
      if (value !== 'Bearer allowed') throw new Error('unauthorized');
      return PRINCIPAL;
    } } });
  try {
    const malformed = await app.inject({ method: 'POST', url: '/v1/community-buys/prepare',
      headers: { authorization: 'Bearer allowed' }, payload: { ...request(fake.buy), network: 'SOLANA_DEVNET' } });
    assert.equal(malformed.statusCode, 400); assert.equal(fake.prepareCalls, 0);
    const prepared = await app.inject({ method: 'POST', url: '/v1/community-buys/prepare',
      headers: { authorization: 'Bearer allowed' }, payload: request(fake.buy) });
    assert.equal(prepared.statusCode, 200); assert.equal(fake.prepareCalls, 1);
    assert.equal(prepared.json().encoding, 'base64-legacy-unsigned');
  } finally { await app.close(); }
});
