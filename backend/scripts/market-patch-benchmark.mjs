import { performance } from 'node:perf_hooks';
import { setTimeout as delay } from 'node:timers/promises';
import { backpackActivity } from '../dist/activity.js';
import { LiveMarketService } from '../dist/live-service.js';
import { RealtimeCatalog } from '../dist/realtime.js';
import { DisabledTrading } from '../dist/schema.js';
import { initialStatistics } from '../dist/statistics.js';

const stockCount = 2_020;
const updateCount = 100;
const observedAt = new Date().toISOString();
const stock = (index, price = '100') => ({
  id: `backpack:S${index}.US`, provider: 'backpack', providerAssetId: `S${index}.US`,
  providerLabel: 'Backpack Securities', symbol: `S${index}.US`, name: `Stock ${index}`, logoUrl: null,
  underlying: { symbol: `S${index}`, isin: null, cusip: null, listingCountry: 'US', currency: 'USD' },
  quote: { price, currency: 'USDC', currencyBasis: 'market_symbol', changeAmount: '0', changePercent: '0',
    asOf: null, receivedAt: observedAt, basis: 'external_reference_non_executable' },
  volume24h: '1000', volume24hBasis: 'quote_currency_turnover', activity: backpackActivity('1000', observedAt),
  statistics: initialStatistics([]), deployments: [], trading: DisabledTrading,
});

const service = new LiveMarketService(async () => { throw new Error('No upstream request expected'); });
// This deterministic benchmark injects accepted provider observations directly.
// TypeScript `private` methods compile to ordinary JavaScript methods and are used
// here only to measure the same validated commit/publication path used in production.
service.running = true;
for (let index = 0; index < stockCount; index++) service.store(stock(index));

const feed = new RealtimeCatalog(async () => service.snapshot());
let observed = 0;
let started = 0;
const timings = [];
feed.subscribe(event => {
  if (event.event !== 'snapshot' || event.patch?.stocks.length !== 1 || started <= 0) return;
  timings.push(performance.now() - started);
  observed++;
});
service.onUpdate((catalog, mutation) => feed.publish(catalog, mutation));
service.publish();

for (let iteration = 0; iteration < updateCount; iteration++) {
  // Exercise the immediate-after-idle path. Burst behavior is covered separately
  // by publication-latency.test.ts and has a bounded 16 ms trailing window.
  await delay(18);
  const index = iteration % stockCount;
  started = performance.now();
  service.store(stock(index, String(101 + iteration)));
  service.publish();
  if (observed !== iteration + 1) throw new Error(`Accepted update ${iteration} was not emitted synchronously.`);
}

service.stop();
feed.stop();
timings.sort((a, b) => a - b);
const percentile = value => timings[Math.max(0, Math.ceil(timings.length * value) - 1)];
const report = {
  measuredPath: 'LiveMarketService accepted stock update through freshness normalization and RealtimeCatalog patch emission (no network or Android rendering)',
  stockCount, updateCount,
  p50Ms: percentile(0.50), p95Ms: percentile(0.95), p99Ms: percentile(0.99), maxMs: timings.at(-1),
  target: { p95Ms: 50, p99Ms: 100 },
};
console.log(JSON.stringify(report, null, 2));
if (report.p95Ms > report.target.p95Ms || report.p99Ms > report.target.p99Ms) process.exitCode = 1;
