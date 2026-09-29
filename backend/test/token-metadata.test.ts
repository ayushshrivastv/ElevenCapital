import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, readFileSync, readdirSync, rmSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { buildApp } from '../src/app.js';
import { SubjectRateLimitError, WalletAuthenticationError, type PrivyPrincipal } from '../src/privy-auth.js';
import { TokenMetadataAdmissionLimiter, TokenMetadataError, TokenMetadataService } from '../src/token-metadata.js';
import { TokenMetadataRequestSchema, type TokenMetadataRequest } from '../src/token-metadata-schema.js';

const PNG_BASE64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2q3sAAAAASUVORK5CYII=';
const PRINCIPAL: PrivyPrincipal = { subject: 'did:privy:metadata-user', sessionId: 'metadata-session' };

function request(overrides: Partial<TokenMetadataRequest> = {}): TokenMetadataRequest {
  return { name: 'Launch Test', symbol: 'TEST', description: 'A public token description.', imageBase64: PNG_BASE64,
    imageMediaType: 'image/png', websiteUrl: 'https://eleven.example/token', xUrl: 'https://x.com/eleven',
    telegramUrl: 'https://t.me/eleven', ...overrides };
}

function verifier() {
  return { async verifyAuthorization(value: string | undefined) {
    if (value !== 'Bearer valid') {
      throw new WalletAuthenticationError('authentication_required', 401, 'A verified Privy session is required.');
    }
    return PRINCIPAL;
  } };
}

test('metadata schema preserves launch identity rules and rejects unsafe URLs, SVG, extras, and oversized descriptions', () => {
  assert.equal(TokenMetadataRequestSchema.safeParse(request()).success, true);
  for (const invalid of [
    request({ name: ' padded ' }), request({ symbol: 'lower' }), request({ description: 'x'.repeat(281) }),
    request({ websiteUrl: 'http://eleven.example/token' }), request({ xUrl: 'https://localhost/profile' }),
    request({ telegramUrl: 'https://192.168.1.2/chat' }), request({ websiteUrl: 'https://127.0.0.1./token' }),
    request({ websiteUrl: 'https://8.8.8.8/token' }), request({ websiteUrl: 'https://[2606:4700:4700::1111]/token' }),
    request({ websiteUrl: 'https://eleven.example:443/token' }), request({ websiteUrl: 'https://ele\tven.example/token' }),
    request({ description: 'unsafe\u0000description' }),
    { ...request(), imageMediaType: 'image/svg+xml' },
    { ...request(), unexpected: true },
  ]) assert.equal(TokenMetadataRequestSchema.safeParse(invalid).success, false);
});

test('publisher requires an existing absolute durable storage root and a valid byte ceiling', async () => {
  for (const service of [
    new TokenMetadataService('relative/token-metadata', 'https://tokens.example'),
    new TokenMetadataService(join(tmpdir(), `missing-token-metadata-${process.pid}`), 'https://tokens.example'),
    new TokenMetadataService(tmpdir(), 'https://tokens.example', 0),
  ]) {
    await assert.rejects(service.publish(request()),
      error => error instanceof TokenMetadataError && error.code === 'metadata_unavailable' && error.statusCode === 503);
  }
});

test('publisher writes exact Metaplex JSON and immutable content-addressed files idempotently across restarts', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'eleven-token-metadata-')); t.after(() => rmSync(directory, { recursive: true, force: true }));
  const service = new TokenMetadataService(directory, 'https://tokens.example');
  const first = await service.publish(request()); const second = await service.publish(request());
  assert.deepEqual(second, first);
  assert.match(first.contentHash, /^[a-f0-9]{64}$/);
  const imageHash = createHash('sha256').update(Buffer.from(PNG_BASE64, 'base64')).digest('hex');
  assert.equal(first.imageUri, `https://tokens.example/v1/token-launches/metadata/images/${imageHash}.png`);
  assert.equal(first.metadataUri, `https://tokens.example/v1/token-launches/metadata/documents/${first.contentHash}.json`);
  assert.deepEqual(readdirSync(join(directory, 'images')), [`${imageHash}.png`]);
  assert.deepEqual(readdirSync(join(directory, 'documents')), [`${first.contentHash}.json`]);
  const expected = JSON.stringify({
    name: 'Launch Test', symbol: 'TEST', description: 'A public token description.', image: first.imageUri,
    external_url: 'https://eleven.example/token', attributes: [],
    properties: { files: [{ uri: first.imageUri, type: 'image/png' }], category: 'image' },
    extensions: { website: 'https://eleven.example/token', twitter: 'https://x.com/eleven', telegram: 'https://t.me/eleven' },
  });
  const stored = readFileSync(join(directory, 'documents', `${first.contentHash}.json`), 'utf8');
  assert.equal(stored, expected);
  assert.equal(createHash('sha256').update(stored).digest('hex'), first.contentHash);
  const restarted = new TokenMetadataService(directory, 'https://tokens.example/');
  assert.deepEqual(await restarted.publish(request()), first);
  assert.equal(readdirSync(join(directory, 'images')).some(value => value.startsWith('.tmp-')), false);
});

test('serialized writes enforce the global storage ceiling under concurrent publications', async t => {
  const measured = mkdtempSync(join(tmpdir(), 'eleven-token-metadata-measure-'));
  const capped = mkdtempSync(join(tmpdir(), 'eleven-token-metadata-capped-'));
  t.after(() => { rmSync(measured, { recursive: true, force: true }); rmSync(capped, { recursive: true, force: true }); });
  const firstRequest = request({ description: 'First bounded metadata document.' });
  const secondRequest = request({ description: 'Second distinct metadata document.' });
  const measurement = new TokenMetadataService(measured, 'https://tokens.example');
  const first = await measurement.publish(firstRequest); const second = await measurement.publish(secondRequest);
  const imageName = new URL(first.imageUri).pathname.split('/').at(-1)!;
  const imageBytes = statSync(join(measured, 'images', imageName)).size;
  const firstBytes = statSync(join(measured, 'documents', `${first.contentHash}.json`)).size;
  const secondBytes = statSync(join(measured, 'documents', `${second.contentHash}.json`)).size;
  const ceiling = imageBytes + Math.max(firstBytes, secondBytes);
  const service = new TokenMetadataService(capped, 'https://tokens.example', ceiling, 2);
  const results = await Promise.allSettled([service.publish(firstRequest), service.publish(secondRequest)]);
  assert.equal(results.filter(result => result.status === 'fulfilled').length, 1);
  const rejected = results.find(result => result.status === 'rejected');
  assert.ok(rejected?.status === 'rejected' && rejected.reason instanceof TokenMetadataError &&
    rejected.reason.code === 'metadata_unavailable');
  const stored = ['images', 'documents'].flatMap(directory => readdirSync(join(capped, directory))
    .map(filename => statSync(join(capped, directory, filename)).size)).reduce((sum, size) => sum + size, 0);
  assert.ok(stored <= ceiling);
});

test('metadata POST authenticates, rate limits, validates image bytes, and fails safely without a public base URL', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'eleven-token-metadata-http-')); t.after(() => rmSync(directory, { recursive: true, force: true }));
  const app = await buildApp(undefined, false, { accessTokenVerifier: verifier(),
    tokenMetadata: new TokenMetadataService(directory, 'https://tokens.example') });
  t.after(() => app.close());
  assert.equal((await app.inject({ method: 'POST', url: '/v1/token-launches/metadata', payload: request() })).statusCode, 401);
  assert.equal((await app.inject({ method: 'POST', url: '/v1/token-launches/metadata', payload: {} })).statusCode, 401);
  const spoofed = await app.inject({ method: 'POST', url: '/v1/token-launches/metadata', headers: { authorization: 'Bearer valid' },
    payload: request({ imageMediaType: 'image/jpeg' }) });
  assert.equal(spoofed.statusCode, 400); assert.equal(spoofed.json().error, 'invalid_request');
  const malformed = await app.inject({ method: 'POST', url: '/v1/token-launches/metadata',
    headers: { authorization: 'Bearer valid' }, payload: request({
      imageBase64: Buffer.from([0xff, 0xd8, 0xff, 0xd9]).toString('base64'), imageMediaType: 'image/jpeg',
    }) });
  assert.equal(malformed.statusCode, 400); assert.equal(malformed.json().error, 'invalid_request');
  const created = await app.inject({ method: 'POST', url: '/v1/token-launches/metadata',
    headers: { authorization: 'Bearer valid', host: 'attacker.invalid' }, payload: request() });
  assert.equal(created.statusCode, 200); assert.equal(new URL(created.json().metadataUri).hostname, 'tokens.example');

  const unavailable = await buildApp(undefined, false, { accessTokenVerifier: verifier(),
    tokenMetadata: new TokenMetadataService(directory, 'http://localhost:8787') });
  t.after(() => unavailable.close());
  const missingConfig = await unavailable.inject({ method: 'POST', url: '/v1/token-launches/metadata',
    headers: { authorization: 'Bearer valid' }, payload: request() });
  assert.equal(missingConfig.statusCode, 503); assert.deepEqual(missingConfig.json(), {
    error: 'metadata_unavailable', message: 'Token metadata publishing is temporarily unavailable.',
  });

  const overlongBase = await buildApp(undefined, false, { accessTokenVerifier: verifier(),
    tokenMetadata: new TokenMetadataService(directory, `https://tokens.example/${'path/'.repeat(30)}`) });
  t.after(() => overlongBase.close());
  const overlong = await overlongBase.inject({ method: 'POST', url: '/v1/token-launches/metadata',
    headers: { authorization: 'Bearer valid' }, payload: request() });
  assert.equal(overlong.statusCode, 503); assert.equal(overlong.json().error, 'metadata_unavailable');

  const limited = await buildApp(undefined, false, { accessTokenVerifier: verifier(),
    tokenMetadata: new TokenMetadataService(directory, 'https://tokens.example'),
    tokenLaunchSubjectRateLimiter: { take() { throw new SubjectRateLimitError('limited'); } } });
  t.after(() => limited.close());
  const rateLimited = await limited.inject({ method: 'POST', url: '/v1/token-launches/metadata',
    headers: { authorization: 'Bearer valid' }, payload: request() });
  assert.equal(rateLimited.statusCode, 429); assert.equal(rateLimited.json().error, 'rate_limited');

  const globallyLimited = await buildApp(undefined, false, { accessTokenVerifier: verifier(),
    tokenMetadata: new TokenMetadataService(directory, 'https://tokens.example'),
    tokenMetadataAdmissionRateLimiter: new TokenMetadataAdmissionLimiter(() => 1_000, 1) });
  t.after(() => globallyLimited.close());
  const admitted = await globallyLimited.inject({ method: 'POST', url: '/v1/token-launches/metadata',
    headers: { authorization: 'Bearer valid' }, payload: request() });
  assert.equal(admitted.statusCode, 200);
  const globallyRateLimited = await globallyLimited.inject({ method: 'POST', url: '/v1/token-launches/metadata',
    headers: { authorization: 'Bearer valid' }, payload: request() });
  assert.equal(globallyRateLimited.statusCode, 429); assert.equal(globallyRateLimited.json().error, 'rate_limited');
  assert.equal(globallyRateLimited.headers['retry-after'], '60');
});

test('public immutable GET routes serve exact bytes and content types without authentication', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'eleven-token-metadata-get-')); t.after(() => rmSync(directory, { recursive: true, force: true }));
  const metadata = new TokenMetadataService(directory, 'https://tokens.example'); const published = await metadata.publish(request());
  const accessTokenVerifier = { async verifyAuthorization(): Promise<PrivyPrincipal> { throw new Error('GET must be public'); } };
  const app = await buildApp(undefined, false, { tokenMetadata: metadata, accessTokenVerifier }); t.after(() => app.close());
  const imagePath = new URL(published.imageUri).pathname; const documentPath = new URL(published.metadataUri).pathname;
  const image = await app.inject({ method: 'GET', url: imagePath });
  assert.equal(image.statusCode, 200); assert.equal(image.headers['content-type'], 'image/png');
  assert.equal(image.headers['cache-control'], 'public, max-age=31536000, immutable');
  assert.deepEqual(image.rawPayload, Buffer.from(PNG_BASE64, 'base64'));
  const document = await app.inject({ method: 'GET', url: documentPath });
  assert.equal(document.statusCode, 200); assert.equal(document.headers['content-type'], 'application/json; charset=utf-8');
  assert.equal(document.headers['cache-control'], 'public, max-age=31536000, immutable');
  assert.equal(createHash('sha256').update(document.rawPayload).digest('hex'), published.contentHash);
  const traversal = await app.inject({ method: 'GET', url: '/v1/token-launches/metadata/images/%2e%2e%2fprivate.json' });
  assert.notEqual(traversal.statusCode, 200);
});
