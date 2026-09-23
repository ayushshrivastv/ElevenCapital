import { Decimal } from 'decimal.js';
import { z } from 'zod';
import type { PrivyPrincipal } from './privy-auth.js';
import type { StoredPurchase } from './purchase-ledger.js';
import { Money } from './portfolio-upstream.js';

const Amount = z.string().max(120).regex(/^(?:0|[1-9]\d*)(?:\.\d+)?$/);
const TransactionId = z.string().min(32).max(128);
export const PurchaseActivityResponseSchema = z.object({
  schemaVersion: z.literal(1), scope: z.literal('completed-purchases'), observedAt: z.iso.datetime(),
  hasMore: z.boolean(), transactions: z.array(z.object({
    id: z.uuid(), transactionId: TransactionId, relatedTransactionIds: z.array(TransactionId).min(1).max(5),
    timestamp: z.iso.datetime(), timestampBasis: z.literal('completion_observed'),
    side: z.enum(['BUY', 'SELL']).nullable(), stockId: z.string().min(8).max(100),
    fromAssetId: z.string().min(5).max(100), inputAmount: Amount, receivedAmount: Amount.nullable(),
    valueUsd: Amount.nullable(), usdBasis: z.literal('quote_estimate').nullable(),
  }).strict()).max(100),
}).strict();
export type PurchaseActivityResponse = z.infer<typeof PurchaseActivityResponseSchema>;

/** Exposes only confirmed, subject-owned purchases; a quote is never presented as a trade. */
export function completedPurchaseActivity(principal: PrivyPrincipal, purchases: readonly StoredPurchase[],
  now = Date.now()): PurchaseActivityResponse {
  const completed = purchases.filter(row => row.subject === principal.subject && row.state === 'COMPLETED' &&
    row.submitted.length === row.quote.actions.length && row.submitted.length > 0);
  completed.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt) || a.operationId.localeCompare(b.operationId));
  const transactions = completed.slice(0, 100).flatMap(row => {
    const relatedTransactionIds = [...new Set([...row.submitted.map(item => item.transactionId),
      ...(row.solanaTransactionSignature ? [row.solanaTransactionSignature] : [])])];
    if (!relatedTransactionIds.length) return [];
    const price = row.quote.executableUnitPriceUsd;
    const amount = row.receivedAmount;
    let valueUsd: string | null = null;
    if (price !== null && amount !== null) {
      const value = new Money(price).times(amount);
      if (value.isFinite() && value.gte(0) && value.lte('1e40')) {
        valueUsd = value.toDecimalPlaces(8, Decimal.ROUND_HALF_UP).toFixed();
      }
    }
    return [{ id: row.operationId, transactionId: row.solanaTransactionSignature ?? relatedTransactionIds.at(-1)!,
      relatedTransactionIds, timestamp: row.updatedAt, timestampBasis: 'completion_observed' as const,
      side: row.side, stockId: row.quote.stockId, fromAssetId: row.quote.fromAssetId,
      inputAmount: row.quote.inputAmount, receivedAmount: row.receivedAmount, valueUsd,
      usdBasis: valueUsd === null ? null : 'quote_estimate' as const }];
  });
  return PurchaseActivityResponseSchema.parse({ schemaVersion: 1, scope: 'completed-purchases',
    observedAt: new Date(now).toISOString(), hasMore: completed.length > 100, transactions });
}
