import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createServer } from 'node:http';
import { appendBoundedLog, authorizedDevices, createSupervisor, isElevenHealth, reverseTarget, selectUsbDevice, serviceLabel, verifyMarketStream, validateWalletCompatibilityResponse,
  validateTransferAuthCompatibilityResponse, readPrivyPublicEnvironment } from './preview-service.mjs';

test('wallet compatibility accepts validation and rejects old or broken backends', () => {
  assert.doesNotThrow(() => validateWalletCompatibilityResponse(400, '{"error":"invalid_request"}'));
  for (const [status, body] of [[404, '{"error":"not_found"}'], [500, '{"error":"invalid_request"}'], [200, '{"status":"ok"}'], [400, '{"error":"other"}'], [400, 'not-json']]) {
    assert.throws(() => validateWalletCompatibilityResponse(status, body));
  }
});

test('transfer compatibility requires bearer authentication before any RPC work', () => {
  assert.doesNotThrow(() => validateTransferAuthCompatibilityResponse(401, '{"error":"authentication_required"}'));
  for (const [status, body] of [[200, '{}'], [400, '{"error":"invalid_request"}'], [503, '{"error":"authentication_unavailable"}'], [401, '{"error":"other"}']]) {
    assert.throws(() => validateTransferAuthCompatibilityResponse(status, body));
  }
});

test('preview backend imports only public Privy identifiers from local properties', () => {
  const project = mkdtempSync(path.join(tmpdir(), 'eleven-privy-env-'));
  writeFileSync(path.join(project, 'local.properties'), 'sdk.dir=/secret\nprivyAppId=app-public\nprivyClientId=client-public\nappSecret=never-copy\n');
  assert.deepEqual(readPrivyPublicEnvironment(project), {
    PRIVY_APP_ID: 'app-public', PRIVY_APP_CLIENT_ID: 'client-public', PRIVY_NATIVE_APP_ID: 'com.elevencapital.app'
  });
  rmSync(project, { recursive: true, force: true });
});

function harness(initialHealth = 'free') {
  const state = { health: initialHealth, children: [], stopped: [], statuses: [], repaired: 0, cleaned: 0 };
  const supervisor = createSupervisor({
    probe: async () => state.health,
    startChild: () => { const child = { alive: true, running() { return this.alive; } }; state.children.push(child); return child; },
    stopChild: async child => { state.stopped.push(child); child.alive = false; },
    repairUsb: async () => { state.repaired += 1; return [{ serial: 'authorized-usb', status: 'connected' }]; },
    cleanupUsb: async () => { state.cleaned += 1; },
    status: value => state.statuses.push(value),
    log: () => {}
  });
  return { state, supervisor };
}

test('attaches to an existing Eleven server without creating or stopping any process', async () => {
  const { state, supervisor } = harness('eleven');
  await supervisor.tick();
  await supervisor.tick();
  assert.equal(state.children.length, 0);
  assert.equal(state.repaired, 2);
  assert.equal(state.statuses.at(-1).state, 'monitoring-existing-server');
  await supervisor.stop();
  assert.equal(state.stopped.length, 0);
  assert.equal(state.cleaned, 1);
});

test('never takes over an occupied, unverified server or forwards a phone to it', async () => {
  const { state, supervisor } = harness('occupied');
  await supervisor.tick();
  assert.equal(state.children.length, 0);
  assert.equal(state.repaired, 0);
  assert.equal(state.statuses.at(-1).state, 'port-occupied-by-unverified-service');
});

test('starts an owned server only after the previous external server disappears', async () => {
  const { state, supervisor } = harness('eleven');
  await supervisor.tick();
  state.health = 'free';
  await supervisor.tick();
  assert.equal(state.children.length, 1);
  state.health = 'eleven';
  await supervisor.tick();
  assert.equal(state.statuses.at(-1).state, 'running-owned-server');
  assert.equal(state.children.length, 1);
});

test('restarts an exited owned child and only stops its current owned child', async () => {
  const { state, supervisor } = harness();
  await supervisor.tick();
  state.children[0].alive = false;
  await supervisor.tick();
  assert.equal(state.children.length, 2);
  await supervisor.stop();
  assert.deepEqual(state.stopped, [state.children[1]]);
  await supervisor.tick();
  assert.equal(state.children.length, 2);
});

test('restarts only its own server after three failed health checks', async () => {
  const { state, supervisor } = harness();
  await supervisor.tick();
  state.health = 'occupied';
  await supervisor.tick();
  await supervisor.tick();
  assert.equal(state.stopped.length, 0);
  await supervisor.tick();
  assert.deepEqual(state.stopped, [state.children[0]]);
  await supervisor.tick();
  assert.equal(state.children.length, 1, 'Unknown port occupant must not be replaced.');
  state.health = 'free';
  await supervisor.tick();
  assert.equal(state.children.length, 2);
});

test('successful health checks reset the failure count', async () => {
  const { state, supervisor } = harness();
  await supervisor.tick();
  await supervisor.tick();
  await supervisor.tick();
  state.health = 'eleven';
  await supervisor.tick();
  state.health = 'occupied';
  await supervisor.tick();
  assert.equal(state.stopped.length, 0);
});

test('requires the exact public health signature', () => {
  assert.equal(isElevenHealth(200, JSON.stringify({ status: 'ok', schemaVersion: 1, mode: 'live-read-only' })), true);
  assert.equal(isElevenHealth(503, JSON.stringify({ status: 'ok', schemaVersion: 1, mode: 'live-read-only' })), false);
  assert.equal(isElevenHealth(200, '{"status":"ok"}'), false);
  assert.equal(isElevenHealth(200, 'not-json'), false);
});

test('excludes offline and unauthorized adb devices', () => {
  assert.deepEqual(authorizedDevices('List of devices attached\nS22 device usb:123 model:SM_S901E\nPRIVATE unauthorized\nOTHER offline\n'), ['S22']);
});

test('detects conflicting reverse destinations without assuming port ownership', () => {
  assert.equal(reverseTarget('S22 tcp:8787 tcp:8787\n'), 'tcp:8787');
  assert.equal(reverseTarget('S22 tcp:8787 tcp:9999\n'), 'tcp:9999');
  assert.equal(reverseTarget('S22 tcp:8080 tcp:8080\n'), null);
});

test('USB selection prefers the known S22 and refuses ambiguous alternatives', () => {
  assert.equal(selectUsbDevice(['OTHER', 'RZCW92MJRCT']), 'RZCW92MJRCT');
  assert.equal(selectUsbDevice(['ONLY_PHONE']), null);
  assert.equal(selectUsbDevice(['ONE', 'TWO']), null);
  assert.equal(selectUsbDevice([]), null);
});

test('project labels are stable and distinguish copied projects with spaces', () => {
  assert.equal(serviceLabel('/tmp/eleven capital'), serviceLabel('/tmp/eleven capital'));
  assert.notEqual(serviceLabel('/tmp/eleven capital'), serviceLabel('/tmp/copy/eleven capital'));
  assert.match(serviceLabel('/tmp/eleven capital'), /^com\.elevencapital\.preview\.[a-f0-9]{12}$/);
});

test('logs rotate to one backup and each file remains bounded', () => {
  const directory = mkdtempSync(path.join(tmpdir(), 'eleven-preview-test-'));
  try {
    const filename = path.join(directory, 'service.log');
    appendBoundedLog(filename, 'a'.repeat(16), 20);
    appendBoundedLog(filename, 'b'.repeat(16), 20);
    appendBoundedLog(filename, 'c'.repeat(40), 20);
    assert.equal(statSync(filename).size, 20);
    assert.equal(statSync(`${filename}.1`).size, 16);
    assert.equal(readFileSync(`${filename}.1`, 'utf8'), 'b'.repeat(16));
    assert.equal(readFileSync(filename, 'utf8'), 'c'.repeat(20));
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

async function withStreamServer(handler, run) {
  const server = createServer(handler);
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  try { return await run(server.address().port); } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
}
const streamSnapshot = { schemaVersion: 1, stocks: [{ id: 'backpack:MSFT.US', activity: { currency: 'USDC' } }] };
const validateSnapshot = value => {
  assert.equal(value.schemaVersion, 1);
  assert.equal(value.stocks[0].activity.currency, 'USDC');
  return value;
};

test('compatibility verifies a real fragmented SSE snapshot and heartbeat after cold loading status', async () => {
  let validationCalls = 0;
  await withStreamServer((req, res) => {
    assert.equal(req.url, '/v1/stocks/stream');
    assert.equal(req.headers.accept, 'text/event-stream');
    res.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8' });
    res.write('retry: 2000\r\n\r\nevent: status\r\ndata: {"state":"loading"}\r\n\r\n');
    res.write('event: snap');
    setTimeout(() => {
      res.write(`shot\r\ndata: ${JSON.stringify(streamSnapshot)}\r\n\r`);
      res.write('\nevent: heartbeat\ndata: {"serverTime":"2026-09-18T12:00:00.000Z"}\n\n');
    }, 5);
  }, async targetPort => {
    const value = await verifyMarketStream(raw => { validationCalls++; return validateSnapshot(raw); }, { targetPort, timeoutMs: 1000 });
    assert.deepEqual(value, streamSnapshot);
    assert.equal(validationCalls, 1);
  });
});

test('compatibility rejects old 404 servers and JSON endpoints even when their status is healthy', async () => {
  for (const [status, contentType, error] of [[404, 'application/json', /HTTP 404/], [200, 'application/json', /text\/event-stream/]]) {
    await withStreamServer((_req, res) => {
      res.writeHead(status, { 'Content-Type': contentType }); res.end(JSON.stringify(streamSnapshot));
    }, targetPort => assert.rejects(verifyMarketStream(validateSnapshot, { targetPort, timeoutMs: 1000 }), error));
  }
});

test('streamed catalog is validated rather than trusting an SSE content-type header', async () => {
  await withStreamServer((_req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/event-stream' });
    res.write('event: snapshot\ndata: {"schemaVersion":0,"stocks":[]}\n\n');
  }, targetPort => assert.rejects(verifyMarketStream(validateSnapshot, { targetPort, timeoutMs: 1000 }), /0 !== 1/));
});

test('streamed malformed JSON and invalid heartbeat timestamps fail compatibility', async () => {
  for (const data of ['event: snapshot\ndata: not-json\n\n', 'event: heartbeat\ndata: {"serverTime":"yesterday"}\n\n']) {
    await withStreamServer((_req, res) => {
      res.writeHead(200, { 'Content-Type': 'text/event-stream' }); res.write(data);
    }, targetPort => assert.rejects(verifyMarketStream(validateSnapshot, { targetPort, timeoutMs: 1000 })));
  }
});

test('silent, heartbeat-only and snapshot-only streams cannot hang installation or pass incomplete checks', async () => {
  for (const initial of ['', 'event: heartbeat\ndata: {"serverTime":"2026-09-18T12:00:00.000Z"}\n\n',
    `event: snapshot\ndata: ${JSON.stringify(streamSnapshot)}\n\n`]) {
    await withStreamServer((_req, res) => {
      res.writeHead(200, { 'Content-Type': 'text/event-stream' }); res.flushHeaders();
      if (initial) res.write(initial);
    }, targetPort => assert.rejects(verifyMarketStream(validateSnapshot, { targetPort, timeoutMs: 40 }), /deadline/));
  }
});

test('compatibility bounds streamed bytes and rejects early disconnects', async () => {
  await withStreamServer((_req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/event-stream' }); res.write(':'.repeat(1025));
  }, targetPort => assert.rejects(verifyMarketStream(validateSnapshot, { targetPort, timeoutMs: 1000, maxBytes: 1024 }), /response limit/));
  await withStreamServer((_req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/event-stream' }); res.end('retry: 2000\n\n');
  }, targetPort => assert.rejects(verifyMarketStream(validateSnapshot, { targetPort, timeoutMs: 1000 }), /closed/));
});

// Use the project's installed server dependency; the probe itself uses Node 22's client.
const { WebSocketServer } = (await import('node:module')).createRequire(
  new URL('../backend/package.json', import.meta.url))('ws');
const { verifyMarketSocket, fetchCatalog } = await import('./preview-service.mjs');
const socketSnapshot = { type: 'snapshot', sessionId: 'preview-session-1', revision: 1, catalog: streamSnapshot };
const socketHeartbeat = { type: 'heartbeat', serverTime: '2026-09-19T12:00:00.000Z' };
const socketDelta = { type: 'delta', sessionId: 'preview-session-1', revision: 2, stocks: [], removedIds: [],
  providers: [], receivedAt: '2026-09-19T12:00:00.000Z' };
async function withSocketServer(handler, run) {
  const server = createServer((_req, res) => { res.writeHead(404); res.end(); });
  const sockets = new WebSocketServer({ server, path: '/v1/market/ws', perMessageDeflate: false });
  sockets.on('connection', handler);
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  try { return await run(server.address().port); } finally {
    for (const client of sockets.clients) client.terminate();
    await new Promise(resolve => sockets.close(resolve));
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
}
const send = (socket, value) => socket.send(JSON.stringify(value));

test('WebSocket compatibility verifies snapshot, exactly one empty subscription, delta and heartbeat', async () => {
  let subscriptionCount = 0;
  await withSocketServer(socket => {
    send(socket, { type: 'status', state: 'loading' });
    send(socket, socketSnapshot);
    socket.on('message', data => {
      subscriptionCount++;
      assert.deepEqual(JSON.parse(data.toString()), { type: 'subscribe', ids: [], detail: null });
      send(socket, socketDelta);
      send(socket, socketHeartbeat);
    });
  }, async targetPort => {
    assert.deepEqual(await verifyMarketSocket(validateSnapshot, { targetPort, timeoutMs: 1000 }), streamSnapshot);
    assert.equal(subscriptionCount, 1);
  });
});

test('REST, WebSocket and legacy SSE accept full catalogs above the old 4 MiB ceiling', async () => {
  const large = { ...streamSnapshot, evidence: 'x'.repeat(5 * 1024 * 1024) };
  await withStreamServer((req, res) => {
    assert.equal(req.url, '/v1/stocks'); res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(large));
  }, async targetPort => assert.deepEqual(await fetchCatalog({ targetPort }), large));
  await withSocketServer(socket => {
    send(socket, { ...socketSnapshot, catalog: large });
    socket.on('message', () => { send(socket, socketDelta); send(socket, socketHeartbeat); });
  }, async targetPort => assert.deepEqual(await verifyMarketSocket(validateSnapshot, { targetPort, timeoutMs: 2000 }), large));
  await withStreamServer((_req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/event-stream' });
    res.write(`event: snapshot\ndata: ${JSON.stringify(large)}\n\nevent: heartbeat\ndata: ${JSON.stringify(socketHeartbeat)}\n\n`);
  }, async targetPort => assert.deepEqual(await verifyMarketStream(validateSnapshot, { targetPort, timeoutMs: 2000 }), large));
});

test('WebSocket compatibility rejects old healthy servers without the upgrade route', async () => {
  await withStreamServer((_req, res) => { res.writeHead(404); res.end('{"status":"ok"}'); },
    targetPort => assert.rejects(verifyMarketSocket(validateSnapshot, { targetPort, timeoutMs: 1000 }), /upgrade|connection/));
});

test('WebSocket snapshot and heartbeat alone cannot hide a broken subscription handler', async () => {
  await withSocketServer(socket => { send(socket, socketSnapshot); send(socket, socketHeartbeat); },
    targetPort => assert.rejects(verifyMarketSocket(validateSnapshot, { targetPort, timeoutMs: 60 }), /deadline/));
});

test('WebSocket compatibility rejects invalid JSON, binary data, envelopes and catalog schemas', async () => {
  const invalid = [
    socket => socket.send('not-json'),
    socket => socket.send(Buffer.from('{}')),
    socket => send(socket, { ...socketSnapshot, revision: -1 }),
    socket => send(socket, { ...socketSnapshot, sessionId: '' }),
    socket => send(socket, { ...socketSnapshot, catalog: { schemaVersion: 0, stocks: [] } }),
    socket => send(socket, { ...socketHeartbeat, serverTime: 'yesterday' }),
    socket => send(socket, { type: 'unknown' }),
    socket => send(socket, socketDelta),
  ];
  for (const handler of invalid) await withSocketServer(handler,
    targetPort => assert.rejects(verifyMarketSocket(validateSnapshot, { targetPort, timeoutMs: 1000 })));
});

test('WebSocket accepts a validated all-catalog changed row before the subscription acknowledgement', async () => {
  let patched = false;
  await withSocketServer(socket => {
    send(socket, socketSnapshot);
    socket.on('message', () => {
      send(socket, { ...socketDelta, stocks: [{ ...streamSnapshot.stocks[0], price: '500.25' }] });
      send(socket, { ...socketDelta, revision: 3 });
      send(socket, { ...socketDelta, revision: 4, stocks: streamSnapshot.stocks });
      send(socket, socketHeartbeat);
    });
  }, async targetPort => {
    await verifyMarketSocket(value => {
      if (value.stocks[0].price === '500.25') patched = true;
      return validateSnapshot(value);
    }, { targetPort, timeoutMs: 1000 });
    assert.equal(patched, true);
  });
});

test('WebSocket rejects inconsistent sessions, backwards revisions and malformed changed rows', async () => {
  for (const delta of [{ ...socketDelta, sessionId: 'other' }, { ...socketDelta, revision: 0 },
    { ...socketDelta, stocks: [null] },
    { ...socketDelta, stocks: [{ id: 'backpack:UNKNOWN.US' }] },
    { ...socketDelta, stocks: [...streamSnapshot.stocks, ...streamSnapshot.stocks] },
    { ...socketDelta, stocks: [{ ...streamSnapshot.stocks[0], activity: { currency: 'invalid' } }] },
    { ...socketDelta, removedIds: ['backpack:UNKNOWN.US'] },
    { ...socketDelta, removedIds: [streamSnapshot.stocks[0].id], stocks: streamSnapshot.stocks }]) {
    await withSocketServer(socket => {
      send(socket, socketSnapshot); socket.on('message', () => send(socket, delta));
    }, targetPort => assert.rejects(verifyMarketSocket(validateSnapshot, { targetPort, timeoutMs: 1000 })));
  }
});

test('WebSocket check bounds individual frames and total received bytes', async () => {
  await withSocketServer(socket => send(socket, { ...socketSnapshot, extra: 'x'.repeat(1024) }),
    targetPort => assert.rejects(verifyMarketSocket(validateSnapshot, { targetPort, maxFrameBytes: 1024 }), /frame/));
  await withSocketServer(socket => {
    for (let i = 0; i < 20; i++) send(socket, { type: 'status', state: 'loading', message: 'x'.repeat(100) });
  }, targetPort => assert.rejects(verifyMarketSocket(validateSnapshot, { targetPort, maxBytes: 1024 }), /response limit/));
});

test('WebSocket early close and silence fail within a bounded deadline', async () => {
  await withSocketServer(socket => socket.close(),
    targetPort => assert.rejects(verifyMarketSocket(validateSnapshot, { targetPort, timeoutMs: 1000 }), /closed/));
  await withSocketServer(() => {},
    targetPort => assert.rejects(verifyMarketSocket(validateSnapshot, { targetPort, timeoutMs: 40 }), /deadline/));
});

test('REST catalog bounds body size and deadline instead of hanging installation', async () => {
  await withStreamServer((_req, res) => { res.writeHead(200); res.write('x'.repeat(2048)); },
    targetPort => assert.rejects(fetchCatalog({ targetPort, maxBytes: 1024 }), /response limit/));
  await withStreamServer((_req, res) => { res.writeHead(200); res.flushHeaders(); },
    targetPort => assert.rejects(fetchCatalog({ targetPort, timeoutMs: 40 }), /deadline/));
});
