// Local administration (run on the server's PC):
//   node --env-file=.env src/admin.js token <email> [device]   → a session token, to test an app without Google
//   node --env-file=.env src/admin.js users                     → accounts and their last use
import { config } from './config.js';
import { openDb } from './db.js';
import { createApp } from './app.js';

const [command, email, device = 'teste'] = process.argv.slice(2);
const db = openDb(config.dataDir);
const app = createApp({ db, dataDir: config.dataDir, verifyIdToken: async () => { throw new Error('não usado'); },
  allowedEmails: config.allowedEmails });

if (command === 'token' && email) {
  if (config.allowedEmails.length && !config.allowedEmails.includes(email.toLowerCase())) {
    console.error(`${email} não está em ALLOWED_EMAILS.`);
    process.exit(1);
  }
  const existing = db.prepare('SELECT * FROM users WHERE email = ?').get(email.toLowerCase());
  const user = existing || app.locals.upsertUser({ sub: `local:${email.toLowerCase()}`, email: email.toLowerCase(), name: email, picture: '' });
  console.log(app.locals.createSession(user.id, device));
} else if (command === 'users') {
  for (const user of db.prepare('SELECT email, name, created, last_seen FROM users ORDER BY id').all()) {
    console.log(`${user.email}\t${user.name}\túltimo uso ${new Date(user.last_seen).toISOString()}`);
  }
} else {
  console.log('uso: admin.js token <email> [aparelho] | users');
}
