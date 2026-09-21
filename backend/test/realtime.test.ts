import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import type { ServerResponse } from 'node:http';
import test from 'node:test';
import { buildApp } from '../src/app.js';
import { MarketStreams, RealtimeCatalog, snapshotAt, type MarketEvent } from '../src/realtime.js';
import { CatalogSchema, DisabledTrading, type Catalog } from '../src/schema.js';
import { unavailableStatistics } from '../src/statistics.js';

const start = Date.parse('2026-09-18T12:00:00.000Z');
function catalog(at = start): Catalog {
  const receivedAt = new Date(at).toISOString();
  const statistics = unavailableStatistics(null, 'No verified Solana deployment is available.');
  statistics.marketCapitalization = { value: '1200000', unit: 'USD', source: 'Jupiter', status: 'available',
    basis: 'token_market_cap', receivedAt, reason: null };
  return CatalogSchema.parse({ schemaVersion: 1, mode: 'live-read-only', receivedAt,
    providers: [{ id: 'backpack', status: 'ok' }], stocks: [{
      id: 'backpack:MSFT.US', provider: 'backpack', providerLabel: 'Backpack Securities', providerAssetId: 'MSFT.US',
      symbol: 'MSFT.US', name: 'Microsoft Corporation', logoUrl: null,
      underlying: { symbol: 'MSFT', isin: 'US5949181045', cusip: '594918104' },
      quote: { price: '497.200000000000000001', currency: 'USDC', currencyBasis: 'market_symbol',
        changeAmount: '1', changePercent: '0.2', asOf: null, receivedAt, basis: 'external_reference_non_executable' },
      volume24h: '5000', volume24hBasis: 'quote_currency_turnover', statistics,
      activity: { currency: 'USDC', source: 'Backpack', scope: 'external_market', volume24h: '5000', netVolume24h: null,
        receivedAt, updatedAt: null, volumeReason: null, netVolumeReason: 'No public buy/sell breakdown is available.' },
      deployments: [], trading: DisabledTrading,
    }] });
}
const delay = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms));
async function until(predicate: () => boolean): Promise<void> {
  const deadline = Date.now() + 1500;
  while (!predicate()) { if (Date.now() >= deadline) throw new Error('Timed out'); await delay(2); }
}

test('replaying cached data advances only the envelope and cannot revive expired prices, metrics or volume', () => {
  const original = catalog();
  const replay = snapshotAt(original, start + 60_000);
  assert.equal(replay.receivedAt, new Date(start + 60_000).toISOString());
  assert.equal(replay.stocks[0]!.quote.receivedAt, original.receivedAt);
  assert.equal(replay.stocks[0]!.activity.receivedAt, original.receivedAt);
  assert.equal(replay.stocks[0]!.statistics.marketCapitalization.receivedAt, original.receivedAt);
  const expired = snapshotAt(original, start + 300_001);
  assert.equal(expired.stocks[0]!.quote.price, null);
  assert.equal(expired.stocks[0]!.quote.changePercent, null);
  assert.equal(expired.stocks[0]!.activity.volume24h, null);
  assert.equal(expired.stocks[0]!.volume24h, null);
  assert.equal(expired.stocks[0]!.statistics.marketCapitalization.status, 'unavailable');
  assert.equal(expired.providers[0]!.status, 'unavailable');
  assert.equal(original.stocks[0]!.quote.price, '497.200000000000000001');
});

test('activity source-time expiry is independent of a recently fetched price or metric', () => {
  const value = catalog(start + 299_000);
  value.stocks[0]!.activity.updatedAt = new Date(start).toISOString();
  const snapshot = snapshotAt(value, start + 300_001);
  assert.notEqual(snapshot.stocks[0]!.quote.price, null);
  assert.equal(snapshot.stocks[0]!.statistics.marketCapitalization.status, 'available');
  assert.equal(snapshot.stocks[0]!.activity.volume24h, null);
});
test('permanent public-quote gaps retain the service coverage status and message without implying reconnection', () => {
  const value = catalog();
  value.providers[0]!.message = '1 of 2 fresh external-market references; not executable quotes.';
  const missing = structuredClone(value.stocks[0]!);
  missing.id = 'backpack:NEW.US'; missing.providerAssetId = 'NEW.US';
  missing.quote = { ...missing.quote, price:null, changeAmount:null, changePercent:null, receivedAt:null };
  value.stocks.push(missing);
  assert.deepEqual(snapshotAt(value, start + 1000).providers, value.providers);
  value.stocks = [missing];value.providers[0] = {id:'backpack',status:'unavailable',message:'No public quote exists for this catalog member.'};
  assert.deepEqual(snapshotAt(value, start + 1000).providers, value.providers);
});
test('an expired or future quote source time marks previously available observations stale even after a fresh receipt', () => {
  for (const asOf of [new Date(start-300001).toISOString(),new Date(start+1).toISOString()]) {
    const value = catalog();const expired = structuredClone(value.stocks[0]!);
    expired.id = 'backpack:OLD.US';expired.quote.asOf = asOf;value.stocks.push(expired);
    const result = snapshotAt(value,start);
    assert.equal(result.providers[0]!.status,'stale');assert.match(result.providers[0]!.message!,/1 previously observed quotes expired/);
    assert.notEqual(result.stocks[0]!.quote.price,null);assert.equal(result.stocks[1]!.quote.price,null);
  }
});
test('statistics with an expired or future source update cannot become fresh through a recent receipt or replay', () => {
  for (const updatedAt of [new Date(start).toISOString(), new Date(start + 300_002).toISOString()]) {
    const value = catalog(start + 299_000);
    value.stocks[0]!.statistics.updatedAt = updatedAt;
    const snapshot = snapshotAt(value, start + 300_001);
    assert.notEqual(snapshot.stocks[0]!.quote.price, null);
    assert.notEqual(snapshot.stocks[0]!.activity.volume24h, null);
    assert.equal(snapshot.stocks[0]!.statistics.marketCapitalization.status, 'unavailable');
    assert.equal(snapshot.stocks[0]!.statistics.marketCapitalization.value, null);
    assert.equal(snapshot.stocks[0]!.statistics.marketCapitalization.receivedAt, new Date(start + 299_000).toISOString());
    assert.equal(snapshot.stocks[0]!.statistics.updatedAt, updatedAt);
  }
});

test('background feed recovers from a cold outage without any client retry or overlapping loader', async () => {
  let loads = 0; let active = 0; let maxActive = 0;
  const events: MarketEvent[] = [];
  const feed = new RealtimeCatalog(async () => {
    loads++; active++; maxActive = Math.max(maxActive, active);
    await delay(8); active--;
    if (loads === 1) throw new Error('PRIVATE UPSTREAM ERROR');
    return catalog();
  }, () => start, 8, 5);
  const unsubscribe = feed.subscribe(event => events.push(event));
  feed.start(); feed.start();
  await until(() => events.some(event => event.event === 'snapshot'));
  feed.stop(); unsubscribe();
  const stoppedAt = loads;
  await delay(30);
  assert.equal(loads, stoppedAt);
  assert.equal(maxActive, 1);
  assert.ok(events.some(event => event.event === 'status' && event.data.state === 'loading'));
  assert.ok(events.some(event => event.event === 'status' && event.data.state === 'reconnecting'));
  assert.ok(!JSON.stringify(events).includes('PRIVATE'));
});

test('a warm snapshot is returned immediately during refresh and failures retain only bounded old observations', async () => {
  let clock = start; let resolve!: (value: Catalog) => void;
  let load: () => Promise<Catalog> = async () => catalog();
  const feed = new RealtimeCatalog(() => load(), () => clock);
  await feed.refresh();
  load = () => new Promise<Catalog>(r => { resolve = r; });
  const pending = feed.refresh();
  assert.equal(feed.refresh(), pending);
  clock += 20_000;
  const immediate = await feed.snapshot();
  assert.equal(immediate.stocks[0]!.quote.receivedAt, new Date(start).toISOString());
  assert.equal(immediate.receivedAt, new Date(clock).toISOString());
  resolve(catalog(clock)); await pending;
  load = async () => { throw new Error('outage'); };
  clock += 20_000;
  const stale = await feed.refresh();
  assert.equal(stale.providers[0]!.status, 'stale');
  assert.notEqual(stale.stocks[0]!.quote.price, null);
  clock += 300_001;
  const expired = await feed.refresh();
  assert.equal(expired.stocks[0]!.quote.price, null);
});

test('incremental publications carry only freshness-normalized changed rows', () => {
  let clock = start + 300_001;
  const feed = new RealtimeCatalog(async () => catalog(), () => clock);
  const events: MarketEvent[] = [];
  feed.subscribe(event => events.push(event));
  feed.publish(catalog(), { changedIds: ['backpack:MSFT.US'], removedIds: [], membershipChanged: false, requestedAt: 12 });
  const event = events.at(-1)!;
  assert.equal(event.event, 'snapshot');
  if (event.event !== 'snapshot') throw new Error('Expected snapshot');
  assert.deepEqual(event.patch?.changedIds, ['backpack:MSFT.US']);
  assert.equal(event.patch?.requestedAt, 12);
  assert.equal(event.patch?.stocks.length, 1);
  assert.equal(event.patch?.stocks[0]!.quote.price, null);
  assert.equal(event.data.stocks[0]!.quote.price, null);
  feed.stop();
});

test('periodic unchanged refresh derives an empty non-membership patch after the initial snapshot', async () => {
  let clock = start; let monotonic = 20;
  const feed = new RealtimeCatalog(async () => catalog(), () => clock, 10_000, 5_000, () => monotonic);
  const events: MarketEvent[] = [];
  feed.subscribe(event => events.push(event));
  await feed.refresh();
  clock += 1_000; monotonic = 27;
  await feed.refresh();
  const snapshots = events.filter((event): event is Extract<MarketEvent, { event: 'snapshot' }> => event.event === 'snapshot');
  assert.equal(snapshots[0]!.patch, undefined);
  assert.deepEqual(snapshots[1]!.patch?.changedIds, []);
  assert.deepEqual(snapshots[1]!.patch?.removedIds, []);
  assert.equal(snapshots[1]!.patch?.membershipChanged, false);
  assert.equal(snapshots[1]!.patch?.requestedAt, 27);
  assert.deepEqual(snapshots[1]!.patch?.stocks, []);
  feed.stop();
});

test('a source mutation also carries unrelated rows that expire during freshness normalization', () => {
  let clock = start;
  const initial = catalog();
  const expiring = structuredClone(initial.stocks[0]!);
  expiring.id = 'backpack:AAPL.US';
  expiring.providerAssetId = 'AAPL.US';
  expiring.symbol = 'AAPL.US';
  expiring.name = 'Apple Inc.';
  expiring.underlying = { symbol: 'AAPL', isin: null, cusip: null };
  initial.stocks.push(expiring);
  const feed = new RealtimeCatalog(async () => initial, () => clock);
  const events: MarketEvent[] = [];
  feed.subscribe(event => events.push(event));
  feed.publish(initial);

  clock = start + 300_001;
  const updated = structuredClone(initial);
  const observed = new Date(clock).toISOString();
  updated.receivedAt = observed;
  updated.stocks[0]!.quote.receivedAt = observed;
  updated.stocks[0]!.activity.receivedAt = observed;
  updated.stocks[0]!.statistics.marketCapitalization.receivedAt = observed;
  feed.publish(updated, {
    changedIds: ['backpack:MSFT.US'], removedIds: [], membershipChanged: false, requestedAt: 31,
  });

  const event = events.at(-1)!;
  assert.equal(event.event, 'snapshot');
  if (event.event !== 'snapshot') throw new Error('Expected snapshot');
  assert.deepEqual(event.patch?.changedIds, ['backpack:MSFT.US', 'backpack:AAPL.US']);
  assert.equal(event.patch?.requestedAt, 31);
  const expired = event.patch?.stocks.find(stock => stock.id === 'backpack:AAPL.US');
  assert.ok(expired);
  assert.equal(expired.quote.price, null);
  assert.equal(expired.activity.volume24h, null);
  assert.equal(expired.statistics.marketCapitalization.status, 'unavailable');
  feed.stop();
});

class Response extends EventEmitter {
  frames: string[] = [];
  writableLength = 0;
  writableEnded = false;
  destroyed = false;
  acceptWrites = true;
  writeHead() { return this; }
  flushHeaders() {}
  write(frame: string) { this.frames.push(frame); return this.acceptWrites; }
  end() { this.writableEnded = true; }
  destroy() { this.destroyed = true; this.emit('close'); }
  asHttp() { return this as unknown as ServerResponse; }
}

test('slow streams retain only the newest pending update, bound client count and remove listeners on close', async () => {
  let clock = start;
  const feed = new RealtimeCatalog(async () => catalog(clock), () => clock, 10_000);
  await feed.refresh();
  const streams = new MarketStreams(feed, () => clock, 10_000, 1);
  const response = new Response();
  assert.equal(streams.attach(response.asHttp()), true);
  assert.equal(streams.attach(new Response().asHttp()), false);
  await delay(1);
  response.acceptWrites = false;
  clock += 1000; await feed.refresh();
  const written = response.frames.length;
  clock += 1000; await feed.refresh();
  clock += 1000; await feed.refresh();
  assert.equal(response.frames.length, written);
  response.acceptWrites = true; response.emit('drain');
  assert.equal(response.frames.length, written + 1);
  assert.ok(response.frames.at(-1)!.includes(new Date(clock).toISOString()));
  response.emit('close');
  assert.equal(streams.size, 0);
  const closedCount = response.frames.length;
  await feed.refresh();
  assert.equal(response.frames.length, closedCount);
  assert.equal(response.listenerCount('drain'), 0);
  streams.close(); feed.stop();
});

test('oversized stream responses close the individual connection without holding an unbounded queue', async () => {
  const value = catalog();
  value.stocks[0]!.name = 'x'.repeat(16 * 1024 * 1024);
  const feed = new RealtimeCatalog(async () => value, () => start);
  await feed.refresh();
  const streams = new MarketStreams(feed, () => start);
  const response = new Response();
  streams.attach(response.asHttp());
  assert.equal(response.destroyed, true);
  assert.equal(streams.size, 0);
  feed.stop();
});

test('HTTP stream sends loading, a validated catalog and heartbeat; cancellation and shutdown finish cleanly', async () => {
  let loads = 0;
  const app = await buildApp({
    catalog: async () => { loads++; await delay(20); return catalog(); },
    chart: async () => { throw new Error('unused'); },
  }, false, { now: () => start, refreshMs: 10_000, heartbeatMs: 10 });
  const address = await app.listen({ host: '127.0.0.1', port: 0 });
  const response = await fetch(`${address}/v1/stocks/stream`, { signal: AbortSignal.timeout(2000) });
  assert.match(response.headers.get('content-type')!, /text\/event-stream/);
  assert.match(response.headers.get('cache-control')!, /no-store/);
  const reader = response.body!.getReader();
  let text = '';
  while (!text.includes('event: snapshot') || !text.includes('event: heartbeat')) {
    const part = await reader.read();
    assert.equal(part.done, false);
    text += new TextDecoder().decode(part.value);
  }
  assert.ok(text.includes('retry: 2000'));
  assert.ok(text.includes('"state":"loading"'));
  const frame = text.split('\n\n').find(part => part.startsWith('event: snapshot'))!;
  assert.equal(CatalogSchema.parse(JSON.parse(frame.split('\ndata: ')[1]!)).stocks.length, 1);
  assert.equal(loads, 1);
  await reader.cancel();
  await app.close();
});
