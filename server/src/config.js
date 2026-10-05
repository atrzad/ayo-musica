// Settings from the environment (.env next to package.json; see .env.example).
import { homedir } from 'node:os';
import { join } from 'node:path';

const list = (value) => String(value || '').split(',').map((item) => item.trim()).filter(Boolean);

export const config = {
  port: Number(process.env.PORT || 3600),
  host: process.env.HOST || '127.0.0.1',
  dataDir: process.env.DATA_DIR || join(homedir(), '.local/share/ayo-musica-sync'),
  // OAuth client ids whose tokens are accepted: the Web one (used by the Android app) and the Desktop one.
  googleClientIds: list(process.env.GOOGLE_CLIENT_IDS),
  // Given to the apps (GET /api/config) so they need no rebuild: the Web client id (Android asks Google for a token
  // meant for it) and the Desktop client (installed-app flow; Google does not treat its secret as confidential).
  webClientId: process.env.GOOGLE_WEB_CLIENT_ID || '',
  desktopClientId: process.env.GOOGLE_DESKTOP_CLIENT_ID || '',
  desktopClientSecret: process.env.GOOGLE_DESKTOP_CLIENT_SECRET || '',
  // Who may use this server (Google e-mails). Empty: nobody but... everybody would be allowed, so it is required.
  allowedEmails: list(process.env.ALLOWED_EMAILS).map((email) => email.toLowerCase()),
  // Music folders on this PC that belong to an account, indexed in place: "email=/path;email2=/other".
  libraryFolders: Object.fromEntries(String(process.env.LIBRARY_FOLDERS || '').split(';').map((pair) => pair.split('='))
    .filter(([email, path]) => email && path).map(([email, path]) => [email.trim().toLowerCase(), path.trim()])),
};
