import test from 'node:test';
import assert from 'node:assert/strict';
import {
  AdbManagerError,
  classifyAdbFailure,
  createAdbManager,
  main,
  parseAdbDevices,
  parseReverseMappings,
  reverseTarget,
} from './adb-manager.mjs';

const adb = '/sdk/platform-tools/adb';
const serial = 'RZCW92MJRCT';
const mdns = 'adb-RZCW92MJRCT-oiofDb._adb-tls-connect._tcp';
const wifi = '192.168.1.18:43529';

function commandError(message, { code = 1, stderr = '' } = {}) {
  const error = new Error(message);
  error.code = code;
  error.stderr = stderr;
  return error;
}

function fixture({ deviceState = 'device', devpath = 'usb:1-1', mapping = null, extraDevices = [], persistReverse = true } = {}) {
  const calls = [];
  const state = { deviceState, devpath, mapping, extraDevices, startFailures: [], persistReverse };
  const exec = async (_file, args) => {
    calls.push(args);
    if (args[0] === 'start-server') {
      const failure = state.startFailures.shift();
      if (failure) throw failure;
      return { stdout: '', stderr: '' };
    }
    if (args[0] === 'kill-server') return { stdout: '', stderr: '' };
    if (args[0] === 'reconnect') {
      if (state.deviceState === 'offline') state.deviceState = 'device';
      return { stdout: 'reconnecting\n', stderr: '' };
    }
    if (args[0] === 'devices') {
      const rows = [`${serial} ${state.deviceState} usb:1-1 model:SM_S901E`, ...state.extraDevices];
      return { stdout: `List of devices attached\n${rows.join('\n')}\n`, stderr: '' };
    }
    if (args[0] === '-s' && args[2] === 'get-devpath') return { stdout: `${state.devpath}\n`, stderr: '' };
    if (args[0] === '-s' && args[2] === 'reverse' && args[3] === '--list') {
      return { stdout: state.mapping ? `UsbFfs tcp:8787 ${state.mapping}\n` : '', stderr: '' };
    }
    if (args[0] === '-s' && args[2] === 'reverse' && args[3] === '--remove') {
      state.mapping = null;
      return { stdout: '', stderr: '' };
    }
    if (args[0] === '-s' && args[2] === 'reverse' && args[3] === 'tcp:8787') {
      if (state.persistReverse) state.mapping = args[4];
      return { stdout: '', stderr: '' };
    }
    throw new Error(`Unexpected command: ${args.join(' ')}`);
  };
  const manager = createAdbManager({ exec, isExecutable: () => true, sleep: async () => {} });
  return { manager, calls, state };
}

function wirelessFixture({ withUsb = false, wrongSerial = false, wrongModel = false } = {}) {
  const calls = [];
  const mappings = new Map();
  const states = new Map([[mdns, 'device'], [wifi, 'device']]);
  const exec = async (_file, args) => {
    calls.push(args);
    if (args[0] === 'start-server') return { stdout: '', stderr: '' };
    if (args[0] === 'devices') {
      const rows = [...(withUsb ? [`${serial} device usb:1-1 model:SM_S901E`] : []),
        ...[...states].map(([name, state]) => `${name} ${state} model:SM_S901E`)];
      return { stdout: `List of devices attached\n${rows.join('\n')}\n`, stderr: '' };
    }
    if (args[0] === '-s' && args[2] === 'get-devpath') return { stdout: 'usb:1-1\n', stderr: '' };
    if (args[0] === '-s' && args[2] === 'shell' && args[3] === 'getprop') {
      if (states.get(args[1]) !== 'device') throw commandError('device offline');
      if (args[4] === 'ro.serialno') return { stdout: `${wrongSerial ? 'OTHER' : serial}\n`, stderr: '' };
      if (args[4] === 'ro.product.model') return { stdout: `${wrongModel ? 'OTHER' : 'SM-S901E'}\n`, stderr: '' };
    }
    if (args[0] === '-s' && args[2] === 'reverse' && args[3] === '--list') {
      return { stdout: mappings.has(args[1]) ? `UsbFfs tcp:8787 ${mappings.get(args[1])}\n` : '', stderr: '' };
    }
    if (args[0] === '-s' && args[2] === 'reverse' && args[3] === 'tcp:8787') {
      mappings.set(args[1], args[4]);
      return { stdout: '', stderr: '' };
    }
    throw new Error(`Unexpected command: ${args.join(' ')}`);
  };
  const manager = createAdbManager({ exec, isExecutable: () => true, sleep: async () => {} });
  return { manager, calls, mappings, states };
}

test('parses authorized, unauthorized, offline and no-permissions rows', () => {
  const devices = parseAdbDevices(`List of devices attached
${serial} device usb:1-1 model:SM_S901E transport_id:1
OTHER unauthorized usb:2-1
OLD offline transport_id:3
???????????? no permissions (user in plugdev group)
* daemon started successfully
`);
  assert.deepEqual(devices.map(({ serial: id, state }) => [id, state]), [
    [serial, 'device'], ['OTHER', 'unauthorized'], ['OLD', 'offline'], ['????????????', 'no-permissions'],
  ]);
  assert.equal(devices[0].attributes.model, 'SM_S901E');
});

test('parses both three-column and two-column reverse listings', () => {
  assert.deepEqual(parseReverseMappings('UsbFfs tcp:8787 tcp:8787\ntcp:9000 tcp:9001\n'), [
    { transport: 'UsbFfs', local: 'tcp:8787', remote: 'tcp:8787' },
    { transport: null, local: 'tcp:9000', remote: 'tcp:9001' },
  ]);
  assert.equal(reverseTarget('UsbFfs tcp:8787 tcp:9999\n'), 'tcp:9999');
});

test('classifies sandbox denial even when adb reports it only in stderr', () => {
  const error = commandError('adb exited 1', {
    stderr: 'ADB server did not ACK\ncould not install *smartsocket* listener: Operation not permitted\n',
  });
  assert.equal(classifyAdbFailure(error).code, 'adb-sandbox-denied');
  assert.match(classifyAdbFailure(error).message, /normal macOS Terminal/);
});

test('classifies missing, timed out and stale-daemon failures separately', () => {
  assert.equal(classifyAdbFailure(commandError('spawn adb ENOENT', { code: 'ENOENT' })).code, 'adb-missing');
  assert.equal(classifyAdbFailure(commandError('timed out', { code: 'ETIMEDOUT' })).code, 'adb-timeout');
  assert.equal(classifyAdbFailure(commandError("ADB server didn't ACK", { stderr: 'failed to start daemon' })).code, 'adb-daemon-failed');
});

test('check creates and then re-reads the exact reverse mapping', async () => {
  const { manager, calls } = fixture();
  const result = await manager.check(adb);
  assert.equal(result.reverse.action, 'created');
  assert.equal(calls.filter(args => args[2] === 'reverse' && args[3] === '--list').length, 2);
  assert.ok(calls.some(args => args.join(' ') === `-s ${serial} reverse tcp:8787 tcp:8787`));
  assert.ok(!calls.some(args => args[0] === 'kill-server'));
});

test('check preserves an existing correct mapping', async () => {
  const { manager, calls } = fixture({ mapping: 'tcp:8787' });
  const result = await manager.check(adb);
  assert.equal(result.reverse.action, 'existing');
  assert.equal(calls.filter(args => args[2] === 'reverse' && args[3] === '--list').length, 2);
  assert.ok(!calls.some(args => args[2] === 'reverse' && args[3] === 'tcp:8787'));
});

test('check reports a conflict without changing it; explicit repair replaces only tcp:8787', async () => {
  const { manager, calls, state } = fixture({ mapping: 'tcp:9999' });
  await assert.rejects(manager.check(adb), error => error.code === 'reverse-conflict');
  assert.equal(state.mapping, 'tcp:9999');
  assert.ok(!calls.some(args => args.includes('--remove')));
  const result = await manager.repair(adb);
  assert.equal(result.reverse.action, 'replaced');
  assert.equal(state.mapping, 'tcp:8787');
  assert.deepEqual(calls.filter(args => args.includes('--remove')).at(-1), ['-s', serial, 'reverse', '--remove', 'tcp:8787']);
  assert.ok(!calls.some(args => args[0] === 'kill-server'));
});

test('success is withheld if reverse creation is not retained', async () => {
  const { manager } = fixture({ persistReverse: false });
  await assert.rejects(manager.check(adb), error => error.code === 'reverse-verification-failed');
});

test('known S22 state produces specific authorization, offline, absent, multiple and non-USB errors', async () => {
  for (const [options, code] of [
    [{ deviceState: 'unauthorized' }, 's22-unauthorized'],
    [{ deviceState: 'offline' }, 's22-offline'],
    [{ devpath: 'tcp:192.168.1.2:5555' }, 's22-non-usb'],
  ]) {
    const { manager } = fixture(options);
    await assert.rejects(manager.check(adb), error => error.code === code);
  }

  const noS22 = fixture();
  noS22.state.extraDevices = [];
  noS22.state.deviceState = 'gone';
  const originalExec = async (_file, args) => {
    noS22.calls.push(args);
    if (args[0] === 'start-server') return { stdout: '', stderr: '' };
    if (args[0] === 'devices') return { stdout: 'List of devices attached\n', stderr: '' };
    throw new Error('unexpected');
  };
  const absent = createAdbManager({ exec: originalExec, isExecutable: () => true, sleep: async () => {} });
  await assert.rejects(absent.check(adb), error => error.code === 's22-absent');

  const multiple = createAdbManager({
    exec: async (_file, args) => {
      if (args[0] === 'start-server') return { stdout: '', stderr: '' };
      if (args[0] === 'devices') return { stdout: 'List of devices attached\nONE device usb:1\nTWO unauthorized usb:2\n', stderr: '' };
      throw new Error('unexpected');
    },
    isExecutable: () => true,
  });
  await assert.rejects(multiple.check(adb), error => error.code === 'multiple-devices');
});

test('known S22 is selected deterministically when other devices are attached', async () => {
  const { manager } = fixture({ extraDevices: ['OTHER device usb:2-1 model:Other'] });
  const result = await manager.check(adb);
  assert.equal(result.serial, serial);
});

test('USB wins over two wireless transports without probing or changing them', async () => {
  const { manager, calls, mappings } = wirelessFixture({ withUsb: true });
  const result = await manager.check(adb);
  assert.equal(result.serial, serial);
  assert.equal(result.device.connection, 'USB');
  assert.deepEqual([...mappings.keys()], [serial]);
  assert.ok(!calls.some(args => args.includes('getprop')));
});

test('two wireless transports of the verified S22 select one and retain its reverse mapping', async () => {
  const { manager, calls, mappings } = wirelessFixture();
  const first = await manager.check(adb);
  const second = await manager.check(adb);
  assert.equal(first.serial, mdns);
  assert.equal(first.hardwareSerial, serial);
  assert.equal(first.device.connection, 'wireless');
  assert.equal(first.reverse.action, 'created');
  assert.equal(second.serial, mdns);
  assert.equal(second.reverse.action, 'existing');
  assert.deepEqual([...mappings], [[mdns, 'tcp:8787']]);
  assert.ok(calls.some(args => args.join(' ') === `-s ${mdns} shell getprop ro.serialno`));
  assert.ok(calls.some(args => args.join(' ') === `-s ${mdns} shell getprop ro.product.model`));
});

test('wireless transport loss fails over to the other verified transport and restores reverse', async () => {
  const { manager, mappings, states } = wirelessFixture();
  assert.equal((await manager.check(adb)).serial, mdns);
  states.set(mdns, 'offline');
  const result = await manager.check(adb);
  assert.equal(result.serial, wifi);
  assert.equal(result.reverse.action, 'created');
  assert.equal(mappings.get(wifi), 'tcp:8787');
});

test('wireless candidates with unverified hardware identity or model are never forwarded', async () => {
  for (const options of [{ wrongSerial: true }, { wrongModel: true }]) {
    const { manager, mappings } = wirelessFixture(options);
    await assert.rejects(manager.check(adb), error => error.code === 's22-unverified-wireless');
    assert.equal(mappings.size, 0);
  }
});

test('check and wait never kill the global adb daemon', async () => {
  const { manager, calls, state } = fixture();
  const failure = commandError("ADB server didn't ACK", { stderr: 'failed to start daemon' });
  state.startFailures.push(failure, failure, failure);
  await assert.rejects(manager.check(adb), error => error.code === 'adb-daemon-failed');
  await assert.rejects(manager.wait(adb, { timeoutMs: 0 }), error => error.code === 'adb-daemon-failed');
  assert.ok(!calls.some(args => args[0] === 'kill-server'));
});

test('explicit repair restarts a stale daemon and then verifies forwarding', async () => {
  const { manager, calls, state } = fixture();
  state.startFailures.push(commandError("ADB server didn't ACK", { stderr: 'failed to start daemon' }));
  const result = await manager.repair(adb);
  assert.equal(result.code, 'ready');
  assert.deepEqual(calls.slice(0, 3).map(args => args[0]), ['start-server', 'kill-server', 'start-server']);
});

test('explicit repair restarts ADB once when a connected S22 is initially absent', async () => {
  const calls = [];
  let restarted = false;
  let mapping = null;
  const manager = createAdbManager({
    isExecutable: () => true,
    sleep: async () => {},
    exec: async (_file, args) => {
      calls.push(args);
      if (args[0] === 'start-server') return { stdout: '', stderr: '' };
      if (args[0] === 'kill-server') { restarted = true; return { stdout: '', stderr: '' }; }
      if (args[0] === 'devices') return {
        stdout: restarted
          ? `List of devices attached\n${serial} device usb:1-1 model:SM_S901E\n`
          : 'List of devices attached\n',
        stderr: '',
      };
      if (args[0] === '-s' && args[2] === 'get-devpath') return { stdout: 'usb:1-1\n', stderr: '' };
      if (args[0] === '-s' && args[2] === 'reverse' && args[3] === '--list') {
        return { stdout: mapping ? `UsbFfs tcp:8787 ${mapping}\n` : '', stderr: '' };
      }
      if (args[0] === '-s' && args[2] === 'reverse' && args[3] === 'tcp:8787') {
        mapping = args[4];
        return { stdout: '', stderr: '' };
      }
      throw new Error(`Unexpected command: ${args.join(' ')}`);
    },
  });
  const result = await manager.repair(adb);
  assert.equal(result.code, 'ready');
  assert.ok(calls.some(args => args[0] === 'kill-server'));
});

test('explicit repair reconnects an offline S22 before considering a daemon restart', async () => {
  const { manager, calls } = fixture({ deviceState: 'offline' });
  const result = await manager.repair(adb);
  assert.equal(result.code, 'ready');
  assert.ok(calls.some(args => args.join(' ') === 'reconnect offline'));
  assert.ok(!calls.some(args => args[0] === 'kill-server'));
});

test('invalid or missing adb paths stop before execution', async () => {
  const calls = [];
  const manager = createAdbManager({ exec: async (_file, args) => { calls.push(args); }, isExecutable: () => false });
  await assert.rejects(manager.check('relative/adb'), error => error.code === 'adb-path-invalid');
  await assert.rejects(manager.check(adb), error => error.code === 'adb-missing');
  assert.deepEqual(calls, []);
});

test('manager errors retain stable codes for shell integration', () => {
  const error = new AdbManagerError('s22-absent', 'missing');
  assert.equal(classifyAdbFailure(error), error);
});

test('CLI validation prints the exact supported invocation', async () => {
  const errors = [];
  await assert.rejects(main([], { printError: message => errors.push(message) }), error => error.code === 'usage');
  assert.deepEqual(errors, ['Usage: node scripts/adb-manager.mjs check|wait|repair <absolute-adb-path> [serial]']);
});
