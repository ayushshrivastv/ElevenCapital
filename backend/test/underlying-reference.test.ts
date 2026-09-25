import test from 'node:test';
import assert from 'node:assert/strict';
import { initialStatistics } from '../src/statistics.js';
import { unavailableJupiterActivity } from '../src/activity.js';
import { applyUnderlyingReference, anchoredReferencePoints, normalizeUnderlyingReference, resolveUnderlyingSymbol,
  underlyingReferenceChartPoints } from '../src/underlying-reference.js';
import type { Stock } from '../src/schema.js';

const NOW = Date.parse('2026-09-21T19:30:00Z');
const stock = (): Stock => ({
  id: 'backed:xrx', provider: 'backed', providerLabel: 'Backed xStocks', providerAssetId: 'xrx', symbol: 'XRXx', name: 'Xerox xStock', logoUrl: null,
  underlying: { symbol: 'XRX', isin: 'US98421M1062', cusip: '98421M106', listingCountry: 'US', currency: 'USD' }, deployments: [],
  quote: { price: '3.35', currency: 'USD', currencyBasis: 'underlying_metadata', changeAmount: null, changePercent: null,
    asOf: null, receivedAt: new Date(NOW).toISOString(), basis: 'provider_indicative_token' },
  statistics: initialStatistics([]), activity: unavailableJupiterActivity('Not received'), volume24h: null, volume24hBasis: null,
  trading: { enabled: false, reason: 'Read only' },
});

test('exact ISIN search resolves one country listing and rejects ambiguity or a foreign cross-listing', () => {
  const row = { exchange: 'NMS', quoteType: 'EQUITY', symbol: 'XRX', isYahooFinance: true };
  assert.equal(resolveUnderlyingSymbol({ quotes: [row, { ...row, exchange: 'STU', symbol: 'US98421M1062.SG' }] }, stock()), 'XRX');
  assert.equal(resolveUnderlyingSymbol({ quotes: [row, { ...row, symbol: 'XRX.A' }] }, stock()), null);
  assert.equal(resolveUnderlyingSymbol({ quotes: [{ ...row, exchange: 'STU' }] }, stock()), null);
});

test('underlying reference keeps issuer USD price, derives percent and turnover, leaves directional net unavailable, and anchors chart', () => {
  const raw = { chart: { result: [{ meta: { symbol: 'XRX', currency: 'USD', instrumentType: 'EQUITY', regularMarketPrice: 3.4,
    regularMarketChangePercent: -2, regularMarketTime: NOW / 1000 }, timestamp: [NOW / 1000 - 300, NOW / 1000],
    indicators: { quote: [{ close: [3.3, 3.4], volume: [100, 200] }] } }] } };
  const reference = normalizeUnderlyingReference(raw, 'XRX', NOW)!;
  const shown = applyUnderlyingReference(stock(), reference, NOW);
  assert.equal(shown.quote.price, '3.35'); assert.equal(shown.quote.changePercent, '-2');
  assert.equal(shown.quote.asOf, new Date(NOW).toISOString());
  assert.equal(shown.quote.basis, 'underlying_share_reference'); assert.equal(shown.activity.source, 'Yahoo');
  assert.equal(shown.activity.volume24h, '995.1470588235294117647058823529411764706'); assert.equal(shown.activity.netVolume24h, null);
  assert.match(shown.activity.netVolumeReason!, /does not publish/);
  const points = anchoredReferencePoints(shown, reference);
  assert.equal(points.length, 2); assert.equal(points.at(-1)!.price, '3.35');
});

test('underlying fallback fails closed for non-US/USD identity or a missing source timestamp', () => {
  const raw = { chart: { result: [{ meta: { symbol: 'XRX', currency: 'USD', instrumentType: 'EQUITY', regularMarketPrice: 3.4,
    regularMarketChangePercent: -2, regularMarketTime: NOW / 1000 }, timestamp: [NOW / 1000],
    indicators: { quote: [{ close: [3.4], volume: [200] }] } }] } };
  const reference = normalizeUnderlyingReference(raw, 'XRX', NOW)!;
  assert.equal(applyUnderlyingReference({ ...stock(), underlying: { ...stock().underlying, currency: 'EUR' } }, reference, NOW).quote.basis,
    'provider_indicative_token');
  assert.equal(applyUnderlyingReference({ ...stock(), underlying: { ...stock().underlying, listingCountry: 'GB' } }, reference, NOW).quote.basis,
    'provider_indicative_token');
  assert.equal(applyUnderlyingReference(stock(), { ...reference, sourceAt: null }, NOW).quote.basis, 'provider_indicative_token');
});

test('one-day Yahoo points can be filtered to one hour but cannot be presented as a longer range', () => {
  const reference = { symbol: 'XRX', fetchedAt: new Date(NOW).toISOString(), sourceAt: new Date(NOW).toISOString(),
    localPrice: '3.4', changePercent: null, turnoverLocal: null,
    points: [{ timestamp: new Date(NOW - 7_200_000).toISOString(), price: '3.3' },
      { timestamp: new Date(NOW - 1_800_000).toISOString(), price: '3.4' }] };
  assert.equal(underlyingReferenceChartPoints(stock(), reference, 'ONE_HOUR', NOW)!.length, 1);
  assert.equal(underlyingReferenceChartPoints(stock(), reference, 'ONE_DAY', NOW)!.length, 2);
  for (const range of ['ONE_WEEK', 'ONE_MONTH', 'YEAR_TO_DATE'] as const) {
    assert.equal(underlyingReferenceChartPoints(stock(), reference, range, NOW), null);
  }
});

test('a separately requested longer Yahoo series can supply verified week and month share history', () => {
  const days = [29, 14, 6, 4, 2, 0];
  const raw = { chart: { result: [{ meta: { symbol: 'XRX', currency: 'USD', instrumentType: 'EQUITY', regularMarketPrice: '3.4',
    regularMarketTime: String(NOW / 1000) }, timestamp: days.map(day => String((NOW - day * 86_400_000) / 1000)),
    indicators: { quote: [{ close: days.map((_, index) => String(3 + index / 10)), volume: days.map(() => '100') }] } }] } };
  const month = normalizeUnderlyingReference(raw, 'XRX', NOW, 31 * 86_400_000)!;
  assert.equal(underlyingReferenceChartPoints(stock(), month, 'ONE_WEEK', NOW)!.length, 4);
  assert.equal(underlyingReferenceChartPoints(stock(), month, 'ONE_MONTH', NOW)!.length, 6);
  assert.equal(underlyingReferenceChartPoints(stock(), month, 'YEAR_TO_DATE', NOW), null);
  const dayOnly = normalizeUnderlyingReference(raw, 'XRX', NOW)!;
  assert.equal(underlyingReferenceChartPoints(stock(), dayOnly, 'ONE_WEEK', NOW), null);
  const recentFragment = { ...month, points: [2, 1, 0].map(day =>
    ({ timestamp: new Date(NOW - day * 86_400_000).toISOString(), price: '3.4' })) };
  assert.equal(underlyingReferenceChartPoints(stock(), recentFragment, 'ONE_WEEK', NOW), null);
  assert.equal(underlyingReferenceChartPoints(stock(), recentFragment, 'ONE_MONTH', NOW), null);
});

test('underlying reference accepts lossless-json string timestamps and volumes', () => {
  const raw = { chart: { result: [{ meta: { symbol: 'XRX', currency: 'USD', instrumentType: 'EQUITY', regularMarketPrice: '3.4',
    regularMarketChangePercent: '-2', regularMarketTime: String(NOW / 1000) },
    timestamp: [String(NOW / 1000 - 300), String(NOW / 1000)],
    indicators: { quote: [{ close: ['3.3', '3.4'], volume: ['100', '200'] }] } }] } };
  const reference = normalizeUnderlyingReference(raw, 'XRX', NOW)!;
  assert.equal(reference.sourceAt, new Date(NOW).toISOString());
  assert.equal(reference.turnoverLocal, '1010');
  assert.equal(reference.points.length, 2);
});

test('chart normalization rejects a different returned ticker, invalid prices and future timestamps', () => {
  const base = { chart: { result: [{ meta: { symbol: 'OTHER', currency: 'USD', instrumentType: 'EQUITY', regularMarketPrice: 3 }, timestamp: [], indicators: { quote: [{}] } }] } };
  assert.equal(normalizeUnderlyingReference(base, 'XRX', NOW), null);
  assert.equal(normalizeUnderlyingReference({ chart: { result: [{ ...base.chart.result[0], meta: { ...base.chart.result[0].meta, symbol: 'XRX', regularMarketPrice: 0 } }] } }, 'XRX', NOW), null);
});


test('share history rejects absent or foreign currency even when the returned ticker matches', () => {
  for (const currency of [undefined, 'EUR', 'GBP', 'USDC']) {
    const raw = { chart: { result: [{ meta: { symbol: 'XRX', currency, instrumentType: 'EQUITY', regularMarketPrice: 3.4 },
      timestamp: [], indicators: { quote: [{}] } }] } };
    assert.equal(normalizeUnderlyingReference(raw, 'XRX', NOW), null);
  }
});
