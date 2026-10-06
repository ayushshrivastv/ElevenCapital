import { z } from 'zod';
import { isSolanaAddress } from './portfolio-schema.js';
import { WalletActivityService } from './wallet-activity.js';

const DecimalAmount = z.string().max(120).regex(/^(?:0|[1-9]\d*)(?:\.\d+)?$/);
const Wallet = z.object({
  chain: z.literal('SOLANA'),
  address: z.string().min(32).max(44).refine(isSolanaAddress),
}).strict();

export const WalletTransactionsRequestSchema = z.object({
  wallets: z.array(Wallet).min(1).max(5),
}).strict().refine(value => new Set(value.wallets.map(wallet => wallet.address)).size === value.wallets.length,
  'Wallet addresses must be unique');
export type WalletTransactionsRequest = z.infer<typeof WalletTransactionsRequestSchema>;

const Row = z.object({
  id: z.string().min(1).max(200),
  chain: z.literal('SOLANA'),
  transactionId: z.string().min(32).max(128),
  timestamp: z.iso.datetime(),
  direction: z.enum(['RECEIVE', 'SEND']),
  assetSymbol: z.string().min(1).max(30),
  amount: DecimalAmount,
  valueUsd: DecimalAmount.nullable(),
  usdBasis: z.literal('current_spot').nullable(),
  counterparty: z.string().min(32).max(44).nullable(),
}).strict();

export const WalletTransactionsResponseSchema = z.object({
  schemaVersion: z.literal(1),
  scope: z.literal('wallet-transactions'),
  wallets: z.array(Wallet).min(1).max(5),
  status: z.enum(['ok', 'partial', 'unavailable']),
  observedAt: z.iso.datetime(),
  transactions: z.array(Row).max(64),
}).strict();
export type WalletTransactionsResponse = z.infer<typeof WalletTransactionsResponseSchema>;

type ReadResult = {
  status: 'ok' | 'partial' | 'unavailable';
  transactions: WalletTransactionsResponse['transactions'];
};

/** Combines the public activity feeds for the connected Solana wallet set. */
export class WalletTransactionsService {
  constructor(private readonly solana: Pick<WalletActivityService, 'activity'> = new WalletActivityService(),
    private readonly now = Date.now) {}

  async activity(input: WalletTransactionsRequest): Promise<WalletTransactionsResponse> {
    const request = WalletTransactionsRequestSchema.parse(input);
    const reads: ReadResult[] = await Promise.all(request.wallets.map(async wallet => {
      const value = await this.solana.activity(wallet.address);
      return {
        status: value.status,
        transactions: value.transactions.map(row => ({
          id: `SOLANA:${row.signature}:${row.mint ?? 'native'}:${row.direction}`,
          chain: 'SOLANA' as const,
          transactionId: row.signature,
          timestamp: row.timestamp,
          direction: row.direction,
          assetSymbol: row.assetSymbol,
          amount: row.amount,
          valueUsd: row.valueUsd,
          usdBasis: row.valueUsd === null ? null : 'current_spot' as const,
          counterparty: row.counterparty,
        })),
      };
    }));
    const status = reads.every(read => read.status === 'ok') ? 'ok' :
      reads.every(read => read.status === 'unavailable') ? 'unavailable' : 'partial';
    const transactions = [...new Map(reads.flatMap(read => read.transactions).map(row => [row.id, row])).values()]
      .sort((a, b) => b.timestamp.localeCompare(a.timestamp) || a.id.localeCompare(b.id)).slice(0, 64);
    return WalletTransactionsResponseSchema.parse({ schemaVersion: 1, scope: 'wallet-transactions',
      wallets: request.wallets, status, observedAt: new Date(this.now()).toISOString(), transactions });
  }
}
