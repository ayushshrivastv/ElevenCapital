import { z } from 'zod';
import { isSolanaAddress } from './portfolio-schema.js';

const Address = z.string().min(32).max(44).refine(isSolanaAddress);
const WalletId = z.string().min(1).max(200).regex(/^[^\s\u0000-\u001f\u007f]+$/);
const Lamports = z.string().regex(/^(?:0|[1-9]\d*)$/).max(20);
const BuyLamports = Lamports.refine(value => BigInt(value) >= 100_000n && BigInt(value) <= 1_000_000_000n,
  'Mainnet buys must be between 0.0001 and 1 SOL');
const Hash = z.string().length(64).regex(/^[a-f0-9]{64}$/);
const Signature = z.string().min(80).max(88).regex(/^[1-9A-HJ-NP-Za-km-z]+$/);
const Transaction = z.string().min(100).max(4_096).regex(/^[A-Za-z0-9+/]+={0,2}$/);
const Iso = z.iso.datetime();

export const CommunityBuyPrepareRequestSchema = z.object({
  schemaVersion: z.literal(1), operationId: z.uuid(), network: z.literal('SOLANA_MAINNET'),
  walletId: WalletId, walletAddress: Address, mintAddress: Address,
  amountInLamports: BuyLamports, slippageBps: z.number().int().min(1).max(500),
}).strict();
export type CommunityBuyPrepareRequest = z.infer<typeof CommunityBuyPrepareRequestSchema>;

export const CommunityBuyPreparationSchema = CommunityBuyPrepareRequestSchema.extend({
  programId: Address, quoteMint: Address, poolAddress: Address, configAddress: Address,
  tokenDecimals: z.number().int().min(0).max(18), expectedAmountOut: Lamports,
  minimumAmountOut: Lamports, estimatedFeeLamports: Lamports,
  maximumWalletDebitLamports: Lamports, balanceLamports: Lamports,
  simulationUnitsConsumed: z.number().int().nonnegative().max(1_400_000), observedSlot: Lamports,
  recentBlockhash: Address, lastValidBlockHeight: Lamports, preparedAt: Iso, expiresAt: Iso,
  transactionSha256: Hash, transactionBase64: Transaction,
  encoding: z.literal('base64-legacy-unsigned'),
}).strict();
export type CommunityBuyPreparation = z.infer<typeof CommunityBuyPreparationSchema>;

export const CommunityBuyIdentitySchema = z.object({
  schemaVersion: z.literal(1), operationId: z.uuid(), walletId: WalletId,
  walletAddress: Address, transactionSha256: Hash,
}).strict();
export type CommunityBuyIdentity = z.infer<typeof CommunityBuyIdentitySchema>;
export const CommunityBuyReleaseRequestSchema = CommunityBuyIdentitySchema.extend({
  reason: z.enum(['provider_definitely_not_invoked', 'wallet_signature_not_returned',
    'signed_transaction_definitely_not_submitted']),
}).strict();
export type CommunityBuyReleaseRequest = z.infer<typeof CommunityBuyReleaseRequestSchema>;
export const CommunityBuySubmitRequestSchema = CommunityBuyIdentitySchema.extend({
  walletSignedTransactionBase64: Transaction,
}).strict();
export type CommunityBuySubmitRequest = z.infer<typeof CommunityBuySubmitRequestSchema>;
export const CommunityBuyMutationResponseSchema = z.object({
  schemaVersion: z.literal(1), operationId: z.uuid(), state: z.enum(['committed', 'released']), idempotent: z.boolean(),
}).strict();
export type CommunityBuyMutationResponse = z.infer<typeof CommunityBuyMutationResponseSchema>;
export const CommunityBuySubmitResponseSchema = z.object({
  schemaVersion: z.literal(1), operationId: z.uuid(), state: z.enum(['broadcast', 'unknown']),
  transactionId: Signature, idempotent: z.boolean(), submittedAt: Iso,
}).strict();
export type CommunityBuySubmitResponse = z.infer<typeof CommunityBuySubmitResponseSchema>;
export const CommunityBuyStatusSchema = z.object({
  schemaVersion: z.literal(1), operationId: z.uuid(), network: z.literal('SOLANA_MAINNET'),
  state: z.enum(['committed', 'broadcast', 'confirmed', 'failed', 'expired', 'unknown']),
  transactionId: Signature.nullable(), isFinalized: z.boolean(), message: z.string().min(1).max(240).nullable(),
  observedAt: Iso, walletAddress: Address, mintAddress: Address, poolAddress: Address,
  amountInLamports: BuyLamports, tokenAmountReceived: Lamports.nullable(),
  walletTokenBalanceRaw: Lamports.nullable(),
  explorerUrl: z.string().url().max(300),
}).strict();
export type CommunityBuyStatus = z.infer<typeof CommunityBuyStatusSchema>;
