import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash, generateKeyPairSync, sign } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createGoogleVerifier } from '../src/google.js';
import { createApp } from '../src/app.js';
import { openDb } from '../src/db.js';

// A fake Google: our own RSA key, published as a JWKS, signing ID tokens like Google does.
const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const jwk = { ...publicKey.export({ format: 'jwk' }), kid: 'k1', alg: 'RS256', use: 'sig' };
const WEB = 'web-client.apps.googleusercontent.com';

function idToken(claims, kid = 'k1') {
  const encode = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');
  const head = encode({ alg: 'RS256', kid, typ: 'JWT' });
  const body = encode({ iss: 'https://accounts.google.com', aud: WEB, sub: '123', email: 'ayo@gmail.com',
    email_verified: true, name: 'Ayo', exp: Math.floor(Date.now() / 1000) + 3600, ...claims });
  return `${head}.${body}.${sign('RSA-SHA256', Buffer.from(`${head}.${body}`), privateKey).toString('base64url')}`;
}

const verifier = createGoogleVerifier({ clientIds: [WEB], fetchKeys: async () => ({ keys: [jwk], maxAge: 3600 }) });

test('Google ID tokens: valid, wrong app, expired, tampered', async () => {
  assert.equal((await verifier(idToken({}))).email, 'ayo@gmail.com');
  await assert.rejects(verifier(idToken({ aud: 'other' })), /outro aplicativo/);
  await assert.rejects(verifier(idToken({ exp: 1000 })), /vencido/);
  await assert.rejects(verifier(idToken({ email_verified: false })), /não verificado/);
  const [h, , s] = idToken({}).split('.');
  const forged = Buffer.from(JSON.stringify({ iss: 'accounts.google.com', aud: WEB, sub: '9', email: 'x@y.z', email_verified: true,
    exp: 9e9 })).toString('base64url');
  await assert.rejects(verifier(`${h}.${forged}.${s}`), /assinatura/);
});

async function server() {
  const dataDir = mkdtempSync(join(tmpdir(), 'ayo-sync-'));
  const app = createApp({ db: openDb(dataDir), dataDir, verifyIdToken: verifier, allowedEmails: ['ayo@gmail.com'] });
  const listener = await new Promise((resolve) => { const s = app.listen(0, '127.0.0.1', () => resolve(s)); });
  const base = `http://127.0.0.1:${listener.address().port}`;
  const call = async (path, { token, body, method = body ? 'POST' : 'GET', headers = {}, raw } = {}) => {
    const response = await fetch(base + path, { method, headers: { ...(body ? { 'content-type': 'application/json' } : {}),
      ...(token ? { authorization: `Bearer ${token}` } : {}), ...headers }, body: raw ?? (body ? JSON.stringify(body) : undefined) });
    const type = response.headers.get('content-type') || '';
    return { status: response.status, data: type.includes('json') ? await response.json() : Buffer.from(await response.arrayBuffer()) };
  };
  return { call, close: () => { listener.close(); rmSync(dataDir, { recursive: true, force: true }); } };
}

test('login: allowed e-mails only, then the session works', async () => {
  const { call, close } = await server();
  try {
    assert.equal((await call('/api/me')).status, 401);
    assert.equal((await call('/api/login', { body: { idToken: idToken({ email: 'outra@gmail.com', sub: '7' }) } })).status, 403);
    const login = await call('/api/login', { body: { idToken: idToken({}), device: 'celular' } });
    assert.equal(login.status, 200);
    const me = await call('/api/me', { token: login.data.token });
    assert.equal(me.data.user.email, 'ayo@gmail.com');
    await call('/api/logout', { token: login.data.token, body: {} });
    assert.equal((await call('/api/me', { token: login.data.token })).status, 401);
  } finally { close(); }
});

test('sync: newest change wins, each device gets what it misses', async () => {
  const { call, close } = await server();
  try {
    const phone = (await call('/api/login', { body: { idToken: idToken({}), device: 'celular' } })).data.token;
    const pc = (await call('/api/login', { body: { idToken: idToken({}), device: 'pc' } })).data.token;
    const first = await call('/api/sync', { token: phone, body: { device: 'celular', since: 0, changes: [
      { kind: 'flags', key: 'djavan|oceano|224', value: { favorite: true }, at: 1000 },
      { kind: 'playlist', key: 'p1', value: { name: 'Viagem', songs: ['djavan|oceano|224'] }, at: 1000 },
      { kind: 'nada', key: 'x', value: 1, at: 1000 },
    ] } });
    assert.deepEqual(first.data.problems, ['nada:x']);
    // The PC unliked it earlier (older clock) and renamed the playlist later.
    const second = await call('/api/sync', { token: pc, body: { device: 'pc', since: 0, changes: [
      { kind: 'flags', key: 'djavan|oceano|224', value: { favorite: false }, at: 500 },
      { kind: 'playlist', key: 'p1', value: { name: 'Estrada', songs: [] }, at: 2000 },
    ] } });
    const byKey = Object.fromEntries(second.data.changes.map((c) => [`${c.kind}:${c.key}`, c.value]));
    assert.equal(byKey['flags:djavan|oceano|224'].favorite, true);  // the phone's newer like stays
    assert.equal(byKey['playlist:p1'].name, 'Estrada');
    // The phone asks for what is new since its last sync: only the rename.
    const third = await call('/api/sync', { token: phone, body: { device: 'celular', since: first.data.rev, changes: [] } });
    assert.deepEqual(third.data.changes.map((c) => c.value.name), ['Estrada']);
    // Deleting is a change too.
    await call('/api/sync', { token: pc, body: { device: 'pc', since: 0, changes: [{ kind: 'playlist', key: 'p1', deleted: true, at: 3000 }] } });
    const fourth = await call('/api/sync', { token: phone, body: { device: 'celular', since: third.data.rev, changes: [] } });
    assert.equal(fourth.data.changes[0].deleted, true);
  } finally { close(); }
});

test('sync pages: a big library arrives in parts', async () => {
  const { call, close } = await server();
  try {
    const token = (await call('/api/login', { body: { idToken: idToken({}) } })).data.token;
    const changes = Array.from({ length: 4500 }, (_, i) => ({ kind: 'flags', key: `a|t${i}|200`, value: { favorite: true }, at: 10 }));
    await call('/api/sync', { token, body: { device: 'a', since: 0, changes } });
    let since = 0; let total = 0; let rounds = 0;
    for (;;) {
      const page = await call('/api/sync', { token, body: { device: 'b', since, changes: [] } });
      total += page.data.changes.length; since = page.data.rev; rounds += 1;
      if (!page.data.more) break;
    }
    assert.equal(total, 4500);
    assert.equal(rounds, 3);
  } finally { close(); }
});

test('blobs: stored by SHA-256, checked, per account', async () => {
  const { call, close } = await server();
  try {
    const token = (await call('/api/login', { body: { idToken: idToken({}) } })).data.token;
    const picture = Buffer.from('fake jpeg bytes');
    const sha = createHash('sha256').update(picture).digest('hex');
    const bad = await call(`/api/blobs/${'0'.repeat(64)}`, { token, method: 'PUT', raw: picture, headers: { 'content-type': 'image/jpeg' } });
    assert.equal(bad.status, 400);
    assert.equal((await call(`/api/blobs/${sha}`, { token, method: 'PUT', raw: picture, headers: { 'content-type': 'image/jpeg' } })).status, 200);
    const back = await call(`/api/blobs/${sha}`, { token });
    assert.equal(back.status, 200);
    assert.deepEqual(back.data, picture);
  } finally { close(); }
});
