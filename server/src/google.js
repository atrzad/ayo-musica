// Checks a Google ID token (from "Entrar com o Google" on the phone or the desktop) without extra libraries:
// RS256 signature against Google's published keys, then issuer, audience, expiry and a verified e-mail.
import { createPublicKey, verify } from 'node:crypto';

const CERTS_URL = 'https://www.googleapis.com/oauth2/v3/certs';
const ISSUERS = new Set(['accounts.google.com', 'https://accounts.google.com']);

export function createGoogleVerifier({ clientIds, fetchKeys = defaultFetchKeys, now = () => Date.now() }) {
  let cache = { keys: new Map(), until: 0 };

  async function keys(force = false) {
    if (!force && cache.until > now()) return cache.keys;
    const { keys: list, maxAge } = await fetchKeys();
    cache = { keys: new Map(list.map((jwk) => [jwk.kid, createPublicKey({ key: jwk, format: 'jwk' })])), until: now() + maxAge * 1000 };
    return cache.keys;
  }

  return async function verifyIdToken(token) {
    const parts = String(token || '').split('.');
    if (parts.length !== 3) throw new AuthError('token inválido');
    const [head, body, signature] = parts;
    const header = parseJson(head);
    if (header.alg !== 'RS256' || !header.kid) throw new AuthError('token inválido');
    let key = (await keys()).get(header.kid);
    if (!key) key = (await keys(true)).get(header.kid);  // Google rotated its keys
    if (!key) throw new AuthError('chave desconhecida');
    const ok = verify('RSA-SHA256', Buffer.from(`${head}.${body}`), key, Buffer.from(signature, 'base64url'));
    if (!ok) throw new AuthError('assinatura inválida');
    const claims = parseJson(body);
    const seconds = Math.floor(now() / 1000);
    if (!ISSUERS.has(claims.iss)) throw new AuthError('emissor inválido');
    if (!clientIds.includes(claims.aud)) throw new AuthError('token de outro aplicativo');
    if (typeof claims.exp !== 'number' || claims.exp < seconds - 60) throw new AuthError('token vencido');
    if (claims.email_verified !== true && claims.email_verified !== 'true') throw new AuthError('e-mail não verificado');
    if (!claims.sub || !claims.email) throw new AuthError('token incompleto');
    return { sub: claims.sub, email: String(claims.email).toLowerCase(), name: claims.name || '', picture: claims.picture || '' };
  };
}

export class AuthError extends Error {}

function parseJson(part) {
  try {
    return JSON.parse(Buffer.from(part, 'base64url').toString('utf8'));
  } catch {
    throw new AuthError('token inválido');
  }
}

async function defaultFetchKeys() {
  const response = await fetch(CERTS_URL);
  if (!response.ok) throw new Error(`Google respondeu ${response.status}`);
  const maxAge = Number(/max-age=(\d+)/.exec(response.headers.get('cache-control') || '')?.[1] || 3600);
  return { keys: (await response.json()).keys, maxAge };
}
