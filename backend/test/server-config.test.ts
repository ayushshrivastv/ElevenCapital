import assert from 'node:assert/strict';
import test from 'node:test';
import { chmod, mkdtemp, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { configureBackendServerEnvironment } from '../src/server-config.js';
import { createGetJson } from '../src/http.js';

const FIXTURE_KEY = 'test-jupiter-server-key';

test('owner-only backend secrets configure Jupiter once without exposing values in status', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'eleven-backend-config-'));
  const path = join(directory, 'backend-secrets.json');
  const original = process.env.JUPITER_API_KEY;
  try {
    delete process.env.JUPITER_API_KEY;
    await writeFile(path, JSON.stringify({ JUPITER_API_KEY: FIXTURE_KEY }), { mode: 0o600 });
    const status = await configureBackendServerEnvironment(path);
    assert.deepEqual(status, { jupiterApiKeyConfigured: true });
    assert.equal(process.env.JUPITER_API_KEY, FIXTURE_KEY);
    assert.ok(!JSON.stringify(status).includes(FIXTURE_KEY));
  } finally {
    if (original === undefined) delete process.env.JUPITER_API_KEY; else process.env.JUPITER_API_KEY = original;
    await rm(directory, { recursive: true, force: true });
  }
});

test('backend secrets reject broad permissions, symlinks and malformed configuration with redacted errors', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'eleven-backend-config-'));
  const path = join(directory, 'backend-secrets.json');
  const link = join(directory, 'linked-secrets.json');
  const original = process.env.JUPITER_API_KEY;
  try {
    delete process.env.JUPITER_API_KEY;
    await writeFile(path, JSON.stringify({ JUPITER_API_KEY: FIXTURE_KEY }), { mode: 0o600 });
    await chmod(path, 0o644);
    await assert.rejects(configureBackendServerEnvironment(path), failure => {
      assert.ok(!String(failure).includes(FIXTURE_KEY)); return /permissions/.test(String(failure));
    });
    await chmod(path, 0o600);
    await symlink(path, link);
    await assert.rejects(configureBackendServerEnvironment(link), /opened securely/);
    await writeFile(path, JSON.stringify({ JUPITER_API_KEY: FIXTURE_KEY, EXTRA_SECRET: FIXTURE_KEY }), { mode: 0o600 });
    await assert.rejects(configureBackendServerEnvironment(path), failure => {
      assert.ok(!String(failure).includes(FIXTURE_KEY)); return /unsupported or invalid/.test(String(failure));
    });
  } finally {
    if (original === undefined) delete process.env.JUPITER_API_KEY; else process.env.JUPITER_API_KEY = original;
    await rm(directory, { recursive: true, force: true });
  }
});

test('Jupiter key is resolved lazily and attached only to Jupiter analytics requests', async () => {
  const originalFetch = globalThis.fetch;
  const requests: { url: string; headers: Headers }[] = [];
  let key: string | undefined;
  try {
    globalThis.fetch = (async (input, init) => {
      requests.push({ url: String(input), headers: new Headers(init?.headers) });
      return new Response('[]', { status: 200, headers: { 'content-type': 'application/json' } });
    }) as typeof fetch;
    const http = createGetJson({ jupiterApiKey: () => key });
    key = FIXTURE_KEY;
    await http('jupiter', '/tokens/v2/search', { query: 'So11111111111111111111111111111111111111112' });
    await http('prestocks', '/api/prestocks');
    assert.equal(requests[0]!.headers.get('x-api-key'), FIXTURE_KEY);
    assert.equal(requests[1]!.headers.has('x-api-key'), false);
    assert.equal(requests.filter(request => request.headers.has('x-api-key')).length, 1);
  } finally { globalThis.fetch = originalFetch; }
});
