import { z } from 'zod';
import { TransferAssetId, TransferChain } from './transfer-schema.js';

const EthereumHash = z.string().regex(/^0x[0-9a-fA-F]{64}$/);
const SolanaSignature = z.string().min(64).max(88).regex(/^[1-9A-HJ-NP-Za-km-z]+$/);
export const TransferStatusRequestSchema = z.object({
  schemaVersion: z.literal(1),
  operationId: z.uuid(),
  walletId: z.string().min(1).max(200).regex(/^[^\s\u0000-\u001f\u007f]+$/),
  chain: TransferChain,
  sender: z.string().min(32).max(44),
  recipient: z.string().min(32).max(44),
  assetId: TransferAssetId,
  baseUnits: z.string().min(1).max(78).regex(/^(?:0|[1-9]\d*)$/),
  transactionId: z.string().min(64).max(88),
}).strict().superRefine((value, context) => {
  const valid = value.chain === 'ETHEREUM' ? EthereumHash.safeParse(value.transactionId).success : SolanaSignature.safeParse(value.transactionId).success;
  if (!valid) context.addIssue({ code: 'custom', path: ['transactionId'], message: 'Transaction identifier does not match chain' });
  if (!value.assetId.startsWith(`${value.chain}:`)) {
    context.addIssue({ code: 'custom', path: ['assetId'], message: 'Asset and chain do not match' });
  }
});
export type TransferStatusRequest = z.infer<typeof TransferStatusRequestSchema>;

const UnsignedInteger = z.string().min(1).max(78).regex(/^(?:0|[1-9]\d*)$/);
const Common = z.object({
  schemaVersion: z.literal(1),
  operationId: z.uuid(),
  chain: TransferChain,
  caip2: z.enum(['eip155:1', 'solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp']),
  transactionId: z.string(),
  sender: z.string(),
  senderVerified: z.boolean().nullable(),
  status: z.enum(['pending', 'confirmed', 'finalized', 'failed', 'unknown']),
  confirmations: UnsignedInteger.nullable(),
  isFinalized: z.boolean(),
  observedAt: z.iso.datetime(),
}).strict();

const EthereumStatus = Common.extend({
  chain: z.literal('ETHEREUM'),
  caip2: z.literal('eip155:1'),
  blockNumber: UnsignedInteger.nullable(),
  blockHash: EthereumHash.nullable(),
  blockTime: z.iso.datetime().nullable(),
  finalizedBlockNumber: UnsignedInteger.nullable(),
  failureCode: z.literal('execution_reverted').nullable(),
}).strict().superRefine((value, context) => {
  const included = ['confirmed', 'finalized', 'failed'].includes(value.status);
  if (included !== (value.blockNumber !== null && value.blockHash !== null && value.blockTime !== null && value.confirmations !== null)) {
    context.addIssue({ code: 'custom', message: 'Ethereum inclusion metadata does not match status' });
  }
  if ((value.status === 'unknown') !== (value.senderVerified === null)) context.addIssue({ code: 'custom', message: 'Ethereum sender verification does not match status' });
  if (value.senderVerified === false) context.addIssue({ code: 'custom', message: 'Sender mismatch must fail closed' });
  if (value.isFinalized !== (value.status === 'finalized' || (value.status === 'failed' && value.finalizedBlockNumber !== null && value.blockNumber !== null && BigInt(value.blockNumber) <= BigInt(value.finalizedBlockNumber)))) {
    context.addIssue({ code: 'custom', message: 'Ethereum finality does not match status' });
  }
  if ((value.status === 'failed') !== (value.failureCode !== null)) context.addIssue({ code: 'custom', message: 'Ethereum failure metadata does not match status' });
});

const SolanaStatus = Common.extend({
  chain: z.literal('SOLANA'),
  caip2: z.literal('solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp'),
  observedSlot: UnsignedInteger,
  slot: UnsignedInteger.nullable(),
  blockTime: z.iso.datetime().nullable(),
  confirmationStatus: z.enum(['processed', 'confirmed', 'finalized']).nullable(),
  failureCode: z.literal('transaction_error').nullable(),
}).strict().superRefine((value, context) => {
  if ((value.status === 'unknown') !== (value.slot === null && value.confirmationStatus === null && value.confirmations === null && value.senderVerified === null)) {
    context.addIssue({ code: 'custom', message: 'Solana unknown metadata does not match status' });
  }
  if (value.status !== 'unknown' && (value.slot === null || value.confirmationStatus === null)) context.addIssue({ code: 'custom', message: 'Solana observed status requires slot metadata' });
  if (value.senderVerified === false) context.addIssue({ code: 'custom', message: 'Sender mismatch must fail closed' });
  if (['confirmed', 'finalized', 'failed'].includes(value.status) && value.senderVerified !== true) {
    context.addIssue({ code: 'custom', message: 'Solana confirmed status requires exact transfer verification' });
  }
  if (value.confirmationStatus !== null && value.confirmationStatus !== 'finalized' && value.confirmations === null) context.addIssue({ code: 'custom', message: 'Non-finalized Solana status requires confirmation count' });
  if (value.isFinalized !== (value.confirmationStatus === 'finalized')) context.addIssue({ code: 'custom', message: 'Solana finality does not match confirmation status' });
  if ((value.status === 'failed') !== (value.failureCode !== null)) context.addIssue({ code: 'custom', message: 'Solana failure metadata does not match status' });
  if ((value.status === 'pending') !== (value.confirmationStatus === 'processed' && value.failureCode === null)) context.addIssue({ code: 'custom', message: 'Solana pending metadata does not match status' });
  if ((value.status === 'confirmed') !== (value.confirmationStatus === 'confirmed' && value.failureCode === null)) context.addIssue({ code: 'custom', message: 'Solana confirmed metadata does not match status' });
  if ((value.status === 'finalized') !== (value.confirmationStatus === 'finalized' && value.failureCode === null)) context.addIssue({ code: 'custom', message: 'Solana finalized metadata does not match status' });
});

export const TransferStatusSchema = z.union([EthereumStatus, SolanaStatus]);
export type TransferStatus = z.infer<typeof TransferStatusSchema>;
