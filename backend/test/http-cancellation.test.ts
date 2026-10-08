import assert from 'node:assert/strict';
import test from 'node:test';
import { limitConcurrency } from '../src/cache.js';
import { createGetJson, geckoTerminalDiagnostics, minimumRequestSpacing } from '../src/http.js';

test('cancelled FIFO waiters reject immediately without consuming a work slot or delaying the next caller', async () => {
  const run = limitConcurrency(1), controller = new AbortController();
  let release!: () => void, cancelledRan = false, nextRan = false;
  const first = run(() => new Promise<void>(resolve => { release = resolve; }));
  const cancelled = run(async () => { cancelledRan = true; }, controller.signal);
  const rejected = assert.rejects(cancelled, /Sweep deadline/);
  const next = run(async () => { nextRan = true; });
  controller.abort(new Error('Sweep deadline'));
  await rejected;
  assert.equal(cancelledRan, false); assert.equal(nextRan, false);
  release(); await Promise.all([first, next]);
  assert.equal(nextRan, true);
  await assert.rejects(run(async () => { cancelledRan = true; }, controller.signal), /Sweep deadline/);
  assert.equal(cancelledRan, false);
});

test('cancelled spacing waits preserve the last actual start and allow following requests to use its original interval', async () => {
  let clock = 1_000, release!: () => void;
  const waits: number[] = [], controller = new AbortController();
  const slot = minimumRequestSpacing(2_050, () => clock, (ms, signal) => new Promise<void>((resolve, reject) => {
    waits.push(ms); release = () => { clock += ms; resolve(); };
    signal?.addEventListener('abort', () => { reject(signal.reason); }, { once: true });
  }));
  await slot();
  const cancelled = slot(controller.signal), rejected = assert.rejects(cancelled, /Sweep deadline/);
  await Promise.resolve();
  controller.abort(new Error('Sweep deadline')); await rejected;
  const next = slot(); await Promise.resolve();
  assert.deepEqual(waits, [2_050, 2_050], 'cancelled waiting never records a fictitious request start');
  release(); await next;
  assert.equal(clock, 3_050);
});

test('a Gecko request cancelled behind a busy shared transport returns before that transport is released and never fetches', async () => {
  const original = globalThis.fetch, requested: string[] = [];
  const firstPath = `/api/v2/networks/robinhood/pools/multi/0x${'a'.repeat(64)}`;
  const cancelledPath = `/api/v2/networks/robinhood/pools/multi/0x${'b'.repeat(64)}`;
  let entered!: () => void, release!: () => void;
  const started = new Promise<void>(resolve => { entered = resolve; });
  try {
    globalThis.fetch = (async input => {
      requested.push(String(input)); entered();
      await new Promise<void>(resolve => { release = resolve; });
      return new Response('{"data":[]}', { status: 200 });
    }) as typeof fetch;
    const http = createGetJson(), query = { include: 'base_token,quote_token', include_volume_breakdown: 'true' };
    const first = http('geckoterminal', firstPath, query);
    await started;
    const controller = new AbortController();
    const cancelled = http('geckoterminal', cancelledPath, query, controller.signal);
    assert.equal(geckoTerminalDiagnostics().queued, 1); assert.equal(geckoTerminalDiagnostics().inFlight, 1);
    const rejected = assert.rejects(cancelled, /Sweep deadline/);
    controller.abort(new Error('Sweep deadline')); await rejected;
    assert.equal(geckoTerminalDiagnostics().queued, 0);
    assert.equal(requested.length, 1);
    release(); await first;
    assert.equal(geckoTerminalDiagnostics().inFlight, 0); assert.equal(geckoTerminalDiagnostics().lastStatus, 200);
    assert.equal(requested.length, 1);
  } finally { globalThis.fetch = original; }
});
