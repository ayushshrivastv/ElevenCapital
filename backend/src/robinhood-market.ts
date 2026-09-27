import { Decimal } from 'decimal.js';
import { isAddress } from 'viem';
import { z } from 'zod';
import { nonnegativeAmount } from './activity.js';
import type { GetJson } from './http.js';
import { featuredRobinhoodStocks } from './robinhood.js';
import { reviewedRobinhoodMarketPool } from './robinhood-market-pools.js';
import { DecimalString, StockSchema, type Stock } from './schema.js';

const Money = Decimal.clone({ precision: 128 });
const TOKEN = /^0x[0-9a-fA-F]{40}$/;
const POOL = /^0x(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})$/;
const USDG = '0x5fc5360d0400a0fd4f2af552add042d716f1d168';
// https://docs.robinhood.com/chain/protocol-contracts/ — mainnet L2 Weth, distinct from testnet.
const WETH = '0x0bd7d308f8e1639fab988df18a8011f41eacad73';
// Uniswap v4 represents Robinhood Chain's native ETH currency with the zero address.
const NATIVE_ETH = '0x0000000000000000000000000000000000000000';
const MAX_AGE_MS = 300_000;
const REFRESH_MS = 60_000;
const DISCOVERY_RETRY_MS = 600_000;
type Row = Record<string, unknown>;
type Market = { identity: string; pool: string; stockSide: 'base' | 'quote'; at: number; activity: Stock['activity'] };
type TokenQuote = { identity: string; at: number; quote: Stock['quote'] };
const object = (value: unknown): Row => typeof value === 'object' && value !== null && !Array.isArray(value) ? value as Row : {};
const rows = (value: unknown): unknown[] => z.array(z.unknown()).max(1_000).parse(value);
const positive = (value: unknown): string | null => { const amount = nonnegativeAmount(value); return amount !== null && new Money(amount).gt(0) ? amount : null; };
function token(stock: Stock): string | null {
  const deployment = stock.deployments[0];
  return stock.provider === 'robinhood' && stock.deployments.length === 1 && deployment?.chainId === 4663 &&
    deployment.network === 'Robinhood Chain' && TOKEN.test(deployment.address) && isAddress(deployment.address)
    ? deployment.address.toLowerCase() : null;
}
const identity = (stock: Stock) => JSON.stringify([stock.providerAssetId, stock.symbol, stock.underlying.isin, stock.deployments]);
function relationship(row: Row, field: string): string | null {
  const data = object(object(object(row.relationships)[field]).data);
  return data.type === 'token' && typeof data.id === 'string' && /^robinhood_0x[0-9a-f]{40}$/.test(data.id) ? data.id : null;
}
function poolAddress(row: Row): string | null {
  const address = object(row.attributes).address;
  return row.type === 'pool' && typeof address === 'string' && POOL.test(address) && row.id === `robinhood_${address.toLowerCase()}`
    ? address.toLowerCase() : null;
}
function stockSide(row: Row, address: string, quoteAddresses: ReadonlySet<string>): 'base' | 'quote' | null {
  const base = relationship(row, 'base_token'), quote = relationship(row, 'quote_token');
  if (!base || !quote || base === quote) return null;
  const extraQuotes = object(object(row.relationships).quote_tokens).data;
  if (extraQuotes !== undefined && (!Array.isArray(extraQuotes) || extraQuotes.length !== 1 ||
    object(extraQuotes[0]).type !== 'token' || object(extraQuotes[0]).id !== quote)) return null;
  // Unrecognized multi-currency attributes cannot establish a two-token quote-side direction.
  if (object(row.attributes).quote_tokens !== undefined) return null;
  if (base === `robinhood_${address}` && quoteAddresses.has(quote.slice('robinhood_'.length))) return 'base';
  const dex = object(object(object(row.relationships).dex).data);
  // These reviewed AMMs are two-currency pools. Do not infer a quote token's flow in an unknown multi-asset protocol.
  const twoTokenAmm = dex.type === 'dex' && ['uniswap-v3-robinhood', 'uniswap-v4-robinhood'].includes(String(dex.id));
  return quote === `robinhood_${address}` && quoteAddresses.has(base.slice('robinhood_'.length)) && twoTokenAmm ? 'quote' : null;
}
function eligiblePool(raw: unknown, address: string, quoteAddresses: ReadonlySet<string>): raw is Row {
  const row = object(raw), attrs = object(row.attributes);
  const liquidity = nonnegativeAmount(attrs.reserve_in_usd);
  return poolAddress(row) !== null && stockSide(row, address, quoteAddresses) !== null && liquidity !== null;
}
function selectPool(raw: readonly unknown[], address: string, quoteAddresses: ReadonlySet<string>): Row | undefined {
  const candidates = raw.filter(row => eligiblePool(row, address, quoteAddresses));
  const counts = new Map<string, number>();
  for (const row of candidates) counts.set(poolAddress(row)!, (counts.get(poolAddress(row)!) ?? 0) + 1);
  return candidates.filter(row => counts.get(poolAddress(row)!) === 1).sort((a, b) =>
    new Money(object(b.attributes).reserve_in_usd as string).comparedTo(object(a.attributes).reserve_in_usd as string) ||
    poolAddress(a)!.localeCompare(poolAddress(b)!))[0];
}
function agrees(first: string, second: string): boolean {
  // Providers independently round aggregate decimal fields. Do not infer sides from counts.
  const tolerance = Money.max('0.000001', new Money(first).abs().times('0.000000000001'));
  return new Money(first).minus(second).abs().lte(tolerance);
}

/** A verified two-token pool supplies USD turnover; default trade/breakdown direction is the base token.
 * https://docs.coingecko.com/reference/pool-trades-contract-address documents token=base as the default.
 */
export function normalizeRobinhoodMarket(raw: unknown, stock: Stock, fetchedAt: number,
  quoteAddresses: ReadonlySet<string>): Omit<Market, 'identity' | 'at'> | null {
  const address = token(stock);
  if (!address || !Number.isFinite(fetchedAt) || !eligiblePool(raw, address, quoteAddresses)) return null;
  const row = object(raw), attrs = object(row.attributes);
  const side = stockSide(row, address, quoteAddresses)!;
  const createdAt = typeof attrs.pool_created_at === 'string' ? Date.parse(attrs.pool_created_at) : NaN;
  if (!Number.isFinite(createdAt) || createdAt > fetchedAt) return null;
  const volume24h = nonnegativeAmount(object(attrs.volume_usd).h24);
  const buy = nonnegativeAmount(object(attrs.buy_volume_usd).h24);
  const sell = nonnegativeAmount(object(attrs.sell_volume_usd).h24);
  const reportedNetRaw = object(attrs.net_buy_volume_usd).h24;
  const reportedNet = DecimalString.safeParse(reportedNetRaw);
  let netVolume24h: string | null = null;
  if (volume24h !== null && buy !== null && sell !== null && agrees(volume24h, new Money(buy).plus(sell).toFixed())) {
    const net = new Money(buy).minus(sell).toFixed();
    if (DecimalString.safeParse(net).success && new Money(net).abs().lte(volume24h) &&
      (reportedNetRaw === undefined || reportedNetRaw === null || reportedNet.success && agrees(net, reportedNet.data))) {
      const oriented = side === 'quote' ? new Money(net).negated().toFixed() : net;
      if (DecimalString.safeParse(oriented).success) netVolume24h = oriented;
    }
  }
  const receivedAt = new Date(fetchedAt).toISOString();
  // This public endpoint has no source observation timestamp. receivedAt is only the actual fetch time.
  return { pool: poolAddress(row)!, stockSide: side, activity: { currency: 'USD', source: 'GeckoTerminal', scope: 'robinhood_chain_token', volume24h, netVolume24h,
      receivedAt, updatedAt: null,
      volumeReason: volume24h === null ? 'The verified spot pool has not reported usable 24h USD turnover.' : null,
      netVolumeReason: netVolume24h === null ? 'Complete matching 24h USD buy and sell volumes are unavailable for the selected spot pool.' : null } };
}

/** This endpoint explicitly returns USD token prices with the same exact-address 24h price change. */
export function normalizeRobinhoodTokenQuotes(raw: unknown, stocks: readonly Stock[], fetchedAt: number): Map<string, Stock['quote']> {
  const data = object(object(raw).data), attrs = object(data.attributes);
  if (data.type !== 'simple_token_price' || !Number.isFinite(fetchedAt)) throw new Error('Invalid Robinhood USD token price response');
  const prices = object(attrs.token_prices), percents = object(attrs.h24_price_change_percentage);
  if (Object.keys(prices).length > 1_000 || Object.keys(percents).length > 1_000) throw new Error('USD token price response is too large');
  const result = new Map<string, Stock['quote']>();
  for (const stock of stocks) {
    const address = token(stock); if (!address) continue;
    const price = positive(prices[address]); if (price === null) continue;
    const parsed = DecimalString.safeParse(percents[address]);
    const percent = parsed.success ? new Money(parsed.data) : null;
    const normalizedPercent = percent?.isFinite() && percent.gt(-100) && percent.abs().lte('1e6') && percent.decimalPlaces() <= 90 ? percent.toFixed() : null;
    const changePercent = DecimalString.safeParse(normalizedPercent).success ? normalizedPercent : null;
    result.set(stock.id, { price, currency: 'USD', currencyBasis: 'quoted_currency', changeAmount: null, changePercent,
      asOf: null, receivedAt: new Date(fetchedAt).toISOString(), basis: 'onchain_token_market' });
  }
  return result;
}

/** Public batches warm independently of the viewport; cached observations keep their original fetch clock. */
export class RobinhoodMarketService {
  private readonly markets = new Map<string, Market>();
  private readonly references = new Map<string, TokenQuote>();
  private readonly pools = new Map<string, { identity: string; pool: string; discoveredAt: number }>();
  private readonly rejectedHints = new Map<string, string>();
  private readonly discoveryMisses = new Map<string, { identity: string; at: number; alternatives: boolean; attemptedAt?: number }>();
  private backgroundCursor = 0;
  private pending?: Promise<void>;
  private firstBatch?: Promise<void>;
  private initialFeaturedAttemptComplete = false;
  private controller?: AbortController;
  private lastRefresh = Number.NEGATIVE_INFINITY;
  private readonly counters = { refreshes: 0, batches: 0, errors: 0, timedOut: 0, lastErrors: [] as { status: number | null; message: string }[] };
  get metrics() {
    const fresh = (at: number) => this.now() - at >= 0 && this.now() - at <= MAX_AGE_MS;
    return { ...this.counters, pending: this.pending !== undefined, backgroundCursor: this.backgroundCursor,
      available: [...this.references.values()].filter(row => fresh(row.at)).length,
      activityAvailable: [...this.markets.values()].filter(row => fresh(row.at) && row.activity.volume24h !== null).length,
      netAvailable: [...this.markets.values()].filter(row => fresh(row.at) && row.activity.netVolume24h !== null).length,
      discoveryMisses: this.discoveryMisses.size };
  }
  constructor(private readonly http: GetJson, private readonly now = Date.now, private readonly sweepBudgetMs = 90_000) {}
  cancel(): void { this.controller?.abort(); }
  observations() {
    return [...new Set([...this.references.keys(), ...this.markets.keys()])].map(stockId => {
      const reference = this.references.get(stockId);
      const market = this.markets.get(stockId);
      return { stockId, network: 'robinhood', chainId: 4663, quoteSource: 'GeckoTerminal contract-keyed USD token price',
        quoteScope: 'contract_usd_token', quoteReceivedAt: reference?.quote.receivedAt ?? null, quoteAsOf: null,
        quoteFresh: !!reference && this.now() - reference.at >= 0 && this.now() - reference.at <= MAX_AGE_MS,
        priceUsd: reference?.quote.price ?? null, changePercent24h: reference?.quote.changePercent ?? null,
        poolAddress: market?.pool ?? null, activitySource: 'GeckoTerminal', activityScope: 'selected_spot_pool',
        activityStockSide: market?.stockSide ?? null,
        activityFresh: !!market && this.now() - market.at >= 0 && this.now() - market.at <= MAX_AGE_MS,
        activityReceivedAt: market?.activity.receivedAt ?? null, activityUpdatedAt: null,
        volumeUsd24h: market?.activity.volume24h ?? null, netVolumeUsd24h: market?.activity.netVolume24h ?? null };
    });
  }
  project(stock: Stock): Stock {
    if (!token(stock)) return stock;
    const reference = this.references.get(stock.id), age = reference ? this.now() - reference.at : NaN;
    const quote = reference && reference.identity === identity(stock) && age >= 0 && age <= MAX_AGE_MS ? reference.quote : null;
    const market = this.markets.get(stock.id), marketAge = market ? this.now() - market.at : NaN;
    const marketActivity = market && market.identity === identity(stock) && marketAge >= 0 && marketAge <= MAX_AGE_MS ? market.activity : null;
    if (!quote && !marketActivity) return stock;
    const activity: Stock['activity'] = marketActivity ?? {
      currency: 'USD', source: 'GeckoTerminal', scope: 'robinhood_chain_token', volume24h: null, netVolume24h: null,
      receivedAt: null, updatedAt: null, volumeReason: 'A verified exact-token spot pool has not reported usable 24h USD turnover.',
      netVolumeReason: 'Complete verified 24h USD buy and sell aggregates are unavailable for the selected spot pool.' };
    return StockSchema.parse({ ...stock, ...(quote ? { robinhoodReference: undefined, quote } : {}), activity,
      volume24h: activity.volume24h, volume24hBasis: activity.volume24h === null ? null : 'quote_currency_turnover' });
  }
  async featuredReady(): Promise<void> { await this.firstBatch; }
  hasCompletedInitialFeaturedAttempt(): boolean { return this.initialFeaturedAttemptComplete; }
  refresh(stocks: readonly Stock[], accept: (stocks: Stock[]) => void): Promise<void> {
    if (this.pending) return this.pending;
    if (this.now() - this.lastRefresh < REFRESH_MS) return Promise.resolve();
    const eligible = stocks.filter(stock => token(stock) !== null);
    if (!eligible.length) return Promise.resolve();
    const featured = featuredRobinhoodStocks(eligible), selected = new Set(featured.map(stock => stock.id));
    const remaining = eligible.filter(stock => !selected.has(stock.id)).sort((a, b) => a.symbol.localeCompare(b.symbol) || a.id.localeCompare(b.id));
    const quotes = new Set([USDG, WETH, NATIVE_ETH, ...eligible.map(stock => token(stock)!)]);
    const currentIdentities = new Map(eligible.map(stock => [stock.id, identity(stock)]));
    for (const [id, row] of this.markets) if (currentIdentities.get(id) !== row.identity) this.markets.delete(id);
    for (const [id, row] of this.pools) if (currentIdentities.get(id) !== row.identity) this.pools.delete(id);
    for (const [id, value] of this.rejectedHints) if (currentIdentities.get(id) !== value) this.rejectedHints.delete(id);
    for (const [id, row] of this.references) if (currentIdentities.get(id) !== row.identity) this.references.delete(id);
    for (const [id, row] of this.discoveryMisses) if (currentIdentities.get(id) !== row.identity) this.discoveryMisses.delete(id);
    this.lastRefresh = this.now();
    const controller = new AbortController(); this.controller = controller;
    const timeout = setTimeout(() => { this.counters.timedOut++; controller.abort(new Error('Robinhood market sweep exceeded its time budget')); }, this.sweepBudgetMs);
    timeout.unref();
    let ready!: () => void;
    const initialAttempt = this.firstBatch === undefined;
    this.firstBatch = new Promise<void>(resolve => { ready = () => {
      if (initialAttempt) this.initialFeaturedAttemptComplete = true;
      resolve();
    }; });
    this.pending = (async () => {
      try {
        const priceBatch = async (batch: readonly Stock[]) => {
          try {
            const raw = await this.http('geckoterminal', `/api/v2/simple/networks/robinhood/token_price/${batch.map(stock => token(stock)!).join(',')}`,
              { include_24hr_price_change: 'true' }, controller.signal);
            const fetchedAt = this.now(), prices = normalizeRobinhoodTokenQuotes(raw, batch, fetchedAt);
            for (const stock of batch) {
              const quote = prices.get(stock.id);
              if (quote) this.references.set(stock.id, { identity: identity(stock), quote, at: fetchedAt });
              else this.references.delete(stock.id);
            }
          } catch (error) { this.failed(error); }
          accept(batch.map(stock => this.project(stock)));
        };
        const activityBatch = async (batch: readonly Stock[]) => {
          try { await this.refreshBatch(batch, quotes, controller.signal, () => accept(batch.map(stock => this.project(stock)))); this.counters.batches++; }
          catch (error) { this.failed(error); }
          accept(batch.map(stock => this.project(stock)));
        };
        if (featured.length) {
          await priceBatch(featured);
          if (controller.signal.aborted) return;
          await activityBatch(featured);
        }
        ready();
        // A bounded round-robin advances before I/O. Failed or slow discovery can never pin every pass to the same rows.
        let attempted = 0;
        for (let index = 0; index < 2 && attempted < remaining.length; index++) {
          if (controller.signal.aborted) return;
          const count = Math.min(20, remaining.length - attempted), start = this.backgroundCursor % remaining.length;
          const batch = Array.from({ length: count }, (_, offset) => remaining[(start + offset) % remaining.length]!);
          this.backgroundCursor = (start + count) % remaining.length; attempted += count;
          await priceBatch(batch);
          if (controller.signal.aborted) return;
          await activityBatch(batch);
        }
      } finally {
        clearTimeout(timeout); this.counters.refreshes++;
        if (this.controller === controller) this.controller = undefined;
        ready();
      }
    })().finally(() => { this.pending = undefined; });
    return this.pending;
  }
  private failed(error: unknown): void {
    this.counters.errors++;
    const status = object(error).status;
    this.counters.lastErrors = [...this.counters.lastErrors.slice(-3), { status: typeof status === 'number' ? status : null,
      message: error instanceof Error ? error.message : 'Market source unavailable' }];
  }
  private async refreshBatch(batch: readonly Stock[], quoteAddresses: ReadonlySet<string>, signal: AbortSignal, publish: () => void): Promise<void> {
    for (const stock of batch) if (!this.pools.has(stock.id)) {
      const pool = reviewedRobinhoodMarketPool(stock);
      if (pool && this.rejectedHints.get(stock.id) !== identity(stock)) this.pools.set(stock.id, { identity: identity(stock), pool, discoveredAt: this.now() });
    }
    const needsDiscovery = batch.filter(stock => {
      const cached = this.pools.get(stock.id);
      const miss = this.discoveryMisses.get(stock.id), missAge = miss ? this.now() - miss.at : NaN;
      return (!cached || cached.identity !== identity(stock) || this.now() - cached.discoveredAt >= 3_600_000) &&
        !(miss?.identity === identity(stock) && missAge >= 0 && missAge < DISCOVERY_RETRY_MS);
    });
    if (needsDiscovery.length) {
      try {
        const response = object(await this.http('geckoterminal', `/api/v2/networks/robinhood/tokens/multi/${needsDiscovery.map(stock => token(stock)!).join(',')}`, { include: 'top_pools' }, signal));
        const tokens = rows(response.data), included = rows(response.included ?? []);
        for (const stock of needsDiscovery) {
          signal.throwIfAborted();
          const address = token(stock)!;
          const matches = tokens.map(object).filter(row => row.type === 'token' && row.id === `robinhood_${address}` &&
            object(row.attributes).address === address && Number(object(row.attributes).decimals) === stock.deployments[0]!.decimals);
          const top = matches.length === 1 ? z.array(z.unknown()).max(1_000).safeParse(object(object(matches[0]!.relationships).top_pools).data) : null;
          const ids = top?.success ? new Set(top.data.map(object).filter(row => row.type === 'pool').map(row => row.id)) : new Set();
          const pool = selectPool(included.filter(row => ids.has(object(row).id)), address, quoteAddresses);
          if (pool) {
            this.pools.set(stock.id, { identity: identity(stock), pool: poolAddress(pool)!, discoveredAt: this.now() });
            this.discoveryMisses.delete(stock.id);
          } else {
            this.pools.delete(stock.id);
            const previous = this.discoveryMisses.get(stock.id);
            this.discoveryMisses.set(stock.id, { identity: identity(stock), at: this.now(), alternatives: matches.length === 1,
              attemptedAt: previous?.identity === identity(stock) ? previous.attemptedAt : undefined });
          }
        }
      } catch (error) { this.failed(error); signal.throwIfAborted(); }
    }
    const selected = batch.filter(stock => this.pools.get(stock.id)?.identity === identity(stock));
    if (selected.length) await this.fetchPoolActivity(selected, quoteAddresses, signal);
    publish();
    signal.throwIfAborted();
    // Publish healthy bulk results before a slower alternative request. Retry one fair miss per batch, never a serial fan-out.
    const alternatives = batch.filter(stock => {
      const miss = this.discoveryMisses.get(stock.id);
      return !this.pools.has(stock.id) && miss?.identity === identity(stock) && miss.alternatives &&
        (miss.attemptedAt === undefined || this.now() - miss.attemptedAt >= DISCOVERY_RETRY_MS);
    }).sort((a, b) => (this.discoveryMisses.get(a.id)!.attemptedAt ?? Number.NEGATIVE_INFINITY) -
      (this.discoveryMisses.get(b.id)!.attemptedAt ?? Number.NEGATIVE_INFINITY) || a.symbol.localeCompare(b.symbol) || a.id.localeCompare(b.id));
    const stock = alternatives[0];
    if (!stock) return;
    this.discoveryMisses.get(stock.id)!.attemptedAt = this.now();
    try {
      const response = object(await this.http('geckoterminal', `/api/v2/networks/robinhood/tokens/${token(stock)!}/pools`,
        { include: 'base_token,quote_token', page: '1' }, signal));
      const pool = selectPool(rows(response.data), token(stock)!, quoteAddresses);
      if (pool) {
        this.pools.set(stock.id, { identity: identity(stock), pool: poolAddress(pool)!, discoveredAt: this.now() });
        this.discoveryMisses.delete(stock.id);
        await this.fetchPoolActivity([stock], quoteAddresses, signal);
        publish();
      }
    } catch (error) { this.failed(error); signal.throwIfAborted(); }
  }
  private async fetchPoolActivity(selected: readonly Stock[], quoteAddresses: ReadonlySet<string>, signal: AbortSignal): Promise<void> {
    const requested = [...new Set(selected.map(stock => this.pools.get(stock.id)!.pool))];
    const response = object(await this.http('geckoterminal', `/api/v2/networks/robinhood/pools/multi/${requested.join(',')}`,
      { include: 'base_token,quote_token', include_volume_breakdown: 'true' }, signal));
    const fetchedAt = this.now(), pools = rows(response.data);
    for (const stock of selected) {
      const requestedPool = this.pools.get(stock.id)!.pool;
      const matches = pools.filter(row => poolAddress(object(row)) === requestedPool);
      const normalized = matches.length === 1 ? normalizeRobinhoodMarket(matches[0], stock, fetchedAt, quoteAddresses) : null;
      if (normalized) this.markets.set(stock.id, { ...normalized, identity: identity(stock), at: fetchedAt });
      else {
        if (reviewedRobinhoodMarketPool(stock) === requestedPool) this.rejectedHints.set(stock.id, identity(stock));
        this.markets.delete(stock.id); this.pools.delete(stock.id);
      }
    }
  }
}
