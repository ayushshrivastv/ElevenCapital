import { z } from 'zod';
import { isSolanaAddress } from './portfolio-schema.js';
import { Money, rpcEndpoint } from './portfolio-upstream.js';
import { activityRowsFromTransaction, publicWalletActivityRpc, type WalletActivityRpc } from './wallet-activity.js';

const Address = z.string().min(32).max(44).refine(isSolanaAddress);
const Signature = z.string().regex(/^[1-9A-HJ-NP-Za-km-z]{80,90}$/);
const Amount = z.string().max(100).regex(/^(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$/);
const Lamports = /^(?:0|[1-9][0-9]*)$/;
// The public Devnet endpoint's full getGenesisHash value, verified independently
// from mainnet before any balance or transaction is accepted.
export const DEVNET_GENESIS = 'EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG';

export const SolanaDevnetRequestSchema = z.object({ walletAddress: Address }).strict();
export type SolanaDevnetRequest = z.infer<typeof SolanaDevnetRequestSchema>;
export const SolanaDevnetResponseSchema = z.object({
  schemaVersion: z.literal(1), scope: z.literal('solana-devnet-wallet'), network: z.literal('SOLANA_DEVNET'),
  walletAddress: Address, status: z.enum(['ok', 'partial', 'unavailable']), observedAt: z.iso.datetime(),
  balanceSol: Amount.nullable(), transactions: z.array(z.object({
    signature: Signature, timestamp: z.iso.datetime(), direction: z.enum(['RECEIVE', 'SEND']),
    amount: Amount.refine(value => new Money(value).gt(0)), counterparty: Address.nullable(),
  }).strict()).max(16),
}).strict().superRefine((value, context) => {
  if ((value.status === 'unavailable') !== (value.balanceSol === null && value.transactions.length === 0)) {
    context.addIssue({ code: 'custom', message: 'Unavailable Devnet state must not claim a balance or activity' });
  }
});
export type SolanaDevnetResponse = z.infer<typeof SolanaDevnetResponseSchema>;

export function publicSolanaDevnetRpc(env: NodeJS.ProcessEnv = process.env): WalletActivityRpc {
  const endpoint = env.SOLANA_DEVNET_RPC_URL ?? 'https://api.devnet.solana.com';
  // Reject a private, local, or credential-bearing endpoint just as the mainnet reader does.
  rpcEndpoint(endpoint);
  return publicWalletActivityRpc({ SOLANA_RPC_URL: endpoint });
}

function record(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
}
function unsigned(value: unknown, maxDigits = 25): bigint | null {
  const raw = typeof value === 'number' && Number.isSafeInteger(value) ? String(value) : value;
  return typeof raw === 'string' && raw.length <= maxDigits && Lamports.test(raw) ? BigInt(raw) : null;
}

/** Devnet SOL is informational and deliberately has no USD valuation or mainnet asset ID. */
export class SolanaDevnetWalletService {
  private readonly cache = new Map<string, { until: number; response: SolanaDevnetResponse }>();
  private readonly pending = new Map<string, Promise<SolanaDevnetResponse>>();
  constructor(private readonly rpc: WalletActivityRpc = publicSolanaDevnetRpc(), private readonly now = Date.now) {}

  async wallet(walletAddress: string): Promise<SolanaDevnetResponse> {
    const address = Address.parse(walletAddress);
    const cached = this.cache.get(address);
    if (cached && cached.until > this.now()) return cached.response;
    const pending = this.pending.get(address);
    if (pending) return pending;
    if (this.pending.size >= 4) return this.response(address, 'unavailable', null, []);
    const task = this.loadAndCache(address);
    this.pending.set(address, task);
    try { return await task; } finally { this.pending.delete(address); }
  }

  private response(address: string, status: 'ok' | 'partial' | 'unavailable', balanceSol: string | null,
    transactions: SolanaDevnetResponse['transactions']): SolanaDevnetResponse {
    return SolanaDevnetResponseSchema.parse({ schemaVersion: 1, scope: 'solana-devnet-wallet', network: 'SOLANA_DEVNET',
      walletAddress: address, status, observedAt: new Date(this.now()).toISOString(), balanceSol, transactions });
  }

  private async loadAndCache(address: string): Promise<SolanaDevnetResponse> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 22_000);
    let response: SolanaDevnetResponse;
    try { response = await this.load(address, controller.signal); }
    catch { response = this.response(address, 'unavailable', null, []); }
    finally { clearTimeout(timer); }
    this.cache.set(address, { until: this.now() + (response.status === 'ok' ? 15_000 : 5_000), response });
    while (this.cache.size > 64) this.cache.delete(this.cache.keys().next().value!);
    return response;
  }

  private async load(address: string, signal: AbortSignal): Promise<SolanaDevnetResponse> {
    if (await this.rpc.call('getGenesisHash', [], signal) !== DEVNET_GENESIS) {
      return this.response(address, 'unavailable', null, []);
    }
    const [balanceRead, signaturesRead] = await Promise.allSettled([
      this.rpc.call('getBalance', [address, { commitment: 'confirmed' }], signal),
      this.rpc.call('getSignaturesForAddress', [address, { limit: 16, commitment: 'confirmed' }], signal),
    ]);
    let partial = balanceRead.status !== 'fulfilled' || signaturesRead.status !== 'fulfilled';
    let balanceSol: string | null = null;
    if (balanceRead.status === 'fulfilled') {
      const lamports = unsigned(record(balanceRead.value)?.value);
      if (lamports !== null && lamports < 2n ** 64n) balanceSol = new Money(lamports.toString()).div('1000000000').toFixed();
      else partial = true;
    }
    const listed = signaturesRead.status === 'fulfilled' && Array.isArray(signaturesRead.value) && signaturesRead.value.length <= 16
      ? signaturesRead.value : null;
    if (listed === null) partial = true;
    const signatures: { signature: string; blockTime: unknown; slot: bigint }[] = [];
    for (const value of listed ?? []) {
      const row = record(value);
      const signature = row?.signature;
      const slot = unsigned(row?.slot, 20);
      if (typeof signature !== 'string' || !Signature.safeParse(signature).success || slot === null || !row || !('err' in row)) {
        partial = true; continue;
      }
      if (row.err !== null) continue;
      signatures.push({ signature, blockTime: row.blockTime, slot });
    }
    const ordered = [...new Map(signatures.map(value => [value.signature, value])).values()]
      .sort((a, b) => a.slot === b.slot ? 0 : a.slot > b.slot ? -1 : 1);
    const reads = await Promise.allSettled(ordered.map(value => this.rpc.call('getTransaction',
      [value.signature, { encoding: 'jsonParsed', maxSupportedTransactionVersion: 0, commitment: 'confirmed' }], signal)));
    const transactions: SolanaDevnetResponse['transactions'] = [];
    for (let index = 0; index < reads.length; index++) {
      const read = reads[index]!;
      if (read.status === 'rejected') { partial = true; continue; }
      const rows = activityRowsFromTransaction(read.value, ordered[index]!.signature, address, new Set(), ordered[index]!.blockTime);
      if (rows === null) { partial = true; continue; }
      for (const row of rows) if (row.mint === null) transactions.push({ signature: row.signature, timestamp: row.timestamp,
        direction: row.direction, amount: row.amount, counterparty: row.counterparty });
    }
    if (balanceSol === null && transactions.length === 0) return this.response(address, 'unavailable', null, []);
    return this.response(address, partial ? 'partial' : 'ok', balanceSol, transactions);
  }
}
