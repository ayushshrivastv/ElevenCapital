import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { buildApp } from '../src/app.js';
import { CommunityChatAccessError, CommunityChatStorageError, CommunityChatStore } from '../src/community-chat.js';
import { WalletAuthenticationError, type PrivyPrincipal } from '../src/privy-auth.js';

const ALICE: PrivyPrincipal = { subject: 'did:privy:alice', sessionId: 'alice-session' };
const BOB: PrivyPrincipal = { subject: 'did:privy:bob', sessionId: 'bob-session' };

function verifier() {
  return { async verifyAuthorization(value: string | undefined): Promise<PrivyPrincipal> {
    if (value === 'Bearer alice') return ALICE;
    if (value === 'Bearer bob') return BOB;
    throw new WalletAuthenticationError('authentication_required', 401, 'A verified Privy session is required.');
  } };
}

test('SPXTR join and text history are shared across users and persist, without exposing Privy subjects', t => {
  const directory = mkdtempSync(join(tmpdir(), 'eleven-community-chat-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const filename = join(directory, 'chat.json');
  const chat = new CommunityChatStore(filename, () => Date.parse('2026-10-09T00:00:00Z'));
  assert.equal(chat.list(ALICE).canPost, false);
  assert.throws(() => chat.post(ALICE, 'Before joining'), CommunityChatAccessError);
  assert.deepEqual(chat.join(ALICE), { schemaVersion: 1, roomId: 'spxtr', canPost: true });
  assert.deepEqual(chat.join(ALICE), { schemaVersion: 1, roomId: 'spxtr', canPost: true });
  const first = chat.post(ALICE, 'Hello from Alice');
  assert.equal(first.message.id, '1');
  assert.equal(first.message.isMine, true);
  assert.match(first.message.authorName, /^Member [A-F0-9]{6}$/);
  assert.equal(chat.list(BOB).canPost, false);
  assert.deepEqual(chat.list(BOB).messages[0], { ...first.message, isMine: false });
  chat.join(BOB);
  assert.equal(chat.post(BOB, 'Hi Alice').message.id, '2');
  const restarted = new CommunityChatStore(filename);
  assert.equal(restarted.list(ALICE).canPost, true);
  assert.equal(restarted.list(BOB).canPost, true);
  assert.deepEqual(restarted.list(ALICE, 1).messages.map(message => message.text), ['Hi Alice']);
  const stored = readFileSync(filename, 'utf8');
  assert.ok(!stored.includes(ALICE.subject) && !stored.includes(BOB.subject));
  assert.equal(stored.includes('Bearer'), false);
});

test('SPXTR history remains bounded and cursor pages preserve ascending order', () => {
  const chat = new CommunityChatStore(null);
  chat.join(ALICE);
  for (let index = 1; index <= 510; index++) chat.post(ALICE, `Message ${index}`);
  const recent = chat.list(BOB);
  assert.equal(recent.messages.length, 50);
  assert.equal(recent.messages[0]?.id, '461');
  assert.equal(recent.messages.at(-1)?.id, '510');
  assert.equal(recent.nextCursor, '510');
  const earlier = chat.list(BOB, 1);
  assert.equal(earlier.messages[0]?.id, '11');
  assert.equal(earlier.messages.at(-1)?.id, '60');
  assert.equal(chat.list(BOB, 510).messages.length, 0);
});

test('chat reset deletes only the authenticated author, revokes their join, and survives restart', t => {
  const directory = mkdtempSync(join(tmpdir(), 'eleven-community-chat-reset-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const filename = join(directory, 'chat.json');
  const chat = new CommunityChatStore(filename);
  chat.join(ALICE);
  chat.join(BOB);
  chat.post(ALICE, 'Alice one');
  chat.post(BOB, 'Bob stays');
  chat.post(ALICE, 'Alice two');
  assert.equal(chat.list(ALICE).deletionVersion, 0);
  assert.equal(JSON.parse(readFileSync(filename, 'utf8')).deletionVersion, undefined);

  // A pre-reset version 1 file must be readable without a migration step.
  const restarted = new CommunityChatStore(filename);
  assert.deepEqual(restarted.clearMine(ALICE), { schemaVersion: 1, roomId: 'spxtr', deletedCount: 2 });
  assert.deepEqual(restarted.list(BOB).messages.map(message => [message.id, message.text, message.isMine]),
    [['2', 'Bob stays', true]]);
  assert.equal(restarted.list(ALICE).canPost, false);
  assert.equal(restarted.list(BOB).canPost, true);
  assert.equal(restarted.list(BOB, 3).deletionVersion, 1);
  assert.deepEqual(restarted.clearMine(ALICE), { schemaVersion: 1, roomId: 'spxtr', deletedCount: 0 });
  assert.equal(restarted.list(BOB).deletionVersion, 1);
  assert.throws(() => restarted.post(ALICE, 'Must rejoin'), CommunityChatAccessError);
  assert.equal(restarted.post(BOB, 'Still here').message.id, '4');

  const reloaded = new CommunityChatStore(filename);
  assert.equal(reloaded.list(ALICE).deletionVersion, 1);
  assert.deepEqual(reloaded.list(BOB).messages.map(message => message.text), ['Bob stays', 'Still here']);
  reloaded.join(ALICE);
  assert.equal(reloaded.post(ALICE, 'Fresh start').message.id, '5');
  assert.deepEqual(reloaded.list(BOB).messages.map(message => message.text),
    ['Bob stays', 'Still here', 'Fresh start']);
  assert.equal(reloaded.clearMine(ALICE).deletedCount, 1);
  assert.equal(new CommunityChatStore(filename).list(BOB).deletionVersion, 2);
  assert.deepEqual(new CommunityChatStore(filename).list(BOB).messages.map(message => message.text),
    ['Bob stays', 'Still here']);
});

test('corrupt persisted history fails closed and does not overwrite the file', t => {
  const directory = mkdtempSync(join(tmpdir(), 'eleven-community-chat-corrupt-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const filename = join(directory, 'chat.json');
  writeFileSync(filename, '{broken', 'utf8');
  const chat = new CommunityChatStore(filename);
  assert.throws(() => chat.list(ALICE), CommunityChatStorageError);
  assert.throws(() => chat.join(ALICE), CommunityChatStorageError);
  assert.throws(() => chat.clearMine(ALICE), CommunityChatStorageError);
  assert.equal(readFileSync(filename, 'utf8'), '{broken');
});

test('HTTP chat only serves SPXTR, authenticates, gates posts on join, and returns incremental updates', async t => {
  const chat = new CommunityChatStore(null);
  const app = await buildApp(undefined, false, { communityChat: chat, accessTokenVerifier: verifier() });
  t.after(() => app.close());
  const get = (authorization?: string, after = '') => app.inject({ method: 'GET',
    url: `/v1/community-chat/spxtr/messages${after ? `?after=${after}` : ''}`,
    headers: authorization ? { authorization } : {} });
  const post = (authorization: string, text: string) => app.inject({ method: 'POST',
    url: '/v1/community-chat/spxtr/messages', headers: { authorization }, payload: { text } });

  assert.equal((await get()).statusCode, 401);
  assert.equal((await post('Bearer wrong', 'Hello')).statusCode, 401);
  assert.equal((await app.inject({ method: 'POST', url: '/v1/community-chat/spxtr/join' })).statusCode, 401);
  const before = await get('Bearer alice');
  assert.equal(before.statusCode, 200);
  assert.equal(before.headers['cache-control'], 'no-store');
  assert.deepEqual(before.json(), { schemaVersion: 1, roomId: 'spxtr', messages: [], nextCursor: '0', canPost: false,
    deletionVersion: 0 });
  const blocked = await post('Bearer alice', 'Hello');
  assert.equal(blocked.statusCode, 403);
  assert.equal(blocked.json().error, 'chat_join_required');
  const joined = await app.inject({ method: 'POST', url: '/v1/community-chat/spxtr/join', headers: { authorization: 'Bearer alice' } });
  assert.equal(joined.statusCode, 200);
  assert.deepEqual(joined.json(), { schemaVersion: 1, roomId: 'spxtr', canPost: true });
  assert.equal(joined.headers['cache-control'], 'no-store');
  assert.equal((await post('Bearer alice', '   ')).statusCode, 400);
  assert.equal((await post('Bearer alice', 'x'.repeat(501))).statusCode, 400);
  assert.equal((await post('Bearer alice', 'bad\u0000text')).statusCode, 400);
  const sent = await post('Bearer alice', '  Hello SPXTR  ');
  assert.equal(sent.statusCode, 200);
  assert.equal(sent.json().message.text, 'Hello SPXTR');
  assert.equal(sent.headers['cache-control'], 'no-store');
  const otherUser = await get('Bearer bob');
  assert.equal(otherUser.statusCode, 200);
  assert.equal(otherUser.json().messages[0].isMine, false);
  assert.equal(otherUser.json().canPost, false);
  assert.equal((await get('Bearer alice', '1')).json().messages.length, 0);
  assert.equal((await get('Bearer alice', '-1')).statusCode, 400);
  assert.equal((await app.inject({ method: 'GET', url: '/v1/community-chat/another/messages',
    headers: { authorization: 'Bearer alice' } })).statusCode, 404);
});

test('HTTP chat reset is authenticated, own-only, idempotent, and visible to polling clients', async t => {
  const chat = new CommunityChatStore(null);
  const app = await buildApp(undefined, false, { communityChat: chat, accessTokenVerifier: verifier() });
  t.after(() => app.close());
  const url = '/v1/community-chat/spxtr/messages/mine';
  const join = (who: string) => app.inject({ method: 'POST', url: '/v1/community-chat/spxtr/join',
    headers: { authorization: `Bearer ${who}` } });
  const post = (who: string, text: string) => app.inject({ method: 'POST', url: '/v1/community-chat/spxtr/messages',
    headers: { authorization: `Bearer ${who}` }, payload: { text } });
  const get = (who: string, after = '') => app.inject({ method: 'GET',
    url: `/v1/community-chat/spxtr/messages${after ? `?after=${after}` : ''}`,
    headers: { authorization: `Bearer ${who}` } });

  assert.equal((await app.inject({ method: 'DELETE', url })).statusCode, 401);
  assert.equal((await app.inject({ method: 'DELETE', url, headers: { authorization: 'Bearer wrong' } })).statusCode, 401);
  const emptyBody = await app.inject({ method: 'DELETE', url,
    headers: { authorization: 'Bearer alice', 'content-length': '0' }, payload: '' });
  assert.equal(emptyBody.statusCode, 200);
  assert.deepEqual(emptyBody.json(), { schemaVersion: 1, roomId: 'spxtr', deletedCount: 0 });
  assert.equal((await join('alice')).statusCode, 200);
  assert.equal((await join('bob')).statusCode, 200);
  assert.equal((await post('alice', 'Alice before')).statusCode, 200);
  assert.equal((await post('bob', 'Bob must stay')).statusCode, 200);
  assert.equal((await post('alice', 'Alice again')).statusCode, 200);
  assert.equal((await app.inject({ method: 'DELETE', url: `${url}?subject=did:privy:bob`,
    headers: { authorization: 'Bearer alice' } })).statusCode, 400);
  assert.equal((await app.inject({ method: 'DELETE', url, headers: { authorization: 'Bearer alice' },
    payload: { subject: BOB.subject } })).statusCode, 400);

  const reset = await app.inject({ method: 'DELETE', url, headers: { authorization: 'Bearer alice' } });
  assert.equal(reset.statusCode, 200);
  assert.equal(reset.headers['cache-control'], 'no-store');
  assert.deepEqual(reset.json(), { schemaVersion: 1, roomId: 'spxtr', deletedCount: 2 });
  const polled = await get('bob', '3');
  assert.equal(polled.statusCode, 200);
  assert.equal(polled.json().deletionVersion, 1);
  assert.deepEqual(polled.json().messages, []);
  assert.deepEqual((await get('bob')).json().messages.map((message: { text: string }) => message.text),
    ['Bob must stay']);
  assert.equal((await get('bob')).json().canPost, true);
  assert.equal((await get('alice')).json().canPost, false);
  assert.equal((await post('alice', 'Blocked after reset')).statusCode, 403);
  const again = await app.inject({ method: 'DELETE', url, headers: { authorization: 'Bearer alice' } });
  assert.deepEqual(again.json(), { schemaVersion: 1, roomId: 'spxtr', deletedCount: 0 });
  assert.equal((await get('bob')).json().deletionVersion, 1);
  assert.equal((await post('bob', 'Bob still posts')).statusCode, 200);
});
