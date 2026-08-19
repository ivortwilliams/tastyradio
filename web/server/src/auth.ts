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

function secret(): string {
  return config.sessionSecret || `derived:${config.accessCode}`;
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
  const a = Buffer.from(String(code));
  const b = Buffer.from(config.accessCode);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
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
