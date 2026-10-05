// The sync API. Every route but /api/login and /api/health needs "Authorization: Bearer <session token>".
import express from 'express';
import { createHash, randomBytes } from 'node:crypto';
import { createReadStream, existsSync, renameSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { AuthError } from './google.js';
import { createCloud, publicTrack } from './cloud.js';

// What can be synced. Values are the apps' JSON; the server only checks size and shape.
export const KINDS = new Set([
  'playlist',  // key: playlist id (uuid) → {name, description, songs: [songKey], cover: sha|null}
  'flags',     // key: songKey → {favorite, noShuffle}
  'plays',     // key: songKey + '@' + device → {plays, skips, lastPlayed} (summed over devices by the apps)
  'lyrics',    // key: songKey → {synced, plain, source, offsetMs} (only lyrics the person chose or synced)
  'fix',       // key: songKey → corrected info {title, artist, album, albumArtist, year, genre, cover: sha|null}
  'ignored',   // key: songKey → true (analyzer: never look this song up again)
  'pref',      // key: setting name → value (theme, tabs...)
]);
const SESSION_DAYS = 180;
const PAGE = 2000;
const MAX_VALUE = 256 * 1024;
const MAX_BLOB = 8 * 1024 * 1024;
const ONLINE_MS = 70_000;
// What the remote control can ask a device to do.
const ACTIONS = new Set(['play', 'pause', 'toggle', 'next', 'previous', 'seek', 'playQueue', 'volume', 'shuffle', 'repeat']);

export const hashToken = (token) => createHash('sha256').update(token).digest('hex');

export function createApp({ db, dataDir, verifyIdToken, allowedEmails, publicConfig = {}, libraryFolders = {},
  now = () => Date.now() }) {
  const app = express();
  app.disable('x-powered-by');
  app.set('trust proxy', 'loopback');
  app.use(express.json({ limit: '6mb' }));

  const attempts = new Map();  // login attempts per IP (simple rate limit)
  const nextRev = (userId) => {
    const rev = currentRev(userId) + 1;
    db.prepare('INSERT INTO revs (user_id, rev) VALUES (?, ?) ON CONFLICT (user_id) DO UPDATE SET rev = excluded.rev').run(userId, rev);
    return rev;
  };
  const cloud = createCloud({ db, dataDir, nextRev, now });
  const scanning = new Map();  // userId → running scan (one at a time)
  /** The owner's music folder on this PC, indexed for their account (LIBRARY_FOLDERS in .env). */
  const scanFor = (user) => {
    const folder = libraryFolders[user.email];
    if (!folder || scanning.has(user.id)) return scanning.get(user.id) || Promise.resolve(null);
    const run = cloud.scanFolder(user.id, folder)
      .then((result) => { console.log(`pasta de ${user.email}: +${result.added} −${result.removed} (${result.total})`); return result; })
      .catch((error) => { console.error('scan', error); return null; })
      .finally(() => scanning.delete(user.id));
    scanning.set(user.id, run);
    return run;
  };
  const allowed = (email) => allowedEmails.length === 0 || allowedEmails.includes(email.toLowerCase());

  app.get('/api/health', (_req, res) => res.json({ ok: true }));
  // What the apps need to start "Entrar com o Google" (no secrets the apps could not hold anyway).
  app.get('/api/config', (_req, res) => res.json({ ...publicConfig, version: 1 }));

  app.post('/api/login', async (req, res) => {
    const ip = req.ip || '';
    const recent = (attempts.get(ip) || []).filter((t) => now() - t < 60_000);
    if (recent.length >= 10) return res.status(429).json({ error: 'Muitas tentativas. Espere um minuto.' });
    attempts.set(ip, [...recent, now()]);
    try {
      const google = await verifyIdToken(req.body?.idToken);
      if (!allowed(google.email)) return res.status(403).json({ error: 'Esta conta Google não tem acesso a este servidor.' });
      const user = upsertUser(google);
      const token = createSession(user.id, String(req.body?.device || '').slice(0, 80));
      scanFor(user);
      res.json({ token, user: publicUser(user) });
    } catch (error) {
      if (error instanceof AuthError) return res.status(401).json({ error: `Login recusado: ${error.message}.` });
      console.error('login', error);
      res.status(502).json({ error: 'Não deu para falar com o Google. Tente de novo.' });
    }
  });

  // Everything below needs a session.
  app.use('/api', (req, res, next) => {
    const token = /^Bearer (.+)$/.exec(req.get('authorization') || '')?.[1];
    if (!token) return res.status(401).json({ error: 'Entre com sua conta.' });
    const session = db.prepare(`SELECT s.user_id, s.expires, s.device, u.* FROM sessions s JOIN users u ON u.id = s.user_id
      WHERE s.token_hash = ?`).get(hashToken(token));
    if (!session || session.expires < now()) return res.status(401).json({ error: 'Sessão vencida. Entre de novo.' });
    if (!allowed(session.email)) return res.status(403).json({ error: 'Esta conta não tem mais acesso.' });
    req.user = session;
    req.tokenHash = hashToken(token);
    db.prepare('UPDATE users SET last_seen = ? WHERE id = ?').run(now(), session.user_id);
    next();
  });

  app.get('/api/me', (req, res) => res.json({ user: publicUser(req.user), rev: currentRev(req.user.user_id) }));

  app.post('/api/logout', (req, res) => {
    db.prepare('DELETE FROM sessions WHERE token_hash = ?').run(req.tokenHash);
    res.json({ ok: true });
  });

  /**
   * One round trip: the device sends what changed since it last synced and gets back everything newer than
   * `since` (in pages of PAGE: when `more` is true it asks again with the returned rev).
   */
  app.post('/api/sync', (req, res) => {
    const userId = req.user.user_id;
    const since = Number(req.body?.since) || 0;
    const device = String(req.body?.device || req.user.device || '').slice(0, 80);
    const changes = Array.isArray(req.body?.changes) ? req.body.changes : [];
    if (changes.length > 5000) return res.status(413).json({ error: 'Mudanças demais de uma vez.' });
    const problems = [];
    db.exec('BEGIN IMMEDIATE');
    try {
      const read = db.prepare('SELECT at, device FROM records WHERE user_id = ? AND kind = ? AND key = ?');
      const write = db.prepare(`INSERT INTO records (user_id, kind, key, value, deleted, at, device, rev)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (user_id, kind, key) DO UPDATE SET value = excluded.value, deleted = excluded.deleted,
          at = excluded.at, device = excluded.device, rev = excluded.rev`);
      let rev = currentRev(userId);
      for (const change of changes) {
        const kind = String(change?.kind || '');
        const key = String(change?.key || '');
        const at = Math.min(Number(change?.at) || 0, now() + 5 * 60_000);  // a clock far ahead cannot win forever
        const value = JSON.stringify(change?.value ?? null);
        if (!KINDS.has(kind) || !key || key.length > 600 || !at) { problems.push(`${kind}:${key}`); continue; }
        if (value.length > MAX_VALUE) { problems.push(`${kind}:${key} grande demais`); continue; }
        const existing = read.get(userId, kind, key);
        // Newest change wins; on a tie the device name decides, so every server and device agrees.
        if (existing && (existing.at > at || (existing.at === at && existing.device >= device))) continue;
        rev += 1;
        write.run(userId, kind, key, value, change?.deleted ? 1 : 0, at, device, rev);
      }
      db.prepare('INSERT INTO revs (user_id, rev) VALUES (?, ?) ON CONFLICT (user_id) DO UPDATE SET rev = excluded.rev')
        .run(userId, rev);
      db.exec('COMMIT');
    } catch (error) {
      db.exec('ROLLBACK');
      throw error;
    }
    const rows = db.prepare(`SELECT kind, key, value, deleted, at, device, rev FROM records
      WHERE user_id = ? AND rev > ? ORDER BY rev LIMIT ?`).all(userId, since, PAGE + 1);
    const more = rows.length > PAGE;
    const page = rows.slice(0, PAGE);
    res.json({
      rev: page.length ? page[page.length - 1].rev : Math.max(since, currentRev(userId)),
      more,
      changes: page.map((row) => ({ kind: row.kind, key: row.key, value: JSON.parse(row.value), deleted: !!row.deleted,
        at: row.at, device: row.device })),
      problems,
    });
  });

  // Pictures (playlist pictures, official covers), stored once per account by their SHA-256.
  app.put('/api/blobs/:sha', express.raw({ type: () => true, limit: MAX_BLOB }), (req, res) => {
    const sha = req.params.sha;
    if (!/^[0-9a-f]{64}$/.test(sha)) return res.status(400).json({ error: 'nome inválido' });
    const body = req.body;
    if (!Buffer.isBuffer(body) || body.length === 0) return res.status(400).json({ error: 'arquivo vazio' });
    if (createHash('sha256').update(body).digest('hex') !== sha) return res.status(400).json({ error: 'conteúdo não confere' });
    const type = /^image\/(jpeg|png|webp)$/.test(req.get('content-type') || '') ? req.get('content-type') : 'image/jpeg';
    const file = blobPath(req.user.user_id, sha);
    if (!existsSync(file)) {
      writeFileSync(`${file}.tmp`, body, { mode: 0o600 });
      renameSync(`${file}.tmp`, file);
    }
    db.prepare(`INSERT INTO blobs (user_id, sha, type, size, created) VALUES (?, ?, ?, ?, ?)
      ON CONFLICT DO NOTHING`).run(req.user.user_id, sha, type, body.length, now());
    res.json({ ok: true });
  });

  app.get('/api/blobs/:sha', (req, res) => {
    const row = db.prepare('SELECT type FROM blobs WHERE user_id = ? AND sha = ?').get(req.user.user_id, req.params.sha);
    const file = blobPath(req.user.user_id, req.params.sha);
    if (!row || !existsSync(file)) return res.status(404).json({ error: 'não encontrado' });
    res.set('Content-Type', row.type).set('Cache-Control', 'private, max-age=31536000, immutable');
    createReadStream(file).pipe(res);
  });


  // ── cloud library ────────────────────────────────────────────────────────
  app.get('/api/tracks', (req, res) => {
    const since = Number(req.query.since) || 0;
    const rows = cloud.changes(req.user.user_id, since, PAGE + 1);
    const page = rows.slice(0, PAGE);
    res.json({ rev: page.length ? page[page.length - 1].rev : since, more: rows.length > PAGE, tracks: page.map(publicTrack),
      scanning: scanning.has(req.user.user_id) });
  });

  app.post('/api/tracks/rescan', async (req, res) => {
    const result = await scanFor({ id: req.user.user_id, email: req.user.email });
    res.json({ result });
  });

  // Is this file already in the cloud? (the device hashes it first and skips the upload when it is)
  app.get('/api/tracks/sha/:sha', (req, res) => {
    const row = cloud.exists(req.user.user_id, req.params.sha);
    if (!row) return res.status(404).json({ error: 'não está na nuvem' });
    res.json({ track: publicTrack(row) });
  });

  app.post('/api/tracks/upload', async (req, res) => {
    const name = decodeURIComponent(req.get('x-filename') || 'musica.mp3');
    const row = await cloud.upload(req.user.user_id, req, name);
    res.json({ track: publicTrack(row) });
  });

  app.delete('/api/tracks/:id', (req, res) => res.json({ ok: cloud.remove(req.user.user_id, Number(req.params.id)) }));

  app.get('/api/tracks/:id/audio', (req, res) => {
    const row = cloud.track(req.user.user_id, Number(req.params.id));
    if (!row || !existsSync(row.path)) return res.status(404).json({ error: 'não encontrado' });
    res.sendFile(row.path, { headers: { 'Content-Type': row.mime, 'Cache-Control': 'private, max-age=86400' }, acceptRanges: true,
      dotfiles: 'allow' });
  });

  app.get('/api/tracks/:id/cover', (req, res) => {
    const row = cloud.track(req.user.user_id, Number(req.params.id));
    const file = row && cloud.coverPath(row.id);
    if (!file) return res.status(404).json({ error: 'sem capa' });
    res.set('Cache-Control', 'private, max-age=604800');
    res.type('image/jpeg');
    createReadStream(file).pipe(res);
  });

  // ── devices: what each one plays, and the remote control ────────────────
  app.post('/api/player/state', (req, res) => {
    const device = String(req.body?.device || '').slice(0, 80);
    if (!device) return res.status(400).json({ error: 'aparelho?' });
    const state = JSON.stringify(req.body?.state ?? null);
    if (state.length > 200_000) return res.status(413).json({ error: 'grande demais' });
    db.prepare(`INSERT INTO devices (user_id, device, name, platform, state, updated, last_seen) VALUES (?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (user_id, device) DO UPDATE SET name = excluded.name, platform = excluded.platform, state = excluded.state,
        updated = excluded.updated, last_seen = excluded.last_seen`)
      .run(req.user.user_id, device, String(req.body?.name || '').slice(0, 80), String(req.body?.platform || '').slice(0, 20),
        state, now(), now());
    notify(req.user.user_id, { type: 'state', device });
    res.json({ ok: true });
  });

  app.get('/api/player/devices', (req, res) => {
    const rows = db.prepare('SELECT * FROM devices WHERE user_id = ? ORDER BY updated DESC').all(req.user.user_id);
    res.json({ devices: rows.map((row) => ({ device: row.device, name: row.name, platform: row.platform,
      state: JSON.parse(row.state), updated: row.updated, online: now() - row.last_seen < ONLINE_MS })) });
  });

  app.post('/api/player/command', (req, res) => {
    const target = String(req.body?.target || '');
    const action = String(req.body?.action || '');
    if (!target || !ACTIONS.has(action)) return res.status(400).json({ error: 'comando inválido' });
    deliver(req.user.user_id, target, { action, args: req.body?.args ?? null, from: String(req.body?.device || ''), at: now() });
    res.json({ ok: true });
  });

  // Long poll: the device waits up to `wait` seconds for commands (being here also means "online").
  app.get('/api/player/commands', (req, res) => {
    const device = String(req.query.device || '');
    if (!device) return res.status(400).json({ error: 'aparelho?' });
    const userId = req.user.user_id;
    db.prepare(`INSERT INTO devices (user_id, device, name, platform, last_seen) VALUES (?, ?, ?, ?, ?)
      ON CONFLICT (user_id, device) DO UPDATE SET last_seen = excluded.last_seen`)
      .run(userId, device, String(req.query.name || '').slice(0, 80), String(req.query.platform || '').slice(0, 20), now());
    const box = mailbox(userId, device);
    if (box.commands.length) return res.json({ commands: box.commands.splice(0) });
    const wait = Math.min(Math.max(Number(req.query.wait) || 25, 0), 50) * 1000;
    const timer = setTimeout(() => finish([]), wait);
    function finish(commands) {
      clearTimeout(timer);
      if (box.waiter === finish) box.waiter = null;
      if (!res.headersSent) res.json({ commands });
    }
    if (box.waiter) box.waiter([]);  // an older poll from the same device ends
    box.waiter = finish;
    req.on('close', () => { if (box.waiter === finish) box.waiter = null; clearTimeout(timer); });
  });

  const mailboxes = new Map();
  function mailbox(userId, device) {
    const key = `${userId}\u0000${device}`;
    if (!mailboxes.has(key)) mailboxes.set(key, { commands: [], waiter: null });
    return mailboxes.get(key);
  }
  function deliver(userId, device, command) {
    const box = mailbox(userId, device);
    if (box.waiter) return box.waiter([command]);
    box.commands.push(command);
    if (box.commands.length > 50) box.commands.shift();
  }
  // Other devices learn that someone's state changed (to refresh the remote control screen).
  function notify(userId, event) {
    for (const [key, box] of mailboxes) {
      if (!key.startsWith(`${userId}\u0000`) || key.endsWith(`\u0000${event.device}`)) continue;
      if (box.waiter) box.waiter([{ action: 'refresh', args: event, at: now() }]);
    }
  }

  app.use((error, _req, res, _next) => {
    console.error(error);
    res.status(error.status || 500).json({ error: error.status === 413 ? 'Grande demais.' : 'Erro no servidor.' });
  });

  function blobPath(userId, sha) {
    return join(dataDir, 'blobs', `${userId}-${sha}`);
  }

  function currentRev(userId) {
    return db.prepare('SELECT rev FROM revs WHERE user_id = ?').get(userId)?.rev || 0;
  }

  function upsertUser(google) {
    // The same verified e-mail is the same person: an account made locally (admin.js) or with an older Google id is kept.
    const byEmail = db.prepare('SELECT id, google_sub FROM users WHERE email = ? AND google_sub != ?').get(google.email, google.sub);
    if (byEmail && !db.prepare('SELECT 1 FROM users WHERE google_sub = ?').get(google.sub)) {
      db.prepare('UPDATE users SET google_sub = ? WHERE id = ?').run(google.sub, byEmail.id);
    }
    db.prepare(`INSERT INTO users (google_sub, email, name, picture, created, last_seen) VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT (google_sub) DO UPDATE SET email = excluded.email, name = excluded.name, picture = excluded.picture,
        last_seen = excluded.last_seen`).run(google.sub, google.email, google.name, google.picture, now(), now());
    return db.prepare('SELECT * FROM users WHERE google_sub = ?').get(google.sub);
  }

  function createSession(userId, device) {
    const token = randomBytes(32).toString('base64url');
    db.prepare('INSERT INTO sessions (token_hash, user_id, device, created, expires) VALUES (?, ?, ?, ?, ?)')
      .run(hashToken(token), userId, device, now(), now() + SESSION_DAYS * 86_400_000);
    return token;
  }

  app.locals.createSession = createSession;
  app.locals.scanFor = scanFor;
  app.locals.cloud = cloud;
  app.locals.upsertUser = upsertUser;
  return app;
}

function publicUser(user) {
  return { email: user.email, name: user.name, picture: user.picture };
}
