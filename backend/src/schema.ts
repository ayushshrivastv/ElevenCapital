import { z } from 'zod';
import { Decimal } from 'decimal.js';

export const DecimalString = z.string().max(100).regex(/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/);
const Iso = z.iso.datetime();
const HttpsProviderUrl = z.url().max(2_048).refine(value => {
  const url = new URL(value);
  return url.protocol === 'https:' && url.hostname.length > 0 && url.username === '' && url.password === '';
}, 'Provider information URLs must be HTTPS origins without embedded credentials');
export const ProviderId = z.enum(['backed', 'backpack', 'prestocks']);
export type Provider = z.infer<typeof ProviderId>;
export const ProviderIds = ProviderId.options;
export const Range = z.enum(['ONE_HOUR', 'ONE_DAY', 'ONE_WEEK', 'ONE_MONTH', 'YEAR_TO_DATE']);
export type ChartRange = z.infer<typeof Range>;
export const MetricSchema = z.object({
  value: DecimalString.nullable(), unit: z.enum(['USD', 'count', 'score']),
  source: z.enum(['Jupiter', 'DEX Screener']).nullable(), status: z.enum(['available', 'unavailable']),
  basis: z.enum(['token_market_cap', 'selected_pool_liquidity', 'reported_token_liquidity', 'token_holders', 'organic_activity_score']),
  receivedAt: Iso.nullable(), reason: z.string().nullable(),
}).superRefine((metric, context) => {
  if (metric.status === 'unavailable') {
    if (metric.value !== null || !metric.reason) context.addIssue({ code: 'custom', message: 'Unavailable metrics require a reason and no value' });
    return;
  }
  if (metric.value === null || metric.source === null || metric.receivedAt === null || metric.reason !== null) {
    context.addIssue({ code: 'custom', message: 'Available metrics require provenance and value' }); return;
  }
  const value = new Decimal(metric.value);
  if (!value.isFinite() || value.isNegative() || value.greaterThan('1e40') ||
    (metric.unit === 'count' && (!value.isInteger() || value.greaterThan('9223372036854775807'))) ||
    (metric.unit === 'score' && value.greaterThan(100))) {
    context.addIssue({ code: 'custom', message: 'Invalid metric value' });
  }
});
export type Metric = z.infer<typeof MetricSchema>;
export const StatisticsSchema = z.object({
  scope: z.literal('solana_token'), network: z.literal('solana').nullable(), mint: z.string().nullable(),
  updatedAt: Iso.nullable(),
  marketCapitalization: MetricSchema, liquidity: MetricSchema, holderCount: MetricSchema, organicScore: MetricSchema,
});
export type Statistics = z.infer<typeof StatisticsSchema>;
export const ActivitySchema = z.object({
  currency: z.enum(['USD', 'USDC']), source: z.enum(['Jupiter', 'Backpack', 'Yahoo']),
  scope: z.enum(['solana_token', 'external_market', 'underlying_share']),
  volume24h: DecimalString.nullable(), netVolume24h: DecimalString.nullable(),
  receivedAt: Iso.nullable(), updatedAt: Iso.nullable(),
  volumeReason: z.string().trim().min(1).nullable(), netVolumeReason: z.string().trim().min(1).nullable(),
}).superRefine((activity, context) => {
  if ((activity.source === 'Jupiter' && (activity.currency !== 'USD' || activity.scope !== 'solana_token')) ||
    (activity.source === 'Backpack' && (activity.currency !== 'USDC' || activity.scope !== 'external_market')) ||
    (activity.source === 'Yahoo' && (activity.currency !== 'USD' || activity.scope !== 'underlying_share'))) {
    context.addIssue({ code: 'custom', message: 'Activity currency and scope must match its source' });
  }
  for (const [valueKey, reasonKey] of [['volume24h', 'volumeReason'], ['netVolume24h', 'netVolumeReason']] as const) {
    const raw = activity[valueKey];
    if (raw === null) {
      if (!activity[reasonKey]) context.addIssue({ code: 'custom', message: 'Missing activity requires a reason' });
      continue;
    }
    const value = new Decimal(raw);
    if (!value.isFinite() || value.abs().greaterThan('1e40') || (valueKey === 'volume24h' && value.isNegative()) ||
      !activity.receivedAt || activity[reasonKey] !== null || (activity.source === 'Jupiter' && !activity.updatedAt)) {
      context.addIssue({ code: 'custom', message: 'Invalid activity value or provenance' });
    }
  }
  if (activity.netVolume24h !== null && (activity.volume24h === null ||
    new Decimal(activity.netVolume24h).abs().greaterThan(activity.volume24h))) {
    context.addIssue({ code: 'custom', message: 'Net volume requires total volume and cannot exceed it in magnitude' });
  }
});
export type Activity = z.infer<typeof ActivitySchema>;
export const StockSchema = z.object({
  id: z.string(), provider: ProviderId, providerLabel: z.string(), providerAssetId: z.string(),
  symbol: z.string(), name: z.string(), logoUrl: z.url().nullable(),
  description: z.string().trim().min(1).max(20_000).nullable().optional(),
  informationUrl: HttpsProviderUrl.nullable().optional(),
  underlying: z.object({ symbol: z.string(), isin: z.string().nullable(), cusip: z.string().nullable(), listingCountry: z.string().nullable().optional(), currency: z.string().nullable().optional() }),
  quote: z.object({
    price: DecimalString.nullable(), currency: z.enum(['USD', 'USDC']),
    currencyBasis: z.enum(['underlying_metadata', 'market_symbol', 'quoted_currency']),
    changeAmount: DecimalString.nullable(), changePercent: DecimalString.nullable(),
    asOf: Iso.nullable(), receivedAt: Iso.nullable(),
    basis: z.enum(['provider_indicative_token', 'external_reference_non_executable', 'onchain_token_market', 'underlying_share_reference']),
  }),
  volume24h: DecimalString.nullable(),
  volume24hBasis: z.literal('quote_currency_turnover').nullable(),
  statistics: StatisticsSchema,
  activity: ActivitySchema,
  deployments: z.array(z.object({
    network: z.string(), address: z.string(), decimals: z.number().int().min(0).max(36).nullable(),
    depositEnabled: z.boolean().nullable(), withdrawEnabled: z.boolean().nullable(),
  })),
  trading: z.object({ enabled: z.literal(false), reason: z.string() }),
  marketState: z.object({ status: z.enum(['open', 'closed', 'halted', 'unknown']), label: z.string(), nextChangeAt: Iso.nullable() }).optional(),
});
export type Stock = z.infer<typeof StockSchema>;
export const CatalogSchema = z.object({
  schemaVersion: z.literal(1), mode: z.literal('live-read-only'), receivedAt: Iso,
  providers: z.array(z.object({ id: ProviderId, status: z.enum(['ok', 'stale', 'unavailable']), message: z.string().optional() })),
  stocks: z.array(StockSchema),
});
export type Catalog = z.infer<typeof CatalogSchema>;
export const ChartSchema = z.object({
  stockId: z.string(), range: Range, status: z.enum(['ok', 'unavailable', 'unsupported']),
  basis: z.string(), currency: z.enum(['USD', 'USDC']), receivedAt: Iso,
  points: z.array(z.object({ timestamp: Iso, price: DecimalString })),
  statusReason: z.string().optional(),
}).superRefine((chart, context) => {
  if ((chart.status === 'ok') !== (chart.points.length > 0)) context.addIssue({ code: 'custom', message: 'Available charts require points; unavailable charts must be empty.' });
});
export type Chart = z.infer<typeof ChartSchema>;
export const ErrorSchema = z.object({ error: z.string(), message: z.string() });
export const DisabledTrading = { enabled: false, reason: 'Live purchases are not connected. Public data only; no executable quote or settlement.' } as const;
