import { z } from 'zod';
import { Decimal } from 'decimal.js';
import { DecimalString, DisabledTrading, type Stock } from './schema.js';
import { Registry, type RegistryEntry } from './registry.js';
import { classifySymbol, classifyForeign, NasdaqBaseline, loadClassificationDirectory, type ClassificationDirectory, type Classification } from './classification.js';
import type { GetJson } from './http.js';
import { initialStatistics } from './statistics.js';
import { backpackActivity, nonnegativeAmount, unavailableJupiterActivity } from './activity.js';
import { isSolanaAddress } from './portfolio-schema.js';
import { featuredCompanySummary } from './featured-about.js';

const HttpsProviderPage = z.url().max(2_048).refine(value => {
  const url = new URL(value);
  return url.protocol === 'https:' && url.hostname.length > 0 && url.username === '' && url.password === '';
});
const BackedAsset = z.object({
  id: z.string(), name: z.string(), symbol: z.string(), isin: z.string(), logo: z.string().optional(),
  description: z.string().trim().min(1).max(20_000).optional().catch(undefined),
  underlying: z.object({ symbol: z.string(), isin: z.string().nullable(), type: z.string().nullable(), currency: z.string(), listingCountry: z.string().nullable() }).nullable(),
  trading: z.object({ isTradingHalted: z.boolean().optional(), openNow: z.boolean().optional(), currentPeriod: z.string().optional(), nextChangeAt: z.string().nullable().optional() }).nullable().optional(),
  deployments: z.array(z.object({ network: z.string(), address: z.string() })).default([]),
});
const Security = z.object({ asset: z.string(), cusip: z.string().nullable(), name: z.string() });
const Asset = z.object({ symbol: z.string(), tokens: z.array(z.object({
  blockchain: z.string(), contractAddress: z.string().nullable(), nativeDecimals: z.coerce.number().int().min(0).max(36),
  depositEnabled: z.boolean(), withdrawEnabled: z.boolean(),
})).default([]) });
const Ticker = z.object({ symbol: z.string(), lastPrice: z.unknown(), priceChange: z.unknown(),
  priceChangePercent: z.unknown(), quoteVolume: z.unknown() });
const PreStock = z.object({
  name: z.string().trim().min(1).max(200),
  symbol: z.string().trim().min(1).max(32).regex(/^[A-Z0-9][A-Z0-9._-]*$/),
  description: z.string().trim().min(1).max(20_000),
  image: z.url(),
  external_url: HttpsProviderPage,
  contract_address: z.string().min(32).max(44).refine(isSolanaAddress, 'Invalid Solana mint'),
  markPrice: DecimalString,
  markValuation: DecimalString,
  tokenPrice: z.unknown(),
  impliedValuation: DecimalString,
  supply: DecimalString,
});
export type PreStockInput = z.infer<typeof PreStock>;
export type BackedAssetInput = z.infer<typeof BackedAsset>;
export type SecurityInput = z.infer<typeof Security>;
export type CatalogReconciliation = {
  sourceCount: number; includedIds: string[]; excluded: { id: string; reason: string }[];
  unresolved: { id: string; reason: string }[]; classificationAsOf: string;
};
export type ProviderSnapshot = { stocks: Stock[]; partial: boolean; retainedQuotes?: boolean; reconciliation?: CatalogReconciliation };
export type ProviderLoadOptions = { metadataOnly?: boolean; directory?: ClassificationDirectory; refreshClassification?: boolean; metadataTtlMs?: number };
export type ClassifiedUnderlying = { symbol: string; isin: string | null; cusip: string | null; classification: 'company_share' | 'equity_etf' };
const idIsin = /^[A-Z]{2}[A-Z0-9]{9}\d$/;
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
function underlyingClassification(symbol: string, directory: ClassificationDirectory): Classification { return classifySymbol(symbol, directory); }
export function classifyBacked(asset: BackedAssetInput, registry = Registry, directory = NasdaqBaseline): ClassifiedUnderlying | undefined {
  const u = asset.underlying;
  if (!u || !u.symbol || !u.isin) return undefined;
  const reviewed = registry.find(entry => entry.backed.symbol === asset.symbol || entry.backed.assetId === asset.id);
  if (reviewed) {
    if (reviewed.backed.assetId !== asset.id || reviewed.backed.symbol !== asset.symbol || reviewed.backed.productIsin !== asset.isin ||
      reviewed.isin !== u.isin || reviewed.symbol !== u.symbol || u.type === 'ETF' || u.currency !== 'USD' || u.listingCountry !== 'US') return undefined;
    return { symbol: reviewed.symbol, isin: reviewed.isin, cusip: reviewed.cusip, classification: 'company_share' };
  }
  if (!uuid.test(asset.id) || !idIsin.test(asset.isin) || !idIsin.test(u.isin)) return undefined;
  let classification = underlyingClassification(u.symbol, directory);
  // Explicit issuer Equity metadata can classify foreign listings, without pretending a null type means equity.
  if (u.listingCountry !== 'US') classification = u.type === 'Equity'
    ? { kind: 'company_share', reason: 'Issuer explicitly identifies Equity.', source: 'https://api.xstocks.fi/api/v2/public/assets' }
    : classifyForeign(u.isin, u.listingCountry);
  if (classification.kind !== 'company_share' && classification.kind !== 'equity_etf') return undefined;
  if ((u.type === 'ETF' && classification.kind !== 'equity_etf') || (u.type === 'Equity' && classification.kind !== 'company_share')) return undefined;
  return { symbol: u.symbol, isin: u.isin, cusip: u.isin.startsWith('US') ? u.isin.slice(2, 11) : null, classification: classification.kind };
}
export function classifyBackpack(security: SecurityInput, registry = Registry, directory = NasdaqBaseline): ClassifiedUnderlying | undefined {
  if (!security.asset.endsWith('.US')) return undefined;
  const symbol = security.asset.slice(0, -3);
  const reviewed = registry.find(entry => entry.backpack.assetId === security.asset);
  if (reviewed) {
    if (security.cusip !== null && reviewed.cusip !== security.cusip) return undefined;
    return { symbol, isin: reviewed.isin, cusip: security.cusip, classification: 'company_share' };
  }
  if (security.cusip !== null && !/^[A-Z0-9]{9}$/.test(security.cusip)) return undefined;
  const classification = underlyingClassification(symbol, directory);
  if (classification.kind !== 'company_share' && classification.kind !== 'equity_etf') return undefined;
  return { symbol, isin: null, cusip: security.cusip, classification: classification.kind };
}
function rejectedClassification(symbol: string | undefined, directory: ClassificationDirectory, foreign = false): Classification {
  if (!symbol || foreign) return { kind: 'unresolved', reason: foreign ? 'Issuer does not classify this non-US listing.' : 'Missing or invalid provider identity.', source: '' };
  const result = classifySymbol(symbol, directory);
  return result.kind === 'company_share' || result.kind === 'equity_etf'
    ? { ...result, kind: 'unresolved', reason: 'Provider identifiers are malformed or conflict with reviewed identity.' } : result;
}
function reportRejection(report: CatalogReconciliation, id: string, result: Classification) {
  (result.kind === 'excluded' ? report.excluded : report.unresolved).push({ id, reason: result.reason });
}
async function directoryFor(http: GetJson, now: () => number, options: ProviderLoadOptions) {
  return options.directory ?? (options.refreshClassification ? loadClassificationDirectory(http, now) : NasdaqBaseline);
}
const PercentageDecimal = Decimal.clone({ precision: 128 });
export function percentagePoints(fraction: string): string {
  return new PercentageDecimal(fraction).times(100).toFixed();
}
function positivePrice(raw: unknown): string | null {
  const value = nonnegativeAmount(raw);
  return value !== null && new Decimal(value).greaterThan(0) ? value : null;
}
function signedAmount(raw: unknown): string | null {
  const parsed = DecimalString.safeParse(raw);
  if (!parsed.success) return null;
  const value = new Decimal(parsed.data);
  if (!value.isFinite() || value.abs().gt('1e38') || value.decimalPlaces() > 100) return null;
  const normalized = value.toFixed();
  return DecimalString.safeParse(normalized).success ? normalized : null;
}
function httpsLogo(value?: string): string | null {
  try { const url = new URL(value ?? ''); return url.protocol === 'https:' ? url.href : null; } catch { return null; }
}
function featuredBackedDescription(symbol: string, productName: string, providerDescription?: string): string | null {
  const company = featuredCompanySummary(symbol);
  if (!company) return providerDescription ?? null;
  return `${company}\n\n${productName} is an xStock tracker certificate. Its issuer says it is collateralized 1:1 by the corresponding share held in segregated custody. It gives economic exposure, not shareholder voting rights.`;
}
function featuredBackpackDescription(symbol: string): string | null {
  const company = featuredCompanySummary(symbol);
  if (!company) return null;
  return `${company}\n\nBackpack Securities offers U.S. stock holdings as security entitlements. Where supported, a tokenized holding can convert 1:1 with the corresponding entitlement.`;
}
/** Backpack does not publish equity artwork. Its verified US ticker only addresses this public image CDN. */
export function backpackCompanyLogo(symbol: string): string | null {
  if (!/^[A-Z][A-Z0-9.-]{0,15}$/.test(symbol)) return null;
  return `https://financialmodelingprep.com/image-stock/${encodeURIComponent(symbol)}.png`;
}
/**
 * PreStocks is itself the authoritative product directory for these private-company
 * instruments. Its mark and valuation fields are intentionally not projected onto
 * public-market metrics; only the published token price seeds the exact mint.
 */
export async function loadPreStocks(http: GetJson, now = Date.now): Promise<ProviderSnapshot> {
  const raw = z.array(z.unknown()).max(1_000).parse(await http('prestocks', '/api/prestocks'));
  if (!raw.length) throw new Error('Empty provider catalog');
  const observedAt = new Date(now()).toISOString();
  const reconciliation: CatalogReconciliation = { sourceCount: raw.length, includedIds: [], excluded: [], unresolved: [], classificationAsOf: observedAt };
  const parsed = raw.map((value, index) => {
    const result = PreStock.safeParse(value);
    if (!result.success) {
      const row = typeof value === 'object' && value !== null ? value as Record<string, unknown> : {};
      reconciliation.unresolved.push({ id: String(row.contract_address ?? row.symbol ?? `invalid-row-${index}`), reason: 'Invalid PreStocks product metadata.' });
      return null;
    }
    return result.data;
  });
  const mintFrequency = new Map<string, number>();
  const symbolFrequency = new Map<string, number>();
  for (const row of parsed) if (row) {
    mintFrequency.set(row.contract_address, (mintFrequency.get(row.contract_address) ?? 0) + 1);
    symbolFrequency.set(row.symbol, (symbolFrequency.get(row.symbol) ?? 0) + 1);
  }
  const stocks: Stock[] = [];
  let partial = false;
  for (const row of parsed) {
    if (!row) continue;
    const id = `prestocks:${row.contract_address}`;
    if (mintFrequency.get(row.contract_address)! !== 1 || symbolFrequency.get(row.symbol)! !== 1) {
      reconciliation.unresolved.push({ id, reason: 'Duplicate PreStocks mint or symbol.' });
      continue;
    }
    const price = positivePrice(row.tokenPrice);
    if (price === null) partial = true;
    const deployments = [{ network: 'Solana', address: row.contract_address, decimals: null,
      depositEnabled: null, withdrawEnabled: null }];
    reconciliation.includedIds.push(id);
    stocks.push({
      id, provider: 'prestocks', providerLabel: 'PreStocks · Pre-IPO', providerAssetId: row.contract_address,
      symbol: row.symbol, name: row.name, logoUrl: httpsLogo(row.image),
      description: row.description, informationUrl: row.external_url,
      underlying: { symbol: row.symbol, isin: null, cusip: null, listingCountry: null, currency: null },
      quote: { price, currency: 'USD', currencyBasis: 'quoted_currency', changeAmount: null,
        changePercent: null, asOf: null, receivedAt: price === null ? null : observedAt, basis: 'provider_indicative_token' },
      volume24h: null, volume24hBasis: null,
      activity: unavailableJupiterActivity('Trading activity has not been received for this exact Solana mint yet.'),
      statistics: initialStatistics(deployments), deployments,
      trading: DisabledTrading,
    });
  }
  // Wallet coverage must never treat a partially parsed product directory as a
  // complete set of Solana mints. The live service retains its previous complete
  // snapshot when a refresh fails; missing prices do not affect mint discovery.
  if (stocks.length !== raw.length) throw new Error('Incomplete PreStocks product metadata');
  return { stocks, partial, reconciliation };
}
export async function loadBacked(http: GetJson, now = Date.now, options: ProviderLoadOptions = {}): Promise<ProviderSnapshot> {
  const directory = await directoryFor(http, now, options);
  const reconciliation: CatalogReconciliation = { sourceCount: 0, includedIds: [], excluded: [], unresolved: [], classificationAsOf: directory.observedAt };
  const assets: BackedAssetInput[] = [];
  const seen = new Set<string>();
  for (let page = 0; page < 100; page++) {
    const payload = z.object({ nodes: z.array(z.unknown()), page: z.object({ currentPage: z.coerce.number(), hasNextPage: z.boolean() }) })
      .parse(await http('backed', '/api/v2/public/assets', { page: String(page), pageSize: '100' }));
    if (payload.page.currentPage !== page) throw new Error('Unexpected provider pagination');
    for (const raw of payload.nodes) {
      const parsed = BackedAsset.safeParse(raw);
      reconciliation.sourceCount++;
      if (!parsed.success) { reportRejection(reconciliation, String((raw as { id?: unknown })?.id ?? 'invalid-row'), rejectedClassification(undefined, directory)); continue; }
      if (seen.has(parsed.data.id)) {
        const existing = assets.findIndex(asset => asset.id === parsed.data.id);
        if (existing >= 0) { assets.splice(existing, 1); reconciliation.includedIds = reconciliation.includedIds.filter(id => id !== `backed:${parsed.data.id}`); reportRejection(reconciliation, `backed:${parsed.data.id}`, { kind: 'unresolved', reason: 'Duplicate provider asset identifier.', source: '' }); }
        reportRejection(reconciliation, `backed:${parsed.data.id}`, { kind: 'unresolved', reason: 'Duplicate provider asset identifier.', source: '' }); continue;
      }
      seen.add(parsed.data.id);
      if (classifyBacked(parsed.data, Registry, directory)) { assets.push(parsed.data); reconciliation.includedIds.push(`backed:${parsed.data.id}`); }
      else reportRejection(reconciliation, `backed:${parsed.data.id}`, rejectedClassification(parsed.data.underlying?.symbol, directory, parsed.data.underlying?.listingCountry !== 'US'));
    }
    if (!payload.page.hasNextPage) break;
    if (page === 99) throw new Error('Provider catalog exceeded configured bound');
  }
  if (!reconciliation.sourceCount) throw new Error('Empty provider catalog');
  let partial = false;
  const stocks = await Promise.all(assets.map(async asset => {
    const verified = classifyBacked(asset, Registry, directory)!;
    let price: string | null = null;
    let receivedAt: string | null = null;
    try {
      if (options.metadataOnly || asset.underlying?.currency !== 'USD') throw new Error('USD token quotes are enriched independently');
      const response = z.object({ quote: DecimalString }).parse(await http('backed', `/api/v2/public/assets/${encodeURIComponent(asset.symbol)}/price-data`));
      price = positivePrice(response.quote);
      if (price === null) throw new Error('Invalid price');
      receivedAt = new Date(now()).toISOString();
    } catch { partial = true; }
    return {
      id: `backed:${asset.id}`, provider: 'backed', providerLabel: 'Backed xStocks', providerAssetId: asset.id,
      symbol: asset.symbol, name: asset.name, logoUrl: httpsLogo(asset.logo),
      description: featuredBackedDescription(verified.symbol, asset.name, asset.description), informationUrl: null,
      underlying: { symbol: verified.symbol, isin: verified.isin, cusip: verified.cusip, listingCountry: asset.underlying?.listingCountry ?? null, currency: asset.underlying?.currency ?? null },
      quote: { price, currency: 'USD', currencyBasis: asset.underlying?.currency === 'USD' ? 'underlying_metadata' : 'quoted_currency', changeAmount: null,
        changePercent: null, asOf: null, receivedAt, basis: 'provider_indicative_token' },
      volume24h: null, volume24hBasis: null,
      activity: unavailableJupiterActivity('Trading activity has not been received yet.'),
      statistics: initialStatistics(asset.deployments.map(deployment => ({ ...deployment, decimals: null, depositEnabled: null, withdrawEnabled: null }))),
      deployments: asset.deployments.map(deployment => ({ ...deployment, decimals: null, depositEnabled: null, withdrawEnabled: null })),
      marketState: { status: asset.trading?.isTradingHalted ? 'halted' : asset.trading?.openNow === true ? 'open' : asset.trading?.openNow === false ? 'closed' : 'unknown',
        label: asset.trading?.isTradingHalted ? 'Trading halted' : asset.trading?.openNow === true ? 'Market open' : asset.trading?.openNow === false ? 'Market closed' : 'Market hours unavailable',
        nextChangeAt: z.iso.datetime().safeParse(asset.trading?.nextChangeAt).success ? asset.trading!.nextChangeAt! : null },
      trading: DisabledTrading,
    } satisfies Stock;
  }));
  return { stocks, partial, reconciliation };
}
const backpackMetadata = new WeakMap<GetJson, { at: number; value: [unknown, unknown]; pending?: Promise<[unknown, unknown]> }>();
async function loadBackpackMetadata(http: GetJson, now: () => number, ttlMs: number): Promise<[unknown, unknown]> {
  const cached = backpackMetadata.get(http);
  if (cached?.pending) return cached.pending;
  if (cached && now() - cached.at < ttlMs) return cached.value;
  const pending = Promise.all([http('backpack', '/api/v1/securities'), http('backpack', '/api/v1/assets')]);
  if (cached) cached.pending = pending;
  try { const value = await pending; backpackMetadata.set(http, { at: now(), value }); return value; }
  finally { if (cached) cached.pending = undefined; }
}
export async function loadBackpack(http: GetJson, now = Date.now, options: ProviderLoadOptions = {}): Promise<ProviderSnapshot> {
  const directory = await directoryFor(http, now, options);
  const [[rawSecurities, rawAssets], rawTickers] = await Promise.all([
    loadBackpackMetadata(http, now, options.metadataTtlMs ?? 0),
    options.metadataOnly ? Promise.resolve([]) : http('backpack', '/api/v1/tickers', { source: 'External', interval: '1d' }),
  ]);
  const securities = z.array(z.unknown()).parse(rawSecurities).flatMap(raw => {
    const result = Security.safeParse(raw); return result.success ? [result.data] : [];
  });
  const assets = z.array(z.unknown()).parse(rawAssets).flatMap(raw => {
    const result = Asset.safeParse(raw); return result.success ? [result.data] : [];
  });
  const tickers = z.array(z.unknown()).parse(rawTickers).flatMap(raw => {
    const result = Ticker.safeParse(raw); return result.success ? [result.data] : [];
  });
  const receivedAt = new Date(now()).toISOString();
  const reconciliation: CatalogReconciliation = { sourceCount: z.array(z.unknown()).parse(rawSecurities).length, includedIds: [], excluded: [], unresolved: [], classificationAsOf: directory.observedAt };
  const frequencies = new Map<string, number>();
  for (const security of securities) frequencies.set(security.asset, (frequencies.get(security.asset) ?? 0) + 1);
  const eligible = securities.filter(security => {
    if (frequencies.get(security.asset)! > 1) { reportRejection(reconciliation, `backpack:${security.asset}`, { kind: 'unresolved', reason: 'Duplicate provider security identifier.', source: '' }); return false; }
    if (classifyBackpack(security, Registry, directory)) { reconciliation.includedIds.push(`backpack:${security.asset}`); return true; }
    reportRejection(reconciliation, `backpack:${security.asset}`, rejectedClassification(security.asset.slice(0, -3), directory)); return false;
  });
  for (const raw of z.array(z.unknown()).parse(rawSecurities)) if (!Security.safeParse(raw).success) reportRejection(reconciliation, String((raw as { asset?: unknown })?.asset ?? 'invalid-row'), rejectedClassification(undefined, directory));
  if (!reconciliation.sourceCount) throw new Error('Empty provider catalog');
  let partial = false;
  const stocks = eligible.map(security => {
    const verified = classifyBackpack(security, Registry, directory)!;
    const asset = assets.find(item => item.symbol === security.asset);
    const matches = tickers.filter(item => item.symbol === `${security.asset}_USDC`);
    const ticker = matches.length === 1 ? matches[0] : undefined;
    const price = positivePrice(ticker?.lastPrice);
    const changeAmount = price === null ? null : signedAmount(ticker?.priceChange);
    const changeFraction = price === null ? null : signedAmount(ticker?.priceChangePercent);
    const activity = backpackActivity(ticker?.quoteVolume, receivedAt);
    if (price === null) partial = true;
    return {
      id: `backpack:${security.asset}`, provider: 'backpack', providerLabel: 'Backpack Securities', providerAssetId: security.asset,
      symbol: security.asset, name: security.name, logoUrl: backpackCompanyLogo(verified.symbol),
      description: featuredBackpackDescription(verified.symbol), informationUrl: null,
      underlying: { symbol: verified.symbol, isin: verified.isin, cusip: security.cusip, listingCountry: 'US', currency: 'USD' },
      quote: { price, currency: 'USDC', currencyBasis: 'market_symbol',
        changeAmount, changePercent: changeFraction === null ? null : percentagePoints(changeFraction),
        asOf: null, receivedAt: price === null ? null : receivedAt, basis: 'external_reference_non_executable' },
      volume24h: activity.volume24h, volume24hBasis: activity.volume24h === null ? null : 'quote_currency_turnover',
      activity,
      statistics: initialStatistics((asset?.tokens ?? []).filter(token => !!token.contractAddress).map(token => ({
        network: token.blockchain, address: token.contractAddress!, decimals: token.nativeDecimals,
        depositEnabled: token.depositEnabled, withdrawEnabled: token.withdrawEnabled,
      }))),
      deployments: (asset?.tokens ?? []).filter(token => !!token.contractAddress).map(token => ({
        network: token.blockchain, address: token.contractAddress!, decimals: token.nativeDecimals,
        depositEnabled: token.depositEnabled, withdrawEnabled: token.withdrawEnabled,
      })),
      trading: DisabledTrading,
    } satisfies Stock;
  });
  return { stocks, partial, reconciliation };
}
