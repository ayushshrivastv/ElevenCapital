import assert from 'node:assert/strict';
import test from 'node:test';
import { setTimeout as wait } from 'node:timers/promises';
import { buildApp } from '../src/app.js';
import { consumePythSse, decodePythUpdate, PythPredictionService, PredictionPriceError,
  PYTH_BTC_USD_FEED_ID, PYTH_ETH_USD_FEED_ID, PYTH_HERMES_URL, type PredictionSnapshot } from '../src/prediction-pyth.js';

const NOW = 1_800_000_000_000;
const KEY = 'private-pyth-trial-test-key';
const encoder = new TextEncoder();
function update(publishTime = NOW / 1_000, price = '348052123456', conf = '5000000', feedId = PYTH_ETH_USD_FEED_ID) {
  return { parsed: [{ id: feedId, price: { price, conf, expo: -8, publish_time: publishTime },
    metadata: { prev_publish_time: publishTime - 1 }, ema_price: { price: '999' } }], binary: { encoding: 'hex', data: ['abcd1234'] } };
}
function json(body: unknown, status = 200) { return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } }); }
async function until(condition: () => boolean, ms = 1_000): Promise<void> {
  const end = Date.now() + ms;
  while (!condition()) { if (Date.now() > end) assert.fail('Timed out waiting for prediction event'); await wait(3); }
}
function upstream(feedId = PYTH_ETH_USD_FEED_ID) {
  const calls: { url: URL; init?: RequestInit }[] = [];
  const streams: ReadableStreamDefaultController<Uint8Array>[] = [];
  let latest: unknown = update(NOW / 1_000, '348052123456', '5000000', feedId);
  let responseStatus = 200;
  const fetcher = (async (input, init) => {
    const url = new URL(String(input)); calls.push({ url, init });
    if (url.pathname.endsWith('/stream')) {
      const body = new ReadableStream<Uint8Array>({ start(controller) {
        streams.push(controller);
        init?.signal?.addEventListener('abort', () => { try { controller.close(); } catch {} }, { once: true });
      } });
      return new Response(body, { headers: { 'content-type': 'text/event-stream' } });
    }
    return json(latest, responseStatus);
  }) as typeof fetch;
  return { calls, streams, fetch: fetcher, setLatest(value: unknown) { latest = value; }, setStatus(value: number) { responseStatus = value; },
    push(value: unknown, index = streams.length - 1) { streams[index]!.enqueue(encoder.encode(`event: price_update\ndata: ${JSON.stringify(value)}\n\n`)); } };
}
function roundUpstream(start: number, prices: readonly number[], confidences: readonly number[] = prices.map(() => 0.01),
  anchorPrice = 3480, anchorConfidence = 0.01, feedId = PYTH_ETH_USD_FEED_ID) {
  const offsets = [270, 180, 90];
  assert.equal(prices.length, offsets.length); assert.equal(confidences.length, offsets.length);
  const observations = new Map<number, ReturnType<typeof update>>();
  for (let i = 0; i < offsets.length; i++) {
    const target = start - offsets[i]!;
    const value = update(target + 1, String(Math.round(prices[i]! * 1e8)), String(Math.round(confidences[i]! * 1e8)), feedId);
    value.parsed[0]!.metadata.prev_publish_time = target - 1;
    observations.set(target, value);
  }
  const anchor = update(start + 1, String(Math.round(anchorPrice * 1e8)), String(Math.round(anchorConfidence * 1e8)), feedId);
  anchor.parsed[0]!.metadata.prev_publish_time = start - 1;
  observations.set(start, anchor);
  const calls: number[] = [];
  const fetcher = (async input => {
    const url = new URL(String(input)); const target = Number(url.pathname.split('/').at(-1)); calls.push(target);
    const value = observations.get(target);
    return value ? json(value) : json({ unavailable: true }, 404);
  }) as typeof fetch;
  return { calls, observations, fetch: fetcher };
}

test('Pyth decoder preserves exact price precision and selects pinned spot ETH rather than EMA', () => {
  const decoded = decodePythUpdate(update(), NOW, true);
  assert.deepEqual(decoded.quote, { priceUsd: '3480.52123456', confidenceUsd: '0.05', publishTime: NOW / 1000 });
  assert.deepEqual(decoded.updateData, ['0xabcd1234']);
  const zeroConf = update(NOW / 1000, '100000000', '0');
  assert.equal(decodePythUpdate(zeroConf, NOW).quote.confidenceUsd, '0');
  assert.equal(decodePythUpdate(zeroConf, NOW).quote.priceUsd, '1');
  const large = update(NOW / 1000, '9223372036854775807'); large.parsed[0]!.price.expo = -18;
  assert.equal(decodePythUpdate(large, NOW).quote.priceUsd, '9.223372036854775807');
});

test('decoder rejects other feeds, invalid numbers, future prices, missing/invalid signed bytes and malformed metadata', () => {
  const mutations: ((data: ReturnType<typeof update>) => void)[] = [
    data => { data.parsed[0]!.id = 'a'.repeat(64); }, data => { data.parsed[0]!.price.price = '0'; },
    data => { data.parsed[0]!.price.price = '-1'; }, data => { data.parsed[0]!.price.price = '1e8'; },
    data => { data.parsed[0]!.price.price = '9223372036854775808'; }, data => { data.parsed[0]!.price.conf = '-1'; },
    data => { data.parsed[0]!.price.expo = -200; }, data => { data.parsed[0]!.price.publish_time += 1; },
    data => { data.parsed[0]!.metadata.prev_publish_time += 1; }, data => { data.binary.data = ['123']; },
    data => { data.binary.encoding = 'base64'; }, data => { data.binary.data = []; }, data => { data.binary.data.push('aabb'); },
    data => { data.parsed.push(data.parsed[0]!); },
  ];
  for (const mutate of mutations) { const value = update(); mutate(value); assert.throws(() => decodePythUpdate(value, NOW, true), PredictionPriceError); }
});

test('BTC decoder accepts only the pinned Pyth BTC/USD feed', () => {
  const btc = update(NOW / 1_000, '10000025000000', '200000000', PYTH_BTC_USD_FEED_ID);
  assert.equal(decodePythUpdate(btc, NOW, true, PYTH_BTC_USD_FEED_ID).quote.priceUsd, '100000.25');
  assert.throws(() => decodePythUpdate(btc, NOW, false, PYTH_ETH_USD_FEED_ID),
    (error: unknown) => error instanceof PredictionPriceError && error.code === 'wrong_pyth_feed');
  assert.throws(() => decodePythUpdate(update(), NOW, false, PYTH_BTC_USD_FEED_ID),
    (error: unknown) => error instanceof PredictionPriceError && error.code === 'wrong_pyth_feed');
});

test('unconfigured service immediately reports unavailable configuration without opening upstream connections', async () => {
  let calls = 0;
  const service = new PythPredictionService({ apiKey: undefined, now: () => NOW, fetch: async () => { calls++; throw new Error('must not fetch'); } });
  const events: PredictionSnapshot[] = [];
  const off = service.subscribe(value => events.push(value));
  try {
    assert.equal(events[0]!.status, 'unconfigured'); assert.equal(events[0]!.quote, null);
    assert.equal((await service.snapshot()).status, 'unconfigured');
    await assert.rejects(service.atTimestamp(NOW / 1000), (e: unknown) => e instanceof PredictionPriceError && e.code === 'pyth_unconfigured');
    assert.equal(calls, 0);
  } finally { off(); service.close(); }
});

test('one shared stream authenticates only to pinned Hermes host and never exposes credentials', async () => {
  const source = upstream(); const events: PredictionSnapshot[] = [];
  const service = new PythPredictionService({ apiKey: KEY, fetch: source.fetch, now: () => NOW });
  const offA = service.subscribe(value => events.push(value)), offB = service.subscribe(() => {});
  try {
    const snapshots = await Promise.all([service.snapshot(), service.snapshot()]);
    assert.equal(source.calls.filter(call => call.url.pathname.endsWith('/stream')).length, 1);
    assert.equal(source.calls.filter(call => call.url.pathname.endsWith('/latest')).length, 1);
    for (const call of source.calls) {
      assert.equal(call.url.origin, new URL(PYTH_HERMES_URL).origin);
      assert.equal(call.url.searchParams.get('ids[]'), PYTH_ETH_USD_FEED_ID);
      assert.equal(new Headers(call.init?.headers).get('authorization'), `Bearer ${KEY}`);
      assert.equal(call.init?.redirect, 'error'); assert.ok(!call.url.href.includes(KEY));
    }
    assert.equal(snapshots[0]!.status, 'live');
    assert.ok(!JSON.stringify({ snapshots, events }).includes(KEY));
  } finally { offA(); offB(); service.close(); }
});

test('source publish time controls staleness even when heartbeat messages continue', async () => {
  let clock = NOW; const source = upstream(), events: PredictionSnapshot[] = [];
  const service = new PythPredictionService({ apiKey: KEY, fetch: source.fetch, now: () => clock, heartbeatMs: 5 });
  const off = service.subscribe(value => events.push(value));
  try {
    await service.snapshot(); clock += 5_001;
    await until(() => events.at(-1)?.status === 'stale');
    assert.equal(events.at(-1)!.quote!.publishTime, NOW / 1000);
    assert.equal(source.calls.length, 2, 'heartbeats do not spend additional REST quota');
    source.push(update(Math.floor(clock / 1000), '350000000000'));
    await until(() => events.at(-1)?.status === 'live');
    assert.equal(events.at(-1)!.quote!.priceUsd, '3500');
  } finally { off(); service.close(); }
});

test('out-of-order and contradictory same-second reports cannot overwrite the accepted observation', async () => {
  let clock = NOW; const source = upstream();
  const service = new PythPredictionService({ apiKey: KEY, fetch: source.fetch, now: () => clock });
  const events: PredictionSnapshot[] = []; const off = service.subscribe(value => events.push(value));
  try {
    await service.snapshot(); source.push(update(NOW / 1000 - 1, '100000000000'));
    source.push(update(NOW / 1000, '200000000000')); await wait(10);
    assert.equal((await service.snapshot()).quote!.priceUsd, '3480.52123456');
    clock += 1_000; source.push(update(clock / 1000, '349000000000'));
    await until(() => events.at(-1)?.quote?.priceUsd === '3490');
  } finally { off(); service.close(); }
});

test('a failed or malformed SSE connection reconnects with one shared stream and recovers only from valid data', async () => {
  const source = upstream(), events: PredictionSnapshot[] = [];
  const service = new PythPredictionService({ apiKey: KEY, fetch: source.fetch, now: () => NOW, reconnectMs: 5 });
  const off = service.subscribe(value => events.push(value));
  try {
    await service.snapshot(); source.streams[0]!.enqueue(encoder.encode('data: malformed\n\n'));
    await until(() => source.streams.length === 2);
    assert.ok(events.some(event => event.status === 'unavailable'));
    source.push(update()); await until(() => events.at(-1)?.status === 'live');
    assert.equal(source.calls.filter(call => call.url.pathname.endsWith('/stream')).length, 2);
  } finally { off(); service.close(); }
});

test('upstream credentials/body/exception details are redacted from failures', async () => {
  const source = upstream(); source.setStatus(401); source.setLatest({ message: `echo ${KEY}` });
  const service = new PythPredictionService({ apiKey: KEY, fetch: source.fetch, now: () => NOW });
  try {
    const snapshot = await service.snapshot(); assert.equal(snapshot.status, 'unavailable'); assert.equal(snapshot.quote, null);
    await assert.rejects(service.atTimestamp(NOW / 1000), (error: unknown) => {
      assert.ok(!String(error).includes(KEY)); return error instanceof PredictionPriceError && error.code === 'pyth_upstream_unavailable';
    });
  } finally { service.close(); }
});

test('boundary lookup uses first-report endpoint, verifies target window/previous timestamp and caches signed data', async () => {
  const source = upstream(), target = NOW / 1000 - 3;
  const value = update(target + 2); value.parsed[0]!.metadata.prev_publish_time = target - 1; source.setLatest(value);
  const service = new PythPredictionService({ apiKey: KEY, fetch: source.fetch, now: () => NOW });
  try {
    const [a, b] = await Promise.all([service.atTimestamp(target), service.atTimestamp(target)]);
    assert.equal(a.targetTimestamp, target); assert.equal(a.quote.publishTime, target + 2); assert.deepEqual(a.updateData, ['0xabcd1234']);
    assert.deepEqual(a, b); await service.atTimestamp(target);
    assert.equal(source.calls.length, 1); assert.ok(source.calls[0]!.url.pathname.endsWith(`/${target}`));
    assert.ok(!JSON.stringify(a).includes(KEY));
  } finally { service.close(); }
});

test('late, non-first, missing-proof and future boundary reports fail closed instead of settling with a latest quote', async () => {
  const target = NOW / 1000 - 10;
  for (const kind of ['late', 'before', 'non-first', 'missing-previous', 'missing-binary'] as const) {
    const source = upstream(); const value = update(target + 1);
    value.parsed[0]!.metadata.prev_publish_time = target - 1;
    if (kind === 'late') value.parsed[0]!.price.publish_time = target + 6;
    if (kind === 'before') { value.parsed[0]!.price.publish_time = target - 1; value.parsed[0]!.metadata.prev_publish_time = target - 2; }
    if (kind === 'non-first') value.parsed[0]!.metadata.prev_publish_time = target;
    if (kind === 'missing-previous') delete (value.parsed[0] as { metadata?: unknown }).metadata;
    if (kind === 'missing-binary') value.binary.data = [];
    source.setLatest(value); const service = new PythPredictionService({ apiKey: KEY, fetch: source.fetch, now: () => NOW });
    try { await assert.rejects(service.atTimestamp(target), PredictionPriceError); } finally { service.close(); }
  }
  const source = upstream(), service = new PythPredictionService({ apiKey: KEY, fetch: source.fetch, now: () => NOW });
  try {
    await assert.rejects(service.atTimestamp(NOW / 1000 + 1), (e: unknown) => e instanceof PredictionPriceError && e.code === 'pyth_target_not_ready');
    await assert.rejects(service.atTimestamp(1), (e: unknown) => e instanceof PredictionPriceError && e.code === 'pyth_target_outside_retention');
    await assert.rejects(service.atTimestamp(1.5), (e: unknown) => e instanceof PredictionPriceError && e.statusCode === 400);
    assert.equal(source.calls.length, 0);
  } finally { service.close(); }
});

test('round range is fixed from three completed rounds, rounded up to cents and shared across clients', async () => {
  const start = NOW / 1000, poolId = start / 90;
  const source = roundUpstream(start, [3480, 3480.2, 3480.4],
    [0.03, 0.03, 0.03], 3480.6, 0.03);
  const service = new PythPredictionService({ apiKey: KEY, fetch: source.fetch, now: () => NOW + 2_000 });
  try {
    const [a, b, c] = await Promise.all([service.round(poolId), service.round(poolId), service.round(poolId)]);
    assert.deepEqual(a, b); assert.deepEqual(a, c); assert.equal(a.bandUsd, '0.15');
    assert.equal(a.forecastSigmaUsd, '0.348060');
    assert.deepEqual(a.quote, { priceUsd: '3480.6', confidenceUsd: '0.03', publishTime: start + 1 });
    assert.equal(source.calls.length, 4, 'each previous boundary and opening anchor is fetched only once');
    assert.deepEqual(source.calls.slice(0, 3), [start - 270, start - 180, start - 90]);
    await service.round(poolId); assert.equal(source.calls.length, 4, 'completed pool is cached');
    assert.ok(!JSON.stringify(a).includes(KEY));
  } finally { service.close(); }
});

test('stable ETH receives the floor while recent completed-round movement widens the next range', async () => {
  const start = NOW / 1000, poolId = start / 90;
  for (const [prices, anchor, expected] of [
    [[3480, 3480, 3480], 3480, '0.05'],
    [[3480, 3480.1, 3480.2], 3480.3, '0.08'],
    [[3480, 3480, 3480], 3481.5, '0.45'],
  ] as const) {
    const source = roundUpstream(start, prices, undefined, anchor);
    const service = new PythPredictionService({ apiKey: KEY, fetch: source.fetch, now: () => NOW + 2_000 });
    try { assert.equal((await service.round(poolId)).bandUsd, expected); }
    finally { service.close(); }
  }
});

test('oracle confidence widens both the safe boundary and odds uncertainty without making Range dominant', async () => {
  const start = NOW / 1000, poolId = start / 90;
  const source = roundUpstream(start, [3480, 3480, 3480], [0.5, 0.5, 0.5], 3480, 0.5);
  const service = new PythPredictionService({ apiKey: KEY, fetch: source.fetch, now: () => NOW + 2_000 });
  try {
    const terms = await service.round(poolId);
    assert.equal(terms.bandUsd, '1.00');
    assert.equal(terms.forecastSigmaUsd, '1.500000');
  } finally { service.close(); }
});

test('the minimum range width still carries enough uncertainty for balanced opening odds', async () => {
  const start = NOW / 1000, poolId = start / 90;
  const source = roundUpstream(start, [1, 1, 1], [0.001, 0.001, 0.001], 1, 0.001);
  const service = new PythPredictionService({ apiKey: KEY, fetch: source.fetch, now: () => NOW + 2_000 });
  try {
    const terms = await service.round(poolId);
    assert.equal(terms.bandUsd, '0.05');
    assert.equal(terms.forecastSigmaUsd, '0.075000');
  } finally { service.close(); }
});

test('three-round band uses movement magnitude without directional bias', async () => {
  const start = NOW / 1000, poolId = start / 90;
  const cases = [
    { prices: [3480, 3480.2, 3480.4], anchor: 3480.6, band: '0.15' },
    { prices: [3480, 3480.2, 3480], anchor: 3480.2, band: '0.15' },
  ];
  for (const entry of cases) {
    const source = roundUpstream(start, entry.prices, undefined, entry.anchor);
    const service = new PythPredictionService({ apiKey: KEY, fetch: source.fetch, now: () => NOW + 2_000 });
    try {
      const terms = await service.round(poolId);
      assert.equal(terms.bandUsd, entry.band);
      assert.ok(Number(terms.forecastSigmaUsd) > Number(terms.bandUsd));
      assert.ok(source.calls.slice(0, -1).every(time => time < start));
    } finally { service.close(); }
  }
});

test('the current opening report closes the third historical round and fixes its next range', async () => {
  const start = NOW / 1000, poolId = start / 90, prices = [3480, 3480, 3480];
  const outcomes = [];
  for (const anchor of [3480, 3480.2]) {
    const source = roundUpstream(start, prices, undefined, anchor);
    const service = new PythPredictionService({ apiKey: KEY, fetch: source.fetch, now: () => NOW + 2_000 });
    try { outcomes.push(await service.round(poolId)); } finally { service.close(); }
  }
  assert.equal(outcomes[0]!.bandUsd, '0.05');
  assert.equal(outcomes[1]!.bandUsd, '0.06');
  assert.notEqual(outcomes[0]!.quote.priceUsd, outcomes[1]!.quote.priceUsd);
});

test('round rejects missing boundaries, excess movement and unsafe oracle confidence', async () => {
  const start = NOW / 1000, poolId = start / 90;
  const cases = [
    { source: roundUpstream(start, [3480, 3480, 3480]), mutate: (s: ReturnType<typeof roundUpstream>) => {
      s.observations.delete(start - 90);
    }, error: 'pyth_upstream_unavailable' },
    { source: roundUpstream(start, [3480, 3480, 3480], undefined, 3500), error: 'pyth_round_unavailable' },
    { source: roundUpstream(start, [3480, 3480, 3480], undefined, 3480, 2.01), error: 'pyth_round_unavailable' },
    { source: roundUpstream(start, [3480, 3480, 3480], [1.9, 1.9, 1.9], 3480, 2.01),
      error: 'pyth_round_unavailable' },
  ];
  for (const entry of cases) {
    entry.mutate?.(entry.source);
    const service = new PythPredictionService({ apiKey: KEY, fetch: entry.source.fetch, now: () => NOW + 2_000 });
    try {
      await assert.rejects(service.round(poolId), (error: unknown) => error instanceof PredictionPriceError && error.code === entry.error);
    } finally { service.close(); }
  }
});

test('BTC rounds derive their symmetric band from the previous three moves', async () => {
  const start = NOW / 1000, poolId = start / 90;
  const cases = [
    { prices: [83404, 83404, 83404], anchor: 83404, band: '0.01' },
    { prices: [100000, 100000, 100000], anchor: 100100, band: '30.00' },
    { prices: [100000, 100000, 100000], anchor: 100250, band: '75.00' },
    { prices: [100000, 100000, 100000], anchor: 100267, band: '80.10' },
    { prices: [100000, 100000, 100000], anchor: 102000, band: '100.00', expectedSigma: '240.000000' },
  ] as const;
  for (const entry of cases) {
    const source = roundUpstream(start, entry.prices, undefined, entry.anchor, 0.01, PYTH_BTC_USD_FEED_ID);
    const service = new PythPredictionService({ asset: 'btc', apiKey: KEY, fetch: source.fetch, now: () => NOW + 2_000 });
    try {
      const terms = await service.round(poolId);
      assert.equal(terms.feedId, PYTH_BTC_USD_FEED_ID);
      assert.equal(terms.bandUsd, entry.band);
      assert.equal(terms.quote.priceUsd, String(entry.anchor), 'opening price is the authenticated boundary');
      if (entry.anchor === 83404) {
        assert.equal(Number(terms.quote.priceUsd) + Number(terms.bandUsd), 83404.01);
        assert.equal(Number(terms.quote.priceUsd) - Number(terms.bandUsd), 83403.99);
      }
      if ('expectedSigma' in entry) assert.equal(terms.forecastSigmaUsd, entry.expectedSigma);
      assert.ok(Number(terms.forecastSigmaUsd) >= 1.5 * Number(terms.bandUsd));
      assert.deepEqual(source.calls, [start - 270, start - 180, start - 90, start]);
    } finally { service.close(); }
  }
  for (const [confidence, expectedSigma] of [[40, '120.000000'], [40.01, '120.030000'],
    [100, '240.000000']] as const) {
    const source = roundUpstream(start, [100000, 100000, 100000], [confidence, confidence, confidence],
      100000, confidence, PYTH_BTC_USD_FEED_ID);
    const service = new PythPredictionService({ asset: 'btc', apiKey: KEY, fetch: source.fetch, now: () => NOW + 2_000 });
    try {
      const terms = await service.round(poolId);
      assert.equal(terms.bandUsd, '0.01', 'Pyth confidence does not choose the prediction range');
      assert.equal(terms.quote.confidenceUsd, String(confidence));
      assert.equal(terms.forecastSigmaUsd, expectedSigma);
    } finally { service.close(); }
  }
});

test('SSE parser accepts CRLF split across chunks and caps untrusted event buffers', async () => {
  const data: unknown[] = [], body = JSON.stringify(update()), control = new AbortController();
  const response = new Response(new ReadableStream({ start(controller) {
    controller.enqueue(encoder.encode(': keepalive\r'));
    controller.enqueue(encoder.encode(`\ndata: ${body}\r`));
    controller.enqueue(encoder.encode('\n\r\n')); controller.close();
  } }), { headers: { 'content-type': 'text/event-stream' } });
  await consumePythSse(response, value => data.push(value), control.signal); assert.equal(data.length, 1);
  const oversized = new Response(`data: ${'a'.repeat(300_000)}`, { headers: { 'content-type': 'text/event-stream' } });
  await assert.rejects(consumePythSse(oversized, () => assert.fail('must not publish'), control.signal), PredictionPriceError);
});

test('close aborts shared stream and all in-flight historical requests without reconnecting', async () => {
  const signals: AbortSignal[] = [];
  const service = new PythPredictionService({ apiKey: KEY, now: () => NOW, fetch: (async (_input, init) => {
    signals.push(init!.signal!);
    return new Promise((_resolve, reject) => init!.signal!.addEventListener('abort', () => reject(new Error(`private ${KEY}`)), { once: true }));
  }) as typeof fetch });
  const off = service.subscribe(() => {}), historical = service.atTimestamp(NOW / 1000);
  const rejected = assert.rejects(historical, (e: unknown) => e instanceof PredictionPriceError && !String(e).includes(KEY));
  service.close(); off(); await rejected; await wait(5);
  assert.equal(signals.length, 2); assert.ok(signals.every(signal => signal.aborted));
});

test('read-only routes expose normalized snapshots and bounded boundary errors, reject client provider/key overrides', async () => {
  const source = upstream(); const service = new PythPredictionService({ apiKey: KEY, fetch: source.fetch, now: () => NOW });
  const app = await buildApp(undefined, false, { prediction: service });
  try {
    const snapshot = await app.inject({ method: 'GET', url: '/v1/prediction/eth' });
    assert.equal(snapshot.statusCode, 200); assert.equal(snapshot.json().status, 'live');
    assert.equal(snapshot.headers['cache-control'], 'no-store'); assert.ok(!snapshot.body.includes(KEY));
    const boundary = await app.inject({ method: 'GET', url: `/v1/prediction/eth/at/${NOW / 1000}` });
    assert.equal(boundary.statusCode, 200); assert.deepEqual(boundary.json().updateData, ['0xabcd1234']);
    for (const path of ['/v1/prediction/eth?apiKey=anything', '/v1/prediction/eth?feedId=abc',
      '/v1/prediction/eth/at/nope', '/v1/prediction/eth/at/0', '/v1/prediction/eth/at/1.5']) {
      assert.equal((await app.inject({ method: 'GET', url: path })).statusCode, 400);
    }
    const future = await app.inject({ method: 'GET', url: `/v1/prediction/eth/at/${NOW / 1000 + 10}` });
    assert.equal(future.statusCode, 503); assert.equal(future.json().error, 'pyth_target_not_ready');
  } finally { await app.close(); }
});

test('round route returns one shared ETH anchor and adaptive band without credentials or client overrides', async () => {
  const start = NOW / 1000, poolId = start / 90;
  const source = roundUpstream(start, [3480, 3480, 3480], undefined, 3480, 0.01);
  const service = new PythPredictionService({ apiKey: KEY, fetch: source.fetch, now: () => NOW + 2_000 });
  const app = await buildApp(undefined, false, { prediction: service });
  try {
    const response = await app.inject({ method: 'GET', url: `/v1/prediction/eth/round/${poolId}` });
    assert.equal(response.statusCode, 200);
    assert.deepEqual(response.json(), { schemaVersion: 1, feedId: PYTH_ETH_USD_FEED_ID, poolId,
      bandUsd: '0.05', forecastSigmaUsd: '0.348000',
      quote: { priceUsd: '3480', confidenceUsd: '0.01', publishTime: start + 1 } });
    assert.equal(response.headers['cache-control'], 'no-store'); assert.ok(!response.body.includes(KEY));
    for (const path of [`/v1/prediction/eth/round/${poolId}?bandUsd=0.01`, '/v1/prediction/eth/round/nope',
      '/v1/prediction/eth/round/0', '/v1/prediction/eth/round/1.5']) {
      assert.equal((await app.inject({ method: 'GET', url: path })).statusCode, 400);
    }
    const future = await app.inject({ method: 'GET', url: `/v1/prediction/eth/round/${poolId + 1}` });
    assert.equal(future.statusCode, 503); assert.equal(future.json().error, 'pyth_target_not_ready');
  } finally { await app.close(); }
});

test('BTC routes pin Hermes requests, expose signed BTC boundaries and independent round terms', async () => {
  const target = NOW / 1000 - 3, start = NOW / 1000, poolId = start / 90;
  const source = upstream(PYTH_BTC_USD_FEED_ID);
  const boundary = update(target + 2, '10000000000000', '100000000', PYTH_BTC_USD_FEED_ID);
  boundary.parsed[0]!.metadata.prev_publish_time = target - 1;
  source.setLatest(boundary);
  const btc = new PythPredictionService({ asset: 'btc', apiKey: KEY, fetch: source.fetch, now: () => NOW });
  const eth = new PythPredictionService({ asset: 'eth', apiKey: undefined, now: () => NOW });
  const app = await buildApp(undefined, false, { prediction: eth, predictionBtc: btc });
  try {
    const snapshot = await app.inject({ method: 'GET', url: '/v1/prediction/btc' });
    assert.equal(snapshot.statusCode, 200);
    assert.equal(snapshot.json().feedId, PYTH_BTC_USD_FEED_ID);
    assert.equal(snapshot.json().quote.priceUsd, '100000');
    assert.equal((await app.inject({ method: 'GET', url: '/v1/prediction/eth' })).json().feedId, PYTH_ETH_USD_FEED_ID);
    const signed = await app.inject({ method: 'GET', url: `/v1/prediction/btc/at/${target}` });
    assert.equal(signed.statusCode, 200);
    assert.equal(signed.json().feedId, PYTH_BTC_USD_FEED_ID);
    assert.deepEqual(signed.json().updateData, ['0xabcd1234']);
    assert.ok(source.calls.every(call => call.url.searchParams.get('ids[]') === PYTH_BTC_USD_FEED_ID));
    for (const path of ['/v1/prediction/btc?feedId=abc', '/v1/prediction/btc/at/nope']) {
      assert.equal((await app.inject({ method: 'GET', url: path })).statusCode, 400);
    }
    assert.ok(!snapshot.body.includes(KEY));
  } finally { await app.close(); }

  const roundSource = roundUpstream(start, [100000, 100005, 100010], undefined,
    100015, 40.01, PYTH_BTC_USD_FEED_ID);
  const roundService = new PythPredictionService({ asset: 'btc', apiKey: KEY, fetch: roundSource.fetch, now: () => NOW + 2_000 });
  const roundApp = await buildApp(undefined, false, {
    prediction: new PythPredictionService({ asset: 'eth', apiKey: undefined, now: () => NOW }), predictionBtc: roundService,
  });
  try {
    const response = await roundApp.inject({ method: 'GET', url: `/v1/prediction/btc/round/${poolId}` });
    assert.equal(response.statusCode, 200);
    assert.equal(response.json().feedId, PYTH_BTC_USD_FEED_ID);
    assert.equal(response.json().bandUsd, '3.75');
    assert.equal(response.json().quote.priceUsd, '100015');
    assert.equal(response.json().quote.confidenceUsd, '40.01', 'clients receive the authenticated confidence');
    assert.equal((await roundApp.inject({ method: 'GET', url: `/v1/prediction/btc/round/${poolId}?bandUsd=0.01` })).statusCode, 400);
  } finally { await roundApp.close(); }
});

test('SSE route sends initial status immediately and application shutdown closes connected streams', async () => {
  const service = new PythPredictionService({ apiKey: undefined, now: () => NOW });
  const app = await buildApp(undefined, false, { prediction: service });
  const address = await app.listen({ host: '127.0.0.1', port: 0 });
  const controller = new AbortController();
  try {
    const response = await fetch(`${address}/v1/prediction/eth/stream`, { signal: controller.signal });
    assert.equal(response.status, 200); assert.ok(response.headers.get('content-type')!.includes('text/event-stream'));
    const reader = response.body!.getReader(); const first = new TextDecoder().decode((await reader.read()).value);
    assert.ok(first.includes('event: price')); assert.ok(first.includes('"status":"unconfigured"')); assert.ok(!first.includes(KEY));
    const close = app.close(); assert.equal((await reader.read()).done, true); await close; reader.releaseLock();
  } finally { controller.abort(); await app.close(); }
});

test('BTC SSE starts with the BTC feed and closes on application shutdown', async () => {
  const btc = new PythPredictionService({ asset: 'btc', apiKey: undefined, now: () => NOW });
  const app = await buildApp(undefined, false, { predictionBtc: btc });
  const address = await app.listen({ host: '127.0.0.1', port: 0 });
  const controller = new AbortController();
  try {
    const response = await fetch(`${address}/v1/prediction/btc/stream`, { signal: controller.signal });
    assert.equal(response.status, 200);
    const reader = response.body!.getReader();
    const first = new TextDecoder().decode((await reader.read()).value);
    assert.ok(first.includes(`"feedId":"${PYTH_BTC_USD_FEED_ID}"`));
    const close = app.close(); assert.equal((await reader.read()).done, true); await close; reader.releaseLock();
  } finally { controller.abort(); await app.close(); }
});
