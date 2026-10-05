import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createApp } from '../src/app.js';
import { openDb } from '../src/db.js';
import { songKey } from '../src/songkey.js';

// Two seconds of tone with tags, made by ffmpeg.
function mp3(path, title, artist, seconds = 2) {
  execFileSync('ffmpeg', ['-v', 'error', '-y', '-f', 'lavfi', '-i', `sine=frequency=440:duration=${seconds}`, '-metadata',
    `title=${title}`, '-metadata', `artist=${artist}`, '-metadata', 'album=Teste', '-b:a', '64k', path]);
}

async function server() {
  const root = mkdtempSync(join(tmpdir(), 'ayo-cloud-'));
  const dataDir = join(root, 'data');
  const music = join(root, 'Music');
  mkdirSync(join(music, 'Djavan'), { recursive: true });
  mp3(join(music, 'Djavan', 'oceano.mp3'), 'Oceano', 'Djavan');
  mp3(join(music, 'Construção.mp3'), 'Construção', 'Chico Buarque', 3);
  const verify = async (token) => ({ sub: token, email: `${token}@gmail.com`, name: token, picture: '' });
  const app = createApp({ db: openDb(dataDir), dataDir, verifyIdToken: verify, allowedEmails: ['ayo@gmail.com', 'haji@gmail.com'],
    libraryFolders: { 'ayo@gmail.com': music } });
  const listener = await new Promise((resolve) => { const s = app.listen(0, '127.0.0.1', () => resolve(s)); });
  const base = `http://127.0.0.1:${listener.address().port}`;
  const call = async (path, { token, body, method, headers = {}, raw } = {}) => {
    const response = await fetch(base + path, { method: method || (body || raw ? 'POST' : 'GET'), headers: {
      ...(body ? { 'content-type': 'application/json' } : {}), ...(token ? { authorization: `Bearer ${token}` } : {}), ...headers },
    body: raw ?? (body ? JSON.stringify(body) : undefined) });
    const type = response.headers.get('content-type') || '';
    return { status: response.status, headers: response.headers,
      data: type.includes('json') ? await response.json() : Buffer.from(await response.arrayBuffer()) };
  };
  const login = async (who, device) => (await call('/api/login', { body: { idToken: who, device } })).data.token;
  return { app, call, login, music, close: () => { listener.close(); rmSync(root, { recursive: true, force: true }); } };
}

test('cloud: the owner\'s folder is indexed in place and streams with ranges', async () => {
  const { app, call, login, close } = await server();
  try {
    const token = await login('ayo', 'pc');
    await app.locals.scanFor({ id: 1, email: 'ayo@gmail.com' });
    const list = await call('/api/tracks?since=0', { token });
    const titles = list.data.tracks.map((t) => t.title).sort();
    assert.deepEqual(titles, ['Construção', 'Oceano']);
    const oceano = list.data.tracks.find((t) => t.title === 'Oceano');
    assert.equal(oceano.songKey, songKey('Djavan', 'Oceano', oceano.durationMs));
    assert.ok(oceano.durationMs > 1500 && oceano.durationMs < 2500);
    const whole = await call(`/api/tracks/${oceano.id}/audio`, { token });
    assert.equal(whole.status, 200);
    assert.equal(whole.headers.get('content-type'), 'audio/mpeg');
    const part = await call(`/api/tracks/${oceano.id}/audio`, { token, headers: { range: 'bytes=100-199' } });
    assert.equal(part.status, 206);
    assert.equal(part.data.length, 100);
    // Someone else's account sees nothing of it.
    const haji = await login('haji', 'celular');
    assert.equal((await call(`/api/tracks/${oceano.id}/audio`, { token: haji })).status, 404);
    assert.equal((await call('/api/tracks?since=0', { token: haji })).data.tracks.length, 0);
  } finally { close(); }
});

test('cloud: uploads are deduplicated by content', async () => {
  const { call, login, music, close } = await server();
  try {
    const token = await login('haji', 'celular');
    const file = readFileSync(join(music, 'Djavan', 'oceano.mp3'));
    const sha = createHash('sha256').update(file).digest('hex');
    assert.equal((await call(`/api/tracks/sha/${sha}`, { token })).status, 404);
    const first = await call('/api/tracks/upload', { token, raw: file, headers: { 'content-type': 'audio/mpeg', 'x-filename': 'oceano.mp3' } });
    assert.equal(first.data.track.title, 'Oceano');
    const again = await call('/api/tracks/upload', { token, raw: file, headers: { 'content-type': 'audio/mpeg', 'x-filename': 'x.mp3' } });
    assert.equal(again.data.track.id, first.data.track.id);
    assert.equal((await call(`/api/tracks/sha/${sha}`, { token })).status, 200);
    await call(`/api/tracks/${first.data.track.id}`, { token, method: 'DELETE' });
    const list = await call('/api/tracks?since=0', { token });
    assert.equal(list.data.tracks[0].deleted, true);
  } finally { close(); }
});

test('remote control: a command reaches the waiting device; states are listed', async () => {
  const { call, login, close } = await server();
  try {
    const phone = await login('ayo', 'celular');
    await call('/api/player/state', { token: phone, body: { device: 'pc-1', name: 'PC', platform: 'linux',
      state: { songKey: 'djavan|oceano|2', positionMs: 1200, playing: true } } });
    const waiting = call('/api/player/commands?device=pc-1&wait=5', { token: phone });
    await new Promise((r) => setTimeout(r, 100));
    await call('/api/player/command', { token: phone, body: { target: 'pc-1', action: 'pause', device: 'cel-1' } });
    const got = await waiting;
    assert.equal(got.data.commands[0].action, 'pause');
    const devices = await call('/api/player/devices', { token: phone });
    assert.equal(devices.data.devices[0].name, 'PC');
    assert.equal(devices.data.devices[0].online, true);
    assert.equal(devices.data.devices[0].state.positionMs, 1200);
    assert.equal((await call('/api/player/command', { token: phone, body: { target: 'pc-1', action: 'rm -rf' } })).status, 400);
  } finally { close(); }
});
