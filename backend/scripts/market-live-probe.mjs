import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import WebSocket from 'ws';
import { CatalogSchema, StockSchema, ChartSchema } from '../dist/schema.js';

const base = process.env.MARKET_PROBE_URL || 'http://127.0.0.1:8787';
const fetchJson = async path => {
  const response = await fetch(base + path, { signal: AbortSignal.timeout(30_000) });
  assert.equal(response.status, 200);
  return response.json();
};
const initial = CatalogSchema.parse(await fetchJson('/v1/stocks'));
const reference = initial.stocks.find(s => s.quote.basis === 'underlying_share_reference' && s.quote.price !== null);
assert.ok(reference, 'An approved reference must exist for this live probe');
const ids = [reference.id, `backpack:${reference.underlying.symbol}.US`, 'backpack:MSFT.US'];
const report = { startedAt: new Date().toISOString(), initialCount: initial.stocks.length, ids, frames: [], connections: [], errors: [], latenciesMs: [], transportLatenciesMs: [], chartChecks: 0 };

async function observe(duration, detailId) {
  const start = Date.now(), state = new Map();
  let revision = -1, session, timer;
  const socket = new WebSocket(base.replace(/^http/, 'ws') + '/v1/market/ws');
  try {
    await new Promise((resolve, reject) => {
      timer = setTimeout(resolve, duration);
      socket.once('error', reject);
      socket.once('open', () => socket.send(JSON.stringify({ type: 'subscribe', ids, detail: { id: detailId, range: 'ONE_DAY' } })));
      socket.on('message', raw => {
        try {
          const frame = JSON.parse(raw.toString());
          const item = { type: frame.type, revision: frame.revision, ms: Date.now() - start, bytes: raw.length };
          if (frame.type === 'status') { item.state = frame.state; item.message = frame.message; }
          if (frame.type === 'snapshot' || frame.type === 'delta') {
            if (session !== undefined) assert.equal(frame.sessionId, session);
            session = frame.sessionId;
            assert.ok(frame.revision > revision, 'Catalog revisions must increase'); revision = frame.revision;
            const rows = frame.type === 'snapshot' ? CatalogSchema.parse(frame.catalog).stocks : frame.stocks.map(s => StockSchema.parse(s));
            if (frame.type === 'snapshot') state.clear();
            for (const row of rows) state.set(row.id, row);
            item.count = rows.length;
            item.selected = rows.filter(row => ids.includes(row.id)).map(row => {
              if (row.quote.asOf) report.latenciesMs.push(Date.now() - Date.parse(row.quote.asOf));
              if (row.quote.receivedAt) report.transportLatenciesMs.push(Date.now() - Date.parse(row.quote.receivedAt));
              return { id: row.id, price: row.quote.price, volume: row.volume24h, basis: row.quote.basis, asOf: row.quote.asOf };
            });
          }
          if (frame.type === 'chart') {
            const chart = ChartSchema.parse(frame.chart), stock = state.get(chart.stockId), last = chart.points.at(-1);
            assert.ok(stock, 'Chart identity must exist in the authoritative catalog');
            assert.equal(chart.currency, stock.quote.currency); assert.equal(chart.basis, stock.quote.basis);
            if (last && stock.quote.price !== null) { assert.equal(last.price, stock.quote.price); report.chartChecks++; }
            item.chart = { id: chart.stockId, basis: chart.basis, status: chart.status, points: chart.points.length, last };
          }
          report.frames.push(item);
        } catch (error) { report.errors.push(String(error)); }
      });
    });
  } finally {
    report.connections.push({ durationMs: Date.now() - start, extensions: socket.extensions, wireBytes: socket._socket?.bytesRead ?? null });
    clearTimeout(timer); socket.terminate();
  }
}

await observe(22_000, reference.id);
await observe(3_000, 'backpack:MSFT.US');
report.diagnostics = await fetchJson('/v1/market/diagnostics');
report.finishedAt = new Date().toISOString();
report.summary = {
  frames: report.frames.length,
  snapshots: report.frames.filter(f => f.type === 'snapshot').length,
  deltas: report.frames.filter(f => f.type === 'delta').length,
  charts: report.chartChecks,
  reconnectStatuses: report.frames.filter(f => f.type === 'status' && f.state === 'reconnecting').length,
  bootstrapMs: report.frames.filter(f => f.type === 'snapshot').map(f => f.ms),
  sourceLatencyMs: report.latenciesMs.length ? { min: Math.min(...report.latenciesMs), max: Math.max(...report.latenciesMs), samples: report.latenciesMs.length } : null,
  invalidUpdates: report.diagnostics.metrics.invalidUpdates,
  upstreamMessages: report.diagnostics.metrics.upstream.messages,
  connections: report.connections,
  errors: report.errors,
};
await mkdir('verification', { recursive: true });
await writeFile('verification/market-live-socket.json', JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify(report.summary, null, 2));
assert.equal(report.errors.length, 0);
assert.equal(report.summary.reconnectStatuses, 0);
assert.equal(report.summary.invalidUpdates, 0);
assert.ok(report.chartChecks > 0);
assert.ok(report.summary.snapshots >= 2, 'Fresh snapshot is required after reconnect');
