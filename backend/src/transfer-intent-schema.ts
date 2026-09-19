import { z } from 'zod';
import { TransferAssetId, TransferChain } from './transfer-schema.js';

export const WalletId = z.string().min(1).max(200).regex(/^[^\s\u0000-\u001f\u007f]+$/);
const BaseUnits = z.string().min(1).max(78).regex(/^[1-9]\d*$/);
const IntentIdentity = z.object({
  schemaVersion: z.literal(1),
  operationId: z.uuid(),
  walletId: WalletId,
  chain: TransferChain,
  sender: z.string().min(32).max(44),
  recipient: z.string().min(32).max(44),
  assetId: TransferAssetId,
  baseUnits: BaseUnits,
}).strict().refine(value => value.assetId.startsWith(`${value.chain}:`), 'Asset and chain do not match');

export const TransferIntentCommitSchema = IntentIdentity;
export type TransferIntentCommit = z.infer<typeof TransferIntentCommitSchema>;
export const TransferIntentReleaseSchema = IntentIdentity.extend({
  reason: z.literal('provider_definitely_not_broadcast'),
}).strict();
export type TransferIntentRelease = z.infer<typeof TransferIntentReleaseSchema>;

export const TransferIntentMutationResponseSchema = z.object({
  schemaVersion: z.literal(1),
  operationId: z.uuid(),
  state: z.enum(['committed', 'released']),
  idempotent: z.boolean(),
}).strict();
export type TransferIntentMutationResponse = z.infer<typeof TransferIntentMutationResponseSchema>;
