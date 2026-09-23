import { constants as fsConstants, accessSync } from 'node:fs';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

const executeFile = promisify(execFile);
export const DEFAULT_S22_SERIAL = 'RZCW92MJRCT';
export const DEFAULT_REVERSE_PORT = 8787;
const S22_MODEL = 'SM-S901E';

function isWirelessTransport(serial) {
  return /^adb-[\w-]+\._adb-tls-connect\._tcp$/.test(serial)
    || /^(?:\d{1,3}\.){3}\d{1,3}:\d{1,5}$/.test(serial)
    || /^\[[0-9a-f:]+\]:\d{1,5}$/i.test(serial);
}

export class AdbManagerError extends Error {
  constructor(code, message, details = {}) {
    super(message);
    this.name = 'AdbManagerError';
    this.code = code;
    this.details = details;
  }
}

function errorText(error) {
  return [error?.code, error?.message, error?.stdout, error?.stderr]
    .filter(value => value != null && String(value).trim())
    .join('\n');
}

/** Classify failures from execFile without relying on its platform-dependent code alone. */
export function classifyAdbFailure(error, operation = 'ADB command') {
  if (error instanceof AdbManagerError) return error;
  const text = errorText(error);
  const lower = text.toLowerCase();
  if (['ENOENT'].includes(error?.code) || /no such file|not found/.test(lower)) {
    return new AdbManagerError('adb-missing', `${operation} failed because the ADB executable was not found.`, { cause: text });
  }
  if (['EPERM', 'EACCES'].includes(error?.code) || /operation not permitted|permission denied/.test(lower)) {
    return new AdbManagerError(
      'adb-sandbox-denied',
      'ADB cannot run in this restricted process because macOS denied its local smart-socket or USB access. Run this command in normal macOS Terminal.',
      { cause: text },
    );
  }
  if (error?.killed || ['ETIMEDOUT', 'ETIME'].includes(error?.code) || /timed out|timeout/.test(lower)) {
    return new AdbManagerError('adb-timeout', `${operation} did not finish before its deadline.`, { cause: text });
  }
  if (/server didn.t ack|failed to start daemon|cannot connect to daemon|failed to check server version|server version .*doesn.t match|smartsocket/.test(lower)) {
    return new AdbManagerError('adb-daemon-failed', 'The ADB daemon could not start or respond.', { cause: text });
  }
  if (/unauthorized/.test(lower)) {
    return new AdbManagerError('s22-unauthorized', 'The S22 is connected but has not authorized USB debugging.', { cause: text });
  }
  if (/device offline|\boffline\b/.test(lower)) {
    return new AdbManagerError('s22-offline', 'The S22 is visible to ADB but is offline.', { cause: text });
  }
  if (/more than one device/.test(lower)) {
    return new AdbManagerError('multiple-devices', 'ADB found more than one device and could not select one safely.', { cause: text });
  }
  if (/device .* not found|no devices?\/emulators? found/.test(lower)) {
    return new AdbManagerError('s22-absent', 'The S22 is not visible to ADB.', { cause: text });
  }
  return new AdbManagerError('adb-command-failed', `${operation} failed.`, { cause: text });
}

/** Parse all rows, including states that are deliberately excluded from normal ADB commands. */
export function parseAdbDevices(output) {
  const devices = [];
  for (const rawLine of String(output ?? '').split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || /^List of devices attached\b/.test(line) || line.startsWith('*')) continue;
    const fields = line.split(/\s+/);
    if (fields.length < 2) continue;
    const serial = fields[0];
    let state = fields[1];
    let detailStart = 2;
    if (state === 'no' && fields[2] === 'permissions') {
      state = 'no-permissions';
      detailStart = 3;
    }
    const attributes = {};
    for (const field of fields.slice(detailStart)) {
      const separator = field.indexOf(':');
      if (separator > 0) attributes[field.slice(0, separator)] = field.slice(separator + 1);
    }
    devices.push({ serial, state, attributes, raw: line });
  }
  return devices;
}

export function parseReverseMappings(output) {
  const mappings = [];
  for (const rawLine of String(output ?? '').split(/\r?\n/)) {
    const fields = rawLine.trim().split(/\s+/).filter(Boolean);
    if (fields.length < 2) continue;
    const local = fields.at(-2);
    const remote = fields.at(-1);
    if (!local?.startsWith('tcp:') || !remote?.startsWith('tcp:')) continue;
    mappings.push({ transport: fields.length > 2 ? fields.slice(0, -2).join(' ') : null, local, remote });
  }
  return mappings;
}

export function reverseTarget(output, port = DEFAULT_REVERSE_PORT) {
  return parseReverseMappings(output).find(mapping => mapping.local === `tcp:${port}`)?.remote ?? null;
}

function defaultIsExecutable(filename) {
  try {
    accessSync(filename, fsConstants.X_OK);
    return true;
  } catch {
    return false;
  }
}

function deviceError(device, serial) {
  const details = { serial, state: device.state, device };
  if (device.state === 'unauthorized' || device.state === 'no-permissions') {
    return new AdbManagerError(
      's22-unauthorized',
      `S22 ${serial} is connected but USB debugging is not authorized. Unlock it and accept the USB debugging prompt.`,
      details,
    );
  }
  if (device.state === 'offline') {
    return new AdbManagerError(
      's22-offline',
      `S22 ${serial} is connected but offline. Reconnect its USB cable and keep the phone unlocked.`,
      details,
    );
  }
  return new AdbManagerError(
    's22-unavailable',
    `S22 ${serial} is in the unsupported ADB state “${device.state}”.`,
    details,
  );
}

export function createAdbManager({
  exec = (adb, args, options) => executeFile(adb, args, options),
  isExecutable = defaultIsExecutable,
  sleep = delay,
  now = Date.now,
  port = DEFAULT_REVERSE_PORT,
  serial = DEFAULT_S22_SERIAL,
  commandTimeoutMs = 8_000,
} = {}) {
  let lastWirelessTransport = null;
  const run = async (adb, args, operation, timeout = commandTimeoutMs) => {
    try {
      const value = await exec(adb, args, { timeout, maxBuffer: 1024 * 1024 });
      if (typeof value === 'string') return { stdout: value, stderr: '' };
      return { stdout: value?.stdout ?? '', stderr: value?.stderr ?? '' };
    } catch (error) {
      throw classifyAdbFailure(error, operation);
    }
  };

  const validateAdb = adb => {
    if (!adb || !path.isAbsolute(adb)) {
      throw new AdbManagerError('adb-path-invalid', 'Provide the absolute Android SDK adb executable path.');
    }
    if (!isExecutable(adb)) {
      throw new AdbManagerError('adb-missing', `ADB is missing or not executable: ${adb}`, { adb });
    }
  };

  const startServer = adb => run(adb, ['start-server'], 'Starting the ADB daemon');
  const listDevices = async adb => parseAdbDevices((await run(adb, ['devices', '-l'], 'Listing ADB devices')).stdout);

  const selectS22 = async (adb, devices) => {
    const physical = devices.find(device => device.serial === serial);
    let devpath;
    if (physical?.state === 'device') {
      devpath = (await run(adb, ['-s', serial, 'get-devpath'], `Checking the transport for S22 ${serial}`)).stdout.trim();
      if (devpath.startsWith('usb:')) return { ...physical, devpath, connection: 'USB' };
    }

    // Wireless ADB has an IP or mDNS transport name instead of the hardware serial.
    // Never infer identity from that name or from `adb devices` model metadata.
    const candidates = devices.filter(device => device.state === 'device' && isWirelessTransport(device.serial));
    candidates.sort((a, b) => {
      const rank = device => device.serial === lastWirelessTransport ? 0
        : device.serial.includes('._adb-tls-connect._tcp') ? 1 : 2;
      return rank(a) - rank(b) || a.serial.localeCompare(b.serial);
    });
    for (const candidate of candidates) {
      try {
        const hardwareSerial = (await run(adb, ['-s', candidate.serial, 'shell', 'getprop', 'ro.serialno'],
          `Verifying wireless S22 ${candidate.serial}`)).stdout.trim();
        if (hardwareSerial !== serial) continue;
        const model = (await run(adb, ['-s', candidate.serial, 'shell', 'getprop', 'ro.product.model'],
          `Verifying wireless S22 model ${candidate.serial}`)).stdout.trim();
        if (model.replaceAll('_', '-').toUpperCase() !== S22_MODEL) continue;
        lastWirelessTransport = candidate.serial;
        return { ...candidate, devpath: 'wireless', connection: 'wireless', hardwareSerial, model };
      } catch (error) {
        // A candidate may drop while another verified transport remains available.
        if (classifyAdbFailure(error).code === 'adb-sandbox-denied') throw error;
      }
    }

    if (physical && physical.state !== 'device') throw deviceError(physical, serial);
    if (physical) {
      throw new AdbManagerError(
        's22-non-usb',
        `S22 ${serial} is visible through “${devpath || 'unknown'}”, not a physical USB connection.`,
        { serial, devpath, device: physical },
      );
    }
    if (candidates.length) {
      throw new AdbManagerError('s22-unverified-wireless',
        `Wireless ADB is present, but no transport verified as S22 ${serial} (${S22_MODEL}).`,
        { serial, candidates });
    }
    if (devices.length > 1) {
      throw new AdbManagerError('multiple-devices',
        `S22 ${serial} is absent and multiple Android devices are attached: ${devices.map(device => device.serial).join(', ')}.`,
        { serial, devices });
    }
    throw new AdbManagerError('s22-absent',
      `S22 ${serial} was not found. Connect it by USB or enable its paired wireless debugging connection.`,
      { serial, devices });
  };

  const listReverse = async (adb, transport) => (await run(
    adb,
    ['-s', transport, 'reverse', '--list'],
    `Reading reverse forwarding for S22 ${transport}`,
  )).stdout;

  const ensureReverse = async (adb, transport, { replaceConflict = false } = {}) => {
    const expected = `tcp:${port}`;
    let target = reverseTarget(await listReverse(adb, transport), port);
    let action = 'existing';
    if (target && target !== expected) {
      if (!replaceConflict) {
        throw new AdbManagerError(
          'reverse-conflict',
          `S22 ${serial} port ${port} currently forwards to ${target}; automatic checks left it unchanged.`,
          { serial: transport, port, target },
        );
      }
      await run(adb, ['-s', transport, 'reverse', '--remove', expected], `Removing the conflicting S22 port ${port} reverse`);
      action = 'replaced';
      target = null;
    }
    if (!target) {
      await run(adb, ['-s', transport, 'reverse', expected, expected], `Creating the S22 port ${port} reverse`);
      if (action !== 'replaced') action = 'created';
    }
    const verified = reverseTarget(await listReverse(adb, transport), port);
    if (verified !== expected) {
      throw new AdbManagerError(
        'reverse-verification-failed',
        `ADB did not retain the required S22 mapping ${expected} -> ${expected}.`,
        { serial: transport, port, expected, actual: verified },
      );
    }
    return { action, local: expected, remote: verified };
  };

  const check = async adb => {
    validateAdb(adb);
    await startServer(adb);
    const devices = await listDevices(adb);
    const device = await selectS22(adb, devices);
    const reverse = await ensureReverse(adb, device.serial);
    return { ok: true, code: 'ready', serial: device.serial, hardwareSerial: serial, port, device, reverse };
  };

  const wait = async (adb, { timeoutMs = 30_000, intervalMs = 1_000 } = {}) => {
    const startedAt = now();
    const maximumAttempts = Math.max(1, Math.ceil(Math.max(0, timeoutMs) / Math.max(1, intervalMs)) + 1);
    let lastError;
    for (let attempt = 1; attempt <= maximumAttempts; attempt += 1) {
      try {
        return await check(adb);
      } catch (error) {
        lastError = classifyAdbFailure(error);
        if (['adb-path-invalid', 'adb-missing', 'adb-sandbox-denied', 'multiple-devices', 's22-non-usb', 'reverse-conflict'].includes(lastError.code)) {
          throw lastError;
        }
        if (attempt === maximumAttempts || now() - startedAt >= timeoutMs) break;
        await sleep(Math.min(intervalMs, Math.max(0, timeoutMs - (now() - startedAt))));
      }
    }
    throw new AdbManagerError(
      lastError?.code ?? 'adb-wait-timeout',
      `${lastError?.message ?? 'ADB was not ready.'} It did not become ready within ${timeoutMs} ms.`,
      { ...(lastError?.details ?? {}), timeoutMs },
    );
  };

  const restartDaemon = async adb => {
    try {
      await run(adb, ['kill-server'], 'Stopping the stale ADB daemon');
    } catch (error) {
      const classified = classifyAdbFailure(error);
      if (['adb-sandbox-denied', 'adb-missing', 'adb-path-invalid'].includes(classified.code)) throw classified;
      // A dead daemon often makes kill-server fail. Starting it is the authoritative next step.
    }
    await sleep(250);
    await startServer(adb);
  };

  const repair = async adb => {
    validateAdb(adb);
    try {
      await startServer(adb);
      const devices = await listDevices(adb);
      const device = await selectS22(adb, devices);
      const reverse = await ensureReverse(adb, device.serial, { replaceConflict: true });
      return { ok: true, code: 'ready', serial: device.serial, hardwareSerial: serial, port, device, reverse };
    } catch (initialError) {
      const classified = classifyAdbFailure(initialError);
      if (['adb-path-invalid', 'adb-missing', 'adb-sandbox-denied', 's22-unauthorized',
        'multiple-devices', 's22-non-usb', 's22-unavailable'].includes(classified.code)) throw classified;

      if (classified.code === 's22-offline') {
        try { await run(adb, ['reconnect', 'offline'], 'Reconnecting offline ADB devices'); } catch (error) {
          const reconnectError = classifyAdbFailure(error);
          if (reconnectError.code === 'adb-sandbox-denied') throw reconnectError;
        }
        await sleep(500);
        try {
          const devices = await listDevices(adb);
          const device = await selectS22(adb, devices);
          const reverse = await ensureReverse(adb, device.serial, { replaceConflict: true });
          return { ok: true, code: 'ready', serial: device.serial, hardwareSerial: serial, port, device, reverse };
        } catch (error) {
          const afterReconnect = classifyAdbFailure(error);
          if (!['s22-offline', 'adb-daemon-failed', 'adb-timeout', 'adb-command-failed'].includes(afterReconnect.code)) throw afterReconnect;
        }
      }

      await restartDaemon(adb);
      const devices = await listDevices(adb);
      const device = await selectS22(adb, devices);
      const reverse = await ensureReverse(adb, device.serial, { replaceConflict: true });
      return { ok: true, code: 'ready', serial: device.serial, hardwareSerial: serial, port, device, reverse };
    }
  };

  return { check, wait, repair };
}

export function formatAdbResult(result) {
  return `ADB ready: S22 ${result.hardwareSerial ?? result.serial} is connected by ${result.device?.connection ?? 'USB'} (${result.serial}); tcp:${result.port} -> tcp:${result.port} verified (${result.reverse.action}).`;
}

export function formatAdbError(error) {
  const classified = classifyAdbFailure(error);
  if (classified.code === 'adb-daemon-failed') {
    return `${classified.message} Run the explicit repair action from normal macOS Terminal.`;
  }
  if (classified.code === 'reverse-conflict') {
    return `${classified.message} Run the explicit repair action to replace only this mapping.`;
  }
  return classified.message;
}

export async function main(args, { manager, print = console.log, printError = console.error } = {}) {
  const [action, adb, suppliedSerial, ...extra] = args;
  try {
    if (!['check', 'wait', 'repair'].includes(action) || !adb || extra.length) {
      throw new AdbManagerError('usage', 'Usage: node scripts/adb-manager.mjs check|wait|repair <absolute-adb-path> [serial]');
    }
    const activeManager = manager ?? createAdbManager({ serial: suppliedSerial || DEFAULT_S22_SERIAL });
    const result = await activeManager[action](adb);
    print(formatAdbResult(result));
    return result;
  } catch (error) {
    printError(formatAdbError(error));
    throw error;
  }
}

const ownFile = fileURLToPath(import.meta.url);
if (process.argv[1] && path.resolve(process.argv[1]) === ownFile) {
  main(process.argv.slice(2)).catch(() => { process.exitCode = 1; });
}
