import { z } from 'zod';
import { isPublicHttpsUrl } from './public-https.js';

const SolanaAddress = z.string().min(32).max(44).regex(/^[1-9A-HJ-NP-Za-km-z]+$/);
const WalletId = z.string().min(1).max(200).regex(/^[^\s\u0000-\u001f\u007f]+$/);
const IntegerAmount = z.string().min(1).max(16).regex(/^[1-9]\d*$/).refine(value => {
  const amount = BigInt(value);
  return amount >= 1n && amount <= 1_000_000_000_000n;
}, 'Total token supply is outside the supported range');
const SolAmount = z.string().min(1).max(32).regex(/^(?:0|[1-9]\d*)(?:\.\d{1,9})?$/).refine(value => {
  const [whole = '0', fraction = ''] = value.split('.');
  const lamports = BigInt(whole) * 1_000_000_000n + BigInt(fraction.padEnd(9, '0'));
  return lamports >= 10_000_000n && lamports <= 10_000_000_000_000n;
}, 'Migration threshold must be between 0.01 and 10,000 SOL');
const PublicMetadataUri = z.string().min(12).max(200).refine(value => {
  if (/^ipfs:\/\/[A-Za-z0-9]{32,}(?:\/[A-Za-z0-9._~!$&'()*+,;=:@%/-]*)?$/.test(value)) return true;
  return isPublicHttpsUrl(value);
}, 'Metadata URI must be a public HTTPS or IPFS URI');
export const TokenName = z.string().min(1).max(32).refine(value => Buffer.byteLength(value, 'utf8') <= 32 &&
  !/[\u0000-\u001f\u007f]/.test(value) && value.trim() === value, 'Token name must be at most 32 UTF-8 bytes');
export const TokenSymbol = z.string().min(1).max(10).regex(/^[A-Z0-9]{1,10}$/);
const Hash = z.string().length(64).regex(/^[a-f0-9]{64}$/);
const Iso = z.iso.datetime();
const Lamports = z.string().regex(/^\d+$/).max(20);
const Signature = z.string().min(80).max(88).regex(/^[1-9A-HJ-NP-Za-km-z]+$/);
const Base64Transaction = z.string().min(100).max(4_096).regex(/^[A-Za-z0-9+/]+={0,2}$/);

export const TokenLaunchIdentitySchema = z.object({
  schemaVersion: z.literal(1), operationId: z.uuid(), walletId: WalletId, walletAddress: SolanaAddress,
  transactionSha256: Hash,
}).strict();
export type TokenLaunchIdentity = z.infer<typeof TokenLaunchIdentitySchema>;

export const TokenLaunchPrepareRequestSchema = z.object({
  schemaVersion: z.literal(1), operationId: z.uuid(), network: z.literal('SOLANA_MAINNET'),
  walletId: WalletId, walletAddress: SolanaAddress,
  name: TokenName, symbol: TokenSymbol, metadataUri: PublicMetadataUri,
  totalTokenSupply: IntegerAmount, feeBps: z.number().int().min(25).max(1_000),
  migrationThresholdSol: SolAmount, percentageSupplyOnMigration: z.number().int().min(1).max(90),
}).strict();
export type TokenLaunchPrepareRequest = z.infer<typeof TokenLaunchPrepareRequestSchema>;

export const TokenLaunchPreparationSchema = z.object({
  schemaVersion: z.literal(1), operationId: z.uuid(), network: z.literal('SOLANA_MAINNET'),
  walletId: WalletId, walletAddress: SolanaAddress,
  name: TokenName, symbol: TokenSymbol, metadataUri: PublicMetadataUri,
  programId: SolanaAddress, quoteMint: SolanaAddress, mintAddress: SolanaAddress,
  configAddress: SolanaAddress, poolAddress: SolanaAddress,
  totalTokenSupply: IntegerAmount, tokenDecimals: z.literal(6), feeBps: z.number().int().min(25).max(1_000),
  migrationThresholdSol: SolAmount, percentageSupplyOnMigration: z.number().int().min(1).max(90),
  migrationTarget: z.literal('METEORA_DAMM_V2'), tokenAuthority: z.literal('IMMUTABLE'),
  dynamicFeeEnabled: z.literal(false), creatorPermanentLockedLiquidityPercentage: z.literal(100),
  estimatedFeeLamports: Lamports, maximumWalletDebitLamports: Lamports, balanceLamports: Lamports,
  simulationUnitsConsumed: z.number().int().nonnegative().max(1_400_000), observedSlot: Lamports,
  recentBlockhash: SolanaAddress, lastValidBlockHeight: Lamports,
  preparedAt: Iso, expiresAt: Iso, transactionSha256: Hash,
  transactionBase64: Base64Transaction, encoding: z.literal('base64-legacy-partially-signed'),
}).strict();
export type TokenLaunchPreparation = z.infer<typeof TokenLaunchPreparationSchema>;

export const TokenLaunchCommitRequestSchema = TokenLaunchIdentitySchema;
export type TokenLaunchCommitRequest = z.infer<typeof TokenLaunchCommitRequestSchema>;
export const TokenLaunchReleaseRequestSchema = TokenLaunchIdentitySchema.extend({
  reason: z.enum(['provider_definitely_not_invoked', 'wallet_signature_not_returned',
    'signed_transaction_definitely_not_submitted']),
}).strict();
export type TokenLaunchReleaseRequest = z.infer<typeof TokenLaunchReleaseRequestSchema>;
export const TokenLaunchMutationResponseSchema = z.object({
  schemaVersion: z.literal(1), operationId: z.uuid(), state: z.enum(['committed', 'released']), idempotent: z.boolean(),
}).strict();
export type TokenLaunchMutationResponse = z.infer<typeof TokenLaunchMutationResponseSchema>;

export const TokenLaunchSubmitRequestSchema = TokenLaunchIdentitySchema.extend({
  walletSignedTransactionBase64: Base64Transaction,
}).strict();
export type TokenLaunchSubmitRequest = z.infer<typeof TokenLaunchSubmitRequestSchema>;
export const TokenLaunchSubmitResponseSchema = z.object({
  schemaVersion: z.literal(1), operationId: z.uuid(), state: z.enum(['broadcast', 'unknown']),
  transactionId: Signature, idempotent: z.boolean(), submittedAt: Iso,
}).strict();
export type TokenLaunchSubmitResponse = z.infer<typeof TokenLaunchSubmitResponseSchema>;

export const TokenLaunchStatusRequestSchema = TokenLaunchIdentitySchema;
export type TokenLaunchStatusRequest = z.infer<typeof TokenLaunchStatusRequestSchema>;
export const TokenLaunchStatusSchema = z.object({
  schemaVersion: z.literal(1), operationId: z.uuid(),
  state: z.enum(['committed', 'broadcast', 'confirmed', 'failed', 'expired', 'unknown']),
  transactionId: Signature.nullable(), isFinalized: z.boolean(), message: z.string().min(1).max(240).nullable(),
  observedAt: Iso, mintAddress: SolanaAddress, configAddress: SolanaAddress, poolAddress: SolanaAddress,
  explorerUrl: z.string().url().max(300),
}).strict();
export type TokenLaunchStatus = z.infer<typeof TokenLaunchStatusSchema>;
