import { createHash, createPublicKey, verify as verifySignature } from 'node:crypto';
import BN from 'bn.js';
import bs58 from 'bs58';
import { ASSOCIATED_TOKEN_PROGRAM_ID, NATIVE_MINT, TOKEN_PROGRAM_ID,
  TokenAccountNotFoundError, TokenInvalidAccountOwnerError,
  createAssociatedTokenAccountIdempotentInstruction, createCloseAccountInstruction, createSyncNativeInstruction,
  getAccount, getAssociatedTokenAddressSync, unpackMint } from '@solana/spl-token';
import { Connection, PublicKey, SystemProgram, Transaction, TransactionInstruction, VersionedTransaction } from '@solana/web3.js';
import { BaseFeeMode, DYNAMIC_BONDING_CURVE_PROGRAM_ID, DynamicBondingCurveClient, SwapMode, TokenType,
  createProgramAccountFilter, deriveDbcEventAuthority, deriveDbcPoolAddress, deriveDbcPoolAuthority,
  deriveDbcTokenVaultAddress, getCurrentPoint } from '@meteora-ag/dynamic-bonding-curve-sdk';
import { SOLANA_MAINNET_GENESIS_HASH } from './token-launch-chain.js';
import { CommunityBuyError } from './community-buy-error.js';
import type { StoredCommunityBuy } from './community-buy-ledger.js';
import type { CommunityBuyPreparation, CommunityBuyPrepareRequest } from './community-buy-schema.js';

export { CommunityBuyError } from './community-buy-error.js';

const MAX_TRANSACTION_BYTES = 1_232;
const PREPARATION_LIFETIME_MS = 75_000;
const MAX_ADDITIONAL_DEBIT_LAMPORTS = 10_000_000n;
const MISSING_SIGNATURE_FINALITY_GRACE_MS = 5 * 60_000;

export interface SignedCommunityBuy { bytes: Uint8Array; base64: string; transactionId: string }
export interface CommunityBuyObservation {
  state: 'committed' | 'broadcast' | 'confirmed' | 'failed' | 'expired' | 'unknown';
  isFinalized: boolean; message: string; tokenAmountReceived: string | null; walletTokenBalanceRaw: string | null;
}
export interface CommunityBuyChain {
  prepare(request: CommunityBuyPrepareRequest): Promise<CommunityBuyPreparation>;
  assembleSigned(preparation: CommunityBuyPreparation, signedBase64: string): SignedCommunityBuy;
  preflight(preparation: CommunityBuyPreparation, signed: SignedCommunityBuy): Promise<void>;
  broadcast(signed: SignedCommunityBuy): Promise<boolean>;
  observe(buy: StoredCommunityBuy): Promise<CommunityBuyObservation>;
}

interface TokenBalanceRow { accountIndex: number; mint: string; owner?: string; uiTokenAmount: { amount: string } }
/** Only a positive delta in the expected wallet ATA for the exact mint counts; a missing pre-token row
 * is safe only when the transaction created that ATA from zero pre-transaction lamports. */
export function verifiedBuyTokenDelta(pre: readonly TokenBalanceRow[] | null | undefined,
  post: readonly TokenBalanceRow[] | null | undefined, tokenIndex: number, mint: string,
  wallet: string, destinationWasCreated: boolean, minimumAmountOut: string): bigint | null {
  if (tokenIndex < 0) return null;
  const before = pre?.find(value => value.accountIndex === tokenIndex && value.mint === mint);
  const after = post?.find(value => value.accountIndex === tokenIndex && value.mint === mint);
  if ((!before && !destinationWasCreated) || !after || (before?.owner && before.owner !== wallet) ||
    (after.owner && after.owner !== wallet)) return null;
  if (!/^\d+$/.test(after.uiTokenAmount.amount) || (before && !/^\d+$/.test(before.uiTokenAmount.amount))) return null;
  const delta = BigInt(after.uiTokenAmount.amount) - BigInt(before?.uiTokenAmount.amount ?? '0');
  return delta >= BigInt(minimumAmountOut) && delta > 0n ? delta : null;
}

function canonicalBase64(value: string): Buffer {
  if (value.length > 4_096) throw new CommunityBuyError('signed_transaction_invalid', 422, 'Wallet transaction is too large.');
  const bytes = Buffer.from(value, 'base64');
  if (bytes.length === 0 || bytes.toString('base64') !== value) {
    throw new CommunityBuyError('signed_transaction_invalid', 422, 'Wallet transaction is malformed.');
  }
  return bytes;
}
function ed25519Key(publicKey: PublicKey) {
  return createPublicKey({ key: Buffer.concat([Buffer.from('302a300506032b6570032100', 'hex'), publicKey.toBuffer()]),
    format: 'der', type: 'spki' });
}
function versioned(transaction: Transaction): VersionedTransaction {
  const message = transaction.compileMessage();
  const result = new VersionedTransaction(message);
  result.signatures = message.accountKeys.slice(0, message.header.numRequiredSignatures).map(key =>
    Uint8Array.from(transaction.signatures.find(value => value.publicKey.equals(key))?.signature ?? new Uint8Array(64)));
  return result;
}
function parseUnsigned(preparation: CommunityBuyPreparation): Transaction {
  const bytes = canonicalBase64(preparation.transactionBase64);
  if (createHash('sha256').update(bytes).digest('hex') !== preparation.transactionSha256) {
    throw new CommunityBuyError('signed_transaction_invalid', 422, 'Reviewed transaction hash changed.');
  }
  let transaction: Transaction;
  try { transaction = Transaction.from(bytes); }
  catch { throw new CommunityBuyError('signed_transaction_invalid', 422, 'Reviewed transaction is malformed.'); }
  if (transaction.signatures.length !== 1 || transaction.signatures[0]?.signature !== null ||
    transaction.signatures[0]?.publicKey.toBase58() !== preparation.walletAddress ||
    transaction.recentBlockhash !== preparation.recentBlockhash) {
    throw new CommunityBuyError('signed_transaction_invalid', 422, 'Reviewed transaction signer or blockhash changed.');
  }
  validateInstructions(transaction, preparation.walletAddress, preparation.poolAddress,
    preparation.configAddress, preparation.mintAddress, preparation.amountInLamports, preparation.minimumAmountOut);
  return transaction;
}
function validateInstructions(transaction: Transaction, walletAddress: string, poolAddress: string,
  configAddress: string, mintAddress: string, amountInLamports: string, minimumAmountOut: string): void {
  const wallet = new PublicKey(walletAddress); const mint = new PublicKey(mintAddress);
  const wsolAta = getAssociatedTokenAddressSync(NATIVE_MINT, wallet, false, TOKEN_PROGRAM_ID);
  const tokenAta = getAssociatedTokenAddressSync(mint, wallet, false, TOKEN_PROGRAM_ID);
  if (!transaction.feePayer?.equals(wallet) || transaction.compileMessage().header.numRequiredSignatures !== 1) {
    throw new CommunityBuyError('signed_transaction_invalid', 422, 'Meteora swap requested an unexpected signer.');
  }
  const instructions = [...transaction.instructions];
  // Transaction.from expands per-instruction flags to the message's global permissions. Compare
  // data/keys here, then compare the entire canonical compiled message below (including flags).
  const equalInstruction = (actual: typeof instructions[number], expected: typeof instructions[number]) =>
    actual.programId.equals(expected.programId) && actual.data.equals(expected.data) &&
    actual.keys.length === expected.keys.length && actual.keys.every((key, index) =>
      key.pubkey.equals(expected.keys[index]!.pubkey));
  const ataOptions = [
    createAssociatedTokenAccountIdempotentInstruction(wallet, wsolAta, wallet, NATIVE_MINT, TOKEN_PROGRAM_ID),
    createAssociatedTokenAccountIdempotentInstruction(wallet, tokenAta, wallet, mint, TOKEN_PROGRAM_ID),
  ];
  const ataPrefix: TransactionInstruction[] = [];
  for (const expected of ataOptions) {
    if (instructions[0]?.programId.equals(ASSOCIATED_TOKEN_PROGRAM_ID) &&
      equalInstruction(instructions[0], expected)) {
      ataPrefix.push(expected); instructions.shift();
    }
  }
  const transfer = SystemProgram.transfer({ fromPubkey: wallet, toPubkey: wsolAta,
    lamports: BigInt(amountInLamports) });
  const sync = createSyncNativeInstruction(wsolAta);
  const close = createCloseAccountInstruction(wsolAta, wallet, wallet, [], TOKEN_PROGRAM_ID);
  if (instructions.length !== 4 || !equalInstruction(instructions[0]!, transfer) ||
    !equalInstruction(instructions[1]!, sync) || !equalInstruction(instructions[3]!, close)) {
    throw new CommunityBuyError('signed_transaction_invalid', 422,
      'Meteora swap requested unrelated token or SOL instructions.');
  }
  const swap = instructions[2]!;
  const expectedDiscriminator = Buffer.from([65, 75, 63, 76, 235, 91, 91, 136]);
  if (!swap.programId.equals(DYNAMIC_BONDING_CURVE_PROGRAM_ID) || swap.keys.length !== 15 || swap.data.length !== 25 ||
    !swap.data.subarray(0, 8).equals(expectedDiscriminator) ||
    swap.data.readBigUInt64LE(8) !== BigInt(amountInLamports) ||
    swap.data.readBigUInt64LE(16) !== BigInt(minimumAmountOut) || swap.data[24] !== SwapMode.ExactIn) {
    throw new CommunityBuyError('signed_transaction_invalid', 422, 'Meteora swap amount or accounts do not match the reviewed buy.');
  }
  const pool = new PublicKey(poolAddress);
  const canonicalSwap = new TransactionInstruction({ programId: DYNAMIC_BONDING_CURVE_PROGRAM_ID,
    data: Buffer.from(swap.data), keys: [
      { pubkey: deriveDbcPoolAuthority(), isSigner: false, isWritable: false },
      { pubkey: new PublicKey(configAddress), isSigner: false, isWritable: false },
      { pubkey: pool, isSigner: false, isWritable: true },
      { pubkey: wsolAta, isSigner: false, isWritable: true },
      { pubkey: tokenAta, isSigner: false, isWritable: true },
      { pubkey: deriveDbcTokenVaultAddress(pool, mint), isSigner: false, isWritable: true },
      { pubkey: deriveDbcTokenVaultAddress(pool, NATIVE_MINT), isSigner: false, isWritable: true },
      { pubkey: mint, isSigner: false, isWritable: false },
      { pubkey: NATIVE_MINT, isSigner: false, isWritable: false },
      { pubkey: wallet, isSigner: true, isWritable: false },
      { pubkey: TOKEN_PROGRAM_ID, isSigner: false, isWritable: false },
      { pubkey: TOKEN_PROGRAM_ID, isSigner: false, isWritable: false },
      { pubkey: DYNAMIC_BONDING_CURVE_PROGRAM_ID, isSigner: false, isWritable: false },
      { pubkey: deriveDbcEventAuthority(), isSigner: false, isWritable: false },
      { pubkey: DYNAMIC_BONDING_CURVE_PROGRAM_ID, isSigner: false, isWritable: false },
    ] });
  const canonical = new Transaction();
  canonical.feePayer = wallet; canonical.recentBlockhash = transaction.recentBlockhash;
  canonical.add(...ataPrefix, transfer, sync, canonicalSwap, close);
  if (!canonical.serializeMessage().equals(transaction.serializeMessage())) {
    throw new CommunityBuyError('signed_transaction_invalid', 422,
      'Meteora swap account order or permissions differ from the reviewed exact-input buy.');
  }
}

/** A standard-SPL, exact-input DBC buy. Never signs or broadcasts without the user's wallet signature. */
export class MeteoraCommunityBuyChain implements CommunityBuyChain {
  private readonly connection: Connection;
  private readonly client: DynamicBondingCurveClient;
  private readonly rpcConfigured: boolean;
  constructor(endpoint?: string,
    private readonly now = Date.now) {
    const configuredEndpoint = endpoint ?? process.env.SOLANA_RPC_URL;
    this.rpcConfigured = configuredEndpoint !== undefined;
    let url: URL;
    try { url = new URL(configuredEndpoint ?? 'https://api.mainnet.solana.com'); }
    catch { throw new Error('SOLANA_RPC_URL must be an HTTPS URL'); }
    if (url.protocol !== 'https:' || url.username || url.password || url.hash) {
      throw new Error('SOLANA_RPC_URL must be an HTTPS URL');
    }
    this.connection = new Connection(url.toString(), { commitment: 'confirmed', confirmTransactionInitialTimeout: 15_000 });
    this.client = new DynamicBondingCurveClient(this.connection, 'confirmed');
  }

  async prepare(request: CommunityBuyPrepareRequest): Promise<CommunityBuyPreparation> {
    await this.ensureMainnet();
    const wallet = new PublicKey(request.walletAddress); const mint = new PublicKey(request.mintAddress);
    const amountIn = new BN(request.amountInLamports);
    const program = this.client.state.getProgram();
    const filters = createProgramAccountFilter(mint, 136);
    const [standardPools, hookPools] = await Promise.all([
      this.safeRead(() => program.account.virtualPool.all(filters)),
      this.safeRead(() => program.account.transferHookPool.all(filters)),
    ]);
    if (standardPools.length + hookPools.length !== 1 || standardPools.length !== 1) {
      throw new CommunityBuyError('unsupported_token', 422,
        'This token needs one unambiguous standard-SPL Meteora mainnet pool before it can be bought.');
    }
    const poolRow = standardPools[0]!;
    const pool = poolRow.publicKey;
    const poolState = poolRow.account.poolState;
    const configAddress = poolState.config;
    const [config, infos] = await Promise.all([
      this.safeRead(() => this.client.state.getPoolConfig(configAddress)),
      this.safeRead(() => this.connection.getMultipleAccountsInfo([mint, pool, configAddress], 'confirmed')),
    ]);
    if (!config || !infos[0] || !infos[1] || !infos[2] || !infos[0].owner.equals(TOKEN_PROGRAM_ID) ||
      !infos[1].owner.equals(DYNAMIC_BONDING_CURVE_PROGRAM_ID) ||
      !infos[2].owner.equals(DYNAMIC_BONDING_CURVE_PROGRAM_ID) ||
      !poolState.baseMint.equals(mint) || !config.quoteMint.equals(NATIVE_MINT) ||
      config.tokenType !== TokenType.SPLToken || poolState.poolType !== TokenType.SPLToken ||
      !deriveDbcPoolAddress(NATIVE_MINT, mint, configAddress).equals(pool) ||
      !poolState.baseVault.equals(deriveDbcTokenVaultAddress(pool, mint)) ||
      !poolState.quoteVault.equals(deriveDbcTokenVaultAddress(pool, NATIVE_MINT)) ||
      config.poolFees.baseFee.baseFeeMode === BaseFeeMode.RateLimiter || config.enableFirstSwapWithMinFee !== 0) {
      throw new CommunityBuyError('unsupported_token', 422, 'This token is not a supported SOL / SPL Meteora mainnet pool.');
    }
    let mintState;
    try { mintState = unpackMint(mint, infos[0], TOKEN_PROGRAM_ID); }
    catch { throw new CommunityBuyError('unsupported_token', 422, 'This token mint is invalid on Solana mainnet.'); }
    if (mintState.decimals !== config.tokenDecimal || poolState.isMigrated !== 0) {
      throw new CommunityBuyError('unsupported_token', 422, 'This token is not an active standard-SPL bonding-curve pool.');
    }
    if (BigInt(poolState.quoteReserve.toString()) >= BigInt(config.migrationQuoteThreshold.toString())) {
      throw new CommunityBuyError('pool_complete', 422, 'This bonding curve is complete and cannot accept DBC buys.');
    }
    let quote;
    try {
      quote = this.client.pool.swapQuote2({ virtualPool: poolRow.account, config, swapBaseForQuote: false,
        swapMode: SwapMode.ExactIn, amountIn, slippageBps: request.slippageBps, hasReferral: false,
        eligibleForFirstSwapWithMinFee: false, currentPoint: await getCurrentPoint(this.connection, config.activationType) });
    } catch { throw new CommunityBuyError('quote_unavailable', 422,
      'The requested buy exceeds available bonding-curve liquidity. Try a smaller amount.'); }
    if (!quote.minimumAmountOut || quote.minimumAmountOut.isZero() || quote.outputAmount.isZero() ||
      !quote.amountLeft.isZero()) throw new CommunityBuyError('quote_unavailable', 422,
      'A complete exact-input quote is unavailable. Try a smaller amount.');
    let transaction: Transaction;
    try {
      transaction = await this.client.pool.swap2({ owner: wallet, payer: wallet, pool, swapBaseForQuote: false,
        swapMode: SwapMode.ExactIn, amountIn, minimumAmountOut: quote.minimumAmountOut, referralTokenAccount: null });
    } catch { throw new CommunityBuyError('quote_unavailable', 422, 'Meteora could not build this mainnet buy.'); }
    const latest = await this.safeRead(() => this.connection.getLatestBlockhashAndContext('confirmed'));
    transaction.feePayer = wallet; transaction.recentBlockhash = latest.value.blockhash;
    transaction.lastValidBlockHeight = latest.value.lastValidBlockHeight;
    validateInstructions(transaction, request.walletAddress, pool.toBase58(), configAddress.toBase58(),
      request.mintAddress, request.amountInLamports, quote.minimumAmountOut.toString());
    const bytes = transaction.serialize({ requireAllSignatures: false, verifySignatures: false });
    if (bytes.length > MAX_TRANSACTION_BYTES) throw new CommunityBuyError('quote_unavailable', 422,
      'Meteora buy exceeds the Solana transaction size limit.');
    const [balance, simulation, fee] = await Promise.all([
      this.safeRead(() => this.connection.getBalance(wallet, 'confirmed')),
      this.simulate(transaction, wallet, false, latest.context.slot),
      this.safeRead(() => this.connection.getFeeForMessage(transaction.compileMessage(), 'confirmed')),
    ]);
    if (!Number.isSafeInteger(balance) || fee.value === null || !simulation.accounts?.[0]) {
      throw new CommunityBuyError('simulation_failed', 422, 'Mainnet could not estimate the full buy cost.');
    }
    const debit = BigInt(balance) - BigInt(simulation.accounts[0].lamports);
    if (debit < 0n || debit > BigInt(request.amountInLamports) + MAX_ADDITIONAL_DEBIT_LAMPORTS) {
      throw new CommunityBuyError('buy_cost_too_high', 422, 'This buy exceeds the reviewed SOL amount and safety overhead.');
    }
    if (BigInt(balance) < debit) throw new CommunityBuyError('insufficient_mainnet_balance', 422,
      'Your SOL balance is insufficient for this buy and network rent.');
    const preparedAt = new Date(this.now()).toISOString();
    return {
      ...request, programId: DYNAMIC_BONDING_CURVE_PROGRAM_ID.toBase58(), quoteMint: NATIVE_MINT.toBase58(),
      poolAddress: pool.toBase58(), configAddress: configAddress.toBase58(), tokenDecimals: mintState.decimals,
      expectedAmountOut: quote.outputAmount.toString(), minimumAmountOut: quote.minimumAmountOut.toString(),
      estimatedFeeLamports: String(fee.value), maximumWalletDebitLamports: debit.toString(),
      balanceLamports: String(balance), simulationUnitsConsumed: simulation.unitsConsumed ?? 0,
      observedSlot: String(latest.context.slot), recentBlockhash: latest.value.blockhash,
      lastValidBlockHeight: String(latest.value.lastValidBlockHeight), preparedAt,
      expiresAt: new Date(this.now() + PREPARATION_LIFETIME_MS).toISOString(),
      transactionSha256: createHash('sha256').update(bytes).digest('hex'),
      transactionBase64: Buffer.from(bytes).toString('base64'), encoding: 'base64-legacy-unsigned',
    };
  }

  assembleSigned(preparation: CommunityBuyPreparation, signedBase64: string): SignedCommunityBuy {
    const unsigned = parseUnsigned(preparation);
    const raw = canonicalBase64(signedBase64);
    if (raw.length > MAX_TRANSACTION_BYTES || raw[0] !== 1 || !raw.subarray(65).equals(unsigned.serializeMessage())) {
      throw new CommunityBuyError('signed_transaction_invalid', 422, 'Wallet-signed message changed from the reviewed buy.');
    }
    const signature = raw.subarray(1, 65); const wallet = new PublicKey(preparation.walletAddress);
    if (!verifySignature(null, unsigned.serializeMessage(), ed25519Key(wallet), signature)) {
      throw new CommunityBuyError('signed_transaction_invalid', 422, 'Wallet signature is invalid for this buy.');
    }
    unsigned.addSignature(wallet, signature);
    if (!unsigned.verifySignatures(true)) throw new CommunityBuyError('signed_transaction_invalid', 422,
      'The signed Meteora buy transaction is invalid.');
    const bytes = unsigned.serialize({ requireAllSignatures: true, verifySignatures: true });
    return { bytes, base64: Buffer.from(bytes).toString('base64'), transactionId: bs58.encode(signature) };
  }

  async preflight(preparation: CommunityBuyPreparation, signed: SignedCommunityBuy): Promise<void> {
    await this.ensureMainnet();
    const reviewed = parseUnsigned(preparation);
    let transaction: Transaction;
    try { transaction = Transaction.from(signed.bytes); }
    catch { throw new CommunityBuyError('signed_transaction_invalid', 422, 'Signed buy transaction is malformed.'); }
    if (!transaction.serializeMessage().equals(reviewed.serializeMessage()) || !transaction.verifySignatures(true)) {
      throw new CommunityBuyError('signed_transaction_invalid', 422, 'Signed buy does not match its reviewed quote.');
    }
    const height = await this.safeRead(() => this.connection.getBlockHeight('confirmed'));
    if (height > Number(preparation.lastValidBlockHeight) || this.now() >= Date.parse(preparation.expiresAt)) {
      throw new CommunityBuyError('blockhash_expired', 422, 'The reviewed buy quote expired. Request a fresh quote.');
    }
    const wallet = new PublicKey(preparation.walletAddress);
    const [balance, simulation] = await Promise.all([
      this.safeRead(() => this.connection.getBalance(wallet, 'confirmed')),
      this.simulate(transaction, wallet, true, Number(preparation.observedSlot)),
    ]);
    if (!Number.isSafeInteger(balance) || !simulation.accounts?.[0]) throw new CommunityBuyError('simulation_failed', 422,
      'Mainnet could not verify this signed buy cost.');
    const debit = BigInt(balance) - BigInt(simulation.accounts[0].lamports);
    if (debit < 0n || debit > BigInt(preparation.maximumWalletDebitLamports) ||
      debit > BigInt(preparation.amountInLamports) + MAX_ADDITIONAL_DEBIT_LAMPORTS ||
      debit > BigInt(balance)) throw new CommunityBuyError('buy_cost_too_high', 422,
      'Signed buy exceeds the maximum wallet debit you reviewed. Request a fresh quote.');
  }

  async broadcast(signed: SignedCommunityBuy): Promise<boolean> {
    await this.ensureMainnet();
    try { return await this.connection.sendRawTransaction(signed.bytes, {
      skipPreflight: false, preflightCommitment: 'confirmed', maxRetries: 3,
    }) === signed.transactionId; } catch { return false; }
  }

  async observe(buy: StoredCommunityBuy): Promise<CommunityBuyObservation> {
    await this.ensureMainnet();
    const currentHeight = await this.safeRead(() => this.connection.getBlockHeight('confirmed'));
    const pending = (state: CommunityBuyObservation['state'], message: string): CommunityBuyObservation => ({
      state, isFinalized: false, message, tokenAmountReceived: null, walletTokenBalanceRaw: null,
    });
    if (!buy.transactionId) return currentHeight > Number(buy.preparation.lastValidBlockHeight)
      ? { ...pending('expired', 'The reviewed buy expired without submission.'), isFinalized: true }
      : pending('committed', 'Waiting for the signed buy transaction.');
    const status = await this.safeRead(() => this.connection.getSignatureStatuses([buy.transactionId!],
      { searchTransactionHistory: true }));
    const result = status.value[0];
    let recovered: Awaited<ReturnType<Connection['getParsedTransaction']>> = null;
    if (!result) {
      // An expired blockhash does not prove a bound signature never landed; a lagging status index
      // can otherwise mislabel a real purchase as expired and unlock a duplicate buy.
      recovered = await this.safeRead(() => this.connection.getParsedTransaction(buy.transactionId!,
        { commitment: 'finalized', maxSupportedTransactionVersion: 0 }));
      if (!recovered) {
        if (currentHeight <= Number(buy.preparation.lastValidBlockHeight) ||
          this.now() < Date.parse(buy.preparation.expiresAt) + MISSING_SIGNATURE_FINALITY_GRACE_MS) {
          return pending('unknown', 'Buy broadcast outcome is still being checked.');
        }
        const history = await this.safeRead(() => this.connection.getSignaturesForAddress(
          new PublicKey(buy.preparation.walletAddress), { limit: 1_000 }, 'finalized'));
        const matching = history.find(row => row.signature === buy.transactionId);
        if (matching) return matching.err === null
          ? pending('unknown', 'Buy signature exists; finalized transaction details are still being checked.')
          : { ...pending('failed', 'Solana mainnet rejected this buy.'), isFinalized: true };
        // A short or empty response is not proof of complete RPC indexing. Require an observed
        // older finalized wallet signature that brackets the whole preparation window.
        const coversPreparation = history.length > 0 &&
          history[history.length - 1]!.slot <= Number(buy.preparation.observedSlot);
        return coversPreparation
          ? { ...pending('expired', 'Buy signature was absent from finalized wallet history after the review expired.'),
              isFinalized: true }
          : pending('unknown', 'Wallet transaction history does not yet cover this buy.');
      }
    }
    if (result && result.confirmationStatus !== 'finalized') return pending('broadcast',
      result.err === null ? 'Meteora buy is awaiting Solana mainnet finality.' : 'Buy result is awaiting Solana mainnet finality.');
    if (result?.err) return { ...pending('failed', 'Solana mainnet rejected this buy.'), isFinalized: true };
    const transaction = recovered ?? await this.safeRead(() => this.connection.getParsedTransaction(buy.transactionId!,
      { commitment: 'finalized', maxSupportedTransactionVersion: 0 }));
    if (!transaction || !transaction.meta) return pending('broadcast', 'Buy confirmed; transaction details are still becoming available.');
    if (transaction.meta.err !== null) return { ...pending('failed', 'Solana mainnet rejected this buy.'), isFinalized: true };
    const wallet = new PublicKey(buy.preparation.walletAddress);
    const mint = new PublicKey(buy.preparation.mintAddress);
    const ata = getAssociatedTokenAddressSync(mint, wallet, false, TOKEN_PROGRAM_ID).toBase58();
    const tokenIndex = transaction.transaction.message.accountKeys.findIndex(key => key.pubkey.toBase58() === ata);
    if (tokenIndex < 0) return { ...pending('failed', 'Confirmed buy did not credit the expected token account.'), isFinalized: true };
    const signedMessage = parseUnsigned(buy.preparation);
    const createdAta = signedMessage.instructions.some(ix => ix.programId.equals(ASSOCIATED_TOKEN_PROGRAM_ID) &&
      ix.keys[1]?.pubkey.toBase58() === ata) && transaction.meta.preBalances[tokenIndex] === 0;
    const received = verifiedBuyTokenDelta(transaction.meta.preTokenBalances,
      transaction.meta.postTokenBalances, tokenIndex, buy.preparation.mintAddress,
      buy.preparation.walletAddress, createdAta, buy.preparation.minimumAmountOut);
    if (received === null) return pending('broadcast', 'Buy confirmed; token delivery proof is still being checked.');
    const walletIndex = transaction.transaction.message.accountKeys.findIndex(key => key.pubkey.equals(wallet));
    const costWarning = walletIndex < 0 || transaction.meta.preBalances[walletIndex] === undefined ||
      transaction.meta.postBalances[walletIndex] === undefined
      ? 'Warning: the finalized SOL debit could not be verified against the reviewed maximum.'
      : BigInt(transaction.meta.preBalances[walletIndex]) - BigInt(transaction.meta.postBalances[walletIndex]) >
          BigInt(buy.preparation.maximumWalletDebitLamports)
        ? 'Warning: the finalized SOL debit exceeded the reviewed maximum. Check wallet activity.' : null;
    let walletTokenBalanceRaw: string | null = null;
    try {
      const account = await getAccount(this.connection, new PublicKey(ata), 'finalized', TOKEN_PROGRAM_ID);
      if (!account.owner.equals(wallet) || !account.mint.equals(mint)) throw new Error('wrong token account');
      walletTokenBalanceRaw = account.amount.toString();
    } catch (error) {
      if (error instanceof TokenAccountNotFoundError || error instanceof TokenInvalidAccountOwnerError) {
        walletTokenBalanceRaw = '0';
      } else {
        return { state: 'confirmed', isFinalized: true,
          message: costWarning ?? 'Purchase finalized; current wallet token balance is unavailable.',
          tokenAmountReceived: received.toString(), walletTokenBalanceRaw: null };
      }
    }
    if (BigInt(walletTokenBalanceRaw) === 0n) return {
      state: 'confirmed', isFinalized: true,
      message: costWarning ?? 'Buy confirmed, but this wallet no longer holds the token.',
      tokenAmountReceived: received.toString(), walletTokenBalanceRaw,
    };
    return { state: 'confirmed', isFinalized: true,
      message: costWarning ?? 'Meteora buy confirmed on Solana mainnet.',
      tokenAmountReceived: received.toString(), walletTokenBalanceRaw };
  }

  private async simulate(transaction: Transaction, wallet: PublicKey, verify: boolean, slot: number) {
    try {
      const result = await this.connection.simulateTransaction(versioned(transaction), {
        sigVerify: verify, replaceRecentBlockhash: false, commitment: 'confirmed', minContextSlot: slot,
        accounts: { encoding: 'base64', addresses: [wallet.toBase58()] },
      });
      if (result.value.err !== null) throw new Error('simulation rejected');
      return result.value;
    } catch { throw new CommunityBuyError('simulation_failed', 422,
      'Meteora mainnet simulation failed. Nothing was submitted.'); }
  }
  private async ensureMainnet(): Promise<void> {
    if (!this.rpcConfigured) throw new CommunityBuyError('mainnet_rpc_unavailable', 503,
      'Mainnet buys require a configured SOLANA_RPC_URL. Nothing was submitted.');
    try {
      if (await this.connection.getGenesisHash() !== SOLANA_MAINNET_GENESIS_HASH) throw new Error('wrong cluster');
    } catch { throw new CommunityBuyError('mainnet_rpc_unavailable', 503,
      'Configured Solana RPC is unavailable or is not mainnet.'); }
  }
  private async safeRead<T>(read: () => Promise<T>): Promise<T> {
    try { return await read(); }
    catch { throw new CommunityBuyError('mainnet_rpc_unavailable', 503, 'Solana mainnet is temporarily unavailable.'); }
  }
}
