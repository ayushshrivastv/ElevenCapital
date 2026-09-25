import { z } from 'zod';
import { Decimal } from 'decimal.js';
import { limitConcurrency } from './cache.js';
import { getJson, parseProviderJson } from './http.js';
import { EthereumAddress, isSolanaAddress, type PortfolioNetwork, type PortfolioReadWallet } from './portfolio-schema.js';

export const Money = Decimal.clone({ precision: 160 });
export const SOL_MINT = 'So11111111111111111111111111111111111111112';
export const SOL_USDC = 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v';
export const ETH_USDC = '0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48';
export const ARB_USDC = '0xaf88d065e77c8cc2239327c5edb3a432268e5831';
export const TOKEN_PROGRAMS = ['TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA', 'TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb'] as const;
// getGenesisHash returns the full hash, not the truncated 32-character CAIP-2 chain reference.
// https://namespaces.chainagnostic.org/solana/caip2
export const MAINNET_GENESIS = '5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d';
export type PortfolioAsset = { key: string; chain: PortfolioNetwork; address: string | null; stockId: string | null; decimals: number | null; pricing: 'solana' | 'ethereum' | 'ETH' | 'USDC'; symbol?: string };
export type AssetBalance = { asset: PortfolioAsset; quantity: string; displayQuantity?: string };
export type BalanceRead = { complete: boolean; observedAt: string | null; balances: AssetBalance[] };
export type RpcCall = { method: string; params: unknown[] };
export interface PortfolioTransport {
  rpc(chain: PortfolioNetwork, calls: RpcCall[], signal: AbortSignal): Promise<unknown[]>;
  json(provider: 'jupiter' | 'coinbase' | 'dexscreener', path: string, query: Record<string, string>, signal: AbortSignal): Promise<unknown>;
}

const AllowedRpc: Record<PortfolioNetwork, readonly string[]> = {
  SOLANA: ['getGenesisHash', 'getBalance', 'getTokenAccountsByOwner', 'getAccountInfo'],
  ETHEREUM: ['eth_chainId', 'eth_blockNumber', 'eth_getBalance', 'eth_call'],
  ARBITRUM: ['eth_chainId', 'eth_blockNumber', 'eth_getBalance', 'eth_call'],
};

/** Solana's public endpoint accepts these reads individually but rate-limits their JSON-RPC batch. */
export function rpcRequestGroups(chain: PortfolioNetwork, calls: RpcCall[]): RpcCall[][] {
  if (calls.length < 1 || calls.length > 100) throw new Error('RPC batch exceeded bound');
  if (calls.some(call => !AllowedRpc[chain].includes(call.method))) throw new Error('Only read-only RPC is permitted');
  return chain === 'SOLANA' ? calls.map(call => [call]) : [calls];
}
export interface PortfolioUpstream {
  balances(wallet: PortfolioReadWallet, assets: PortfolioAsset[], signal: AbortSignal): Promise<BalanceRead>;
  prices(assets: PortfolioAsset[], signal: AbortSignal): Promise<Map<string, string>>;
}
export type PortfolioReadFailure = 'wrong_network' | 'rpc_rejected' | 'http_unavailable' | 'network_unavailable' | 'invalid_response' | 'timeout';
export type PortfolioReadDiagnostic = { chain: PortfolioNetwork; reason: PortfolioReadFailure };
class ReadFailure extends Error {
  constructor(readonly reason: PortfolioReadFailure) { super('Wallet read unavailable'); }
}
/** Only fixed reason codes escape. RPC error bodies, URLs and account addresses never enter logs. */
export function portfolioReadFailure(error: unknown): PortfolioReadFailure {
  if (error instanceof ReadFailure) return error.reason;
  if (error instanceof Error && ['AbortError', 'TimeoutError'].includes(error.name)) return 'timeout';
  if (error instanceof TypeError) return 'network_unavailable';
  return 'invalid_response';
}

/** Environment-controlled endpoints are fixed for the server lifetime, never supplied by a request. */
export function rpcEndpoint(raw: string): URL {
  let url: URL;
  try { url = new URL(raw); } catch { throw new Error('Invalid RPC HTTPS configuration'); }
  if (url.protocol !== 'https:' || url.username || url.password || url.hash || url.port ||
    url.hostname === 'localhost' || url.hostname.endsWith('.localhost') || url.hostname.endsWith('.local') ||
    /^[\d.]+$/.test(url.hostname) || url.hostname.includes(':')) throw new Error('RPC configuration must use a public HTTPS hostname');
  return url;
}
const runHttp = limitConcurrency(4);
async function fetchJson(url: URL, signal: AbortSignal, body?: unknown): Promise<unknown> {
  return runHttp(async () => {
    signal.throwIfAborted();
    const response = await fetch(url, { method: body ? 'POST' : 'GET', redirect: 'error',
      signal: AbortSignal.any([signal, AbortSignal.timeout(8_000)]),
      headers: { Accept: 'application/json', ...(body ? { 'Content-Type': 'application/json' } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
    if (!response.ok) throw new ReadFailure('http_unavailable');
    const reader = response.body?.getReader();
    if (!reader) throw new Error('Missing provider response');
    let size = 0;
    const chunks: Uint8Array[] = [];
    try {
      while (true) {
        const chunk = await reader.read();
        if (chunk.done) break;
        size += chunk.value.length;
        if (size > 4_000_000) throw new Error('Provider response exceeded bound');
        chunks.push(chunk.value);
      }
    } finally { await reader.cancel(); }
    return parseProviderJson(Buffer.concat(chunks).toString('utf8'));
  });
}
export function publicPortfolioTransport(env: NodeJS.ProcessEnv = process.env): PortfolioTransport {
  const urls = {
    SOLANA: rpcEndpoint(env.SOLANA_RPC_URL ?? 'https://api.mainnet.solana.com'),
    ETHEREUM: rpcEndpoint(env.ETHEREUM_RPC_URL ?? 'https://ethereum-rpc.publicnode.com'),
    ARBITRUM: rpcEndpoint(env.ARBITRUM_RPC_URL ?? 'https://arbitrum-one-rpc.publicnode.com'),
  };
  return {
    async rpc(chain, calls, signal) {
      const requests = calls.map((call, index) => ({ jsonrpc: '2.0' as const, id: String(index + 1), ...call }));
      const results: { id: string; jsonrpc: '2.0'; result?: unknown; error?: unknown }[] = [];
      let offset = 0;
      for (const group of rpcRequestGroups(chain, calls)) {
        const payload = requests.slice(offset, offset + group.length);
        offset += group.length;
        const raw = await fetchJson(urls[chain], signal, payload.length === 1 ? payload[0] : payload);
        const parsed = z.array(z.object({ id: z.string(), jsonrpc: z.literal('2.0'), result: z.unknown().optional(), error: z.unknown().optional() })).max(100)
          .parse(Array.isArray(raw) ? raw : [raw]);
        results.push(...parsed);
      }
      if (results.length !== requests.length || new Set(results.map(row => row.id)).size !== results.length) throw new Error('Incomplete RPC response');
      return requests.map(request => {
        const row = results.find(value => value.id === request.id);
        if (!row || row.error !== undefined || row.result === undefined) throw new ReadFailure('rpc_rejected');
        return row.result;
      });
    },
    async json(provider, path, query, signal) {
      if (provider === 'jupiter') return getJson('jupiter', path, query, signal);
      const origin = provider === 'coinbase' ? 'https://api.coinbase.com' : 'https://api.dexscreener.com';
      const valid = provider === 'coinbase' ? /^\/v2\/prices\/(ETH|USDC)-USD\/spot$/ : /^\/tokens\/v1\/ethereum\/0x[0-9a-f]{40}(?:,0x[0-9a-f]{40}){0,29}$/;
      if (!valid.test(path) || Object.keys(query).length) throw new Error('Invalid public price request');
      return fetchJson(new URL(path, origin), signal);
    },
  };
}
const Unsigned = z.string().regex(/^(?:0|[1-9]\d*)$/).max(78);
function quantity(raw: unknown, decimals: number): string {
  const integer = Unsigned.parse(raw);
  if (!Number.isInteger(decimals) || decimals < 0 || decimals > 36 || BigInt(integer) >= 2n ** 256n) throw new Error('Invalid asset precision');
  const amount = new Money(integer).div(new Money(10).pow(decimals));
  if (amount.gt('1e40')) throw new Error('Balance exceeded bound');
  return amount.toFixed();
}
function hexInteger(raw: unknown): bigint {
  if (typeof raw !== 'string' || !/^0x[0-9a-fA-F]{1,64}$/.test(raw)) throw new Error('Invalid RPC integer');
  return BigInt(raw);
}
function positivePrice(raw: unknown): string | null {
  if (typeof raw !== 'string' || raw.length > 100 || !/^(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(raw)) return null;
  const price = new Money(raw);
  return price.isFinite() && price.gt(0) && price.lte('1e20') && price.decimalPlaces() <= 36 ? price.toFixed() : null;
}
export function jupiterPrices(raw: unknown, requested: PortfolioAsset[], now: number): Map<string, string> {
  const rows = z.array(z.unknown()).max(100).parse(raw);
  const result = new Map<string, string>();
  for (const asset of requested) {
    const mint = asset.address ?? SOL_MINT;
    const matches = rows.filter((row): row is Record<string, unknown> => typeof row === 'object' && row !== null && 'id' in row && row.id === mint);
    if (matches.length !== 1) continue;
    const row = matches[0]!;
    const timestamp = typeof row.updatedAt === 'string' ? Date.parse(row.updatedAt) : NaN;
    const price = positivePrice(row.usdPrice);
    if (price !== null && Number.isFinite(timestamp) && timestamp >= now - 300_000 && timestamp <= now + 30_000) result.set(asset.key, price);
  }
  return result;
}
export function ethereumPrices(raw: unknown, requested: PortfolioAsset[]): Map<string, string> {
  const rows = z.array(z.unknown()).max(2_000).parse(raw);
  const result = new Map<string, string>();
  for (const asset of requested) {
    let best: { liquidity: Decimal; price: string; pair: string } | undefined;
    for (const candidate of rows) {
      const parsed = z.object({ chainId: z.literal('ethereum'), baseToken: z.object({ address: z.string() }),
        pairAddress: z.string().regex(EthereumAddress), priceUsd: z.unknown(), liquidity: z.object({ usd: z.unknown() }) }).safeParse(candidate);
      if (!parsed.success || parsed.data.baseToken.address.toLowerCase() !== asset.address) continue;
      const price = positivePrice(parsed.data.priceUsd);
      const liquidity = positivePrice(parsed.data.liquidity.usd);
      if (!price || !liquidity) continue;
      const value = new Money(liquidity);
      const pair = parsed.data.pairAddress.toLowerCase();
      if (!best || value.gt(best.liquidity) || (value.eq(best.liquidity) && pair < best.pair)) best = { liquidity: value, price, pair };
    }
    if (best) result.set(asset.key, best.price);
  }
  return result;
}

/** Every operation is an unsigned read. No wallet credential, signing or transaction method exists here. */
export class PublicPortfolioUpstream implements PortfolioUpstream {
  private readonly priceCache = new Map<string, { at: number; expiresAt: number; value: Map<string, string> }>();
  private readonly lastFailureAt = new Map<string, number>();
  constructor(private readonly transport = publicPortfolioTransport(), private readonly now = Date.now,
    private readonly diagnostic?: (value: PortfolioReadDiagnostic) => void) {}
  async balances(wallet: PortfolioReadWallet, assets: PortfolioAsset[], signal: AbortSignal): Promise<BalanceRead> {
    try {
      if (wallet.chain === 'SOLANA') return await this.solana(wallet, assets, signal);
      const balances = await this.evm(wallet, assets, signal);
      return { complete: true, observedAt: new Date(this.now()).toISOString(), balances };
    } catch (error) {
      const reason = portfolioReadFailure(error);
      const key = `${wallet.chain}:${reason}`;
      const last = this.lastFailureAt.get(key);
      if (last === undefined || this.now() - last >= 60_000 || this.now() < last) {
        this.lastFailureAt.set(key, this.now());
        try { this.diagnostic?.({ chain: wallet.chain, reason }); } catch { /* Observability cannot change a financial response. */ }
      }
      return { complete: false, observedAt: null, balances: [] };
    }
  }
  private async solana(wallet: { address: string }, assets: PortfolioAsset[], signal: AbortSignal): Promise<BalanceRead> {
    const [genesis] = await this.transport.rpc('SOLANA', [{ method: 'getGenesisHash', params: [] }], signal);
    if (genesis !== MAINNET_GENESIS) throw new ReadFailure('wrong_network');
    let complete = true;
    const nativeAsset = assets.find(asset => asset.address === null)!;
    const balances: AssetBalance[] = [];
    try {
      const [raw] = await this.transport.rpc('SOLANA', [{ method: 'getBalance',
        params: [wallet.address, { commitment: 'confirmed' }] }], signal);
      const native = z.object({ value: Unsigned }).parse(raw);
      if (BigInt(native.value) >= 2n ** 64n) throw new Error('Invalid lamports');
      balances.push({ asset: nativeAsset, quantity: quantity(native.value, 9) });
    } catch { complete = false; }
    const seenAccounts = new Set<string>();
    const seenDecimals = new Map<string, number>();
    const scaledBalances: { position: AssetBalance; mint: string; decimals: number; rawUnits: string }[] = [];
    for (const program of TOKEN_PROGRAMS) {
      const initialBalanceCount = balances.length;
      const initialScaledCount = scaledBalances.length;
      const previousAccounts = new Set(seenAccounts);
      const previousDecimals = new Map(seenDecimals);
      try {
      const [rawAccounts] = await this.transport.rpc('SOLANA', [{ method: 'getTokenAccountsByOwner',
        params: [wallet.address, { programId: program }, { encoding: 'jsonParsed', commitment: 'confirmed' }] }], signal);
      const accounts = z.object({ value: z.array(z.unknown()).max(2_000) }).parse(rawAccounts);
      for (const raw of accounts.value) {
        const account = z.object({ pubkey: z.string(), account: z.object({ owner: z.literal(program), data: z.object({ parsed: z.object({ type: z.literal('account'), info: z.object({
          mint: z.string(), owner: z.literal(wallet.address), tokenAmount: z.unknown(),
        }) }) }) }) }).parse(raw);
        if (!isSolanaAddress(account.pubkey) || seenAccounts.has(account.pubkey)) throw new Error('Duplicate or invalid token account');
        seenAccounts.add(account.pubkey);
        const info = account.account.data.parsed.info;
        if (!isSolanaAddress(info.mint)) throw new Error('Invalid token mint');
        const listed = assets.find(asset => asset.address === info.mint);
        const token = z.object({ amount: Unsigned, decimals: z.coerce.number().int().min(0).max(36) }).parse(info.tokenAmount);
        // An owned SPL account is a real holding even if the token is not in the
        // curated stock catalog. Keep its exact mint and quantity without inventing
        // an issuer name or USD valuation.
        const asset: PortfolioAsset = listed ?? { key: `SOLANA:${info.mint}`, chain: 'SOLANA', address: info.mint,
          stockId: null, decimals: token.decimals, pricing: 'solana', symbol: `${info.mint.slice(0, 4)}…${info.mint.slice(-4)}` };
        if ((asset.decimals !== null && asset.decimals !== token.decimals) ||
          (seenDecimals.has(asset.key) && seenDecimals.get(asset.key) !== token.decimals)) throw new Error('Token decimal mismatch');
        seenDecimals.set(asset.key, token.decimals);
        if (BigInt(token.amount) >= 2n ** 64n) throw new Error('Invalid token balance');
        const position: AssetBalance = { asset, quantity: quantity(token.amount, token.decimals) };
        balances.push(position);
        if (program === TOKEN_PROGRAMS[1] && asset.stockId && BigInt(token.amount) > 0n) {
          scaledBalances.push({ position, mint: info.mint, decimals: token.decimals, rawUnits: token.amount });
        }
      }
      } catch {
        // Preserve already verified native and other-program balances. A failed
        // program scan cannot prove an empty wallet or a complete USD total.
        complete = false;
        balances.length = initialBalanceCount;
        scaledBalances.length = initialScaledCount;
        seenAccounts.clear(); for (const account of previousAccounts) seenAccounts.add(account);
        seenDecimals.clear(); for (const [key, value] of previousDecimals) seenDecimals.set(key, value);
      }
    }
    if (scaledBalances.length) {
      // Only held Token-2022 stock mints need this read. Jupiter's current USD price
      // is per scaled UI unit, so both share display and valuation use this quantity.
      const { readSolanaMintUiMultiplier } = await import('./solana-swap-validation.js');
      const mints = [...new Set(scaledBalances.map(value => value.mint))];
      if (mints.length > 100) throw new Error('Too many distinct held stock mints');
      for (const mint of mints) {
        const heldForMint = scaledBalances.filter(value => value.mint === mint);
        try {
          const [raw] = await this.transport.rpc('SOLANA', [{ method: 'getAccountInfo',
            params: [mint, { encoding: 'base64', commitment: 'confirmed' }] }], signal);
          const envelope = z.object({ value: z.unknown() }).parse(raw);
          for (const held of heldForMint) {
            const factor = readSolanaMintUiMultiplier(envelope.value, held.decimals, this.now());
            held.position.displayQuantity = new Money(held.rawUnits).times(factor).toDecimalPlaces(0, Decimal.ROUND_DOWN)
              .div(new Money(10).pow(held.decimals)).toFixed();
          }
        } catch {
          complete = false;
          // Raw Token-2022 units may be scaled. Omit this mint until its exact
          // on-chain multiplier can be read rather than showing a false quantity.
          for (const held of heldForMint) {
            const index = balances.indexOf(held.position);
            if (index >= 0) balances.splice(index, 1);
          }
        }
      }
    }
    return { complete, observedAt: balances.length ? new Date(this.now()).toISOString() : null, balances };
  }
  private async evm(wallet: PortfolioReadWallet, assets: PortfolioAsset[], signal: AbortSignal): Promise<AssetBalance[]> {
    if (wallet.chain !== 'ETHEREUM' && wallet.chain !== 'ARBITRUM') throw new Error('Unsupported EVM network');
    const network = wallet.chain;
    const expectedChainId = network === 'ARBITRUM' ? 42161n : 1n;
    const [chain, block] = await this.transport.rpc(network, [{ method: 'eth_chainId', params: [] }, { method: 'eth_blockNumber', params: [] }], signal);
    if (hexInteger(chain) !== expectedChainId || hexInteger(block) < 1n) throw new ReadFailure('wrong_network');
    const tokenAssets = assets.filter(asset => asset.address !== null);
    const calls = [{ method: 'eth_getBalance', params: [wallet.address, block] }, ...tokenAssets.flatMap(asset => [
      { method: 'eth_call', params: [{ to: asset.address, data: `0x70a08231${wallet.address.slice(2).toLowerCase().padStart(64, '0')}` }, block] },
      { method: 'eth_call', params: [{ to: asset.address, data: '0x313ce567' }, block] },
    ])];
    const batches: RpcCall[][] = [];
    for (let start = 0; start < calls.length; start += 80) batches.push(calls.slice(start, start + 80));
    const raw = (await Promise.all(batches.map(batch => this.transport.rpc(network, batch, signal)))).flat();
    const balances = [{ asset: assets.find(asset => asset.address === null)!, quantity: quantity(hexInteger(raw[0]).toString(), 18) }];
    tokenAssets.forEach((asset, index) => {
      const decimals = Number(hexInteger(raw[index * 2 + 2]));
      if (!Number.isSafeInteger(decimals) || decimals > 36 || (asset.decimals !== null && asset.decimals !== decimals)) throw new Error('Token decimal mismatch');
      balances.push({ asset, quantity: quantity(hexInteger(raw[index * 2 + 1]).toString(), decimals) });
    });
    return balances;
  }
  async prices(assets: PortfolioAsset[], signal: AbortSignal): Promise<Map<string, string>> {
    const result = new Map<string, string>();
    const groups: PortfolioAsset[][] = [assets.filter(asset => asset.pricing === 'solana'), assets.filter(asset => asset.pricing === 'ethereum'),
      assets.filter(asset => asset.pricing === 'ETH'), assets.filter(asset => asset.pricing === 'USDC')];
    await Promise.all(groups.filter(group => group.length).map(async group => {
      const key = group.map(asset => asset.key).sort().join(',');
      const cached = this.priceCache.get(key);
      if (cached && this.now() >= cached.at && this.now() < cached.expiresAt) { for (const entry of cached.value) result.set(...entry); return; }
      try {
        let values: Map<string, string>;
        let sourceExpiresAt = Number.POSITIVE_INFINITY;
        const kind = group[0]!.pricing;
        if (kind === 'solana') {
          values = new Map();
          for (let start = 0; start < group.length; start += 30) {
            const batch = group.slice(start, start + 30);
            const raw = await this.transport.json('jupiter', '/tokens/v2/search',
              { query: [...new Set(batch.map(asset => asset.address ?? SOL_MINT))].join(',') }, signal);
            for (const entry of jupiterPrices(raw, batch, this.now())) values.set(...entry);
            const pricedMints = new Set(batch.filter(asset => values.has(asset.key)).map(asset => asset.address ?? SOL_MINT));
            // A cache hit must not extend source observations beyond the five-minute cutoff.
            for (const row of raw as Record<string, unknown>[]) if (row && pricedMints.has(String(row.id))) {
              sourceExpiresAt = Math.min(sourceExpiresAt, Date.parse(String(row.updatedAt)) + 300_000);
            }
          }
        } else if (kind === 'ethereum') {
          values = new Map();
          for (let start = 0; start < group.length; start += 30) {
            const batch = group.slice(start, start + 30);
            for (const entry of ethereumPrices(await this.transport.json('dexscreener', `/tokens/v1/ethereum/${batch.map(asset => asset.address).join(',')}`, {}, signal), batch)) values.set(...entry);
          }
        } else {
          const raw = z.object({ data: z.object({ currency: z.literal('USD'), amount: z.unknown(), base: z.string().optional() }) }).parse(
            await this.transport.json('coinbase', `/v2/prices/${kind}-USD/spot`, {}, signal));
          const price = positivePrice(raw.data.amount);
          values = price && (raw.data.base === undefined || raw.data.base === kind) ? new Map(group.map(asset => [asset.key, price])) : new Map();
        }
        if (this.priceCache.size >= 128) this.priceCache.delete(this.priceCache.keys().next().value!);
        const at = this.now();
        this.priceCache.set(key, { at, expiresAt: Math.min(at + 15_000, sourceExpiresAt), value: values });
        for (const entry of values) result.set(...entry);
      } catch { this.priceCache.delete(key); }
    }));
    return result;
  }
}
