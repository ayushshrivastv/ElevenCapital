import { z } from 'zod';

export const TransferChain = z.literal('SOLANA');
export type TransferChain = z.infer<typeof TransferChain>;
export const TransferAssetId = z.enum(['SOLANA:native', 'SOLANA:USDC']);
export type TransferAssetId = z.infer<typeof TransferAssetId>;

/** Canonical user-entered decimal: no exponent, sign, separators, whitespace or implicit rounding. */
export const TransferAmount = z.string().min(1).max(80).regex(/^(?:0|[1-9]\d*)(?:\.\d+)?$/);
export const TransferRequestSchema = z.object({
  schemaVersion: z.literal(1),
  operationId: z.uuid(),
  walletId: z.string().min(1).max(200).regex(/^[^\s\u0000-\u001f\u007f]+$/),
  chain: TransferChain,
  sender: z.string().min(32).max(44),
  recipient: z.string().min(32).max(44),
  assetId: TransferAssetId,
  amount: TransferAmount,
}).strict().refine(value => value.assetId.startsWith(`${value.chain}:`), 'Asset and chain do not match');
export type TransferRequest = z.infer<typeof TransferRequestSchema>;

const UnsignedInteger = z.string().min(1).max(78).regex(/^(?:0|[1-9]\d*)$/);
const HexQuantity = z.string().regex(/^0x(?:0|[1-9a-f][0-9a-f]*)$/);
const Asset = z.object({
  id: TransferAssetId,
  symbol: z.enum(['USDC', 'SOL']),
  kind: z.enum(['native', 'spl']),
  address: z.string().nullable(),
  decimals: z.union([z.literal(6), z.literal(9), z.literal(18)]),
}).strict();
const Common = z.object({
  schemaVersion: z.literal(1),
  operationId: z.uuid(),
  chain: TransferChain,
  caip2: z.literal('solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp'),
  asset: Asset,
  sender: z.string(),
  recipient: z.string(),
  amount: TransferAmount,
  baseUnits: UnsignedInteger,
  balanceBaseUnits: UnsignedInteger,
  estimatedFeeBaseUnits: UnsignedInteger,
  maxFeeBaseUnits: UnsignedInteger,
  preparedAt: z.iso.datetime(),
  expiresAt: z.iso.datetime(),
}).strict();

export const TransferPreparationSchema = Common.extend({
    chain: z.literal('SOLANA'),
    caip2: z.literal('solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp'),
    observedSlot: UnsignedInteger,
    recentBlockhash: z.string().min(32).max(44),
    lastValidBlockHeight: UnsignedInteger,
    transactionBase64: z.string().min(1).max(2_000),
    encoding: z.literal('base64'),
  }).strict();
export type TransferPreparation = z.infer<typeof TransferPreparationSchema>;
