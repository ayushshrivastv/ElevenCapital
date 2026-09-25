import assert from 'node:assert/strict';
import test from 'node:test';
import { LiveMarketService, locallyObservedHistoryCoversRange } from '../src/live-service.js';
import { loadPreStocks } from '../src/providers.js';
import type { GetJson } from '../src/http.js';
import type { Stock } from '../src/schema.js';

const START = Date.parse('2026-09-24T12:00:00.000Z');
const MINT = 'Pren1FvFX6J3E4kXhJuCiAD5aDmGEb7qJRncwA8Lkhw';
const point = (at: number, price: string) => ({ timestamp: new Date(at).toISOString(), price });

test('server observations must span the requested window before being presented as history', () => {
  const threeRecent = [point(START - 120_000, '100'), point(START - 60_000, '101'), point(START, '102')];
  assert.equal(locallyObservedHistoryCoversRange(threeRecent, 'ONE_DAY', START), false);
  assert.equal(locallyObservedHistoryCoversRange(threeRecent, 'ONE_WEEK', START), false);
  assert.equal(locallyObservedHistoryCoversRange(threeRecent, 'ONE_HOUR', START), false);
  const fullHour = [point(START - 3_600_000, '100'), point(START - 1_800_000, '101'), point(START, '102')];
  assert.equal(locallyObservedHistoryCoversRange(fullHour, 'ONE_HOUR', START), true);
  assert.equal(locallyObservedHistoryCoversRange(fullHour, 'ONE_DAY', START), false);
});

test('a newly observed PreStock quote does not masquerade as a full-day chart', async () => {
  let now = START;
  const http: GetJson = async (provider) => {
    if (provider === 'prestocks') return [{ name: 'Anthropic', symbol: 'ANTHROPIC', description: 'Issuer-listed private company tracker.',
      image: 'https://prestocks.com/anthropic.png', external_url: 'https://prestocks.com/', contract_address: MINT,
      markPrice: '100', markValuation: '1000000', tokenPrice: '100', impliedValuation: '1000000', supply: '10000' }];
    if (provider === 'geckoterminal') return { data: [] };
    throw new Error('Unexpected upstream request');
  };
  const stock = (await loadPreStocks(http, () => now)).stocks[0]!;
  const service = new LiveMarketService(http, () => now);
  const store = (service as unknown as { store(stock: Stock): void }).store.bind(service);
  try {
    for (const minute of [0, 1, 2]) {
      now = START + minute * 60_000;
      store({ ...stock, quote: { ...stock.quote, price: String(100 + minute), receivedAt: new Date(now).toISOString() } });
    }
    const day = await service.chart(stock.id, 'ONE_DAY');
    assert.equal(day.status, 'unavailable');
    assert.deepEqual(day.points, []);
    assert.match(day.statusReason!, /do not cover the selected ONE_DAY window/);
    assert.deepEqual(service.withCurrent(day), day, 'a live quote must not turn unavailable history into a graph');

    for (const minute of [10, 20, 30, 40, 50, 60]) {
      now = START + minute * 60_000;
      store({ ...stock, quote: { ...stock.quote, price: String(100 + minute), receivedAt: new Date(now).toISOString() } });
    }
    const hour = await service.chart(stock.id, 'ONE_HOUR');
    assert.equal(hour.status, 'ok');
    assert.equal(hour.basis, 'provider_indicative_token');
    assert.ok(hour.points.length >= 3);
    assert.equal((await service.chart(stock.id, 'ONE_DAY')).status, 'unavailable');
  } finally { service.stop(); }
});
