import { Decimal } from 'decimal.js';
import { ActivitySchema, DecimalString, type Activity } from './schema.js';

// Inputs are bounded to 100 characters and values to 1e40. Keep subtraction exact even for tiny deltas.
const ActivityDecimal = Decimal.clone({ precision: 256 });
export function nonnegativeAmount(raw: unknown): string | null {
  const parsed = DecimalString.safeParse(raw);
  if (!parsed.success) return null;
  const value = new ActivityDecimal(parsed.data);
  if (!value.isFinite() || value.isNegative() || value.greaterThan('1e40') || value.decimalPlaces() > 100) return null;
  const normalized = value.toFixed();
  return DecimalString.safeParse(normalized).success ? normalized : null;
}

export function unavailableJupiterActivity(reason: string, receivedAt: string | null = null, updatedAt: string | null = null): Activity {
  return { currency: 'USD', source: 'Jupiter', scope: 'solana_token', volume24h: null, netVolume24h: null,
    receivedAt, updatedAt, volumeReason: reason, netVolumeReason: reason };
}

/** Total turnover and directional net are derived only when BOTH sides of the same 24h observation exist. */
export function jupiterActivity(raw: unknown, receivedAt: string, updatedAt: string): Activity {
  const stats = typeof raw === 'object' && raw !== null && !Array.isArray(raw) ? raw as Record<string, unknown> : {};
  const buy = nonnegativeAmount(stats.buyVolume);
  const sell = nonnegativeAmount(stats.sellVolume);
  if (buy === null || sell === null) return unavailableJupiterActivity(
    'Jupiter has not reported both 24h buy and sell volumes for this stock’s Solana token.', receivedAt, updatedAt);
  const volume = new ActivityDecimal(buy).plus(sell);
  if (volume.greaterThan('1e40')) return unavailableJupiterActivity('The reported 24h volume is outside the supported range.', receivedAt, updatedAt);
  const result = ActivitySchema.safeParse({ currency: 'USD', source: 'Jupiter', scope: 'solana_token',
    volume24h: volume.toFixed(), netVolume24h: new ActivityDecimal(buy).minus(sell).toFixed(),
    receivedAt, updatedAt, volumeReason: null, netVolumeReason: null });
  return result.success ? result.data : unavailableJupiterActivity('The reported 24h volume is outside the supported range.', receivedAt, updatedAt);
}

export function backpackActivity(rawQuoteVolume: unknown, receivedAt: string): Activity {
  const volume24h = nonnegativeAmount(rawQuoteVolume);
  return ActivitySchema.parse({ currency: 'USDC', source: 'Backpack', scope: 'external_market',
    volume24h, netVolume24h: null, receivedAt: volume24h === null ? null : receivedAt, updatedAt: null,
    volumeReason: volume24h === null ? 'Backpack has not reported usable 24h external-market turnover.' : null,
    netVolumeReason: 'Backpack’s public external-market feed does not provide a 24h buy/sell volume breakdown.' });
}
