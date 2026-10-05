// Ayo Música sync server: listens on localhost only; Tailscale publishes it (deploy/tailscale).
import { config } from './config.js';
import { openDb } from './db.js';
import { createGoogleVerifier } from './google.js';
import { createApp } from './app.js';

if (config.allowedEmails.length === 0) {
  console.error('Defina ALLOWED_EMAILS no .env (os e-mails Google que podem entrar).');
  process.exit(1);
}
if (config.googleClientIds.length === 0) console.warn('GOOGLE_CLIENT_IDS vazio: ninguém consegue entrar com o Google ainda.');

const db = openDb(config.dataDir);
const app = createApp({
  db,
  dataDir: config.dataDir,
  verifyIdToken: createGoogleVerifier({ clientIds: config.googleClientIds }),
  allowedEmails: config.allowedEmails,
  libraryFolders: config.libraryFolders,
  publicConfig: { webClientId: config.webClientId, desktopClientId: config.desktopClientId,
    desktopClientSecret: config.desktopClientSecret },
});
app.listen(config.port, config.host, () => console.log(`Ayo Música sync em http://${config.host}:${config.port}`));

// Keep the music folders indexed: at start, then every 30 minutes (new files, changed tags, files gone).
const rescanAll = () => {
  for (const user of db.prepare('SELECT id, email FROM users').all()) app.locals.scanFor(user);
};
rescanAll();
setInterval(rescanAll, 30 * 60_000).unref();
