import assert from 'node:assert/strict';
import { generateKeyPairSync } from 'node:crypto';
import test from 'node:test';
import { PrivyPublicJwksProvider } from '../src/privy-auth.js';

test('Privy public key provider accepts the compact PEM returned by public app configuration', async () => {
  const { publicKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const expected = publicKey.export({ format: 'jwk' });
  const compactPem = publicKey.export({ format: 'pem', type: 'spki' }).replace(/\r?\n/g, '');
  const fetcher = (async () => new Response(JSON.stringify({ verification_key: compactPem }))) as typeof fetch;
  const provider = new PrivyPublicJwksProvider('app-public', 'client-public', 'com.elevencapital.app', Date.now, fetcher);

  const keys = await provider.keys();
  assert.equal(keys.length, 1);
  assert.deepEqual({ kty: keys[0]?.kty, crv: keys[0]?.crv, x: keys[0]?.x, y: keys[0]?.y },
    { kty: 'EC', crv: 'P-256', x: expected.x, y: expected.y });
});
