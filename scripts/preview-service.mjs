import { createHash } from 'node:crypto';
import { appendFileSync, existsSync, mkdirSync, readFileSync, realpathSync, renameSync, statSync, writeFileSync } from 'node:fs';
import { request } from 'node:http';
import path from 'node:path';
import { spawn, spawnSync, execFile } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { promisify } from 'node:util';
import { setTimeout as delay } from 'node:timers/promises';
import { classifyAdbFailure, createAdbManager } from './adb-manager.mjs';

const execute = promisify(execFile);
const port = 8787;
const ownFile = fileURLToPath(import.meta.url);
const logLimit = 1024 * 1024;
const marketFrameLimit = 16 * 1024 * 1024;
const marketResponseLimit = 64 * 1024 * 1024;

export function serviceLabel(project) {
  return `com.elevencapital.preview.${createHash('sha256').update(project).digest('hex').slice(0, 12)}`;
}

export function appendBoundedLog(filename, value, limit = logLimit) {
  let data = Buffer.from(value);
  if (data.length > limit) data = data.subarray(data.length - limit);
  if (existsSync(filename) && statSync(filename).size + data.length > limit) {
    renameSync(filename, `${filename}.1`);
  }
  appendFileSync(filename, data, { mode: 0o600 });
}

export function authorizedDevices(output) {
  return output.split('\n').map(line => line.trim().split(/\s+/))
    .filter(parts => parts[1] === 'device').map(parts => parts[0]);
}

export function selectUsbDevice(devices) {
  if (devices.includes('RZCW92MJRCT')) return 'RZCW92MJRCT';
  return null;
}

export function reverseTarget(output) {
  for (const line of output.split('\n')) {
    const fields = line.trim().split(/\s+/);
    if (fields[1] === `tcp:${port}`) return fields[2];
  }
  return null;
}

// Only the exact existing public preview health contract is eligible for attachment.
export function isElevenHealth(status, body) {
  try {
    const value = JSON.parse(body);
    return status === 200 && value.status === 'ok' && value.schemaVersion === 1 && value.mode === 'live-read-only';
  } catch { return false; }
}

export function probeHealth() {
  return new Promise(resolve => {
    const req = request({ hostname: '127.0.0.1', port, path: '/health', method: 'GET', timeout: 2000 }, res => {
      let body = '';
      res.setEncoding('utf8');
      res.on('data', chunk => {
        body += chunk;
        if (body.length > 8192) { resolve('occupied'); req.destroy(); }
      });
      res.on('end', () => resolve(isElevenHealth(res.statusCode, body) ? 'eleven' : 'occupied'));
      res.on('error', () => resolve('occupied'));
    });
    req.on('timeout', () => { resolve('occupied'); req.destroy(); });
    req.on('error', error => resolve(error.code === 'ECONNREFUSED' ? 'free' : 'occupied'));
    req.end();
  });
}

export async function fetchCatalog({ targetPort = port, timeoutMs = 45_000, maxBytes = marketFrameLimit } = {}) {
  return new Promise((resolve, reject) => {
    const req = request({ hostname: '127.0.0.1', port: targetPort, path: '/v1/stocks', method: 'GET' }, res => {
      if (res.statusCode !== 200) { req.destroy(new Error(`Catalog returned HTTP ${res.statusCode}`)); return; }
      const chunks = [];
      let length = 0;
      res.on('data', chunk => {
        length += chunk.length;
        if (length > Math.min(maxBytes, marketFrameLimit)) { req.destroy(new Error('Catalog exceeded its 16 MiB response limit')); return; }
        chunks.push(chunk);
      });
      res.on('end', () => {
        try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); } catch (error) { reject(error); }
      });
      res.on('error', reject);
    });
    const deadline = setTimeout(() => req.destroy(new Error('Catalog did not load within its deadline (maximum 45 seconds)')), Math.min(timeoutMs, 45_000));
    req.on('close', () => clearTimeout(deadline));
    req.on('error', reject);
    req.end();
  });
}

export function validateWalletCompatibilityResponse(statusCode, content) {
  const result = JSON.parse(content);
  if (statusCode !== 400 || result?.error !== 'invalid_request') {
    throw new Error('Wallet balance endpoint is missing or incompatible; update the backend before installing.');
  }
}

export function validateTransferAuthCompatibilityResponse(statusCode, content) {
  const result = JSON.parse(content);
  if (statusCode !== 401 || result?.error !== 'authentication_required') {
    throw new Error('Authenticated wallet-transfer endpoint is missing or incompatible; update the backend before installing.');
  }
}

/** Only public Android identifiers are passed to the local backend; secrets are ignored. */
export function readPrivyPublicEnvironment(project) {
  const filename = path.join(project, 'local.properties');
  if (!existsSync(filename)) return {};
  const values = new Map();
  for (const line of readFileSync(filename, 'utf8').split(/\r?\n/)) {
    const match = /^(privyAppId|privyClientId)=(\S+)$/.exec(line.trim());
    if (match && match[2].length <= 200) values.set(match[1], match[2]);
  }
  const appId = values.get('privyAppId'); const appClientId = values.get('privyClientId');
  if (!appId || !appClientId) return {};
  return { PRIVY_APP_ID: appId, PRIVY_APP_CLIENT_ID: appClientId, PRIVY_NATIVE_APP_ID: 'com.elevencapital.app' };
}

/** Valid body + missing bearer must fail before any balance/RPC work. */
export function verifyWalletTransferAuthEndpoint({ targetPort = port, timeoutMs = 5_000 } = {}) {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify({ schemaVersion: 1, operationId: '5a40fc8d-10d4-4da9-b44f-b4e28ef917cc',
      walletId: 'compatibility-wallet', chain: 'ETHEREUM', sender: '0x1111111111111111111111111111111111111111',
      recipient: '0x2222222222222222222222222222222222222222', assetId: 'ETHEREUM:native', amount: '0.1' });
    const req = request({ hostname: '127.0.0.1', port: targetPort, path: '/v1/wallet/transfer/prepare', method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) } }, res => {
      let content = ''; res.setEncoding('utf8');
      res.on('data', chunk => { content += chunk; if (Buffer.byteLength(content) > 4_096) req.destroy(new Error('Transfer compatibility response was too large.')); });
      res.on('end', () => { try { validateTransferAuthCompatibilityResponse(res.statusCode, content); resolve(); } catch (error) { reject(error); } });
      res.on('error', reject);
    });
    const deadline = setTimeout(() => req.destroy(new Error('Transfer authentication compatibility check timed out.')), timeoutMs);
    req.on('close', () => clearTimeout(deadline)); req.on('error', reject); req.end(body);
  });
}

/** Validate the new route with an invalid body, without querying any wallet or upstream RPC. */
export function verifyWalletPortfolioEndpoint({ targetPort = port, timeoutMs = 5_000 } = {}) {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify({ wallets: [] });
    const req = request({ hostname: '127.0.0.1', port: targetPort, path: '/v1/wallet/portfolio', method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) } }, res => {
      let content = '';
      res.setEncoding('utf8');
      res.on('data', chunk => {
        content += chunk;
        if (Buffer.byteLength(content) > 4_096) req.destroy(new Error('Wallet compatibility response was too large.'));
      });
      res.on('end', () => {
        try {
          validateWalletCompatibilityResponse(res.statusCode, content);
          resolve();
        } catch (error) { reject(error); }
      });
      res.on('error', reject);
    });
    const deadline = setTimeout(() => req.destroy(new Error('Wallet compatibility check timed out.')), timeoutMs);
    req.on('close', () => clearTimeout(deadline));
    req.on('error', reject);
    req.end(body);
  });
}

/** A health-compatible older server may still lack the streaming API required by this APK. */
export function verifyMarketStream(validateCatalog, { timeoutMs = 45000, targetPort = port,
  maxBytes = marketResponseLimit, maxFrameBytes = marketFrameLimit } = {}) {
  return new Promise((resolve, reject) => {
    let finished = false;
    let response;
    let deadline;
    let buffer = '';
    let length = 0;
    let snapshot;
    let heartbeat = false;
    const finish = (error) => {
      if (finished) return;
      finished = true;
      clearTimeout(deadline);
      response?.destroy();
      req.destroy();
      if (error) reject(error); else resolve(snapshot);
    };
    const consume = (frame) => {
      let event = 'message';
      const data = [];
      for (const line of frame.split(/\r?\n/)) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
      }
      if (event === 'snapshot') snapshot = validateCatalog(JSON.parse(data.join('\n')));
      if (event === 'heartbeat') {
        const value = JSON.parse(data.join('\n'));
        if (typeof value?.serverTime !== 'string' || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z$/.test(value.serverTime) ||
          !Number.isFinite(Date.parse(value.serverTime))) throw new Error('Streaming heartbeat has an invalid server timestamp.');
        heartbeat = true;
      }
      if (snapshot && heartbeat) finish();
    };
    const req = request({ hostname: '127.0.0.1', port: targetPort, path: '/v1/stocks/stream', method: 'GET',
      headers: { Accept: 'text/event-stream' } }, res => {
      response = res;
      if (res.statusCode !== 200) { finish(new Error(`Streaming endpoint returned HTTP ${res.statusCode}; this backend may be outdated.`)); return; }
      if (!/^text\/event-stream(?:\s*;|$)/i.test(res.headers['content-type'] ?? '')) {
        finish(new Error('Streaming endpoint did not return text/event-stream; this backend is incompatible.')); return;
      }
      res.setEncoding('utf8');
      res.on('data', chunk => {
        if (finished) return;
        length += Buffer.byteLength(chunk);
        if (length > Math.min(maxBytes, marketResponseLimit)) { finish(new Error('Streaming compatibility check exceeded its response limit.')); return; }
        buffer += chunk;
        try {
          let boundary;
          while (!finished && (boundary = /\r?\n\r?\n/.exec(buffer))) {
            const frame = buffer.slice(0, boundary.index);
            buffer = buffer.slice(boundary.index + boundary[0].length);
            if (Buffer.byteLength(frame) > Math.min(maxFrameBytes, marketFrameLimit)) throw new Error('Streaming frame exceeded its 16 MiB limit.');
            consume(frame);
          }
          if (Buffer.byteLength(buffer) > Math.min(maxFrameBytes, marketFrameLimit)) throw new Error('Incomplete streaming frame exceeded its 16 MiB limit.');
        } catch (error) { finish(error); }
      });
      res.on('end', () => finish(new Error('Streaming endpoint closed before a valid snapshot and heartbeat arrived.')));
      res.on('error', error => finish(error));
      res.on('close', () => finish(new Error('Streaming connection closed before compatibility was verified.')));
    });
    deadline = setTimeout(() => finish(new Error('A valid streaming snapshot and heartbeat did not arrive within the compatibility-check deadline.')), Math.min(timeoutMs, 45_000));
    req.on('error', error => finish(error));
    req.end();
  });
}

/** Verify the actual APK transport, including a bidirectional empty viewport subscription. */
export function verifyMarketSocket(validateCatalog, { timeoutMs = 45_000, targetPort = port,
  maxBytes = marketResponseLimit, maxFrameBytes = marketFrameLimit } = {}) {
  return new Promise((resolve, reject) => {
    if (typeof WebSocket !== 'function') { reject(new Error('Node 22 is required to verify market WebSocket compatibility.')); return; }
    let finished = false;
    let receivedBytes = 0;
    let snapshot;
    let sessionId;
    let revision = -1;
    let heartbeat = false;
    let subscribed = false;
    let subscriptionAccepted = false;
    const socket = new WebSocket(`ws://127.0.0.1:${targetPort}/v1/market/ws`);
    const finish = error => {
      if (finished) return;
      finished = true;
      clearTimeout(deadline);
      try { socket.close(); } catch { /* The connection may have failed before upgrade. */ }
      if (error) reject(error); else resolve(snapshot);
    };
    const deadline = setTimeout(() => finish(new Error('Market WebSocket snapshot, subscription and heartbeat did not arrive within the compatibility-check deadline.')),
      Math.min(timeoutMs, 45_000));
    const validateEnvelope = value => {
      if (typeof value.sessionId !== 'string' || !value.sessionId.trim() || value.sessionId.length > 128 ||
          !Number.isSafeInteger(value.revision) || value.revision < 0) throw new Error('Market WebSocket has an invalid session or revision.');
      if (sessionId != null && (value.sessionId !== sessionId || value.revision < revision)) {
        throw new Error('Market WebSocket session changed or revisions went backwards.');
      }
      sessionId = value.sessionId;
      revision = value.revision;
    };
    socket.addEventListener('message', event => {
      if (finished) return;
      try {
        if (typeof event.data !== 'string') throw new Error('Market WebSocket returned a binary frame; JSON text is required.');
        const bytes = Buffer.byteLength(event.data);
        receivedBytes += bytes;
        if (bytes > Math.min(maxFrameBytes, marketFrameLimit)) throw new Error('Market WebSocket frame exceeded its 16 MiB limit.');
        if (receivedBytes > Math.min(maxBytes, marketResponseLimit)) throw new Error('Market WebSocket exceeded its response limit.');
        const value = JSON.parse(event.data);
        if (value?.type === 'snapshot') {
          validateEnvelope(value);
          snapshot = validateCatalog(value.catalog);
          if (!snapshot?.stocks?.length) throw new Error('Market WebSocket catalog has no live stock rows.');
          if (!subscribed) {
            subscribed = true;
            socket.send(JSON.stringify({ type: 'subscribe', ids: [], detail: null }));
          }
        } else if (value?.type === 'delta') {
          if (!snapshot || !subscribed) throw new Error('Market WebSocket sent a delta before its initial snapshot.');
          validateEnvelope(value);
          if (!Array.isArray(value.stocks) || !Array.isArray(value.removedIds) ||
              value.removedIds.some(id => typeof id !== 'string')) {
            throw new Error('Market WebSocket returned an invalid catalog delta.');
          }
          const knownIds = new Set(snapshot.stocks.map(stock => stock.id));
          const changes = new Map();
          for (const stock of value.stocks) {
            if (!stock || !knownIds.has(stock.id) || changes.has(stock.id)) {
              throw new Error('Market WebSocket delta contains an unknown or duplicate stock.');
            }
            changes.set(stock.id, stock);
          }
          if (new Set(value.removedIds).size !== value.removedIds.length ||
              value.removedIds.some(id => !knownIds.has(id) || changes.has(id))) {
            throw new Error('Market WebSocket delta contains invalid removals.');
          }
          validateCatalog({ ...snapshot, receivedAt: value.receivedAt, providers: value.providers,
            stocks: snapshot.stocks.filter(stock => !value.removedIds.includes(stock.id))
              .map(stock => changes.get(stock.id) ?? stock) });
          // An empty subscription receives an immediate empty acknowledgement. Later
          // changed-row deltas may contain any catalog ID: viewport interest only selects
          // high-frequency upstream work, never downstream delivery eligibility.
          if (!value.stocks.length && !value.removedIds.length) subscriptionAccepted = true;
        } else if (value?.type === 'heartbeat') {
          if (typeof value.serverTime !== 'string' || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z$/.test(value.serverTime) ||
              !Number.isFinite(Date.parse(value.serverTime))) throw new Error('Market WebSocket heartbeat has an invalid server timestamp.');
          heartbeat = true;
        } else if (value?.type === 'status') {
          if (!['loading', 'reconnecting'].includes(value.state)) throw new Error('Market WebSocket returned an invalid status.');
        } else throw new Error('Market WebSocket returned an unknown message format.');
        if (snapshot && subscriptionAccepted && heartbeat) finish();
      } catch (error) { finish(error); }
    });
    socket.addEventListener('error', () => finish(new Error('Market WebSocket upgrade or connection failed; this backend may be outdated.')));
    socket.addEventListener('close', () => finish(new Error('Market WebSocket closed before compatibility was verified.')));
  });
}

// The controller only stops child objects it created. External PIDs are never adopted.
export function createSupervisor(io) {
  let child = null;
  let stopped = false;
  let unhealthyTicks = 0;
  return {
    async tick() {
      if (stopped) return;
      if (child && !child.running()) { io.log('Owned backend exited; preparing a restart.'); child = null; }
      const health = await io.probe();
      if (stopped) return;
      let state;
      if (health === 'eleven') {
        unhealthyTicks = 0;
        state = child ? 'running-owned-server' : 'monitoring-existing-server';
      } else if (child) {
        unhealthyTicks += 1;
        state = 'waiting-for-owned-server';
        if (unhealthyTicks >= 3) {
          io.log('Owned backend failed three health checks; restarting only our child.');
          await io.stopChild(child);
          child = null;
          unhealthyTicks = 0;
          state = 'restarting-owned-server';
        }
      } else if (health === 'free') {
        child = io.startChild();
        unhealthyTicks = 0;
        state = 'starting-owned-server';
      } else {
        state = 'port-occupied-by-unverified-service';
      }
      // Do not route a phone to a process whose health contract is unknown.
      const devices = health === 'eleven' ? await io.repairUsb() : [];
      io.status({ state, health, ownsServer: Boolean(child), devices });
    },
    async stop() {
      stopped = true;
      if (child) { await io.stopChild(child); child = null; }
      await io.cleanupUsb();
      io.status({ state: 'stopped', ownsServer: false, devices: [] });
    }
  };
}

function runtimePaths(project) {
  const directory = path.join(project, 'scripts', '.preview-service');
  return { directory, log: path.join(directory, 'service.log'), status: path.join(directory, 'status.json') };
}

async function runController(project, adb) {
  const runtime = runtimePaths(project);
  mkdirSync(runtime.directory, { recursive: true, mode: 0o700 });
  const log = message => appendBoundedLog(runtime.log, `${new Date().toISOString()} ${message}\n`);
  const createdForwards = new Set();
  let lastState = '';
  const adbCall = args => execute(adb, args, { timeout: 4000, maxBuffer: 1024 * 1024 });
  const selectedSerial = process.env.ELEVEN_ANDROID_SERIAL || 'RZCW92MJRCT';
  const privyPublicEnvironment = readPrivyPublicEnvironment(project);
  const adbManager = createAdbManager({ serial: selectedSerial, commandTimeoutMs: 4000 });
  const supervisor = createSupervisor({
    probe: probeHealth,
    log,
    startChild() {
      log('Starting owned backend on 127.0.0.1:8787.');
      const bundledEntry = path.join(project, 'backend', 'runtime', 'eleven-capital-backend.cjs');
      const serverEntry = existsSync(bundledEntry) ? bundledEntry : path.join(project, 'backend', 'dist', 'server.js');
      const processChild = spawn(process.execPath, [serverEntry], {
        cwd: path.join(project, 'backend'),
        env: { ...process.env, ...privyPublicEnvironment, PORT: String(port) },
        stdio: ['ignore', 'pipe', 'pipe']
      });
      let ended = false;
      processChild.once('error', error => { ended = true; log(`Backend start error: ${error.message}`); });
      processChild.once('exit', (code, signal) => { ended = true; log(`Backend exited: code=${code} signal=${signal}`); });
      for (const stream of [processChild.stdout, processChild.stderr]) stream.on('data', data => appendBoundedLog(runtime.log, data));
      return { process: processChild, running: () => !ended };
    },
    async stopChild(child) {
      if (!child.running()) return;
      child.process.kill('SIGTERM');
      for (let i = 0; i < 20 && child.running(); i += 1) await delay(100);
      if (child.running()) child.process.kill('SIGKILL');
    },
    async repairUsb() {
      try {
        const ready = await adbManager.check(adb);
        if (ready.reverse.action === 'created') {
          createdForwards.add(ready.serial);
          log(`Restored ${ready.device.connection} ADB reverse for verified S22 ${ready.hardwareSerial} on ${ready.serial}.`);
        }
        return [{ serial: ready.serial, hardwareSerial: ready.hardwareSerial,
          connection: ready.device.connection, status: 'connected', reverse: ready.reverse.action }];
      } catch (error) {
        const failure = classifyAdbFailure(error);
        log(`ADB check failed (${failure.code}): ${failure.message}`);
        return [{ serial: failure.details?.serial ?? selectedSerial, status: failure.code, message: failure.message }];
      }
    },
    async cleanupUsb() {
      for (const serial of createdForwards) {
        try {
          const mapping = reverseTarget((await adbCall(['-s', serial, 'reverse', '--list'])).stdout);
          if (mapping === `tcp:${port}`) await adbCall(['-s', serial, 'reverse', '--remove', `tcp:${port}`]);
        } catch { /* A disconnected transport already lost its temporary forwarding. */ }
      }
    },
    status(value) {
      const status = { ...value, checkedAt: new Date().toISOString(), project, pid: process.pid };
      writeFileSync(`${runtime.status}.tmp`, JSON.stringify(status, null, 2), { mode: 0o600 });
      renameSync(`${runtime.status}.tmp`, runtime.status);
      const state = JSON.stringify([value.state, value.devices]);
      if (lastState !== state) { log(`Preview state: ${state}`); lastState = state; }
    }
  });
  const stopping = new AbortController();
  for (const signal of ['SIGTERM', 'SIGINT', 'SIGHUP']) process.once(signal, () => stopping.abort());
  log('Preview supervisor started for this login session.');
  try {
    while (!stopping.signal.aborted) {
      const started = Date.now();
      try { await supervisor.tick(); } catch (error) { log(`Supervisor check failed: ${error.message}`); }
      await delay(Math.max(100, 5000 - (Date.now() - started)), undefined, { signal: stopping.signal }).catch(() => {});
    }
  } finally {
    await supervisor.stop();
    log('Preview supervisor stopped.');
  }
}

function launchctl(...args) {
  return spawnSync('/bin/launchctl', args, { encoding: 'utf8', timeout: 15000 });
}

export async function main(args) {
  const [action, suppliedProject, adb] = args;
  if (!suppliedProject) throw new Error('Provide a project directory.');
  const project = realpathSync(suppliedProject);
  const label = serviceLabel(project);
  const runtime = runtimePaths(project);
  if (action === 'run') {
    if (!adb) throw new Error('Provide the absolute adb executable path.');
    await runController(project, adb);
    return;
  }
  if (action === 'health') { console.log(await probeHealth()); return; }
  if (action === 'wait-ready') {
    const deadline = Date.now() + 45000;
    while (Date.now() < deadline) {
      const health = await probeHealth();
      if (health === 'eleven') { console.log('Eleven backend health verified on 127.0.0.1:8787.'); return; }
      if (health === 'occupied') throw new Error('Port 8787 is occupied or unresponsive. No verified Eleven backend is ready; inspect status-live-preview.command.');
      await delay(1000);
    }
    throw new Error('The Eleven backend did not become healthy within 45 seconds; inspect status-live-preview.command.');
  }
  if (action === 'verify-catalog') {
    const { CatalogSchema } = await import(pathToFileURL(path.join(project, 'backend', 'dist', 'schema.js')).href);
    const catalog = CatalogSchema.parse(await fetchCatalog());
    if (catalog.stocks.length === 0) throw new Error('No live stock rows were returned, so app compatibility could not be verified.');
    console.log(`Verified ${catalog.stocks.length} live rows against the current schema, including activity, source and currency.`);
    const streamed = await verifyMarketSocket(value => {
      const snapshot = CatalogSchema.parse(value);
      if (!snapshot.stocks.length) throw new Error('Streaming catalog has no live stock rows; compatibility could not be verified.');
      return snapshot;
    });
    console.log(`Verified WebSocket updates: ${streamed.stocks.length} schema-valid live rows, viewport subscription and server heartbeat.`);
    await verifyWalletPortfolioEndpoint();
    console.log('Verified read-only wallet portfolio endpoint and its request validation.');
    await verifyWalletTransferAuthEndpoint();
    console.log('Verified Privy bearer authentication is required before wallet transfer preparation.');
    return;
  }
  const present = launchctl('list', label).status === 0;
  if (action === 'running') { process.exitCode = present ? 0 : 1; return; }
  if (action === 'status') {
    console.log(`Login-session job: ${present ? 'active' : 'not active'} (${label})`);
    console.log(`Backend health now: ${await probeHealth()}`);
    if (existsSync(runtime.status)) console.log(`Last supervisor status:\n${readFileSync(runtime.status, 'utf8')}`);
    console.log(`Bounded log: ${runtime.log}`);
    return;
  }
  if (action === 'start') {
    if (present) { console.log('Preview service is already active.'); return; }
    if (!adb) throw new Error('Provide the absolute adb executable path.');
    if (!existsSync(path.join(project, 'backend', 'runtime', 'eleven-capital-backend.cjs')) &&
      !existsSync(path.join(project, 'backend', 'dist', 'server.js'))) throw new Error('Build the backend first.');
    mkdirSync(runtime.directory, { recursive: true, mode: 0o700 });
    const result = launchctl('submit', '-l', label, '-o', '/dev/null', '-e', '/dev/null', '--', process.execPath, ownFile, 'run', project, adb);
    if (result.status !== 0) throw new Error(`Could not start preview service: ${result.stderr || result.error?.message || `launchctl exit ${result.status}`}`);
    if (launchctl('list', label).status !== 0) throw new Error('The submitted preview service did not remain active. Inspect the project log.');
    console.log(`Started ${label}. No login item or LaunchAgent file was installed.`);
    return;
  }
  if (action === 'stop') {
    if (!present) { console.log('This project has no active preview service. External servers are unchanged.'); return; }
    const result = launchctl('remove', label);
    if (result.status !== 0) throw new Error(`Could not stop preview service: ${result.stderr || result.error?.message || `launchctl exit ${result.status}`}`);
    // launchctl remove returns before signal cleanup finishes. Wait so a following
    // start cannot mistake the departing job for an already-running service.
    for (let attempt = 0; attempt < 150; attempt += 1) {
      if (launchctl('list', label).status !== 0) break;
      await delay(100);
    }
    if (launchctl('list', label).status === 0) throw new Error('Preview service is still stopping. Wait a moment before starting it again.');
    console.log('Stopped this project’s preview service. Servers started elsewhere are unchanged.');
    return;
  }
  throw new Error('Use start, stop, status, health, wait-ready, verify-catalog, running, or run.');
}

if (process.argv[1] && path.resolve(process.argv[1]) === ownFile) {
  main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
}
