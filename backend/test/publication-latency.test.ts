import assert from 'node:assert/strict';
import { performance } from 'node:perf_hooks';
import test from 'node:test';
import { backpackActivity } from '../src/activity.js';
import { LiveMarketService } from '../src/live-service.js';
import type { CatalogMutation } from '../src/market-update.js';
import { DisabledTrading, type Catalog, type Stock } from '../src/schema.js';
import { initialStatistics } from '../src/statistics.js';

const observedAt = new Date().toISOString();
function stock(price: string): Stock {
  return {
    id: 'backpack:MSFT.US', provider: 'backpack', providerAssetId: 'MSFT.US',
    providerLabel: 'Backpack Securities', symbol: 'MSFT.US', name: 'Microsoft', logoUrl: null,
    underlying: { symbol: 'MSFT', isin: 'US5949181045', cusip: '594918104', listingCountry: 'US', currency: 'USD' },
    quote: { price, currency: 'USDC', currencyBasis: 'market_symbol', changeAmount: '1', changePercent: '1',
      asOf: null, receivedAt: observedAt, basis: 'external_reference_non_executable' },
    volume24h: '100', volume24hBasis: 'quote_currency_turnover', activity: backpackActivity('100', observedAt),
    statistics: initialStatistics([]), deployments: [], trading: DisabledTrading,
  };
}
type Controls = {
  running: boolean;
  store(value: Stock): void;
  removeFromCatalog(id: string): boolean;
  publish(): void;
};
type Publication = { catalog: Catalog; mutation: CatalogMutation; at: number };
const delay = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));
async function until(predicate: () => boolean) {
  const deadline = performance.now() + 1_000;
  while (!predicate()) {
    if (performance.now() > deadline) throw new Error('Timed out waiting for publication');
    await delay(1);
  }
}

test('idle publications are immediate and burst changes coalesce behind a 16ms monotonic gate', async () => {
  const service = new LiveMarketService(async () => { throw new Error('No upstream request expected'); });
  const controls = service as unknown as Controls;
  const publications: Publication[] = [];
  controls.running = true;
  service.onUpdate((catalog, mutation) => publications.push({ catalog, mutation, at: performance.now() }));
  try {
    controls.store(stock('100'));
    const idleRequestedAt = performance.now();
    controls.publish();
    assert.equal(publications.length, 1, 'the first publication after an idle period must not wait for a timer');
    assert.ok(publications[0]!.at - idleRequestedAt < 16);
    assert.deepEqual(publications[0]!.mutation.changedIds, ['backpack:MSFT.US']);
    assert.deepEqual(publications[0]!.mutation.removedIds, []);
    assert.equal(publications[0]!.mutation.membershipChanged, true);

    controls.store(stock('101'));
    const burstRequestedAt = performance.now();
    controls.publish();
    controls.store(stock('102'));
    controls.publish();
    assert.equal(publications.length, 1, 'a burst inside the gate must wait for one trailing publication');
    await until(() => publications.length === 2);
    const burst = publications[1]!;
    assert.ok(burst.at - burstRequestedAt < 100, 'local coalescing must remain in millisecond latency');
    assert.deepEqual(burst.mutation.changedIds, ['backpack:MSFT.US']);
    assert.deepEqual(burst.mutation.removedIds, []);
    assert.equal(burst.mutation.membershipChanged, false);
    assert.equal(burst.catalog.stocks[0]!.quote.price, '102', 'the newest value in a burst must win');
    assert.ok(burst.mutation.requestedAt <= burst.at);

    assert.equal(controls.removeFromCatalog('backpack:MSFT.US'), true);
    controls.publish();
    await until(() => publications.length === 3);
    assert.deepEqual(publications[2]!.mutation.changedIds, []);
    assert.deepEqual(publications[2]!.mutation.removedIds, ['backpack:MSFT.US']);
    assert.equal(publications[2]!.mutation.membershipChanged, true);
    assert.equal(publications[2]!.catalog.stocks.length, 0);

    controls.publish();
    await until(() => publications.length === 4);
    assert.deepEqual(publications[3]!.mutation.changedIds, []);
    assert.deepEqual(publications[3]!.mutation.removedIds, []);
    assert.equal(publications[3]!.mutation.membershipChanged, false,
      'provider-only state publications must not be discarded');

    const metrics = service.diagnostics().metrics;
    assert.equal(metrics.publicationLatencySamples, 4);
    assert.equal(typeof metrics.lastPublicationLatencyMs, 'number');
    assert.equal(typeof metrics.maxPublicationLatencyMs, 'number');
    assert.equal(typeof metrics.p95PublicationLatencyMs, 'number');
    assert.equal(typeof metrics.p99PublicationLatencyMs, 'number');
    assert.ok(metrics.maxPublicationLatencyMs! < 100);
  } finally { service.stop(); }
});

test('publication latency diagnostics keep a bounded rolling sample', () => {
  let monotonic = 0;
  const service = new LiveMarketService(async () => { throw new Error('No upstream request expected'); }, Date.now, () => monotonic);
  const controls = service as unknown as Controls;
  controls.running = true;
  try {
    for (let index = 0; index < 300; index++) {
      monotonic += 20;
      controls.publish();
    }
    const metrics = service.diagnostics().metrics;
    assert.equal(metrics.published, 300);
    assert.equal(metrics.publicationLatencySamples, 256);
    assert.equal(metrics.lastPublicationLatencyMs, 0);
    assert.equal(metrics.maxPublicationLatencyMs, 0);
    assert.equal(metrics.p95PublicationLatencyMs, 0);
    assert.equal(metrics.p99PublicationLatencyMs, 0);
  } finally { service.stop(); }
});
