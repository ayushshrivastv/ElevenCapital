import { Decimal } from 'decimal.js';
import { z } from 'zod';
import { parseProviderJson } from './http.js';
import { EthereumAddress, isSolanaAddress } from './portfolio-schema.js';
import { Money } from './portfolio-upstream.js';
import { WalletActivityService } from './wallet-activity.js';

const DecimalAmount = z.string().max(120).regex(/^(?:0|[1-9]\d*)(?:\.\d+)?$/);
const Chain = z.enum(['SOLANA', 'ARBITRUM', 'ETHEREUM']);
const Wallet = z.object({ chain: Chain, address: z.string().min(32).max(44) }).strict().refine(value =>
  value.chain === 'SOLANA' ? isSolanaAddress(value.address) : EthereumAddress.test(value.address));
export const WalletTransactionsRequestSchema = z.object({ wallets: z.array(Wallet).min(1).max(3) }).strict()
  .refine(value => new Set(value.wallets.map(wallet => wallet.chain)).size === value.wallets.length);
export type WalletTransactionsRequest = z.infer<typeof WalletTransactionsRequestSchema>;
const Row = z.object({
  id: z.string().min(1).max(200), chain: Chain, transactionId: z.string().min(32).max(128),
  timestamp: z.iso.datetime(), direction: z.enum(['RECEIVE', 'SEND']),
  assetSymbol: z.string().min(1).max(30), amount: DecimalAmount,
  valueUsd: DecimalAmount.nullable(), usdBasis: z.literal('current_spot').nullable(),
  counterparty: z.string().min(32).max(44).nullable(),
}).strict();
export const WalletTransactionsResponseSchema = z.object({
  schemaVersion: z.literal(1), scope: z.literal('wallet-transactions'),
  wallets: z.array(Wallet).min(1).max(3), status: z.enum(['ok', 'partial', 'unavailable']),
  observedAt: z.iso.datetime(), transactions: z.array(Row).max(64),
}).strict();
export type WalletTransactionsResponse = z.infer<typeof WalletTransactionsResponseSchema>;
type TransactionRow = WalletTransactionsResponse['transactions'][number];
type ReadResult = { status: 'ok' | 'partial' | 'unavailable'; transactions: TransactionRow[] };

function object(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
}
function ethAddress(value: unknown): string | null {
  return typeof value === 'string' && EthereumAddress.test(value) ? value.toLowerCase() : null;
}
function addressField(value: unknown): string | null { return ethAddress(object(value)?.hash); }
function hash(value: unknown): string | null {
  return typeof value === 'string' && /^0x[0-9a-fA-F]{64}$/.test(value) ? value.toLowerCase() : null;
}
function timestamp(value: unknown): string | null {
  if (typeof value !== 'string' || value.length > 40) return null;
  const milliseconds = Date.parse(value);
  return Number.isFinite(milliseconds) && milliseconds > 1_000_000_000_000 ? new Date(milliseconds).toISOString() : null;
}
function amount(value: unknown, decimals: number): string | null {
  if (typeof value !== 'string' || value.length > 78 || !/^(?:0|[1-9]\d*)$/.test(value) ||
    !Number.isInteger(decimals) || decimals < 0 || decimals > 36) return null;
  const result = new Money(value).div(new Money(10).pow(decimals));
  return result.isFinite() && result.gt(0) && result.lte('1e40') ? result.toFixed() : null;
}
function indexNumber(value: unknown): number | null {
  const text = typeof value === 'number' && Number.isSafeInteger(value) ? String(value) : value;
  if (typeof text !== 'string' || !/^(?:0|[1-9]\d{0,9})$/.test(text)) return null;
  const parsed = Number(text);
  return Number.isSafeInteger(parsed) && parsed <= 2_147_483_647 ? parsed : null;
}
function usdValue(quantity: string, price: unknown): string | null {
  if (typeof price !== 'string' || price.length > 100 ||
    !/^(?:0|[1-9]\d*)(?:\.\d+)?$/.test(price)) return null;
  const unit = new Money(price);
  const value = new Money(quantity).times(unit);
  return unit.gt(0) && unit.lte('1e20') && value.isFinite() && value.lte('1e40')
    ? value.toDecimalPlaces(8, Decimal.ROUND_HALF_UP).toFixed() : null;
}
function direction(from: string | null, to: string | null, wallet: string): 'RECEIVE' | 'SEND' | null {
  if (from === wallet && to !== wallet && to !== null) return 'SEND';
  if (to === wallet && from !== wallet && from !== null) return 'RECEIVE';
  return null;
}
function page(raw: unknown): { items: unknown[]; hasMore: boolean } | null {
  const data = object(raw);
  if (!data || !Array.isArray(data.items) || data.items.length > 100 ||
    !('next_page_params' in data) || (data.next_page_params !== null && !object(data.next_page_params))) return null;
  return { items: data.items, hasMore: data.next_page_params !== null };
}

export type BlockscoutKind = 'transactions' | 'token-transfers' | 'internal-transactions';
type EvmChain = 'ARBITRUM' | 'ETHEREUM';
export interface BlockscoutReader { read(chain: EvmChain, wallet: string, kind: BlockscoutKind, signal: AbortSignal): Promise<unknown> }

/** A fixed public explorer origin; the request controls only a validated address and allowlisted path. */
export function publicBlockscoutReader(fetcher: typeof fetch = fetch): BlockscoutReader {
  return { async read(chain, wallet, kind, signal) {
    if (!EthereumAddress.test(wallet) || !['transactions', 'token-transfers', 'internal-transactions'].includes(kind)) {
      throw new Error('Invalid explorer request');
    }
    const origin = chain === 'ARBITRUM' ? 'https://arbitrum.blockscout.com' : 'https://eth.blockscout.com';
    const url = `${origin}/api/v2/addresses/${wallet}/${kind}`;
    const response = await fetcher(url, { redirect: 'error', signal: AbortSignal.any([signal, AbortSignal.timeout(8_000)]),
      headers: { Accept: 'application/json' } });
    if (!response.ok) throw new Error('Explorer unavailable');
    const reader = response.body?.getReader();
    if (!reader) throw new Error('Explorer response missing');
    const chunks: Uint8Array[] = [];
    let size = 0;
    try {
      while (true) {
        const chunk = await reader.read();
        if (chunk.done) break;
        size += chunk.value.length;
        if (size > 2_000_000) throw new Error('Explorer response too large');
        chunks.push(chunk.value);
      }
    } finally { await reader.cancel(); }
    return parseProviderJson(Buffer.concat(chunks).toString('utf8'));
  } };
}

/** Public EVM transfers are derived from explorer-indexed confirmed transactions and logs. */
export class EvmActivityService {
  private readonly cache = new Map<string, { expiresAt: number; value: ReadResult }>();
  private readonly pending = new Map<string, Promise<ReadResult>>();
  constructor(private readonly chain: EvmChain, private readonly explorer: BlockscoutReader = publicBlockscoutReader(),
    private readonly now = Date.now) {}

  async activity(walletAddress: string): Promise<ReadResult> {
    const wallet = ethAddress(walletAddress);
    if (!wallet) throw new Error('Invalid EVM wallet');
    const cached = this.cache.get(wallet);
    if (cached && cached.expiresAt > this.now()) return cached.value;
    const inflight = this.pending.get(wallet);
    if (inflight) return inflight;
    if (this.pending.size >= 4) return { status: 'unavailable', transactions: [] };
    const task = this.load(wallet).catch((): ReadResult => ({ status: 'unavailable', transactions: [] }))
      .then(value => {
        if (this.cache.size >= 64) this.cache.delete(this.cache.keys().next().value!);
        this.cache.set(wallet, { expiresAt: this.now() + (value.status === 'ok' ? 15_000 : 5_000), value });
        return value;
      }).finally(() => this.pending.delete(wallet));
    this.pending.set(wallet, task);
    return task;
  }

  private async load(wallet: string): Promise<ReadResult> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 22_000);
    try {
      const kinds: BlockscoutKind[] = ['transactions', 'token-transfers', 'internal-transactions'];
      const reads = await Promise.allSettled(kinds.map(kind => this.explorer.read(this.chain, wallet, kind, controller.signal)));
      let partial = false;
      let success = 0;
      const rows: TransactionRow[] = [];
      const pricesByHash = new Map<string, unknown>();
      const nativeRead = reads[0]!;
      if (nativeRead.status === 'fulfilled') {
        const parsed = page(nativeRead.value);
        if (parsed) for (const raw of parsed.items) {
          const item = object(raw);
          const transactionId = hash(item?.hash);
          if (transactionId && item?.status === 'ok') pricesByHash.set(transactionId, item.exchange_rate);
        }
      }
      for (const [index, read] of reads.entries()) {
        if (read.status === 'rejected') { partial = true; continue; }
        const parsed = page(read.value);
        if (!parsed) { partial = true; continue; }
        success++;
        if (parsed.hasMore) partial = true;
        for (const raw of parsed.items) {
          const item = object(raw);
          if (!item) { partial = true; continue; }
          const from = addressField(item.from);
          const to = addressField(item.to);
          const flow = direction(from, to, wallet);
          if (!flow) continue;
          const time = timestamp(item.timestamp);
          const transactionId = hash(index === 0 ? item.hash : item.transaction_hash);
          if (!time || !transactionId) { partial = true; continue; }
          if (index === 0 || index === 2) {
            if (index === 0 && item.status !== 'ok') continue;
            if (index === 2 && item.success !== true) continue;
            const quantity = amount(item.value, 18);
            if (!quantity) continue;
            const logIndex = index === 2 ? indexNumber(item.index) : 0;
            if (logIndex === null) { partial = true; continue; }
            const valueUsd = usdValue(quantity, index === 0 ? item.exchange_rate : pricesByHash.get(transactionId));
            rows.push({ id: `${this.chain}:${transactionId}:ETH:${index === 0 ? 'outer' : `internal:${logIndex}`}`,
              chain: this.chain, transactionId, timestamp: time, direction: flow, assetSymbol: 'ETH',
              amount: quantity, valueUsd, usdBasis: valueUsd === null ? null : 'current_spot',
              counterparty: flow === 'SEND' ? to : from });
            continue;
          }
          if (item.token_type !== 'ERC-20') continue;
          const token = object(item.token);
          const total = object(item.total);
          const tokenAddress = ethAddress(token?.address_hash);
          const decimals = typeof total?.decimals === 'string' && /^\d{1,2}$/.test(total.decimals)
            ? Number(total.decimals) : null;
          const quantity = decimals === null ? null : amount(total?.value, decimals);
          const symbol = token?.symbol;
          const logIndex = indexNumber(item.log_index);
          if (!tokenAddress || !quantity || typeof symbol !== 'string' ||
            !/^[A-Za-z0-9._-]{1,30}$/.test(symbol) || logIndex === null) {
            partial = true; continue;
          }
          const valueUsd = token?.reputation === 'ok' ? usdValue(quantity, token.exchange_rate) : null;
          rows.push({ id: `${this.chain}:${transactionId}:${tokenAddress}:${logIndex}`, chain: this.chain,
            transactionId, timestamp: time, direction: flow, assetSymbol: symbol,
            amount: quantity, valueUsd, usdBasis: valueUsd === null ? null : 'current_spot',
            counterparty: flow === 'SEND' ? to : from });
        }
      }
      if (success === 0) return { status: 'unavailable', transactions: [] };
      const unique = [...new Map(rows.map(row => [row.id, row])).values()];
      unique.sort((a, b) => b.timestamp.localeCompare(a.timestamp) || a.id.localeCompare(b.id));
      if (unique.length > 64) partial = true;
      return { status: partial ? 'partial' : 'ok', transactions: unique.slice(0, 64) };
    } finally { clearTimeout(timer); }
  }
}

export class WalletTransactionsService {
  constructor(private readonly solana: Pick<WalletActivityService, 'activity'> = new WalletActivityService(),
    private readonly arbitrum: Pick<EvmActivityService, 'activity'> = new EvmActivityService('ARBITRUM'),
    private readonly ethereum: Pick<EvmActivityService, 'activity'> = new EvmActivityService('ETHEREUM'),
    private readonly now = Date.now) {}

  async activity(input: WalletTransactionsRequest): Promise<WalletTransactionsResponse> {
    const request = WalletTransactionsRequestSchema.parse(input);
    const wallets = request.wallets.map(wallet => wallet.chain !== 'SOLANA'
      ? { ...wallet, address: wallet.address.toLowerCase() } : wallet);
    const reads = await Promise.all(wallets.map(async (wallet): Promise<ReadResult> => {
      if (wallet.chain === 'ARBITRUM') return this.arbitrum.activity(wallet.address);
      if (wallet.chain === 'ETHEREUM') return this.ethereum.activity(wallet.address);
      const value = await this.solana.activity(wallet.address);
      return { status: value.status, transactions: value.transactions.map(row => ({
        id: `SOLANA:${row.signature}:${row.mint ?? 'native'}:${row.direction}`, chain: 'SOLANA',
        transactionId: row.signature, timestamp: row.timestamp, direction: row.direction,
        assetSymbol: row.assetSymbol, amount: row.amount, valueUsd: row.valueUsd,
        usdBasis: row.valueUsd === null ? null : 'current_spot', counterparty: row.counterparty,
      })) };
    }));
    const status = reads.every(read => read.status === 'ok') ? 'ok' :
      reads.every(read => read.status === 'unavailable') ? 'unavailable' : 'partial';
    const transactions = [...new Map(reads.flatMap(read => read.transactions).map(row => [row.id, row])).values()]
      .sort((a, b) => b.timestamp.localeCompare(a.timestamp) || a.id.localeCompare(b.id)).slice(0, 64);
    return WalletTransactionsResponseSchema.parse({ schemaVersion: 1, scope: 'wallet-transactions', wallets,
      status, observedAt: new Date(this.now()).toISOString(), transactions });
  }
}
