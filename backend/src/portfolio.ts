import { createHash } from 'node:crypto';
import type { Catalog } from './schema.js';
import { Registry } from './registry.js';
import { EthereumAddress, isSolanaAddress, PortfolioRequestSchema, PortfolioSchema, type Portfolio, type PortfolioRequest, type PortfolioNetwork, type PortfolioReadWallet } from './portfolio-schema.js';
import { ARB_USDC, ETH_USDC, SOL_MINT, SOL_USDC, Money, PublicPortfolioUpstream, type AssetBalance, type BalanceRead, type PortfolioAsset, type PortfolioUpstream } from './portfolio-upstream.js';

const CHAINS: PortfolioNetwork[] = ['SOLANA', 'ETHEREUM', 'ARBITRUM'];
const ExpectedStockIds = Registry.flatMap(entry => [`backed:${entry.backed.assetId}`, `backpack:${entry.backpack.assetId}`]);
function tokenSymbol(asset: PortfolioAsset): string {
  if (asset.key === 'SOLANA:native') return 'SOL';
  if (asset.key === `SOLANA:${SOL_MINT}`) return 'WSOL';
  if (asset.key === `SOLANA:${SOL_USDC}` || asset.key === `ETHEREUM:${ETH_USDC}` || asset.key === `ARBITRUM:${ARB_USDC}`) return 'USDC';
  if (asset.key === 'ETHEREUM:native' || asset.key === 'ARBITRUM:native') return 'ETH';
  if (asset.symbol && asset.chain === 'SOLANA' && asset.address && asset.symbol === `${asset.address.slice(0, 4)}…${asset.address.slice(-4)}`) return asset.symbol;
  throw new Error('Unsupported token identity');
}
function discoveredSolanaToken(asset: PortfolioAsset): boolean {
  return asset.chain === 'SOLANA' && asset.stockId === null && asset.address !== null && isSolanaAddress(asset.address) &&
    asset.key === `SOLANA:${asset.address}` && Number.isInteger(asset.decimals) && asset.decimals! >= 0 && asset.decimals! <= 36 &&
    asset.pricing === 'solana' && asset.symbol === `${asset.address.slice(0, 4)}…${asset.address.slice(-4)}`;
}
export function portfolioAssets(catalog: Catalog, expectedIds = ExpectedStockIds): { assets: PortfolioAsset[]; complete: boolean } {
  const assets: PortfolioAsset[] = [
    { key: 'SOLANA:native', chain: 'SOLANA', address: null, stockId: null, decimals: 9, pricing: 'solana' },
    { key: `SOLANA:${SOL_MINT}`, chain: 'SOLANA', address: SOL_MINT, stockId: null, decimals: 9, pricing: 'solana' },
    { key: `SOLANA:${SOL_USDC}`, chain: 'SOLANA', address: SOL_USDC, stockId: null, decimals: 6, pricing: 'solana' },
    { key: 'ETHEREUM:native', chain: 'ETHEREUM', address: null, stockId: null, decimals: 18, pricing: 'ETH' },
    { key: `ETHEREUM:${ETH_USDC}`, chain: 'ETHEREUM', address: ETH_USDC, stockId: null, decimals: 6, pricing: 'USDC' },
    { key: 'ARBITRUM:native', chain: 'ARBITRUM', address: null, stockId: null, decimals: 18, pricing: 'ETH' },
    { key: `ARBITRUM:${ARB_USDC}`, chain: 'ARBITRUM', address: ARB_USDC, stockId: null, decimals: 6, pricing: 'USDC' },
  ];
  let complete = expectedIds.length > 0;
  const byKey = new Map<string, PortfolioAsset>(assets.map(asset => [asset.key, asset]));
  const ambiguous = new Set<string>();
  for (const id of expectedIds) {
    const candidates = catalog.stocks.filter(stock => stock.id === id);
    if (candidates.length !== 1) { complete = false; continue; }
    const stock = candidates[0]!;
    let supported = 0;
    for (const deployment of stock.deployments) {
      const name = deployment.network.trim().toLowerCase();
      const chain = name === 'solana' ? 'SOLANA' : name === 'ethereum' ? 'ETHEREUM' : null;
      if (!chain) continue;
      if (!(chain === 'SOLANA' ? isSolanaAddress(deployment.address) : EthereumAddress.test(deployment.address))) { complete = false; continue; }
      const address = chain === 'ETHEREUM' ? deployment.address.toLowerCase() : deployment.address;
      const key = `${chain}:${address}`;
      const asset: PortfolioAsset = { key, chain, address, stockId: id, decimals: deployment.decimals, pricing: chain === 'SOLANA' ? 'solana' : 'ethereum' };
      const previous = byKey.get(key);
      if (previous && (previous.stockId !== id || previous.decimals !== asset.decimals)) { ambiguous.add(key); complete = false; }
      else byKey.set(key, asset);
      supported++;
    }
    if (!supported) complete = false;
  }
  // An address claimed by two provider products cannot be counted twice or assigned arbitrarily.
  return { assets: [...byKey.values()].filter(asset => !ambiguous.has(asset.key)), complete };
}
export class PortfolioBusyError extends Error {}
export class WalletPortfolioService {
  private readonly cache = new Map<string, { at: number; result: Portfolio }>();
  private readonly pending = new Map<string, Promise<Portfolio>>();
  constructor(private readonly catalog: () => Promise<Catalog>, private readonly upstream: PortfolioUpstream = new PublicPortfolioUpstream(),
    private readonly now = Date.now, private readonly expectedIds = ExpectedStockIds, private readonly timeoutMs = 25_000) {}
  async portfolio(input: PortfolioRequest): Promise<Portfolio> {
    const request = PortfolioRequestSchema.parse(input);
    const wallets = request.wallets.map(wallet => ({ ...wallet, address: wallet.chain === 'ETHEREUM' ? wallet.address.toLowerCase() : wallet.address }))
      .sort((a, b) => `${a.chain}:${a.address}`.localeCompare(`${b.chain}:${b.address}`));
    // Never put wallet addresses in URLs, logs, disk, or cache identifiers.
    const key = createHash('sha256').update(JSON.stringify(wallets)).digest('hex');
    const active = this.pending.get(key);
    if (active) return active;
    const cached = this.cache.get(key);
    if (cached && this.now() - cached.at >= 0 && this.now() - cached.at < (cached.result.status === 'ok' ? 15_000 : 5_000)) return cached.result;
    if (this.pending.size >= 8) throw new PortfolioBusyError('Portfolio reads are busy');
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.timeoutMs);
    const task = this.load({ wallets }, controller.signal).catch(() => this.unavailable()).then(result => {
      if (this.cache.size >= 128) this.cache.delete(this.cache.keys().next().value!);
      this.cache.set(key, { at: this.now(), result });
      return result;
    }).finally(() => { clearTimeout(timeout); this.pending.delete(key); });
    this.pending.set(key, task);
    return task;
  }
  private unavailable(): Portfolio {
    return { schemaVersion: 1, scope: 'supported-wallet-assets', currency: 'USD', status: 'unavailable', receivedAt: new Date(this.now()).toISOString(),
      balanceUsd: null, holdingsComplete: false, holdings: [], tokenHoldings: [], unpricedAssets: 0,
      networks: CHAINS.map(chain => ({ chain, status: 'unavailable', observedAt: null })),
      message: 'Wallet balances are temporarily unavailable. Refreshing automatically.' };
  }
  private async load(request: PortfolioRequest, signal: AbortSignal): Promise<Portfolio> {
    const abort = new Promise<never>((_resolve, reject) => signal.addEventListener('abort', () => reject(new Error('Portfolio read timed out')), { once: true }));
    const run = async (): Promise<Portfolio> => {
      const catalog = await this.catalog();
      signal.throwIfAborted();
      const dynamicPreStocks = this.expectedIds === ExpectedStockIds;
      const solanaIds = catalog.stocks.filter(stock => stock.deployments.some(d => d.network.trim().toLowerCase() === 'solana')).map(stock => stock.id);
      const expectedIds = dynamicPreStocks ? [...new Set([...this.expectedIds, ...solanaIds])] : this.expectedIds;
      // Solana owner-account discovery has constant RPC cost regardless of catalog size.
      // Keep Ethereum's separately bounded contract registry while discovering every
      // verified Solana stock the user can buy through the router.
      const scopedCatalog = dynamicPreStocks ? { ...catalog, stocks: catalog.stocks.map(stock =>
        this.expectedIds.includes(stock.id) ? stock : { ...stock, deployments: stock.deployments.filter(d => d.network.trim().toLowerCase() === 'solana') }) } : catalog;
      const { assets, complete: assetsComplete } = portfolioAssets(scopedCatalog, expectedIds);
      // Provider status includes quote freshness, which does not affect balance
      // discovery. Keep the total unavailable until PreStocks deployment metadata
      // has loaded, but do not hide a fully scanned wallet when only quotes lag.
      const preStocksComplete = !dynamicPreStocks || catalog.stocks.some(stock =>
        stock.provider === 'prestocks' && stock.deployments.some(deployment => deployment.network.trim().toLowerCase() === 'solana'));
      const catalogComplete = assetsComplete && preStocksComplete;
      if (assets.length > 3_000) return this.unavailable();
      const readWallets: PortfolioReadWallet[] = [
        ...request.wallets,
        ...request.wallets.filter(wallet => wallet.chain === 'ETHEREUM').map(wallet => ({ chain: 'ARBITRUM' as const, address: wallet.address })),
      ];
      const responses = await Promise.all(readWallets.map(async wallet => {
        let result: BalanceRead;
        try { result = await this.upstream.balances(wallet, assets.filter(asset => asset.chain === wallet.chain), signal); }
        catch { result = { complete: false, observedAt: null, balances: [] }; }
        return { wallet, result };
      }));
      signal.throwIfAborted();
      const networks: Portfolio['networks'] = CHAINS.map(chain => {
        const reads = responses.filter(response => response.wallet.chain === chain);
        const complete = reads.length > 0 && reads.every(read => read.result.complete && read.result.observedAt !== null);
        return { chain, status: complete ? 'ok' : 'unavailable', observedAt: complete ? reads.map(read => read.result.observedAt!).sort()[0]! : null };
      });
      const positions = new Map<string, AssetBalance>();
      for (const { result } of responses) for (const balance of result.balances) {
        // Injected/read adapters must still return one of this catalog's exact identities.
        let asset = assets.find(asset => asset.key === balance.asset.key);
        if (asset && (asset.chain !== balance.asset.chain || asset.address !== balance.asset.address ||
          asset.stockId !== balance.asset.stockId || asset.decimals !== balance.asset.decimals)) throw new Error('Catalog balance identity mismatch');
        if (!asset && discoveredSolanaToken(balance.asset)) asset = balance.asset;
        if (!asset || !/^(?:0|[1-9]\d*)(?:\.\d+)?$/.test(balance.quantity) || balance.quantity.length > 100) throw new Error('Invalid balance result');
        const amount = new Money(balance.quantity);
        if (!amount.isFinite() || amount.lt(0) || amount.gt('1e40')) throw new Error('Invalid balance');
        const display = balance.displayQuantity ?? balance.quantity;
        if (!/^(?:0|[1-9]\d*)(?:\.\d+)?$/.test(display) || display.length > 100 || new Money(display).gt('1e40')) throw new Error('Invalid display balance');
        if (amount.isZero()) continue;
        const old = positions.get(asset.key);
        positions.set(asset.key, { asset, quantity: amount.plus(old?.quantity ?? 0).toFixed(),
          displayQuantity: new Money(display).plus(old?.displayQuantity ?? 0).toFixed() });
      }
      // Empty wallets can be proven empty even when every pricing API is unavailable.
      let prices = new Map<string, string>();
      if (positions.size) {
        try { prices = await this.upstream.prices([...positions.values()].map(position => position.asset), signal); } catch { /* Keep positive quantities. */ }
      }
      signal.throwIfAborted();
      const holdings = new Map<string, Portfolio['holdings'][number]>();
      const tokenHoldings: Portfolio['tokenHoldings'] = [];
      let total = new Money(0);
      let unpricedAssets = 0;
      for (const { asset, quantity, displayQuantity } of positions.values()) {
        const raw = prices.get(asset.key);
        const valid = raw !== undefined && /^(?:0|[1-9]\d*)(?:\.\d+)?$/.test(raw) && raw.length <= 100 && new Money(raw).gt(0) && new Money(raw).lte('1e20');
        const value = valid ? new Money(displayQuantity ?? quantity).times(raw!).toFixed() : null;
        if (value === null) unpricedAssets++; else total = total.plus(value);
        if (asset.stockId !== null) {
          const old = holdings.get(asset.stockId);
          holdings.set(asset.stockId, { stockId: asset.stockId, quantity: new Money(displayQuantity ?? quantity).plus(old?.quantity ?? 0).toFixed(),
            valueUsd: value === null || old?.valueUsd === null ? null : new Money(value).plus(old?.valueUsd ?? 0).toFixed() });
        } else {
          tokenHoldings.push({ chain: asset.chain, assetId: asset.key, symbol: tokenSymbol(asset),
            quantity: displayQuantity ?? quantity, valueUsd: value, unitPriceUsd: valid ? raw! : null });
        }
      }
      const holdingsComplete = catalogComplete && networks.every(network => network.status === 'ok');
      const complete = holdingsComplete && unpricedAssets === 0;
      const anyNetwork = networks.some(network => network.status === 'ok');
      return PortfolioSchema.parse({ schemaVersion: 1, scope: 'supported-wallet-assets', currency: 'USD',
        status: complete ? 'ok' : anyNetwork || positions.size > 0 ? 'partial' : 'unavailable',
        receivedAt: new Date(this.now()).toISOString(), balanceUsd: complete ? total.toFixed() : null, holdingsComplete,
        holdings: [...holdings.values()].sort((a, b) => a.stockId.localeCompare(b.stockId)),
        tokenHoldings: tokenHoldings.sort((a, b) => a.assetId.localeCompare(b.assetId)), unpricedAssets, networks,
        message: complete ? null : !holdingsComplete ? 'Some wallet balances are still refreshing. The total is unavailable until all supported assets are checked.' :
          'Some assets do not have an available USD price. Holdings are shown and prices refresh automatically.',
      });
    };
    return Promise.race([run(), abort]);
  }
}
