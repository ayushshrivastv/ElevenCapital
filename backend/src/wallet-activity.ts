import { z } from 'zod';
import { Decimal } from 'decimal.js';
import { limitConcurrency } from './cache.js';
import { parseProviderJson } from './http.js';
import { isSolanaAddress } from './portfolio-schema.js';
import { MAINNET_GENESIS, Money, PublicPortfolioUpstream, SOL_USDC, TOKEN_PROGRAMS, rpcEndpoint,
  type PortfolioAsset, type PortfolioUpstream } from './portfolio-upstream.js';

const ADDRESS = z.string().min(32).max(44).refine(isSolanaAddress);
const SIGNATURE = /^[1-9A-HJ-NP-Za-km-z]{80,90}$/;
const UNSIGNED = /^(?:0|[1-9][0-9]*)$/;
const MAX_TOKEN_ACCOUNTS = 24;
const MAX_TRANSACTIONS = 16;
const SOL_DECIMALS = 9;
const SOL_MINT = null;
const runRpc = limitConcurrency(4);

export const WalletActivityRequestSchema = z.object({ walletAddress: ADDRESS }).strict();
export type WalletActivityRequest = z.infer<typeof WalletActivityRequestSchema>;
const ActivityRowSchema = z.object({
  signature: z.string().regex(SIGNATURE), timestamp: z.iso.datetime(),
  direction: z.enum(['RECEIVE', 'SEND']), assetSymbol: z.string().min(1).max(30),
  mint: ADDRESS.nullable(), amount: z.string().regex(/^(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$/).max(100),
  valueUsd: z.string().regex(/^(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$/).max(120).nullable(),
  counterparty: ADDRESS.nullable(),
}).strict();
export const WalletActivityResponseSchema = z.object({
  schemaVersion: z.literal(1), scope: z.literal('solana-wallet-activity'),
  walletAddress: ADDRESS, status: z.enum(['ok', 'partial', 'unavailable']),
  observedAt: z.iso.datetime(), transactions: z.array(ActivityRowSchema).max(MAX_TRANSACTIONS * 4),
}).strict();
export type WalletActivityResponse = z.infer<typeof WalletActivityResponseSchema>;
type ActivityRow = WalletActivityResponse['transactions'][number];

export interface WalletActivityRpc {
  call(method: 'getGenesisHash' | 'getTokenAccountsByOwner' | 'getSignaturesForAddress' | 'getTransaction',
    params: unknown[], signal: AbortSignal): Promise<unknown>;
}

/** No request field can choose the endpoint or RPC method. Response bodies are capped before parsing. */
export function publicWalletActivityRpc(env: NodeJS.ProcessEnv = process.env): WalletActivityRpc {
  const endpoint = rpcEndpoint(env.SOLANA_RPC_URL ?? 'https://api.mainnet.solana.com');
  return {
    async call(method, params, signal) {
      if (params.length > 3) throw new Error('Unbounded wallet activity read');
      return runRpc(async () => {
        const response = await fetch(endpoint, {
          method: 'POST', redirect: 'error', signal: AbortSignal.any([signal, AbortSignal.timeout(6_000)]),
          headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
          body: JSON.stringify({ jsonrpc: '2.0', id: '1', method, params }),
        });
        if (!response.ok) throw new Error('Wallet activity RPC unavailable');
        const reader = response.body?.getReader();
        if (!reader) throw new Error('Wallet activity RPC response missing');
        const chunks: Uint8Array[] = [];
        let size = 0;
        try {
          while (true) {
            const chunk = await reader.read();
            if (chunk.done) break;
            size += chunk.value.length;
            if (size > 2_000_000) throw new Error('Wallet activity RPC response too large');
            chunks.push(chunk.value);
          }
        } finally { await reader.cancel(); }
        const raw = parseProviderJson(Buffer.concat(chunks).toString('utf8'));
        const envelope = z.object({ jsonrpc: z.literal('2.0'), id: z.union([z.literal('1'), z.literal(1)]),
          result: z.unknown().optional(), error: z.unknown().optional() }).passthrough().parse(raw);
        if (envelope.error !== undefined || envelope.result === undefined) throw new Error('Wallet activity RPC rejected');
        return envelope.result;
      });
    },
  };
}

function record(value: unknown): Record<string, unknown> | null {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
}
function unsigned(value: unknown, maximumDigits = 30): bigint | null {
  const text = typeof value === 'number' && Number.isSafeInteger(value) ? String(value) : value;
  return typeof text === 'string' && text.length <= maximumDigits && UNSIGNED.test(text) ? BigInt(text) : null;
}
function secondTimestamp(value: unknown): string | null {
  const seconds = unsigned(value, 13);
  if (seconds === null || seconds < 1_000_000_000n || seconds > 9_999_999_999n) return null;
  return new Date(Number(seconds) * 1_000).toISOString();
}
function decimal(baseUnits: bigint, places: number): string {
  const digits = baseUnits.toString().padStart(places + 1, '0');
  if (places === 0) return digits;
  const whole = digits.slice(0, -places);
  const fraction = digits.slice(-places).replace(/0+$/, '');
  return fraction ? `${whole}.${fraction}` : whole;
}
function assetSymbol(mint: string | null): string {
  if (mint === null) return 'SOL';
  if (mint === SOL_USDC) return 'USDC';
  return `${mint.slice(0, 4)}…${mint.slice(-4)}`;
}
function accountKeys(raw: unknown): string[] | null {
  const keys = record(raw)?.accountKeys;
  if (!Array.isArray(keys) || keys.length > 256) return null;
  const result = keys.map(value => typeof value === 'string' ? value : record(value)?.pubkey);
  return result.every(value => typeof value === 'string' && isSolanaAddress(value)) ? result as string[] : null;
}
type TokenSnapshot = Map<string, { amount: bigint; decimals: number }>;
function ownedTokenSnapshot(raw: unknown, keys: string[], wallet: string, ownedAccounts: Set<string>): TokenSnapshot | null {
  if (!Array.isArray(raw) || raw.length > 256) return null;
  const balances: TokenSnapshot = new Map();
  for (const value of raw) {
    const row = record(value);
    const index = unsigned(row?.accountIndex, 3);
    const mint = row?.mint;
    const owner = row?.owner;
    const token = record(row?.uiTokenAmount);
    const amount = unsigned(token?.amount, 78);
    const decimals = unsigned(token?.decimals, 2);
    if (index === null || index >= BigInt(keys.length) || typeof mint !== 'string' || !isSolanaAddress(mint) ||
      amount === null || decimals === null || decimals > 36n ||
      (owner !== undefined && (typeof owner !== 'string' || !isSolanaAddress(owner)))) return null;
    const address = keys[Number(index)]!;
    // The balance's owner field takes precedence. Older RPC records can omit it;
    // only then can a token account independently proven owned by this wallet fill the gap.
    if (owner !== wallet && !(owner === undefined && ownedAccounts.has(address))) continue;
    const previous = balances.get(mint);
    if (previous && previous.decimals !== Number(decimals)) return null;
    balances.set(mint, { amount: (previous?.amount ?? 0n) + amount, decimals: Number(decimals) });
  }
  return balances;
}
function parsedInstructions(raw: Record<string, unknown>): Record<string, unknown>[] {
  const message = record(record(raw.transaction)?.message);
  const meta = record(raw.meta);
  const top = Array.isArray(message?.instructions) ? message.instructions : [];
  const inner = Array.isArray(meta?.innerInstructions) ? meta.innerInstructions.flatMap(group => {
    const list = record(group)?.instructions;
    return Array.isArray(list) ? list : [];
  }) : [];
  return [...top, ...inner].map(record).filter((row): row is Record<string, unknown> => row !== null).slice(0, 256);
}
function nativeTransferDelta(raw: Record<string, unknown>, wallet: string, ownedAccounts: Set<string>):
  { delta: bigint; counterparty: string | null } {
  let delta = 0n;
  const counterparties = new Set<string>();
  for (const instruction of parsedInstructions(raw)) {
    if (instruction.program !== 'system') continue;
    const parsed = record(instruction.parsed);
    if (parsed?.type !== 'transfer' && parsed?.type !== 'transferWithSeed') continue;
    const info = record(parsed.info);
    const source = info?.source;
    const destination = info?.destination;
    const lamports = unsigned(info?.lamports, 25);
    if (typeof source !== 'string' || typeof destination !== 'string' || !isSolanaAddress(source) ||
      !isSolanaAddress(destination) || lamports === null) continue;
    if (source === wallet && destination !== wallet && !ownedAccounts.has(destination)) {
      delta -= lamports; counterparties.add(destination);
    } else if (destination === wallet && source !== wallet && !ownedAccounts.has(source)) {
      delta += lamports; counterparties.add(source);
    }
  }
  return { delta, counterparty: counterparties.size === 1 ? [...counterparties][0]! : null };
}

/** Resolve an SPL transfer peer only when RPC token ownership and instructions agree. */
function tokenCounterparty(raw: Record<string, unknown>, keys: string[], wallet: string,
  ownedAccounts: Set<string>, mint: string, direction: 'RECEIVE' | 'SEND'): string | null {
  const meta = record(raw.meta);
  const owners = new Map<string, { owner: string; mint: string }>();
  for (const balance of [meta?.preTokenBalances, meta?.postTokenBalances]) {
    if (!Array.isArray(balance)) continue;
    for (const value of balance) {
      const row = record(value);
      const index = unsigned(row?.accountIndex, 3);
      const owner = row?.owner;
      const tokenMint = row?.mint;
      if (index === null || index >= BigInt(keys.length) || typeof owner !== 'string' ||
        !isSolanaAddress(owner) || typeof tokenMint !== 'string' || !isSolanaAddress(tokenMint)) continue;
      const address = keys[Number(index)]!;
      const existing = owners.get(address);
      if (existing && (existing.owner !== owner || existing.mint !== tokenMint)) return null;
      owners.set(address, { owner, mint: tokenMint });
    }
  }
  const counterparties = new Set<string>();
  for (const instruction of parsedInstructions(raw)) {
    if (instruction.program !== 'spl-token' && instruction.program !== 'spl-token-2022') continue;
    const parsed = record(instruction.parsed);
    if (parsed?.type !== 'transfer' && parsed?.type !== 'transferChecked') continue;
    const info = record(parsed.info);
    const source = info?.source;
    const destination = info?.destination;
    if (typeof source !== 'string' || typeof destination !== 'string' ||
      !isSolanaAddress(source) || !isSolanaAddress(destination)) continue;
    const sourceOwner = owners.get(source);
    const destinationOwner = owners.get(destination);
    if (info?.mint !== undefined && info.mint !== mint) continue;
    if (sourceOwner?.mint !== mint && destinationOwner?.mint !== mint) continue;
    const sourceWallet = ownedAccounts.has(source) || sourceOwner?.owner === wallet;
    const destinationWallet = ownedAccounts.has(destination) || destinationOwner?.owner === wallet;
    if (direction === 'RECEIVE' && destinationWallet && !sourceWallet && sourceOwner?.owner) {
      counterparties.add(sourceOwner.owner);
    } else if (direction === 'SEND' && sourceWallet && !destinationWallet && destinationOwner?.owner) {
      counterparties.add(destinationOwner.owner);
    }
  }
  return counterparties.size === 1 ? [...counterparties][0]! : null;
}

/** Turn confirmed wallet-owned balance changes into exact positive transfer amounts. */
export function activityRowsFromTransaction(raw: unknown, signature: string, wallet: string,
  ownedAccounts: Set<string>, fallbackTime: unknown): ActivityRow[] | null {
  const transaction = record(raw);
  const meta = record(transaction?.meta);
  if (!transaction || !meta || !('err' in meta)) return null;
  if (meta.err !== null) return []; // Failed transactions are never displayed as a transfer.
  const timestamp = secondTimestamp(transaction.blockTime ?? fallbackTime);
  const keys = accountKeys(record(transaction.transaction)?.message);
  if (!timestamp || !keys) return null;
  const result: ActivityRow[] = [];
  const walletIndex = keys.indexOf(wallet);
  const preBalances = meta.preBalances;
  const postBalances = meta.postBalances;
  const fee = unsigned(meta.fee, 25);
  if (walletIndex >= 0 && Array.isArray(preBalances) && Array.isArray(postBalances) &&
    preBalances.length > walletIndex && postBalances.length > walletIndex && fee !== null) {
    const before = unsigned(preBalances[walletIndex], 25);
    const after = unsigned(postBalances[walletIndex], 25);
    if (before !== null && after !== null) {
      const net = after - before + (keys[0] === wallet ? fee : 0n);
      const transfer = nativeTransferDelta(transaction, wallet, ownedAccounts);
      // Rent deposits, account closures, and fee-only transactions are not SOL sends/receives.
      // A pure System transfer must also match the independently observed balance delta.
      if (net !== 0n && net === transfer.delta) result.push({ signature, timestamp,
        direction: net > 0n ? 'RECEIVE' : 'SEND', assetSymbol: 'SOL', mint: SOL_MINT,
        amount: decimal(net > 0n ? net : -net, SOL_DECIMALS), valueUsd: null,
        counterparty: transfer.counterparty });
    }
  }
  const beforeTokens = ownedTokenSnapshot(meta.preTokenBalances, keys, wallet, ownedAccounts);
  const afterTokens = ownedTokenSnapshot(meta.postTokenBalances, keys, wallet, ownedAccounts);
  if (beforeTokens === null || afterTokens === null) return result;
  for (const mint of new Set([...beforeTokens.keys(), ...afterTokens.keys()])) {
    const before = beforeTokens.get(mint);
    const after = afterTokens.get(mint);
    const decimals = before?.decimals ?? after?.decimals;
    if (decimals === undefined || (before && after && before.decimals !== after.decimals)) continue;
    const delta = (after?.amount ?? 0n) - (before?.amount ?? 0n);
    if (delta === 0n) continue;
    const direction = delta > 0n ? 'RECEIVE' : 'SEND';
    result.push({ signature, timestamp, direction, assetSymbol: assetSymbol(mint), mint,
      amount: decimal(delta > 0n ? delta : -delta, decimals), valueUsd: null,
      counterparty: tokenCounterparty(transaction, keys, wallet, ownedAccounts, mint, direction) });
  }
  return result;
}

function tokenAccounts(raw: unknown): string[] | null {
  const values = record(raw)?.value;
  if (!Array.isArray(values) || values.length > 1_000) return null;
  const addresses = values.map(row => record(row)?.pubkey);
  if (!addresses.every(value => typeof value === 'string' && isSolanaAddress(value))) return null;
  return [...new Set(addresses as string[])];
}
type SignatureInfo = { signature: string; blockTime: unknown; slot: bigint };
function signatures(raw: unknown): SignatureInfo[] | null {
  if (!Array.isArray(raw) || raw.length > 12) return null;
  const found: SignatureInfo[] = [];
  for (const item of raw) {
    const row = record(item);
    const signature = row?.signature;
    const slot = unsigned(row?.slot, 20);
    if (typeof signature !== 'string' || !SIGNATURE.test(signature) || slot === null || !row || !('err' in row)) return null;
    if (row.err !== null) continue;
    found.push({ signature, blockTime: row.blockTime, slot });
  }
  return found;
}

export class WalletActivityService {
  private readonly cache = new Map<string, { until: number; value: WalletActivityResponse }>();
  private readonly pending = new Map<string, Promise<WalletActivityResponse>>();
  constructor(private readonly rpc: WalletActivityRpc = publicWalletActivityRpc(), private readonly now = Date.now,
    private readonly priceReader: Pick<PortfolioUpstream, 'prices'> = new PublicPortfolioUpstream(undefined, now)) {}

  async activity(walletAddress: string): Promise<WalletActivityResponse> {
    const wallet = ADDRESS.parse(walletAddress);
    const cached = this.cache.get(wallet);
    if (cached && cached.until > this.now()) return cached.value;
    const pending = this.pending.get(wallet);
    if (pending) return pending;
    if (this.pending.size >= 4) return this.response(wallet, 'unavailable', []);
    const load = this.loadAndCache(wallet);
    this.pending.set(wallet, load);
    try { return await load; } finally { this.pending.delete(wallet); }
  }

  private async loadAndCache(wallet: string): Promise<WalletActivityResponse> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 22_000);
    let value: WalletActivityResponse;
    try { value = await this.load(wallet, controller.signal); }
    catch { value = this.response(wallet, 'unavailable', []); }
    finally { clearTimeout(timer); }
    this.cache.set(wallet, { until: this.now() + (value.status === 'ok' ? 15_000 : 5_000), value });
    while (this.cache.size > 64) this.cache.delete(this.cache.keys().next().value!);
    return value;
  }

  private response(wallet: string, status: 'ok' | 'partial' | 'unavailable', transactions: ActivityRow[]): WalletActivityResponse {
    return WalletActivityResponseSchema.parse({ schemaVersion: 1, scope: 'solana-wallet-activity', walletAddress: wallet,
      status, observedAt: new Date(this.now()).toISOString(), transactions });
  }

  private async load(wallet: string, signal: AbortSignal): Promise<WalletActivityResponse> {
    const genesis = await this.rpc.call('getGenesisHash', [], signal);
    if (genesis !== MAINNET_GENESIS) return this.response(wallet, 'unavailable', []);
    const accountsRead = await Promise.allSettled(TOKEN_PROGRAMS.map(programId => this.rpc.call('getTokenAccountsByOwner',
      [wallet, { programId }, { encoding: 'jsonParsed', commitment: 'confirmed' }], signal)));
    let partial = false;
    let accounts: string[] = [];
    for (const read of accountsRead) {
      if (read.status === 'rejected') { partial = true; continue; }
      const parsed = tokenAccounts(read.value);
      if (parsed === null) { partial = true; continue; }
      accounts.push(...parsed);
    }
    accounts = [...new Set(accounts)];
    if (accounts.length > MAX_TOKEN_ACCOUNTS) { accounts = accounts.slice(0, MAX_TOKEN_ACCOUNTS); partial = true; }
    const ownedAccounts = new Set(accounts);
    const signatureReads = await Promise.allSettled([wallet, ...accounts].map(address =>
      this.rpc.call('getSignaturesForAddress', [address, { limit: 12, commitment: 'confirmed' }], signal)));
    const allSignatures: SignatureInfo[] = [];
    let successfulReads = 0;
    for (const read of signatureReads) {
      if (read.status === 'rejected') { partial = true; continue; }
      const parsed = signatures(read.value);
      if (parsed === null) { partial = true; continue; }
      successfulReads++;
      allSignatures.push(...parsed);
    }
    if (successfulReads === 0) return this.response(wallet, 'unavailable', []);
    const unique = new Map<string, SignatureInfo>();
    for (const item of allSignatures) unique.set(item.signature, item);
    const newest = [...unique.values()].sort((a, b) => a.slot === b.slot ? 0 : a.slot > b.slot ? -1 : 1);
    if (newest.length > MAX_TRANSACTIONS) partial = true;
    const selected = newest.slice(0, MAX_TRANSACTIONS);
    const transactionReads = await Promise.allSettled(selected.map(item => this.rpc.call('getTransaction',
      [item.signature, { encoding: 'jsonParsed', maxSupportedTransactionVersion: 0, commitment: 'confirmed' }], signal)));
    const transactions: ActivityRow[] = [];
    for (let index = 0; index < transactionReads.length; index++) {
      const read = transactionReads[index]!;
      if (read.status === 'rejected') { partial = true; continue; }
      const rows = activityRowsFromTransaction(read.value, selected[index]!.signature, wallet, ownedAccounts,
        selected[index]!.blockTime);
      if (rows === null) { partial = true; continue; }
      transactions.push(...rows);
    }
    transactions.sort((a, b) => b.timestamp.localeCompare(a.timestamp) || a.signature.localeCompare(b.signature));
    const visible = transactions.slice(0, MAX_TRANSACTIONS * 4);
    // These are current spot estimates, never claimed to be the price at transaction time.
    // Jupiter's existing price reader accepts only fresh observations; missing quotes stay null.
    const assets = [...new Set(visible.map(row => row.mint))].map(mint => ({
      key: mint === null ? 'SOLANA:native' : `SOLANA:${mint}`, chain: 'SOLANA' as const,
      address: mint, stockId: null, decimals: null, pricing: 'solana' as const,
    } satisfies PortfolioAsset));
    const prices = assets.length ? await this.priceReader.prices(assets, signal) : new Map<string, string>();
    const valued = visible.map(row => {
      const price = prices.get(row.mint === null ? 'SOLANA:native' : `SOLANA:${row.mint}`);
      if (!price) return row;
      const usd = new Money(row.amount).times(price);
      if (!usd.isFinite() || usd.isNegative() || usd.gt('1e40')) return row;
      return { ...row, valueUsd: usd.toDecimalPlaces(8, Decimal.ROUND_HALF_UP).toFixed() };
    });
    return this.response(wallet, partial ? 'partial' : 'ok', valued);
  }
}
