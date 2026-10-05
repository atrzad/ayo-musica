// SQLite (node:sqlite): accounts, sessions and the synced records. One file in DATA_DIR.
import { DatabaseSync } from 'node:sqlite';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { CLOUD_MIGRATION } from './cloud.js';

const MIGRATIONS = [
  `CREATE TABLE users (
     id INTEGER PRIMARY KEY,
     google_sub TEXT UNIQUE NOT NULL,
     email TEXT NOT NULL,
     name TEXT NOT NULL DEFAULT '',
     picture TEXT NOT NULL DEFAULT '',
     created INTEGER NOT NULL,
     last_seen INTEGER NOT NULL);
   CREATE TABLE sessions (
     token_hash TEXT PRIMARY KEY,
     user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
     device TEXT NOT NULL DEFAULT '',
     created INTEGER NOT NULL,
     expires INTEGER NOT NULL);
   CREATE INDEX sessions_user ON sessions(user_id);
   -- One row per synced thing (a playlist, a song's flags, one device's play counts...). The newest "at" (the
   -- device's clock when it changed) wins; "rev" orders the changes so each device asks only for what is new.
   CREATE TABLE records (
     user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
     kind TEXT NOT NULL,
     key TEXT NOT NULL,
     value TEXT NOT NULL DEFAULT 'null',
     deleted INTEGER NOT NULL DEFAULT 0,
     at INTEGER NOT NULL,
     device TEXT NOT NULL DEFAULT '',
     rev INTEGER NOT NULL,
     PRIMARY KEY (user_id, kind, key));
   CREATE INDEX records_rev ON records(user_id, rev);
   CREATE TABLE revs (user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE, rev INTEGER NOT NULL);
   -- Files the records point to (playlist pictures, official covers), by their SHA-256.
   CREATE TABLE blobs (
     user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
     sha TEXT NOT NULL,
     type TEXT NOT NULL,
     size INTEGER NOT NULL,
     created INTEGER NOT NULL,
     PRIMARY KEY (user_id, sha));`,
  CLOUD_MIGRATION,
];

export function openDb(dataDir) {
  mkdirSync(dataDir, { recursive: true, mode: 0o700 });
  mkdirSync(join(dataDir, 'blobs'), { recursive: true, mode: 0o700 });
  const db = new DatabaseSync(join(dataDir, 'sync.sqlite3'));
  db.exec('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;');
  db.exec('CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)');
  const row = db.prepare('SELECT version FROM schema_version').get();
  let version = row ? row.version : 0;
  if (!row) db.prepare('INSERT INTO schema_version (version) VALUES (0)').run();
  while (version < MIGRATIONS.length) {
    db.exec('BEGIN');
    try {
      db.exec(MIGRATIONS[version]);
      version += 1;
      db.prepare('UPDATE schema_version SET version = ?').run(version);
      db.exec('COMMIT');
    } catch (error) {
      db.exec('ROLLBACK');
      throw error;
    }
  }
  return db;
}
