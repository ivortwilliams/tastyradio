import crypto from 'node:crypto';
import type http from 'node:http';
import { config } from './config.js';

/**
 * One shared password for everybody, remembered in a signed cookie.
 *
 * Deliberately not an account system — Tasty Radio has never had accounts and this shouldn't be the
 * thing that introduces them. It exists for one reason: the stream proxy relays audio to whoever
 * asks, so without a gate it is an open relay and the bandwidth is billed to the owner.
 *
 * The signature is derived from the access code itself, so changing `ACCESS_CODE` invalidates every
 * cookie already issued, and *not* changing it means a server restart doesn't log everybody out.
 */

const COOKIE = 'tr_access';

/**
 * Case and surrounding whitespace are not the point of the gate.
 *
 * This is one code shared between friends over a text message, so it arrives with a trailing space
 * from a copy-paste, or capitalised by a phone keyboard, or shouted in caps by the person who chose
 * it. Rejecting those is a support burden for no security: the code is a doorbell, not a vault, and
 * anyone who has the word at all is meant to be let in.
 */
function normalise(code: string): string {
  return String(code).trim().toLowerCase();
}

function secret(): string {
  return config.sessionSecret || `derived:${normalise(config.accessCode)}`;
}

function token(): string {
  return crypto.createHmac('sha256', secret()).update('tasty-radio-access-v1').digest('base64url');
}

export const gateEnabled = (): boolean => config.accessCode !== '';

export function parseCookies(header: string | undefined): Record<string, string> {
  const out: Record<string, string> = {};
  for (const piece of (header ?? '').split(';')) {
    const eq = piece.indexOf('=');
    if (eq < 0) continue;
    out[piece.slice(0, eq).trim()] = decodeURIComponent(piece.slice(eq + 1).trim());
  }
  return out;
}

export function isAuthed(req: http.IncomingMessage): boolean {
  if (!gateEnabled()) return true;
  const presented = parseCookies(req.headers.cookie)[COOKIE];
  if (!presented) return false;
  const expected = token();
  const a = Buffer.from(presented);
  const b = Buffer.from(expected);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

export function checkCode(code: string): boolean {
  if (!gateEnabled()) return true;
  // Hashing before comparing means the timing-safe compare always gets equal-length inputs, so a
  // guess of the wrong length is rejected in constant time rather than immediately — which would
  // otherwise leak how long the real code is.
  const a = crypto.createHash('sha256').update(normalise(code)).digest();
  const b = crypto.createHash('sha256').update(normalise(config.accessCode)).digest();
  return crypto.timingSafeEqual(a, b);
}

export function grantCookie(res: http.ServerResponse, secure: boolean): void {
  const parts = [
    `${COOKIE}=${token()}`,
    'Path=/',
    'HttpOnly',
    'SameSite=Lax',
    `Max-Age=${60 * 60 * 24 * 365}`,
  ];
  if (secure) parts.push('Secure');
  res.setHeader('Set-Cookie', parts.join('; '));
}
