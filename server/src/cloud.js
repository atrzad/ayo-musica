// The music in the cloud: files uploaded from the devices and the owner's music folder on this PC (indexed in place,
// never copied). Each account sees only its own tracks. Audio is served with ranges (seeking while streaming).
import { createHash, randomBytes } from 'node:crypto';
import { createWriteStream, existsSync, mkdirSync, readdirSync, renameSync, statSync, unlinkSync,
  writeFileSync } from 'node:fs';
import { extname, join } from 'node:path';
import { pipeline } from 'node:stream/promises';
import { parseFile } from 'music-metadata';
import { songKey } from './songkey.js';

export const AUDIO = new Map([
  ['.mp3', 'audio/mpeg'], ['.flac', 'audio/flac'], ['.m4a', 'audio/mp4'], ['.mp4', 'audio/mp4'], ['.aac', 'audio/aac'],
  ['.ogg', 'audio/ogg'], ['.oga', 'audio/ogg'], ['.opus', 'audio/ogg'], ['.wav', 'audio/wav'], ['.wma', 'audio/x-ms-wma'],
]);
const MAX_UPLOAD = 400 * 1024 * 1024;

export const CLOUD_MIGRATION = `
  CREATE TABLE tracks (
    id INTEGER PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    origin TEXT NOT NULL,          -- 'upload' or 'folder'
    ident TEXT NOT NULL,           -- upload: sha256 of the file; folder: the file's path
    path TEXT NOT NULL,            -- where the file is
    size INTEGER NOT NULL,
    mtime INTEGER NOT NULL,
    mime TEXT NOT NULL,
    title TEXT NOT NULL DEFAULT '',
    artist TEXT NOT NULL DEFAULT '',
    album TEXT NOT NULL DEFAULT '',
    album_artist TEXT NOT NULL DEFAULT '',
    genre TEXT NOT NULL DEFAULT '',
    year INTEGER NOT NULL DEFAULT 0,
    track INTEGER NOT NULL DEFAULT 0,
    disc INTEGER NOT NULL DEFAULT 0,
    duration_ms INTEGER NOT NULL DEFAULT 0,
    song_key TEXT NOT NULL,
    has_cover INTEGER NOT NULL DEFAULT 0,
    added INTEGER NOT NULL,
    deleted INTEGER NOT NULL DEFAULT 0,
    rev INTEGER NOT NULL,
    UNIQUE (user_id, origin, ident));
  CREATE INDEX tracks_rev ON tracks(user_id, rev);
  CREATE INDEX tracks_key ON tracks(user_id, song_key);
  -- What each device is playing (for "continuar de onde parou" and the remote control).
  CREATE TABLE devices (
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device TEXT NOT NULL,
    name TEXT NOT NULL DEFAULT '',
    platform TEXT NOT NULL DEFAULT '',
    state TEXT NOT NULL DEFAULT 'null',
    updated INTEGER NOT NULL DEFAULT 0,
    last_seen INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, device));`;

export function createCloud({ db, dataDir, nextRev, now = () => Date.now() }) {
  const tracksDir = join(dataDir, 'tracks');
  const coversDir = join(dataDir, 'covers');
  mkdirSync(tracksDir, { recursive: true, mode: 0o700 });
  mkdirSync(coversDir, { recursive: true, mode: 0o700 });

  async function readTags(path) {
    try {
      const { common, format } = await parseFile(path, { duration: true, skipCovers: false });
      return {
        title: common.title || '', artist: common.artist || (common.artists || []).join(', '), album: common.album || '',
        album_artist: common.albumartist || '', genre: (common.genre || [])[0] || '', year: common.year || 0,
        track: common.track?.no || 0, disc: common.disk?.no || 0,
        duration_ms: Math.round((format.duration || 0) * 1000), picture: (common.picture || [])[0] || null,
      };
    } catch {
      return { title: '', artist: '', album: '', album_artist: '', genre: '', year: 0, track: 0, disc: 0, duration_ms: 0,
        picture: null };
    }
  }

  function fromFileName(path) {
    const name = path.split('/').pop().replace(/\.[^.]+$/, '').replace(/\(MP3_\d+K\)$/i, '').trim();
    const parts = name.split(/\s+[-–—]\s+/);
    return parts.length >= 2 ? { artist: parts[0], title: parts.slice(1).join(' - ') } : { artist: '', title: name };
  }

  /** Adds or updates one track; returns its row. */
  async function save(userId, origin, ident, path, stat) {
    const tags = await readTags(path);
    const guess = fromFileName(path);
    const title = tags.title || guess.title;
    const artist = tags.artist || guess.artist;
    const existing = db.prepare('SELECT id FROM tracks WHERE user_id = ? AND origin = ? AND ident = ?').get(userId, origin, ident);
    const rev = nextRev(userId);
    const values = [path, stat.size, Math.round(stat.mtimeMs), AUDIO.get(extname(path).toLowerCase()) || 'audio/mpeg', title,
      artist, tags.album, tags.album_artist, tags.genre, tags.year, tags.track, tags.disc, tags.duration_ms,
      songKey(artist, title, tags.duration_ms), tags.picture ? 1 : 0, rev];
    let id;
    if (existing) {
      db.prepare(`UPDATE tracks SET path = ?, size = ?, mtime = ?, mime = ?, title = ?, artist = ?, album = ?, album_artist = ?,
        genre = ?, year = ?, track = ?, disc = ?, duration_ms = ?, song_key = ?, has_cover = ?, rev = ?, deleted = 0
        WHERE id = ?`).run(...values, existing.id);
      id = existing.id;
    } else {
      id = Number(db.prepare(`INSERT INTO tracks (path, size, mtime, mime, title, artist, album, album_artist, genre, year,
        track, disc, duration_ms, song_key, has_cover, rev, user_id, origin, ident, added)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`).run(...values, userId, origin, ident, now()).lastInsertRowid);
    }
    if (tags.picture) writeFileSync(join(coversDir, `${id}`), tags.picture.data, { mode: 0o600 });
    return db.prepare('SELECT * FROM tracks WHERE id = ?').get(id);
  }

  /** Indexes a folder for an account: new and changed files are read, files gone are marked deleted. */
  async function scanFolder(userId, folder) {
    const seen = new Set();
    const known = new Map(db.prepare(`SELECT ident, size, mtime FROM tracks WHERE user_id = ? AND origin = 'folder' AND deleted = 0`)
      .all(userId).map((row) => [row.ident, row]));
    let added = 0;
    const walk = async (dir) => {
      let entries;
      try { entries = readdirSync(dir, { withFileTypes: true }); } catch { return; }
      for (const entry of entries) {
        if (entry.name.startsWith('.')) continue;
        const path = join(dir, entry.name);
        if (entry.isDirectory()) { await walk(path); continue; }
        if (!entry.isFile() || !AUDIO.has(extname(entry.name).toLowerCase())) continue;
        seen.add(path);
        const stat = statSync(path);
        const old = known.get(path);
        if (old && old.size === stat.size && old.mtime === Math.round(stat.mtimeMs)) continue;
        await save(userId, 'folder', path, path, stat);
        added += 1;
      }
    };
    await walk(folder);
    let removed = 0;
    for (const ident of known.keys()) {
      if (seen.has(ident)) continue;
      db.prepare(`UPDATE tracks SET deleted = 1, rev = ? WHERE user_id = ? AND origin = 'folder' AND ident = ?`)
        .run(nextRev(userId), userId, ident);
      removed += 1;
    }
    return { added, removed, total: seen.size };
  }

  /** An upload from a device: streamed to disk, deduplicated by SHA-256. */
  async function upload(userId, request, fileName) {
    const ext = AUDIO.has(extname(fileName).toLowerCase()) ? extname(fileName).toLowerCase() : '.mp3';
    const temp = join(tracksDir, `.up-${randomBytes(8).toString('hex')}`);
    const hash = createHash('sha256');
    let size = 0;
    try {
      await pipeline(request, async function* (source) {
        for await (const chunk of source) {
          size += chunk.length;
          if (size > MAX_UPLOAD) throw Object.assign(new Error('grande demais'), { status: 413 });
          hash.update(chunk);
          yield chunk;
        }
      }, createWriteStream(temp, { mode: 0o600 }));
    } catch (error) {
      if (existsSync(temp)) unlinkSync(temp);
      throw error;
    }
    const sha = hash.digest('hex');
    const existing = db.prepare(`SELECT * FROM tracks WHERE user_id = ? AND origin = 'upload' AND ident = ? AND deleted = 0`)
      .get(userId, sha);
    if (existing) { unlinkSync(temp); return existing; }
    const target = join(tracksDir, `${userId}-${sha}${ext}`);
    renameSync(temp, target);
    return save(userId, 'upload', sha, target, statSync(target));
  }

  function exists(userId, sha) {
    return db.prepare(`SELECT * FROM tracks WHERE user_id = ? AND origin = 'upload' AND ident = ? AND deleted = 0`).get(userId, sha);
  }

  function remove(userId, id) {
    const row = db.prepare('SELECT * FROM tracks WHERE id = ? AND user_id = ?').get(id, userId);
    if (!row || row.deleted) return false;
    db.prepare('UPDATE tracks SET deleted = 1, rev = ? WHERE id = ?').run(nextRev(userId), id);
    if (row.origin === 'upload' && existsSync(row.path)) unlinkSync(row.path);  // the folder's files are never touched
    return true;
  }

  function changes(userId, since, limit) {
    return db.prepare('SELECT * FROM tracks WHERE user_id = ? AND rev > ? ORDER BY rev LIMIT ?').all(userId, since, limit);
  }

  function track(userId, id) {
    return db.prepare('SELECT * FROM tracks WHERE id = ? AND user_id = ? AND deleted = 0').get(id, userId);
  }

  function coverPath(id) {
    const file = join(coversDir, `${id}`);
    return existsSync(file) ? file : null;
  }

  return { scanFolder, upload, exists, remove, changes, track, coverPath };
}

/** A track as the apps see it (no paths). */
export function publicTrack(row) {
  return {
    id: row.id, title: row.title, artist: row.artist, album: row.album, albumArtist: row.album_artist, genre: row.genre,
    year: row.year, track: row.track, disc: row.disc, durationMs: row.duration_ms, size: row.size, mime: row.mime,
    songKey: row.song_key, cover: !!row.has_cover, origin: row.origin, deleted: !!row.deleted, added: row.added,
  };
}
