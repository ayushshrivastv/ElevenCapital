import { open, stat, unlink } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { homedir } from 'node:os';
import { resolve } from 'node:path';
import { createServer } from 'node:net';

// Probe only capabilities the updater needs. Never change permissions, start
// ADB/Gradle, or overwrite an existing cache file.
const gradleDirectory = resolve(process.env.GRADLE_USER_HOME || resolve(homedir(), '.gradle'));

async function checkGradleWrite() {
  const entry = await stat(gradleDirectory);
  if (!entry.isDirectory()) throw new Error('Gradle user home is not a directory.');
  const probe = resolve(gradleDirectory, `.eleven-preflight-${randomUUID()}`);
  let created = false;
  try {
    const handle = await open(probe, 'wx', 0o600);
    created = true;
    await handle.close();
  } finally {
    if (created) await unlink(probe);
  }
}

async function checkLocalSocket() {
  await new Promise((resolveCheck, rejectCheck) => {
    const server = createServer();
    server.unref();
    let settled = false;
    const finish = (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(deadline);
      if (error) rejectCheck(error);
      else resolveCheck();
    };
    const deadline = setTimeout(() => {
      server.close(() => {});
      finish(new Error('Local socket check exceeded its 3-second deadline.'));
    }, 3000);
    server.once('error', finish);
    server.listen({ host: '127.0.0.1', port: 0, exclusive: true }, () => {
      // Port zero selects an unused port; it never touches the backend or ADB.
      server.close(finish);
    });
  });
}

const checks = [
  { label: `Gradle cache write (${gradleDirectory})`, run: checkGradleWrite },
  { label: 'Local socket binding (127.0.0.1, temporary port)', run: checkLocalSocket },
];
const results = await Promise.allSettled(checks.map(({ run }) => run()));
let failed = false;
for (const [index, result] of results.entries()) {
  if (result.status === 'fulfilled') continue;
  failed = true;
  const error = result.reason;
  console.error(`${checks[index].label}: ${error?.code || 'failed'} — ${error?.message || String(error)}`);
}
if (failed) {
  console.error('\nUpdate stopped before tests, builds, backend changes, or phone installation.');
  const permissionDenied = results.some((result) => result.status === 'rejected'
    && ['EPERM', 'EACCES'].includes(result.reason?.code));
  if (permissionDenied) {
    console.error('This process is denied a local capability required by Gradle and ADB.');
    console.error('In a restricted task, enabling USB debugging does not change the process permissions.');
    console.error('Allow the required local execution, or run this updater in macOS Terminal outside the restricted task.');
  } else {
    console.error('Resolve the local error above, then run this updater again.');
  }
  process.exitCode = 1;
} else {
  console.log('Preflight passed: Gradle cache writes and local sockets are available.');
}
