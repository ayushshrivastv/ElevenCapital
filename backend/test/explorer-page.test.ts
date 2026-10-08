import assert from 'node:assert/strict';
import test from 'node:test';
import { buildApp } from '../src/app.js';

test('the mobile explorer serves only the self-contained project page, without exposing backend files', async t => {
  const app = await buildApp();
  t.after(() => app.close());

  for (const path of ['/', '/explorer', '/index.html']) {
    const response = await app.inject({ method: 'GET', url: path });
    assert.equal(response.statusCode, 200, path);
    assert.match(response.headers['content-type'] ?? '', /^text\/html; charset=utf-8/i);
    assert.equal(response.headers['cache-control'], 'no-store');
    assert.equal(response.headers['x-frame-options'], 'DENY');
    assert.match(response.body, /^<!doctype html>/i);
    assert.match(response.body, /<title>Transaction Details \| Eleven Capital<\/title>/);
    assert.doesNotMatch(response.body, /Preview · not on-chain/);
    assert.doesNotMatch(response.body, /explorer preview; not an on-chain transaction/);
  }

  for (const path of ['/backend/package.json', '/explorer/index.html', '/index.html/../backend/package.json']) {
    const response = await app.inject({ method: 'GET', url: path });
    assert.equal(response.statusCode, 404, path);
    assert.equal(response.headers['content-type']?.startsWith('text/html'), false);
  }
});
