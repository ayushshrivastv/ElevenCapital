import { createHash, createPublicKey, verify as verifySignature } from 'node:crypto';
import bs58 from 'bs58';
import { NATIVE_MINT, TOKEN_PROGRAM_ID, unpackMint } from '@solana/spl-token';
import { Connection, Keypair, PublicKey, SystemProgram, Transaction, TransactionInstruction, VersionedTransaction,
  type Message } from '@solana/web3.js';
import { ActivationType, BaseFeeMode, CollectFeeMode, DammV2BaseFeeMode, DammV2DynamicFeeMode,
  DYNAMIC_BONDING_CURVE_PROGRAM_ID, DynamicBondingCurveClient, MigrationFeeOption, MigrationOption,
  MigratedCollectFeeMode, TokenAuthorityOption, TokenDecimal, TokenType, buildCurve, deriveDbcEventAuthority,
  deriveDbcPoolAuthority, deriveDbcPoolAddress, deriveDbcTokenVaultAddress, deriveMintMetadata,
  type PoolConfig, type VirtualPool,
} from '@meteora-ag/dynamic-bonding-curve-sdk';
import { TokenLaunchError } from './token-launch-error.js';
import type { StoredTokenLaunch } from './token-launch-ledger.js';
import type { TokenLaunchPreparation, TokenLaunchPrepareRequest } from './token-launch-schema.js';

export const SOLANA_MAINNET_GENESIS_HASH = '5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d';
export const TOKEN_LAUNCH_REQUIRED_BALANCE_LAMPORTS = 50_000_000;
export const TOKEN_LAUNCH_MAXIMUM_DEBIT_LAMPORTS = 50_000_000;
const MAXIMUM_TRANSACTION_BYTES = 1_232;
const PREPARATION_LIFETIME_MS = 75_000;
const MISSING_SIGNATURE_FINALITY_GRACE_MS = 5 * 60_000;

export interface SignedLaunchTransaction { bytes: Uint8Array; base64: string; transactionId: string }
export interface TokenLaunchChainObservation {
  state: 'committed' | 'broadcast' | 'confirmed' | 'failed' | 'expired' | 'unknown';
  isFinalized: boolean; message: string | null;
}
export interface TokenLaunchChain {
  prepare(request: TokenLaunchPrepareRequest): Promise<TokenLaunchPreparation>;
  assembleSigned(preparation: TokenLaunchPreparation, walletSignedTransactionBase64: string): SignedLaunchTransaction;
  preflight(preparation: TokenLaunchPreparation, signed: SignedLaunchTransaction): Promise<void>;
  broadcast(signed: SignedLaunchTransaction): Promise<boolean>;
  observe(launch: StoredTokenLaunch): Promise<TokenLaunchChainObservation>;
}

function decimalNumber(value: string): number {
  const result = Number(value);
  if (!Number.isFinite(result)) throw new TokenLaunchError('invalid_launch', 422, 'Token launch amount is invalid.');
  return result;
}
function canonicalBase64(value: string): Buffer {
  const decoded = Buffer.from(value, 'base64');
  if (decoded.length === 0 || decoded.toString('base64') !== value) {
    throw new TokenLaunchError('signed_transaction_invalid', 422, 'Wallet returned a malformed signed transaction.');
  }
  return decoded;
}
function versionedTransaction(transaction: Transaction): VersionedTransaction {
  const message = transaction.compileMessage();
  const result = new VersionedTransaction(message);
  const required = message.accountKeys.slice(0, message.header.numRequiredSignatures);
  result.signatures = required.map(key => {
    const pair = transaction.signatures.find(item => item.publicKey.equals(key));
    return pair?.signature ? Uint8Array.from(pair.signature) : new Uint8Array(64);
  });
  return result;
}
function publicKeyForEd25519(publicKey: PublicKey) {
  const prefix = Buffer.from('302a300506032b6570032100', 'hex');
  return createPublicKey({ key: Buffer.concat([prefix, publicKey.toBuffer()]), format: 'der', type: 'spki' });
}
function signerAddresses(message: Message): string[] {
  return message.accountKeys.slice(0, message.header.numRequiredSignatures).map(key => key.toBase58());
}
function hasExactLaunchSigners(message: Message, wallet: string, mint: string, config: string): boolean {
  const signers = signerAddresses(message);
  return signers.length === 3 && signers[0] === wallet && new Set(signers.slice(1)).size === 2 &&
    signers.slice(1).includes(mint) && signers.slice(1).includes(config);
}
function expectedSupply(value: string): bigint { return BigInt(value) * 1_000_000n; }
function solLamports(value: string): bigint {
  const [whole = '0', fraction = ''] = value.split('.');
  return BigInt(whole) * 1_000_000_000n + BigInt(fraction.padEnd(9, '0'));
}
function integerText(value: { toString(): string }): string { return value.toString(); }

export function reviewedCurve(request: Pick<TokenLaunchPrepareRequest, 'totalTokenSupply' | 'feeBps' |
  'migrationThresholdSol' | 'percentageSupplyOnMigration'>) {
  const curve = buildCurve({
    token: { totalTokenSupply: decimalNumber(request.totalTokenSupply), tokenBaseDecimal: TokenDecimal.SIX,
      tokenQuoteDecimal: 9, tokenType: TokenType.SPLToken, tokenAuthorityOption: TokenAuthorityOption.Immutable, leftover: 0 },
    fee: { baseFeeParams: { baseFeeMode: BaseFeeMode.FeeSchedulerLinear,
      feeSchedulerParam: { startingFeeBps: request.feeBps, endingFeeBps: request.feeBps, numberOfPeriod: 0, totalDuration: 0 } },
    dynamicFeeEnabled: false, collectFeeMode: CollectFeeMode.QuoteToken, creatorTradingFeePercentage: 100,
    poolCreationFee: 0, enableFirstSwapWithMinFee: false },
    migration: { migrationOption: MigrationOption.MET_DAMM_V2, migrationFeeOption: MigrationFeeOption.Customizable,
      migrationFee: { feePercentage: 0, creatorFeePercentage: 0 },
      migratedPoolFee: { collectFeeMode: MigratedCollectFeeMode.QuoteToken, dynamicFee: DammV2DynamicFeeMode.Disabled,
        poolFeeBps: request.feeBps, baseFeeMode: DammV2BaseFeeMode.FeeTimeSchedulerLinear } },
    liquidityDistribution: { partnerPermanentLockedLiquidityPercentage: 0, partnerLiquidityPercentage: 0,
      creatorPermanentLockedLiquidityPercentage: 100, creatorLiquidityPercentage: 0 },
    lockedVesting: { totalLockedVestingAmount: 0, numberOfVestingPeriod: 0, cliffUnlockAmount: 0,
      totalVestingDuration: 0, cliffDurationFromMigrationTime: 0 },
    activationType: ActivationType.Timestamp,
    percentageSupplyOnMigration: request.percentageSupplyOnMigration,
    migrationQuoteThreshold: decimalNumber(request.migrationThresholdSol),
  });
  if (!curve.tokenSupply ||
    integerText(curve.migrationQuoteThreshold) !== solLamports(request.migrationThresholdSol).toString() ||
    integerText(curve.tokenSupply.preMigrationTokenSupply) !== expectedSupply(request.totalTokenSupply).toString() ||
    integerText(curve.tokenSupply.postMigrationTokenSupply) !== expectedSupply(request.totalTokenSupply).toString()) {
    throw new TokenLaunchError('invalid_launch', 422,
      'Meteora could not encode the reviewed supply or migration threshold exactly. Choose different values.');
  }
  return curve;
}

/** Exact post-confirmation binding between the reviewed economics and Meteora's decoded DBC accounts. */
export function matchesMeteoraLaunchState(preparation: TokenLaunchPreparation, config: PoolConfig, pool: VirtualPool): boolean {
  const wallet = new PublicKey(preparation.walletAddress); const mint = new PublicKey(preparation.mintAddress);
  const configAddress = new PublicKey(preparation.configAddress); const state = pool.poolState;
  const feeNumerator = BigInt(preparation.feeBps) * 100_000n;
  return config.quoteMint.equals(NATIVE_MINT) && config.feeClaimer.equals(wallet) && config.leftoverReceiver.equals(wallet) &&
    config.collectFeeMode === CollectFeeMode.QuoteToken && config.migrationOption === MigrationOption.MET_DAMM_V2 &&
    config.activationType === ActivationType.Timestamp && config.tokenDecimal === TokenDecimal.SIX &&
    config.tokenType === TokenType.SPLToken && config.quoteTokenFlag === TokenType.SPLToken &&
    config.partnerPermanentLockedLiquidityPercentage === 0 && config.partnerLiquidityPercentage === 0 &&
    config.creatorPermanentLockedLiquidityPercentage === 100 && config.creatorLiquidityPercentage === 0 &&
    config.migrationFeeOption === MigrationFeeOption.Customizable && config.fixedTokenSupplyFlag === 1 &&
    config.creatorTradingFeePercentage === 100 && config.tokenUpdateAuthority === TokenAuthorityOption.Immutable &&
    config.migrationFeePercentage === 0 && config.creatorMigrationFeePercentage === 0 &&
    integerText(config.migrationQuoteThreshold) === solLamports(preparation.migrationThresholdSol).toString() &&
    integerText(config.preMigrationTokenSupply) === expectedSupply(preparation.totalTokenSupply).toString() &&
    integerText(config.postMigrationTokenSupply) === expectedSupply(preparation.totalTokenSupply).toString() &&
    config.migratedCollectFeeMode === MigratedCollectFeeMode.QuoteToken &&
    config.migratedDynamicFee === DammV2DynamicFeeMode.Disabled && config.migratedPoolFeeBps === preparation.feeBps &&
    config.migratedPoolBaseFeeMode === DammV2BaseFeeMode.FeeTimeSchedulerLinear && config.enableFirstSwapWithMinFee === 0 &&
    config.migratedCompoundingFeeBps === 0 && integerText(config.poolCreationFee) === '0' &&
    config.poolFees.baseFee.baseFeeMode === BaseFeeMode.FeeSchedulerLinear &&
    integerText(config.poolFees.baseFee.cliffFeeNumerator) === feeNumerator.toString() &&
    config.poolFees.baseFee.firstFactor === 0 && integerText(config.poolFees.baseFee.secondFactor) === '0' &&
    integerText(config.poolFees.baseFee.thirdFactor) === '0' && config.poolFees.dynamicFee.initialized === 0 &&
    state.config.equals(configAddress) && state.creator.equals(wallet) && state.baseMint.equals(mint) &&
    state.poolType === TokenType.SPLToken && (state.isMigrated === 0 || state.isMigrated === 1);
}

/** Real Meteora DBC implementation. Transactions require a configured RPC and pinned mainnet genesis. */
export class MeteoraTokenLaunchChain implements TokenLaunchChain {
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
    if (url.protocol !== 'https:' || url.username || url.password || url.hash) throw new Error('SOLANA_RPC_URL must be an HTTPS URL');
    this.connection = new Connection(url.toString(), { commitment: 'confirmed', confirmTransactionInitialTimeout: 15_000 });
    this.client = new DynamicBondingCurveClient(this.connection, 'confirmed');
  }

  async prepare(request: TokenLaunchPrepareRequest): Promise<TokenLaunchPreparation> {
    await this.ensureMainnet();
    const wallet = new PublicKey(request.walletAddress);
    let balance: number;
    try { balance = await this.connection.getBalance(wallet, 'confirmed'); }
    catch { throw new TokenLaunchError('mainnet_rpc_unavailable', 503, 'Solana mainnet balance is temporarily unavailable.'); }
    if (balance < TOKEN_LAUNCH_REQUIRED_BALANCE_LAMPORTS) throw new TokenLaunchError('insufficient_mainnet_balance', 422,
      'At least 0.05 SOL is required to cover network rent and fees. This is not a service fee.');

    const config = Keypair.generate(); const baseMint = Keypair.generate();
    try {
      const configParameters = reviewedCurve(request);
      const transaction = await this.client.partner.createConfigAndPool({
        ...configParameters, config: config.publicKey, feeClaimer: wallet, leftoverReceiver: wallet,
        quoteMint: NATIVE_MINT, payer: wallet,
        preCreatePoolParam: { name: request.name, symbol: request.symbol, uri: request.metadataUri,
          poolCreator: wallet, baseMint: baseMint.publicKey },
      });
      let latest;
      try { latest = await this.connection.getLatestBlockhashAndContext('confirmed'); }
      catch { throw new TokenLaunchError('mainnet_rpc_unavailable', 503, 'Solana mainnet blockhash is temporarily unavailable.'); }
      transaction.feePayer = wallet; transaction.recentBlockhash = latest.value.blockhash;
      transaction.lastValidBlockHeight = latest.value.lastValidBlockHeight;
      this.validateUnsignedTransaction(transaction, wallet, baseMint.publicKey, config.publicKey, request);
      transaction.partialSign(baseMint, config);
      if (!transaction.verifySignatures(false)) throw new TokenLaunchError('invalid_launch', 422,
        'Meteora launch signer validation failed safely.');
      const partialBytes = transaction.serialize({ requireAllSignatures: false, verifySignatures: true });
      if (partialBytes.length > MAXIMUM_TRANSACTION_BYTES) throw new TokenLaunchError('invalid_launch', 422,
        'Meteora launch transaction exceeds Solana limits.');
      const simulation = await this.simulate(transaction, wallet, false, latest.context.slot);
      const message = transaction.compileMessage();
      let fee;
      try { fee = await this.connection.getFeeForMessage(message, 'confirmed'); }
      catch { throw new TokenLaunchError('mainnet_rpc_unavailable', 503, 'Solana mainnet fee estimate is temporarily unavailable.'); }
      if (fee.value === null || !simulation.accounts?.[0]) throw new TokenLaunchError('simulation_failed', 422,
        'Mainnet could not produce a complete cost estimate for this launch.');
      const maximumDebit = balance - simulation.accounts[0].lamports;
      if (!Number.isSafeInteger(maximumDebit) || maximumDebit < fee.value) throw new TokenLaunchError('simulation_failed', 422,
        'Mainnet returned an invalid launch cost estimate.');
      if (maximumDebit > TOKEN_LAUNCH_MAXIMUM_DEBIT_LAMPORTS) throw new TokenLaunchError('launch_cost_too_high', 422,
        'This launch would debit more than the 0.05 SOL safety ceiling. Nothing was submitted.');
      if (maximumDebit > balance) throw new TokenLaunchError('insufficient_mainnet_balance', 422,
        'SOL balance is insufficient for the simulated network rent and fees.');
      const preparedAt = new Date(this.now()).toISOString();
      const pool = deriveDbcPoolAddress(NATIVE_MINT, baseMint.publicKey, config.publicKey);
      return {
        schemaVersion: 1, operationId: request.operationId, network: 'SOLANA_MAINNET', walletId: request.walletId,
        walletAddress: request.walletAddress, name: request.name, symbol: request.symbol, metadataUri: request.metadataUri,
        programId: DYNAMIC_BONDING_CURVE_PROGRAM_ID.toBase58(), quoteMint: NATIVE_MINT.toBase58(),
        mintAddress: baseMint.publicKey.toBase58(), configAddress: config.publicKey.toBase58(), poolAddress: pool.toBase58(),
        totalTokenSupply: request.totalTokenSupply, tokenDecimals: 6, feeBps: request.feeBps,
        migrationThresholdSol: request.migrationThresholdSol, percentageSupplyOnMigration: request.percentageSupplyOnMigration,
        migrationTarget: 'METEORA_DAMM_V2', tokenAuthority: 'IMMUTABLE', dynamicFeeEnabled: false,
        creatorPermanentLockedLiquidityPercentage: 100, estimatedFeeLamports: String(fee.value),
        maximumWalletDebitLamports: String(maximumDebit), balanceLamports: String(balance),
        simulationUnitsConsumed: simulation.unitsConsumed ?? 0, observedSlot: String(latest.context.slot),
        recentBlockhash: latest.value.blockhash, lastValidBlockHeight: String(latest.value.lastValidBlockHeight),
        preparedAt, expiresAt: new Date(this.now() + PREPARATION_LIFETIME_MS).toISOString(),
        transactionSha256: createHash('sha256').update(partialBytes).digest('hex'),
        transactionBase64: partialBytes.toString('base64'), encoding: 'base64-legacy-partially-signed',
      };
    } catch (error) {
      if (error instanceof TokenLaunchError) throw error;
      throw new TokenLaunchError('invalid_launch', 422, 'Meteora rejected these launch settings. Review the curve values.');
    } finally {
      config.secretKey.fill(0); baseMint.secretKey.fill(0);
    }
  }

  assembleSigned(preparation: TokenLaunchPreparation, walletSignedTransactionBase64: string): SignedLaunchTransaction {
    const partialBytes = canonicalBase64(preparation.transactionBase64);
    if (createHash('sha256').update(partialBytes).digest('hex') !== preparation.transactionSha256) {
      throw new TokenLaunchError('signed_transaction_invalid', 422, 'Stored launch transaction hash is invalid.');
    }
    let partial: Transaction;
    try { partial = Transaction.from(partialBytes); }
    catch (error) {
      if (error instanceof TokenLaunchError) throw error;
      throw new TokenLaunchError('signed_transaction_invalid', 422, 'Stored launch transaction is invalid.');
    }
    this.validateUnsignedTransaction(partial, new PublicKey(preparation.walletAddress), new PublicKey(preparation.mintAddress),
      new PublicKey(preparation.configAddress), preparation);
    const partialSignatures = partial.signatures;
    if (partialSignatures[0]?.signature !== null || partialSignatures.slice(1).some(value => value.signature === null) ||
      !partial.verifySignatures(false)) throw new TokenLaunchError('signed_transaction_invalid', 422,
      'Stored Meteora partial signatures are invalid.');
    const raw = canonicalBase64(walletSignedTransactionBase64);
    // Privy OnDevice 0.15 emits one signature + the original message. TEE implementations may preserve all three slots.
    if (raw.length < 66 || (raw[0] !== 1 && raw[0] !== 3)) throw new TokenLaunchError('signed_transaction_invalid', 422,
      'Wallet returned an unsupported signature layout for the reviewed launch.');
    const signatureCount = raw[0];
    const walletSignature = raw.subarray(1, 65);
    const walletMessage = raw.subarray(1 + signatureCount * 64);
    const expectedMessage = partial.serializeMessage();
    if (!walletMessage.equals(expectedMessage)) throw new TokenLaunchError('signed_transaction_invalid', 422,
      'Wallet-signed message does not match the reviewed launch.');
    if (signatureCount === 3) {
      const storedSignatures = partialSignatures.map(value => value.signature);
      if (!storedSignatures[1] || !storedSignatures[2] ||
        !raw.subarray(65, 129).equals(storedSignatures[1]) || !raw.subarray(129, 193).equals(storedSignatures[2])) {
        throw new TokenLaunchError('signed_transaction_invalid', 422,
          'Wallet changed the backend-authorized Meteora signer slots.');
      }
    }
    const wallet = new PublicKey(preparation.walletAddress);
    if (!verifySignature(null, walletMessage, publicKeyForEd25519(wallet), walletSignature)) {
      throw new TokenLaunchError('signed_transaction_invalid', 422, 'Wallet signature is invalid for this launch.');
    }
    if (!hasExactLaunchSigners(partial.compileMessage(), preparation.walletAddress, preparation.mintAddress, preparation.configAddress)) {
      throw new TokenLaunchError('signed_transaction_invalid', 422, 'Stored launch signer set is invalid.');
    }
    partial.addSignature(wallet, walletSignature);
    if (!partial.verifySignatures(true)) throw new TokenLaunchError('signed_transaction_invalid', 422,
      'The combined Meteora transaction signatures are invalid.');
    const bytes = partial.serialize({ requireAllSignatures: true, verifySignatures: true });
    if (bytes.length > MAXIMUM_TRANSACTION_BYTES) throw new TokenLaunchError('signed_transaction_invalid', 422,
      'Signed launch transaction exceeds Solana limits.');
    return { bytes, base64: Buffer.from(bytes).toString('base64'), transactionId: bs58.encode(walletSignature) };
  }

  async preflight(preparation: TokenLaunchPreparation, signed: SignedLaunchTransaction): Promise<void> {
    await this.ensureMainnet();
    let transaction: Transaction;
    try { transaction = Transaction.from(signed.bytes); } catch { throw new TokenLaunchError('signed_transaction_invalid', 422,
      'Combined launch transaction is malformed.'); }
    if (!transaction.verifySignatures(true)) throw new TokenLaunchError('signed_transaction_invalid', 422,
      'Combined launch transaction signatures are invalid.');
    this.validateUnsignedTransaction(transaction, new PublicKey(preparation.walletAddress),
      new PublicKey(preparation.mintAddress), new PublicKey(preparation.configAddress), preparation);
    if (!transaction.serializeMessage().equals(Transaction.from(Buffer.from(preparation.transactionBase64, 'base64')).serializeMessage())) {
      throw new TokenLaunchError('signed_transaction_invalid', 422, 'Combined launch message changed after wallet signing.');
    }
    const currentHeight = await this.safeBlockHeight();
    if (currentHeight > Number(preparation.lastValidBlockHeight) || this.now() >= Date.parse(preparation.expiresAt))
      throw new TokenLaunchError('blockhash_expired', 422,
      'The reviewed launch expired before broadcast. Prepare it again.');
    let balance: number;
    try { balance = await this.connection.getBalance(new PublicKey(preparation.walletAddress), 'confirmed'); }
    catch { throw new TokenLaunchError('mainnet_rpc_unavailable', 503, 'Solana mainnet balance is temporarily unavailable.'); }
    if (balance < TOKEN_LAUNCH_REQUIRED_BALANCE_LAMPORTS) throw new TokenLaunchError('insufficient_mainnet_balance', 422,
      'At least 0.05 SOL is required at submission time. This is not a service fee.');
    const result = await this.simulate(transaction, new PublicKey(preparation.walletAddress), true, Number(preparation.observedSlot));
    if (!result.accounts?.[0]) throw new TokenLaunchError('simulation_failed', 422, 'Mainnet did not return the signed launch cost.');
    const debit = balance - result.accounts[0].lamports;
    if (!Number.isSafeInteger(debit) || debit < 0 || debit > TOKEN_LAUNCH_MAXIMUM_DEBIT_LAMPORTS ||
      BigInt(debit) > BigInt(preparation.maximumWalletDebitLamports)) {
      throw new TokenLaunchError('launch_cost_too_high', 422,
        'Signed launch exceeds the maximum wallet debit you reviewed. Nothing was submitted.');
    }
  }

  async broadcast(signed: SignedLaunchTransaction): Promise<boolean> {
    await this.ensureMainnet();
    try {
      const returned = await this.connection.sendRawTransaction(signed.bytes, {
        skipPreflight: false, preflightCommitment: 'confirmed', maxRetries: 3,
      });
      return returned === signed.transactionId;
    } catch { return false; }
  }

  async observe(launch: StoredTokenLaunch): Promise<TokenLaunchChainObservation> {
    await this.ensureMainnet();
    const currentHeight = await this.safeBlockHeight();
    if (!launch.transactionId) {
      if (currentHeight > Number(launch.preparation.lastValidBlockHeight)) return {
        state: 'expired', isFinalized: true, message: 'Prepared transaction expired before broadcast.',
      };
      return { state: 'committed', isFinalized: false, message: 'Waiting for the signed transaction.' };
    }
    let status;
    try { status = (await this.connection.getSignatureStatuses([launch.transactionId], { searchTransactionHistory: true })).value[0]; }
    catch { throw new TokenLaunchError('mainnet_rpc_unavailable', 503, 'Solana mainnet status is temporarily unavailable.'); }
    if (!status) {
      let transaction;
      try { transaction = await this.connection.getParsedTransaction(launch.transactionId,
        { commitment: 'finalized', maxSupportedTransactionVersion: 0 }); }
      catch { throw new TokenLaunchError('mainnet_rpc_unavailable', 503, 'Solana mainnet transaction lookup is temporarily unavailable.'); }
      if (transaction) {
        if (transaction.meta?.err) return { state: 'failed', isFinalized: true, message: 'Solana mainnet rejected the token launch.' };
        if (!transaction.meta) return { state: 'unknown', isFinalized: false, message: 'Finalized launch details are still being checked.' };
      } else {
        if (currentHeight <= Number(launch.preparation.lastValidBlockHeight) ||
          this.now() < Date.parse(launch.preparation.expiresAt) + MISSING_SIGNATURE_FINALITY_GRACE_MS) {
          return { state: 'unknown', isFinalized: false, message: 'Broadcast outcome is still being checked.' };
        }
        let history;
        try { history = await this.connection.getSignaturesForAddress(new PublicKey(launch.preparation.walletAddress),
          { limit: 1_000 }, 'finalized'); }
        catch { throw new TokenLaunchError('mainnet_rpc_unavailable', 503, 'Solana mainnet wallet history is temporarily unavailable.'); }
        const matching = history.find(row => row.signature === launch.transactionId);
        if (matching) return matching.err === null
          ? { state: 'unknown', isFinalized: false, message: 'Finalized launch details are still being checked.' }
          : { state: 'failed', isFinalized: true, message: 'Solana mainnet rejected the token launch.' };
        const coversPreparation = history.length > 0 &&
          history[history.length - 1]!.slot <= Number(launch.preparation.observedSlot);
        return coversPreparation
          ? { state: 'expired', isFinalized: true, message: 'Launch signature was absent from finalized wallet history.' }
          : { state: 'unknown', isFinalized: false, message: 'Wallet history does not yet cover this launch.' };
      }
    }
    if (status && status.confirmationStatus !== 'finalized') return {
      state: 'broadcast', isFinalized: false, message: 'Token launch is awaiting Solana mainnet finality.',
    };
    if (status?.err) return { state: 'failed', isFinalized: true, message: 'Solana mainnet rejected the token launch.' };
    const validState = await this.verifyCreatedState(launch.preparation);
    if (validState === 'pending') return { state: 'broadcast', isFinalized: false,
      message: 'Transaction confirmed; Meteora account state is still becoming available.' };
    if (validState === 'invalid') return { state: 'unknown', isFinalized: false,
      message: 'Launch finalized, but the expected Meteora token accounts need manual verification.' };
    return { state: 'confirmed', isFinalized: true, message: 'Token launch finalized on Solana mainnet.' };
  }

  private validateUnsignedTransaction(transaction: Transaction, wallet: PublicKey, mint: PublicKey, config: PublicKey,
    reviewed: Pick<TokenLaunchPrepareRequest, 'name' | 'symbol' | 'metadataUri' | 'totalTokenSupply' | 'feeBps' |
      'migrationThresholdSol' | 'percentageSupplyOnMigration'>): void {
    const message = transaction.compileMessage();
    if (!transaction.feePayer?.equals(wallet) || !hasExactLaunchSigners(message, wallet.toBase58(), mint.toBase58(), config.toBase58())) {
      throw new TokenLaunchError('invalid_launch', 422, 'Meteora transaction requested unexpected signers.');
    }
    if (transaction.instructions.length !== 2 || transaction.instructions.some(ix => !ix.programId.equals(DYNAMIC_BONDING_CURVE_PROGRAM_ID))) {
      throw new TokenLaunchError('invalid_launch', 422, 'Meteora transaction contained an unexpected program.');
    }
    const pool = deriveDbcPoolAddress(NATIVE_MINT, mint, config);
    const eventAuthority = deriveDbcEventAuthority();
    const program = DYNAMIC_BONDING_CURVE_PROGRAM_ID;
    const metadataProgram = new PublicKey('metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s');
    const key = (pubkey: PublicKey, isSigner = false, isWritable = false) => ({ pubkey, isSigner, isWritable });
    let configData: Buffer; let poolData: Buffer;
    try {
      const coder = this.client.state.getProgram().coder.instruction;
      configData = coder.encode('createConfig', { configParameters: reviewedCurve(reviewed) });
      poolData = coder.encode('initializeVirtualPoolWithSplToken', {
        params: { name: reviewed.name, symbol: reviewed.symbol, uri: reviewed.metadataUri },
      });
    } catch { throw new TokenLaunchError('invalid_launch', 422, 'Meteora launch instruction encoding is unsupported.'); }
    const canonical = new Transaction({ feePayer: wallet, recentBlockhash: transaction.recentBlockhash });
    canonical.add(
      new TransactionInstruction({ programId: program, data: configData, keys: [
        key(config, true, true), key(wallet), key(wallet), key(NATIVE_MINT), key(wallet, true, true),
        key(SystemProgram.programId), key(eventAuthority), key(program),
      ] }),
      new TransactionInstruction({ programId: program, data: poolData, keys: [
        key(config), key(deriveDbcPoolAuthority()), key(wallet, true), key(mint, true, true),
        key(NATIVE_MINT), key(pool, false, true), key(deriveDbcTokenVaultAddress(pool, mint), false, true),
        key(deriveDbcTokenVaultAddress(pool, NATIVE_MINT), false, true), key(deriveMintMetadata(mint), false, true),
        key(metadataProgram), key(wallet, true, true), key(TOKEN_PROGRAM_ID), key(TOKEN_PROGRAM_ID),
        key(SystemProgram.programId), key(eventAuthority), key(program),
      ] }),
    );
    if (!canonical.serializeMessage().equals(transaction.serializeMessage())) {
      throw new TokenLaunchError('invalid_launch', 422,
        'Meteora launch accounts, instructions, or reviewed economics do not match the expected token creation.');
    }
  }

  private async simulate(transaction: Transaction, wallet: PublicKey, verify: boolean, minimumSlot: number) {
    try {
      const result = await this.connection.simulateTransaction(versionedTransaction(transaction), {
        sigVerify: verify, replaceRecentBlockhash: false, commitment: 'confirmed', minContextSlot: minimumSlot,
        accounts: { encoding: 'base64', addresses: [wallet.toBase58()] },
      });
      if (result.value.err !== null) throw new Error('simulation error');
      return result.value;
    } catch { throw new TokenLaunchError('simulation_failed', 422,
      'Meteora launch simulation failed on Solana mainnet. Nothing was submitted.'); }
  }

  private async verifyCreatedState(preparation: TokenLaunchPreparation): Promise<'valid' | 'pending' | 'invalid'> {
    const mint = new PublicKey(preparation.mintAddress); const config = new PublicKey(preparation.configAddress);
    const pool = new PublicKey(preparation.poolAddress);
    let accounts;
    try { accounts = await this.connection.getMultipleAccountsInfo([mint, config, pool], 'confirmed'); }
    catch { throw new TokenLaunchError('mainnet_rpc_unavailable', 503, 'Solana mainnet account verification is temporarily unavailable.'); }
    if (!accounts[0] || !accounts[1] || !accounts[2]) return 'pending';
    if (!accounts[0].owner.equals(TOKEN_PROGRAM_ID) || !accounts[1].owner.equals(DYNAMIC_BONDING_CURVE_PROGRAM_ID) ||
      !accounts[2].owner.equals(DYNAMIC_BONDING_CURVE_PROGRAM_ID)) return 'invalid';
    let mintState;
    try { mintState = unpackMint(mint, accounts[0], TOKEN_PROGRAM_ID); } catch { return 'invalid'; }
    if (mintState.decimals !== 6 || mintState.mintAuthority !== null || mintState.freezeAuthority !== null ||
      mintState.supply !== expectedSupply(preparation.totalTokenSupply)) return 'invalid';
    try {
      const [configState, poolState] = await Promise.all([this.client.state.getPoolConfig(config), this.client.state.getPool(pool)]);
      if (!configState || !poolState) return 'pending';
      return matchesMeteoraLaunchState(preparation, configState, poolState) ? 'valid' : 'invalid';
    } catch { throw new TokenLaunchError('mainnet_rpc_unavailable', 503,
      'Meteora mainnet state verification is temporarily unavailable.'); }
  }

  private async ensureMainnet(): Promise<void> {
    if (!this.rpcConfigured) throw new TokenLaunchError('mainnet_rpc_unavailable', 503,
      'Mainnet launches require a configured SOLANA_RPC_URL. Nothing was submitted.');
    try {
      if (await this.connection.getGenesisHash() !== SOLANA_MAINNET_GENESIS_HASH) throw new Error('wrong cluster');
    } catch { throw new TokenLaunchError('mainnet_rpc_unavailable', 503,
      'Configured Solana RPC is unavailable or is not mainnet.'); }
  }
  private async safeBlockHeight(): Promise<number> {
    try { return await this.connection.getBlockHeight('confirmed'); }
    catch { throw new TokenLaunchError('mainnet_rpc_unavailable', 503, 'Solana mainnet block height is temporarily unavailable.'); }
  }
}
