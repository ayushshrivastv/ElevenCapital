import { Decimal } from 'decimal.js';
import { z } from 'zod';
import { limitConcurrency } from './cache.js';
import { parseProviderJson } from './http.js';
import { MAINNET_GENESIS, Money, PublicPortfolioUpstream, SOL_MINT, rpcEndpoint,
  type PortfolioAsset, type PortfolioUpstream } from './portfolio-upstream.js';
import type { PaymentAsset, PurchaseDestination, PurchaseNetwork, PurchaseWallet } from './purchase-schema.js';
import { readSolanaMintUiMultiplier } from './solana-swap-validation.js';

const Units = Decimal.clone({ precision: 160 });

export const PURCHASE_CHAIN_IDS: Record<PurchaseNetwork, string> = { SOLANA: '1151111081099710' };
export const SOL_NATIVE = '11111111111111111111111111111111';

export interface PaymentAssetDefinition {
  id: PaymentAsset['id']; symbol: PaymentAsset['symbol']; name: string; network: PurchaseNetwork;
  chainId: PaymentAsset['chainId']; address: string; decimals: number; walletChain: PurchaseWallet['chain'];
  uiMultiplier?: string;
}

export const PAYMENT_ASSETS: readonly PaymentAssetDefinition[] = [
  { id: 'SOLANA:SOL', symbol: 'SOL', name: 'Solana', network: 'SOLANA', chainId: '1151111081099710', address: SOL_NATIVE, decimals: 9, walletChain: 'SOLANA' },
  { id: 'SOLANA:USDC', symbol: 'USDC', name: 'USD Coin on Solana', network: 'SOLANA', chainId: '1151111081099710', address: 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v', decimals: 6, walletChain: 'SOLANA' },
] as const;

export function paymentAssetDefinition(id: string): PaymentAssetDefinition | undefined {
  return PAYMENT_ASSETS.find(asset => asset.id === id);
}

export function walletForNetwork(wallets: readonly PurchaseWallet[], network: PurchaseNetwork): PurchaseWallet | undefined {
  return network === 'SOLANA' ? wallets.find(wallet => wallet.chain === 'SOLANA') : undefined;
}

export function humanAmount(baseUnits: string, decimals: number): string {
  const value = new Units(baseUnits).div(new Units(10).pow(decimals)).toFixed(decimals);
  return (value.includes('.') ? value.replace(/0+$/, '').replace(/\.$/, '') : value) || '0';
}

export function displayAmount(baseUnits: string, decimals: number, multiplier = '1'): string {
  const factor = new Units(multiplier);
  if (!factor.isFinite() || factor.lte(0) || factor.gt('1e12')) throw new Error('Invalid token display multiplier');
  // Match Token-2022's scaled UI conversion: multiply raw units, truncate, then apply decimals.
  const scaledUnits = new Units(baseUnits).times(factor).toDecimalPlaces(0, Decimal.ROUND_DOWN).toFixed(0);
  return humanAmount(scaledUnits, decimals);
}

export function normalizeNetwork(value: string): PurchaseNetwork | null {
  const normalized = value.toLowerCase().replace(/[^a-z0-9]/g, '');
  if (normalized === 'solana') return 'SOLANA';
  return null;
}

export interface PurchaseChainReader {
  paymentAssets(wallets: readonly PurchaseWallet[]): Promise<PaymentAsset[]>;
  balance(asset: PaymentAssetDefinition, wallet: PurchaseWallet): Promise<string>;
  tokenDecimals(network: PurchaseNetwork, address: string): Promise<number>;
  tokenUiMultiplier?(network: PurchaseNetwork, address: string, decimals: number): Promise<string>;
}

export interface JsonRpc { call(network: PurchaseNetwork, method: string, params: unknown[]): Promise<unknown> }
type PurchasePriceReader = Pick<PortfolioUpstream, 'prices'>;
const runRpc = limitConcurrency(8);
const AllowedMethods = new Set(['getBalance', 'getTokenAccountsByOwner', 'getTokenSupply',
  'getAccountInfo', 'getMultipleAccounts', 'getFeeForMessage', 'simulateTransaction', 'getBlockHeight', 'getGenesisHash', 'getTransaction']);

export class PurchaseRpcError extends Error { constructor() { super('Purchase chain data could not be verified'); } }
export class PurchaseRpcUnavailableError extends PurchaseRpcError {}
// The shared JSON reader preserves every number as a string to avoid rounding balances.
// Mint precision is small and can be normalized only after its integer shape is checked.
const MintDecimals = z.union([z.number(), z.string().regex(/^(?:0|[1-9]\d?)$/).transform(Number)])
  .pipe(z.number().int().min(0).max(36));

async function readBounded(response: Response): Promise<unknown> {
  if (!response.ok) throw new PurchaseRpcUnavailableError();
  const reader = response.body?.getReader();
  if (!reader) throw new PurchaseRpcUnavailableError();
  const chunks: Uint8Array[] = []; let size = 0;
  try {
    while (true) {
      const chunk = await reader.read(); if (chunk.done) break;
      size += chunk.value.length; if (size > 1_000_000) throw new PurchaseRpcUnavailableError(); chunks.push(chunk.value);
    }
  } finally { await reader.cancel(); }
  try { return parseProviderJson(Buffer.concat(chunks).toString('utf8')); } catch { throw new PurchaseRpcUnavailableError(); }
}

export function publicPurchaseRpc(env: NodeJS.ProcessEnv = process.env, fetcher: typeof fetch = fetch): JsonRpc {
  const endpoints: Record<PurchaseNetwork, URL> = {
    SOLANA: rpcEndpoint(env.SOLANA_RPC_URL ?? 'https://api.mainnet-beta.solana.com'),
  };
  return { async call(network, method, params) {
    if (!AllowedMethods.has(method) || params.length > 3) throw new Error('Only bounded purchase reads are permitted');
    return runRpc(async () => {
      let raw: unknown;
      try {
        const response = await fetcher(endpoints[network], { method: 'POST', redirect: 'error', signal: AbortSignal.timeout(6_000),
          headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
          body: JSON.stringify({ jsonrpc: '2.0', id: '1', method, params }) });
        raw = await readBounded(response);
      } catch { throw new PurchaseRpcUnavailableError(); }
      const parsed = z.object({ jsonrpc: z.literal('2.0'), id: z.union([z.literal('1'), z.literal(1)]),
        result: z.unknown().optional(), error: z.unknown().optional() }).strict().safeParse(raw);
      if (!parsed.success) throw new PurchaseRpcUnavailableError();
      if (parsed.data.error !== undefined) {
        const code = z.object({ code: z.union([z.number(), z.string()]) }).passthrough().safeParse(parsed.data.error);
        if (code.success && ['-32600', '-32602'].includes(String(code.data.code))) throw new PurchaseRpcError();
        throw new PurchaseRpcUnavailableError();
      }
      if (parsed.data.result === undefined) throw new PurchaseRpcUnavailableError();
      return parsed.data.result;
    });
  } };
}

export class PublicPurchaseChainReader implements PurchaseChainReader {
  private solanaVerifiedUntil = 0;
  private solanaVerification: Promise<void> | null = null;
  private readonly paymentSnapshots = new Map<string, { until: number; result: Promise<PaymentAsset[]> }>();
  constructor(private readonly rpc: JsonRpc = publicPurchaseRpc(),
    private readonly priceReader: PurchasePriceReader = new PublicPortfolioUpstream(),
    private readonly now: () => number = Date.now) {}

  private async requireSolanaMainnet(): Promise<void> {
    if (this.solanaVerifiedUntil > this.now() && this.solanaVerifiedUntil - this.now() <= 60_000) return;
    if (!this.solanaVerification) {
      this.solanaVerification = (async () => {
        const genesis = await this.rpc.call('SOLANA', 'getGenesisHash', []);
        if (genesis !== MAINNET_GENESIS) throw new PurchaseRpcError();
        this.solanaVerifiedUntil = this.now() + 60_000;
      })().finally(() => { this.solanaVerification = null; });
    }
    await this.solanaVerification;
  }

  async paymentAssets(wallets: readonly PurchaseWallet[]): Promise<PaymentAsset[]> {
    // Options are identical across stock Buy screens for the same wallet pair. Share
    // the in-flight RPC/price read and briefly reuse its result; quote() still calls
    // balance() directly so a stale options snapshot cannot authorize a purchase.
    const key = JSON.stringify([...wallets].map(wallet => ({ chain: wallet.chain, address: wallet.address }))
      .sort((a, b) => a.chain.localeCompare(b.chain)));
    const existing = this.paymentSnapshots.get(key);
    if (existing && existing.until > this.now()) return (await existing.result).map(asset => ({ ...asset }));
    const entry = { until: Infinity, result: this.readPaymentAssets(wallets) };
    this.paymentSnapshots.set(key, entry);
    if (this.paymentSnapshots.size > 32) this.paymentSnapshots.delete(this.paymentSnapshots.keys().next().value!);
    try {
      const rows = await entry.result;
      if (this.paymentSnapshots.get(key) === entry) {
        const degraded = rows.some(asset => !asset.enabled ||
          (BigInt(asset.balanceBaseUnits) > 0n && asset.usdValue === null));
        entry.until = this.now() + (degraded ? 2_000 : 15_000);
      }
      return rows.map(asset => ({ ...asset }));
    } catch (error) {
      if (this.paymentSnapshots.get(key) === entry) this.paymentSnapshots.delete(key);
      throw error;
    }
  }

  private async readPaymentAssets(wallets: readonly PurchaseWallet[]): Promise<PaymentAsset[]> {
    const balances = await Promise.all(PAYMENT_ASSETS.flatMap(asset => {
      const wallet = wallets.find(item => item.chain === asset.walletChain);
      if (!wallet) return [];
      return [this.balance(asset, wallet).then(balanceBaseUnits => ({
        id: asset.id, symbol: asset.symbol, name: asset.name, network: asset.network, chainId: asset.chainId,
        address: asset.address, decimals: asset.decimals, balanceBaseUnits,
        balance: humanAmount(balanceBaseUnits, asset.decimals), usdValue: null, enabled: true,
      } satisfies PaymentAsset)).catch(() => ({
        id: asset.id, symbol: asset.symbol, name: asset.name, network: asset.network, chainId: asset.chainId,
        address: asset.address, decimals: asset.decimals, balanceBaseUnits: '0', balance: '0', usdValue: null, enabled: false,
      } satisfies PaymentAsset))];
    }));
    const positive = balances.filter(asset => asset.enabled && BigInt(asset.balanceBaseUnits) > 0n);
    if (positive.length === 0) return balances;
    const priceAssets = positive.map(paymentPriceAsset);
    const prices = await this.priceReader.prices(priceAssets, AbortSignal.timeout(10_000)).catch(() => new Map<string, string>());
    return balances.map(asset => ({ ...asset, usdValue: paymentUsdValue(asset, prices.get(asset.id)) }));
  }

  async balance(asset: PaymentAssetDefinition, wallet: PurchaseWallet): Promise<string> {
    if (wallet.chain !== asset.walletChain) throw new PurchaseRpcError();
    await this.requireSolanaMainnet();
    if (asset.address === SOL_NATIVE) {
      const raw = await this.rpc.call('SOLANA', 'getBalance', [wallet.address, { commitment: 'confirmed' }]);
      return z.object({ value: z.union([z.string().regex(/^\d+$/), z.number().int().nonnegative()]) }).passthrough()
        .parse(raw).value.toString();
    }
    const raw = await this.rpc.call('SOLANA', 'getTokenAccountsByOwner', [wallet.address, { mint: asset.address }, { encoding: 'jsonParsed', commitment: 'confirmed' }]);
    const tokenAmount = z.object({ amount: z.string().regex(/^\d+$/), decimals: MintDecimals }).passthrough();
    const tokenAccount = z.object({ account: z.object({ data: z.object({ parsed: z.object({ info: z.object({
      tokenAmount,
    }).passthrough() }).passthrough() }).passthrough() }).passthrough() }).passthrough();
    const rows = z.object({ value: z.array(tokenAccount).max(64) }).passthrough().parse(raw).value;
    return rows.reduce((total, row) => {
      const amount = row.account.data.parsed.info.tokenAmount;
      if (amount.decimals !== asset.decimals) throw new PurchaseRpcError();
      return total + BigInt(amount.amount);
    }, 0n).toString();
  }

  async tokenDecimals(network: PurchaseNetwork, address: string): Promise<number> {
    await this.requireSolanaMainnet();
    const raw = await this.rpc.call(network, 'getTokenSupply', [address, { commitment: 'confirmed' }]);
    return z.object({ value: z.object({ decimals: MintDecimals }).passthrough() }).passthrough().parse(raw).value.decimals;
  }

  async tokenUiMultiplier(network: PurchaseNetwork, address: string, decimals: number): Promise<string> {
    await this.requireSolanaMainnet();
    const raw = await this.rpc.call(network, 'getAccountInfo', [address, { encoding: 'base64', commitment: 'confirmed' }]);
    const envelope = z.object({ value: z.unknown() }).passthrough().parse(raw);
    return readSolanaMintUiMultiplier(envelope.value, decimals, Date.now());
  }

}

/** Purchase defaults use the same bounded, exact-asset price reader as wallet valuation. */
function paymentPriceAsset(asset: PaymentAsset): PortfolioAsset {
  if (!['SOL', 'USDC'].includes(asset.symbol)) throw new Error('Only supported funding assets have payment valuations');
  return {
    key: asset.id,
    chain: 'SOLANA',
    address: asset.symbol === 'SOL' ? SOL_MINT : asset.symbol === 'USDC' ? asset.address : null,
    stockId: null,
    decimals: asset.decimals,
    pricing: 'solana',
  };
}

/** An unavailable, malformed, zero or unbounded price never becomes an inferred wallet value. */
function paymentUsdValue(asset: PaymentAsset, rawPrice: string | undefined): string | null {
  if (!asset.enabled || BigInt(asset.balanceBaseUnits) <= 0n || rawPrice === undefined ||
    !/^(?:0|[1-9]\d*)(?:\.\d+)?$/.test(rawPrice) || rawPrice.length > 100) return null;
  try {
    const price = new Money(rawPrice);
    const value = new Money(asset.balance).times(price);
    if (!price.isFinite() || price.lte(0) || price.gt('1e20') || !value.isFinite() || value.lte(0) || value.gt('1e60')) return null;
    const serialized = value.toFixed();
    return serialized.length <= 100 ? serialized : null;
  } catch { return null; }
}

export function destinationFrom(network: PurchaseNetwork, address: string, symbol: string, decimals: number): PurchaseDestination {
  return { id: `${network}:${address}`, network, chainId: PURCHASE_CHAIN_IDS[network] as PurchaseDestination['chainId'],
    address, symbol, decimals, enabled: true };
}
