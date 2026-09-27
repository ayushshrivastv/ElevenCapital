import { z } from 'zod';
import { Decimal } from 'decimal.js';
import { getAddress, isAddress } from 'viem';
import { DecimalString, DisabledTrading, type RobinhoodReference, type Stock } from './schema.js';
import { classifySymbol, loadClassificationDirectory, NasdaqBaseline } from './classification.js';
import { featuredCompanySummary } from './featured-about.js';
import { nonnegativeAmount } from './activity.js';
import { initialStatistics } from './statistics.js';
import { reviewedRobinhoodCompanyLogo } from './robinhood-company-logos.js';
import type { GetJson } from './http.js';
import type { CatalogReconciliation, ProviderLoadOptions, ProviderSnapshot } from './providers.js';

export const ROBINHOOD_CHAIN_ID = 4663;
export const ROBINHOOD_EXPLORER_ORIGIN = 'https://robin.etherscan.io';
export const ROBINHOOD_FEATURED_SYMBOLS = ['MSFT', 'AVGO', 'TSLA', 'GOOGL', 'META', 'NFLX', 'AAPL', 'NVDA', 'AMZN', 'LLY',
  'COIN', 'AMD', 'PLTR', 'ORCL', 'ADBE', 'CRM', 'INTC', 'COST', 'TSM', 'CSCO'] as const;
const featuredRanks = new Map<string, number>(ROBINHOOD_FEATURED_SYMBOLS.map((symbol, index) => [symbol, index]));
export function featuredRobinhoodStocks(stocks: readonly Stock[]): Stock[] {
  return stocks.filter(stock => stock.provider === 'robinhood' && featuredRanks.has(stock.symbol))
    .sort((first, second) => featuredRanks.get(first.symbol)! - featuredRanks.get(second.symbol)!);
}
const Money = Decimal.clone({ precision: 128 });
const Address = z.string().regex(/^0x[0-9a-fA-F]{40}$/).refine(value => isAddress(value, { strict: true }) && !/^0x0{40}$/i.test(value));
const Deployment = z.object({
  contractAddress: Address, chainId: z.coerce.number().int().positive().max(Number.MAX_SAFE_INTEGER),
  networkName: z.string().max(100).optional(),
});
const Asset = z.object({
  id: z.string().regex(/^0x[0-9a-fA-F]{64}$/),
  tokenSymbol: z.string().trim().min(1).max(32).regex(/^[A-Z0-9][A-Z0-9._-]*$/),
  tokenName: z.string().trim().min(1).max(200),
  deployments: z.array(Deployment).min(1).max(20), tokenDecimals: z.coerce.number().int().min(0).max(36),
  currentMultiplier: DecimalString, isin: z.string().regex(/^[A-Z]{2}[A-Z0-9]{9}\d$/),
  status: z.string().min(1).max(100), logoUrl: z.string().max(2_048).optional(),
});
export type RobinhoodAsset = z.infer<typeof Asset>;
const Quote = z.object({
  tokenSymbol: z.string(), deployments: z.array(Deployment).min(1).max(20),
  bid: DecimalString, ask: DecimalString, currency: z.literal('USD'),
  generatedAt: z.iso.datetime(), isTradingHalt: z.boolean(),
  tokenBid: DecimalString.optional(), tokenAsk: DecimalString.optional(),
  dailyHigh: z.unknown().optional(), dailyLow: z.unknown().optional(), dailyTradingVolume: z.unknown().optional(),
});
function positive(raw: unknown): string | null {
  const value = nonnegativeAmount(raw);
  return value !== null && new Money(value).gt(0) ? value : null;
}

function referenceFields(row: z.infer<typeof Quote>, asset: RobinhoodAsset, quote: Stock['quote']): RobinhoodReference {
  const volume = nonnegativeAmount(row.dailyTradingVolume);
  return { basis: 'underlying_share_reference', currency: 'USD', bid: positive(row.bid)!, ask: positive(row.ask)!,
    // Live payloads include dailyHigh/dailyLow, but their units and session are undocumented.
    dailyHigh: null, dailyLow: null, dailyTradingVolume: volume !== null && new Money(volume).gt(0) ? volume : null,
    volumeUnit: 'underlying_shares', currentMultiplier: positive(asset.currentMultiplier)!, tokenDecimals: asset.tokenDecimals,
    asOf: quote.asOf!, receivedAt: quote.receivedAt!, isTradingHalt: row.isTradingHalt };
}
function deploymentIdentity(deployments: z.infer<typeof Deployment>[]): string {
  return deployments.map(item => `${item.chainId}:${item.contractAddress.toLowerCase()}`).sort().join(',');
}
function supportedDeployment(asset: RobinhoodAsset): boolean {
  return asset.deployments.length === 1 && asset.deployments[0]!.chainId === ROBINHOOD_CHAIN_ID &&
    (asset.deployments[0]!.networkName === undefined || asset.deployments[0]!.networkName === 'Robinhood Chain');
}
function logoUrl(raw?: string): string | null {
  try {
    const url = new URL(raw ?? '');
    return url.protocol === 'https:' && !url.username && !url.password ? url.href : null;
  } catch { return null; }
}

/** Public bid/ask describe the underlier; the multiplier converts their midpoint to a token reference. */
export function normalizeRobinhoodQuote(raw: unknown, asset: RobinhoodAsset, fetchedAt: number): Stock['quote'] | null {
  const result = Quote.safeParse(raw);
  if (!result.success || !Number.isFinite(fetchedAt) || !supportedDeployment(asset)) return null;
  const row = result.data;
  if (row.tokenSymbol !== asset.tokenSymbol || deploymentIdentity(row.deployments) !== deploymentIdentity(asset.deployments) ||
    row.deployments.some(item => item.networkName !== undefined && item.networkName !== 'Robinhood Chain')) return null;
  const asOf = Date.parse(row.generatedAt), age = fetchedAt - asOf;
  if (!Number.isFinite(asOf) || age < 0 || age > 300_000) return null;
  const bid = positive(row.bid), ask = positive(row.ask), multiplier = positive(asset.currentMultiplier);
  if (bid === null || ask === null || multiplier === null || new Money(ask).lt(bid)) return null;
  const tokenBid = new Money(bid).times(multiplier), tokenAsk = new Money(ask).times(multiplier);
  // Current responses also publish tokenBid/tokenAsk rounded to token precision.
  // Reject a mismatched factor instead of silently using a stale multiplier.
  for (const [reported, expected] of [[row.tokenBid, tokenBid], [row.tokenAsk, tokenAsk]] as const) {
    if (reported !== undefined) {
      const value = positive(reported);
      if (value === null || new Money(value).minus(expected).abs().gt(new Money(10).pow(-asset.tokenDecimals))) return null;
    }
  }
  const price = positive(tokenBid.plus(tokenAsk).div(2).toFixed());
  if (price === null) return null;
  return { price, currency: 'USD', currencyBasis: 'quoted_currency', changeAmount: null, changePercent: null,
    asOf: new Date(asOf).toISOString(), receivedAt: new Date(fetchedAt).toISOString(), basis: 'provider_indicative_token' };
}

/** The official catalog is authoritative for ERC-20 identity; exchange metadata establishes stock/ETF scope. */
export async function loadRobinhood(http: GetJson, now = Date.now, options: ProviderLoadOptions = {}): Promise<ProviderSnapshot> {
  const [payload, directory] = await Promise.all([
    http('robinhood', '/rhj/assets'),
    options.directory ? Promise.resolve(options.directory) : options.refreshClassification ? loadClassificationDirectory(http, now) : Promise.resolve(NasdaqBaseline),
  ]);
  const raw = z.object({ assets: z.array(z.unknown()).max(1_000) }).parse(payload).assets;
  if (!raw.length) throw new Error('Empty Robinhood provider catalog');
  const reconciliation: CatalogReconciliation = { sourceCount: raw.length, includedIds: [], excluded: [], unresolved: [], classificationAsOf: directory.observedAt };
  const parsed = raw.map((value, index) => {
    const result = Asset.safeParse(value);
    if (!result.success) {
      const row = typeof value === 'object' && value !== null ? value as Record<string, unknown> : {};
      reconciliation.unresolved.push({ id: String(row.id ?? row.tokenSymbol ?? `invalid-row-${index}`), reason: 'Invalid Robinhood asset identity or deployment metadata.' });
      return null;
    }
    return result.data;
  });
  const identities = new Map<string, number>();
  for (const asset of parsed) if (asset) for (const identity of [
    `id:${asset.id.toLowerCase()}`, `symbol:${asset.tokenSymbol}`,
    ...asset.deployments.map(item => `contract:${item.chainId}:${item.contractAddress.toLowerCase()}`),
  ]) identities.set(identity, (identities.get(identity) ?? 0) + 1);
  let quotes: unknown[] = [];
  try {
    if (!options.metadataOnly) quotes = z.object({ quotes: z.array(z.unknown()).max(1_000) }).parse(await http('robinhood', '/rhj/prices')).quotes;
  } catch { /* Verified identities remain browseable when their references are unavailable. */ }
  const observedAt = new Date(now()).toISOString();
  const stocks: Stock[] = [];
  let partial = false;
  for (const asset of parsed) {
    if (!asset) continue;
    const id = `robinhood:${asset.id.toLowerCase()}`;
    if ([`id:${asset.id.toLowerCase()}`, `symbol:${asset.tokenSymbol}`, ...asset.deployments.map(item => `contract:${item.chainId}:${item.contractAddress.toLowerCase()}`)]
      .some(identity => identities.get(identity)! !== 1)) {
      reconciliation.unresolved.push({ id, reason: 'Duplicate Robinhood asset ID, ticker, or chain contract.' }); continue;
    }
    if (asset.status !== 'ASSET_STATUS_ACTIVE') {
      reconciliation.excluded.push({ id, reason: `Robinhood asset is not active (${asset.status}).` }); continue;
    }
    if (!supportedDeployment(asset) || positive(asset.currentMultiplier) === null) {
      reconciliation.unresolved.push({ id, reason: 'A single Robinhood Chain (4663) deployment and valid token multiplier are required.' }); continue;
    }
    const classification = classifySymbol(asset.tokenSymbol, directory);
    if (classification.kind !== 'company_share' && classification.kind !== 'equity_etf') {
      reconciliation[classification.kind === 'excluded' ? 'excluded' : 'unresolved'].push({ id, reason: classification.reason }); continue;
    }
    const matches = quotes.filter(row => typeof row === 'object' && row !== null && (row as { tokenSymbol?: unknown }).tokenSymbol === asset.tokenSymbol);
    const quote = matches.length === 1 ? normalizeRobinhoodQuote(matches[0], asset, now()) : null;
    if (!quote) partial = true;
    const contract = getAddress(asset.deployments[0]!.contractAddress);
    const deployments: Stock['deployments'] = [{ network: 'Robinhood Chain', chainId: ROBINHOOD_CHAIN_ID,
      address: contract, explorerUrl: `${ROBINHOOD_EXPLORER_ORIGIN}/address/${contract}`,
      decimals: asset.tokenDecimals, depositEnabled: null, withdrawEnabled: null }];
    const company = featuredCompanySummary(asset.tokenSymbol);
    const instrument = asset.tokenSymbol === 'TSM' && asset.isin === 'US8740391003'
      ? 'The referenced underlying instrument is the NYSE-listed American depositary share (ADS); each ADS represents five Taiwan ordinary shares.' : null;
    const reference = 'Robinhood Stock Tokens provide economic exposure to the underlying stock or ETF without legal ownership of its shares. Public issuer references use the bid/ask midpoint adjusted by the current token multiplier; spot market prices may differ. Issued on Robinhood Chain (chain ID 4663).';
    const validQuote = quote ? Quote.parse(matches[0]) : null;
    stocks.push({
      id, provider: 'robinhood', providerLabel: 'Robinhood Stock Tokens', providerAssetId: asset.id.toLowerCase(),
      symbol: asset.tokenSymbol, name: asset.tokenName, logoUrl: logoUrl(reviewedRobinhoodCompanyLogo(asset) ?? asset.logoUrl),
      description: [company, instrument, reference].filter(Boolean).join('\n\n'), informationUrl: 'https://robinhood.com/rhj/stocktokens/',
      ...(validQuote && quote ? { robinhoodReference: referenceFields(validQuote, asset, quote) } : {}),
      underlying: { symbol: asset.tokenSymbol, isin: asset.isin, cusip: asset.isin.startsWith('US') ? asset.isin.slice(2, 11) : null, listingCountry: 'US', currency: 'USD' },
      quote: quote ?? { price: null, currency: 'USD', currencyBasis: 'quoted_currency', changeAmount: null, changePercent: null,
        asOf: null, receivedAt: null, basis: 'provider_indicative_token' },
      volume24h: null, volume24hBasis: null,
      activity: { currency: 'USD', source: 'Robinhood', scope: 'robinhood_chain_token', volume24h: null, netVolume24h: null,
        receivedAt: observedAt, updatedAt: null,
        volumeReason: 'Robinhood reports underlying-share and mint/burn activity; verified token-market turnover is unavailable.',
        netVolumeReason: 'Robinhood does not publish a verified token-market buy/sell volume breakdown.' },
      statistics: initialStatistics(deployments), deployments, trading: DisabledTrading,
      marketState: { status: validQuote?.isTradingHalt ? 'halted' : 'unknown',
        label: validQuote?.isTradingHalt ? 'Trading halted' : 'Market hours unavailable', nextChangeAt: null },
    });
    reconciliation.includedIds.push(id);
  }
  stocks.sort((first, second) => (featuredRanks.get(first.symbol) ?? ROBINHOOD_FEATURED_SYMBOLS.length) -
    (featuredRanks.get(second.symbol) ?? ROBINHOOD_FEATURED_SYMBOLS.length) || first.symbol.localeCompare(second.symbol));
  return { stocks, partial: partial || reconciliation.unresolved.length > 0, reconciliation };
}
