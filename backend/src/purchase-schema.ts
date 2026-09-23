import { z } from 'zod';
import { decodeSignature } from './transfer-status.js';

const SolanaSignature = z.string().min(80).max(88).refine(value => {
  try { decodeSignature(value); return true; } catch { return false; }
});

export const PurchaseNetworkSchema = z.enum(['ETHEREUM', 'BASE', 'ARBITRUM', 'SOLANA']);
export type PurchaseNetwork = z.infer<typeof PurchaseNetworkSchema>;

export const PurchaseWalletSchema = z.discriminatedUnion('chain', [
  z.object({ chain: z.literal('ETHEREUM'), address: z.string().regex(/^0x[0-9a-fA-F]{40}$/) }).strict(),
  z.object({ chain: z.literal('SOLANA'), address: z.string().min(32).max(44).regex(/^[1-9A-HJ-NP-Za-km-z]+$/) }).strict(),
]);
export type PurchaseWallet = z.infer<typeof PurchaseWalletSchema>;

const WalletsSchema = z.array(PurchaseWalletSchema).min(1).max(2).superRefine((wallets, context) => {
  const chains = new Set(wallets.map(wallet => wallet.chain));
  if (chains.size !== wallets.length) context.addIssue({ code: 'custom', message: 'Wallet chains must be unique.' });
});

const StockId = z.string().min(8).max(100).regex(/^(backed|backpack|prestocks):[A-Za-z0-9.-]+$/);
const AssetId = z.string().min(5).max(100).regex(/^(?:(?:ETHEREUM|BASE|ARBITRUM|SOLANA):(ETH|SOL|USDC)|SOLANA:[1-9A-HJ-NP-Za-km-z]{32,44})$/);
const DestinationId = z.string().min(8).max(180).regex(/^(ETHEREUM|BASE|ARBITRUM|SOLANA):[A-Za-z0-9]+$/);
const BaseUnits = z.string().max(100).regex(/^[1-9]\d*$/);
const DecimalAmount = z.string().max(100).regex(/^(?:0|[1-9]\d*)(?:\.\d+)?$/);
const NullableDecimalAmount = DecimalAmount.nullable();
const Iso = z.iso.datetime();
const ChainId = z.enum(['1', '8453', '42161', '1151111081099710']);

export const PurchaseOptionsRequestSchema = z.object({
  schemaVersion: z.literal(1), stockId: StockId, side: z.enum(['BUY', 'SELL']).optional(), wallets: WalletsSchema,
}).strict();
export type PurchaseOptionsRequest = z.infer<typeof PurchaseOptionsRequestSchema>;

export const PaymentAssetSchema = z.object({
  id: AssetId, symbol: z.string().regex(/^[A-Za-z0-9._-]{1,30}$/), name: z.string().min(1).max(80),
  network: PurchaseNetworkSchema, chainId: ChainId, address: z.string().min(1).max(64), decimals: z.number().int().min(0).max(36),
  balanceBaseUnits: z.string().max(100).regex(/^\d+$/), balance: DecimalAmount,
  usdValue: NullableDecimalAmount, enabled: z.boolean(),
  uiMultiplier: DecimalAmount.optional(),
}).strict();
export type PaymentAsset = z.infer<typeof PaymentAssetSchema>;

export const PurchaseDestinationSchema = z.object({
  id: DestinationId, network: PurchaseNetworkSchema, chainId: ChainId, address: z.string().min(1).max(64),
  symbol: z.string().min(1).max(30), decimals: z.number().int().min(0).max(36), enabled: z.boolean(),
  uiMultiplier: DecimalAmount.optional(),
}).strict();
export type PurchaseDestination = z.infer<typeof PurchaseDestinationSchema>;

export const PurchaseOptionsResponseSchema = z.object({
  schemaVersion: z.literal(1), stockId: StockId, purchasable: z.boolean(), reason: z.string().min(1).max(240).nullable(),
  executionEnabled: z.boolean(), executionReason: z.string().min(1).max(240).nullable(),
  paymentAssets: z.array(PaymentAssetSchema).max(8), destinations: z.array(PurchaseDestinationSchema).max(4),
  defaultPaymentAssetId: AssetId.nullable(), defaultDestinationId: DestinationId.nullable(),
}).strict();
export type PurchaseOptionsResponse = z.infer<typeof PurchaseOptionsResponseSchema>;

export const PurchaseQuoteRequestSchema = z.object({
  schemaVersion: z.literal(1), operationId: z.uuid(), stockId: StockId, side: z.enum(['BUY', 'SELL']).optional(), fromAssetId: AssetId,
  destinationId: DestinationId.nullable(), amountBaseUnits: BaseUnits,
  slippageBps: z.number().int().min(1).max(500), wallets: WalletsSchema,
}).strict();
export type PurchaseQuoteRequest = z.infer<typeof PurchaseQuoteRequestSchema>;

const HexData = z.string().min(2).max(200_002).regex(/^0x(?:[0-9a-fA-F]{2})*$/);
const HexQuantity = z.string().min(3).max(80).regex(/^0x(?:0|[1-9a-fA-F][0-9a-fA-F]*)$/);
const EvmTransactionSchema = z.object({
  from: z.string().regex(/^0x[0-9a-fA-F]{40}$/), to: z.string().regex(/^0x[0-9a-fA-F]{40}$/),
  data: HexData, value: HexQuantity, gas: HexQuantity.nullable(), gasPrice: HexQuantity.nullable(),
  maxFeePerGas: HexQuantity.nullable(), maxPriorityFeePerGas: HexQuantity.nullable(), nonce: HexQuantity.nullable(),
}).strict();

export const EvmPurchaseActionSchema = z.object({
  id: z.uuid(), index: z.number().int().min(0).max(3), type: z.enum(['EVM_APPROVAL', 'EVM_ROUTE']),
  network: z.enum(['ETHEREUM', 'BASE', 'ARBITRUM']), chainId: z.enum(['1', '8453', '42161']),
  walletAddress: z.string().regex(/^0x[0-9a-fA-F]{40}$/), transaction: EvmTransactionSchema,
}).strict();

export const SolanaPurchaseActionSchema = z.object({
  id: z.uuid(), index: z.number().int().min(0).max(3), type: z.literal('SOLANA_ROUTE'),
  network: z.literal('SOLANA'), chainId: z.literal('1151111081099710'),
  walletAddress: z.string().min(32).max(44).regex(/^[1-9A-HJ-NP-Za-km-z]+$/),
  transactionBase64: z.string().min(16).max(5_464).regex(/^[A-Za-z0-9+/]+={0,2}$/),
  minContextSlot: z.string().regex(/^\d+$/).nullable(), lastValidBlockHeight: z.string().regex(/^\d+$/).nullable(),
}).strict();

export const PurchaseActionSchema = z.discriminatedUnion('type', [
  EvmPurchaseActionSchema.extend({ type: z.literal('EVM_APPROVAL') }).strict(),
  EvmPurchaseActionSchema.extend({ type: z.literal('EVM_ROUTE') }).strict(),
  SolanaPurchaseActionSchema,
]);
export type PurchaseAction = z.infer<typeof PurchaseActionSchema>;

export const PurchaseQuoteResponseSchema = z.object({
  schemaVersion: z.literal(1), operationId: z.uuid(), quoteId: z.uuid(), stockId: StockId, fromAssetId: AssetId, destinationId: DestinationId,
  inputAmount: DecimalAmount, inputBaseUnits: BaseUnits,
  estimatedOutputAmount: DecimalAmount, estimatedOutputBaseUnits: BaseUnits,
  outputDecimals: z.number().int().min(0).max(36).optional(), outputUiMultiplier: DecimalAmount.optional(),
  executableUnitPriceUsd: NullableDecimalAmount, feesUsd: NullableDecimalAmount, priceImpactPercent: NullableDecimalAmount,
  slippageBps: z.number().int().min(1).max(500), minimumReceived: DecimalAmount, minimumReceivedBaseUnits: BaseUnits,
  expiresAt: Iso, walletConfirmations: z.number().int().min(1).max(4), actions: z.array(PurchaseActionSchema).min(1).max(4),
  executionEnabled: z.boolean(), executionReason: z.string().min(1).max(240).nullable(),
}).strict();
export type PurchaseQuoteResponse = z.infer<typeof PurchaseQuoteResponseSchema>;

export const PurchaseCommitRequestSchema = z.object({ schemaVersion: z.literal(1) }).strict();
export type PurchaseCommitRequest = z.infer<typeof PurchaseCommitRequestSchema>;
export const PurchaseCommitResponseSchema = z.object({
  schemaVersion: z.literal(1), quoteId: z.uuid(), state: z.literal('COMMITTED'), idempotent: z.boolean(),
}).strict();
export type PurchaseCommitResponse = z.infer<typeof PurchaseCommitResponseSchema>;

export const PurchaseInvokingRequestSchema = z.object({ schemaVersion: z.literal(1) }).strict();
export type PurchaseInvokingRequest = z.infer<typeof PurchaseInvokingRequestSchema>;
export const PurchaseInvokingResponseSchema = z.object({
  schemaVersion: z.literal(1), quoteId: z.uuid(), actionId: z.uuid(), state: z.literal('INVOKING'), idempotent: z.boolean(),
}).strict();
export type PurchaseInvokingResponse = z.infer<typeof PurchaseInvokingResponseSchema>;
export const PurchaseInvocationReleaseRequestSchema = z.object({
  schemaVersion: z.literal(1), reason: z.literal('provider_definitely_not_invoked'),
}).strict();
export type PurchaseInvocationReleaseRequest = z.infer<typeof PurchaseInvocationReleaseRequestSchema>;

export const PurchaseSubmittedRequestSchema = z.object({
  schemaVersion: z.literal(1), transactionId: z.string().min(32).max(128).regex(/^(?:0x[0-9a-fA-F]{64}|[1-9A-HJ-NP-Za-km-z]{32,128})$/),
}).strict();
export type PurchaseSubmittedRequest = z.infer<typeof PurchaseSubmittedRequestSchema>;

export const PurchaseStatusSchema = z.object({
  schemaVersion: z.literal(1), quoteId: z.uuid(),
  state: z.enum(['COMMITTED', 'EXECUTING', 'COMPLETED', 'FAILED', 'EXPIRED']),
  step: z.number().int().min(0).max(4), stepCount: z.number().int().min(1).max(4),
  transactionIds: z.array(z.string().min(32).max(128)).max(4), receivedAmount: DecimalAmount.nullable(),
  solanaTransactionSignature: SolanaSignature.nullable().optional(),
  message: z.string().min(1).max(240).nullable(), updatedAt: Iso,
}).strict();
export type PurchaseStatus = z.infer<typeof PurchaseStatusSchema>;

export const PurchaseIdParamsSchema = z.object({ quoteId: z.uuid() }).strict();
export const PurchaseActionParamsSchema = z.object({ quoteId: z.uuid(), actionId: z.uuid() }).strict();
