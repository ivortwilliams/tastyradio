import { DEFAULT_DELAY_MS, FLAT, type Tone } from '../audio/graph.js';
import type { Station } from './types.js';

/**
 * A mix, in a link.
 *
 * The whole point of this app is that two unrelated stations become a third thing — and a third
 * thing you can't hand to anybody is half a feature. This is that handing-over, and it is the same
 * format on the phone: `android/app/src/main/java/com/tastyradio/share/MixLink.kt` writes and reads
 * exactly these bytes. A link made on a phone opens on the web and the other way round.
 *
 * ## The shape
 *
 * ```
 * https://radio.truthseekersbyo.com/m#<payload>
 * ```
 *
 * **The payload is in the fragment, deliberately.** Fragments are never sent to a server, so a mix
 * you send someone is between the two of you — no row in a database, no id to look up, nothing in
 * an access log. It is also why there is no expiry and nothing to keep alive: the link *is* the
 * mix, so it works for as long as the stations do.
 *
 * **The path is `/m` so Android can claim it.** An App Link filter matches on host and path, never
 * on the fragment, and claiming the whole domain would mean tapping any link to the site opened the
 * app instead of the site. `/m` is the one path the phone takes.
 *
 * ## The short one
 *
 * ```
 * https://radio.truthseekersbyo.com/s/<id>#<key>
 * ```
 *
 * 67 characters instead of 376, because a link people won't paste is a link that doesn't work.
 * `shortMixLink` encrypts the payload below, posts the ciphertext, and keeps the key in the
 * fragment — so the server holds a blob it cannot open and the privacy above survives intact. It
 * falls back to the long link whenever the server isn't there, which is the whole reason the long
 * link stays: it needs nothing and nobody.
 *
 * ## The payload
 * A marker character, then base64url:
 *
 * - `d` — raw-deflated JSON, which is what both platforms write.
 * - `j` — the same JSON uncompressed, for a browser without `CompressionStream`.
 *
 * Compression is worth the twenty lines: base64 inflates by a third, stream URLs are long, and a
 * four-channel mix pasted into a chat app is the difference between one line and a paragraph.
 *
 * Keys are short and defaults are omitted for the same reason. `v` is the format version; a reader
 * that meets a version it doesn't know refuses rather than guessing.
 */

/** The path the phone claims. Everything after the `#` is the mix. */
export const SHARE_PATH = '/m';

/** The short form: `/s/<id>#<key>`. The phone claims this too, from the build that added it. */
export const SHORT_PATH = '/s';

/** The site links point at, regardless of where the page making them is served from. */
const SHARE_ORIGIN = 'https://radio.truthseekersbyo.com';

export interface SharedChannel {
  /** Always `id: ''` — a shared mix carries stations, not rows in someone else's collection. */
  station: Station;
  fader: number;
  muted: boolean;
  tone: Tone;
}

export interface SharedMix {
  name: string;
  channels: SharedChannel[];
}

/** What a channel looks like on the wire. Short keys, defaults omitted. */
interface WireChannel {
  u: string;
  n: string;
  i?: string;
  f: number;
  m?: 1;
  lo?: number;
  md?: number;
  hi?: number;
  rv?: number;
  dl?: number;
  dm?: number;
  /** Provenance, so a station kept from a shared mix can still feed radio-browser's click count. */
  id?: string;
  tg?: string;
}

interface Wire {
  v: 1;
  n: string;
  c: WireChannel[];
}

/** Anything with a fader and a tone. Both a live desk channel and a saved mix channel fit. */
export interface Shareable {
  station: Station;
  fader: number;
  muted: boolean;
  tone: Tone;
}

export async function encodeMix(name: string, channels: Shareable[]): Promise<string> {
  const wire: Wire = {
    v: 1,
    n: name.trim().slice(0, 120),
    c: channels.map(toWire),
  };
  const json = JSON.stringify(wire);
  const packed = await deflate(json);
  return packed === null ? `j${base64url(new TextEncoder().encode(json))}` : `d${base64url(packed)}`;
}

/** The link to hand somebody. */
export async function mixLink(name: string, channels: Shareable[]): Promise<string> {
  return `${SHARE_ORIGIN}${SHARE_PATH}#${await encodeMix(name, channels)}`;
}

export async function decodeMix(payload: string): Promise<SharedMix | null> {
  const trimmed = payload.trim().replace(/^#/, '');
  if (trimmed.length < 2) return null;

  const marker = trimmed[0];
  const bytes = unbase64url(trimmed.slice(1));
  if (bytes === null) return null;

  let json: string | null = null;
  if (marker === 'd') json = await inflate(bytes);
  else if (marker === 'j') json = new TextDecoder().decode(bytes);
  if (json === null) return null;

  try {
    return fromWire(JSON.parse(json) as Wire);
  } catch {
    return null;
  }
}

/**
 * The mix in a URL, if there is one — from a full link, or from the address bar of this page.
 *
 * Accepts a bare payload too, so pasting the tail of a link that a chat app mangled still works.
 */
export function payloadIn(href: string): string | null {
  const hash = href.includes('#') ? href.slice(href.indexOf('#') + 1) : '';
  if (hash === '') return null;
  // The tab hashes (`#mixes`, `#search`) live in the same place and are not payloads.
  if (/^[a-z]+$/.test(hash)) return null;
  return hash;
}

// ---------------------------------------------------------------- the short form

/**
 * The same mix, in about 67 characters: `https://radio.truthseekersbyo.com/s/<id>#<key>`.
 *
 * A two-channel mix is 376 characters in a `/m#…` link and a four-channel one is well past that.
 * Pasted into a chat that is a wall of base64, and a wall of base64 is not a thing people send each
 * other. So the payload goes to the server and the link carries the id.
 *
 * **What the server gets is ciphertext.** The key is generated here, used here, and put in the
 * fragment — the one part of a URL that is never sent to anybody — so the row on the droplet is
 * 300 bytes of noise to everyone including us, and the property the long link was built around
 * survives the shortening. Whoever you send the link to has the key; the server never does.
 *
 * Anything that goes wrong — an old browser without `crypto.subtle`, a server that isn't there, a
 * flaky connection — falls back to the long link, which needs nobody's help to work. A share button
 * that fails is worse than a share button that produces a long link.
 */
export async function shortMixLink(name: string, channels: Shareable[]): Promise<string> {
  const payload = await encodeMix(name, channels);
  return (await shorten(payload)) ?? `${SHARE_ORIGIN}${SHARE_PATH}#${payload}`;
}

async function shorten(payload: string): Promise<string | null> {
  const subtle = globalThis.crypto?.subtle;
  if (!subtle) return null;
  try {
    const secret = crypto.getRandomValues(new Uint8Array(16));
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const key = await subtle.importKey('raw', secret, 'AES-GCM', false, ['encrypt']);
    const sealed = new Uint8Array(
      await subtle.encrypt({ name: 'AES-GCM', iv }, key, new TextEncoder().encode(payload)),
    );

    const blob = new Uint8Array(iv.length + sealed.length);
    blob.set(iv);
    blob.set(sealed, iv.length);

    const response = await fetch('/api/mix', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ b: base64url(blob) }),
    });
    if (!response.ok) return null;
    const { id } = (await response.json()) as { id?: string };
    if (typeof id !== 'string' || id === '') return null;

    return `${SHARE_ORIGIN}${SHORT_PATH}/${id}#${base64url(secret)}`;
  } catch {
    return null;
  }
}

/**
 * The id and key out of a short link — this page's own address, or a link pasted into a box.
 *
 * A regex rather than `new URL`, for the same reason `MixLink.fromText` uses one: links arrive
 * wrapped in "sent from…" and typed-around, and the useful part is still in there.
 */
export function shortLinkIn(href: string): { id: string; key: string } | null {
  const match = /\/s\/([A-Za-z0-9_-]{4,32})#([A-Za-z0-9_-]{20,64})/.exec(href);
  return match ? { id: match[1], key: match[2] } : null;
}

/** Fetches a short-linked mix and opens it with the key from the fragment. */
export async function fetchShortMix(id: string, key: string): Promise<SharedMix | null> {
  const subtle = globalThis.crypto?.subtle;
  if (!subtle) return null;
  try {
    const response = await fetch(`/api/mix/${encodeURIComponent(id)}`);
    if (!response.ok) return null;
    const { b } = (await response.json()) as { b?: string };
    const stored = unbase64url(String(b ?? ''));
    const raw = unbase64url(key);
    if (stored === null || raw === null || stored.length < 13) return null;

    // Copied into arrays of their own: `atob` hands back a view whose buffer TypeScript will not
    // promise isn't shared, and WebCrypto only takes the plain kind.
    const blob = new Uint8Array(stored);
    const secret = new Uint8Array(raw);

    const material = await subtle.importKey('raw', secret, 'AES-GCM', false, ['decrypt']);
    const opened = await subtle.decrypt(
      { name: 'AES-GCM', iv: blob.slice(0, 12) },
      material,
      blob.slice(12),
    );
    return await decodeMix(new TextDecoder().decode(opened));
  } catch {
    return null;
  }
}

function toWire(channel: Shareable): WireChannel {
  const wire: WireChannel = {
    u: channel.station.streamUrl,
    n: channel.station.name.slice(0, 120),
    f: round(channel.fader),
  };
  // A phone's own artwork is a `content://` URI from its photo picker and means nothing anywhere
  // else; a data: URI would dwarf the rest of the link. Only a real fetchable image travels.
  const image = channel.station.imageUrl;
  if (image && /^https?:\/\//i.test(image) && image.length <= 300) wire.i = image;
  if (channel.station.sourceUuid) wire.id = channel.station.sourceUuid;
  if (channel.station.tags) wire.tg = channel.station.tags.slice(0, 200);

  if (channel.muted) wire.m = 1;
  const tone = channel.tone;
  if (tone.low !== 0) wire.lo = round(tone.low);
  if (tone.mid !== 0) wire.md = round(tone.mid);
  if (tone.high !== 0) wire.hi = round(tone.high);
  if (tone.reverb !== 0) wire.rv = round(tone.reverb);
  if (tone.delay !== 0) wire.dl = round(tone.delay);
  if (tone.delay !== 0 && tone.delayMs !== DEFAULT_DELAY_MS) wire.dm = Math.round(tone.delayMs);
  return wire;
}

function fromWire(wire: Wire): SharedMix | null {
  if (!wire || wire.v !== 1 || !Array.isArray(wire.c)) return null;

  const channels: SharedChannel[] = [];
  for (const entry of wire.c) {
    if (typeof entry?.u !== 'string' || !/^https?:\/\//i.test(entry.u)) continue;
    channels.push({
      station: {
        id: '',
        name: (entry.n || entry.u).slice(0, 120),
        streamUrl: entry.u,
        imageUrl: typeof entry.i === 'string' ? entry.i : undefined,
        sortOrder: 0,
        sourceUuid: typeof entry.id === 'string' ? entry.id : undefined,
        source: 'shared',
        tags: typeof entry.tg === 'string' ? entry.tg : undefined,
      },
      fader: clamp(num(entry.f, 1), 0, 1),
      muted: entry.m === 1,
      tone: {
        ...FLAT,
        low: clamp(num(entry.lo, 0), -1, 1),
        mid: clamp(num(entry.md, 0), -1, 1),
        high: clamp(num(entry.hi, 0), -1, 1),
        reverb: clamp(num(entry.rv, 0), 0, 1),
        delay: clamp(num(entry.dl, 0), 0, 1),
        delayMs: clamp(num(entry.dm, DEFAULT_DELAY_MS), 60, 1500),
      },
    });
  }
  if (channels.length === 0) return null;

  return { name: (typeof wire.n === 'string' && wire.n.trim()) || 'A shared mix', channels };
}

function num(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

/** Three decimals is finer than any fader you can move, and shorter than a float's full print. */
function round(value: number): number {
  return Math.round(value * 1000) / 1000;
}

// ---------------------------------------------------------------- bytes

function base64url(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function unbase64url(text: string): Uint8Array | null {
  try {
    const padded = text.replace(/-/g, '+').replace(/_/g, '/');
    const binary = atob(padded + '='.repeat((4 - (padded.length % 4)) % 4));
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
  } catch {
    return null;
  }
}

/** `deflate-raw` and not `deflate`, because that is what `Deflater(level, nowrap = true)` writes. */
async function deflate(text: string): Promise<Uint8Array | null> {
  if (typeof CompressionStream === 'undefined') return null;
  try {
    const stream = new Blob([text]).stream().pipeThrough(new CompressionStream('deflate-raw'));
    return new Uint8Array(await new Response(stream).arrayBuffer());
  } catch {
    return null;
  }
}

async function inflate(bytes: Uint8Array): Promise<string | null> {
  if (typeof DecompressionStream === 'undefined') return null;
  try {
    const stream = new Blob([bytes as BlobPart]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
    return await new Response(stream).text();
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------- handing it over

/**
 * The share sheet where there is one, the clipboard where there isn't.
 *
 * `navigator.share` is a phone and a Mac; a desktop Chrome on Windows mostly isn't, and falling
 * back to "copied" is better than a button that does nothing.
 */
export async function handOver(name: string, link: string): Promise<'shared' | 'copied' | 'failed'> {
  if (navigator.share) {
    try {
      await navigator.share({ title: `${name} — Tasty Radio`, text: `${name} — a Tasty Radio mix`, url: link });
      return 'shared';
    } catch (error) {
      // A cancelled share sheet is not a failure, and must not then paste over their clipboard.
      if ((error as Error)?.name === 'AbortError') return 'shared';
    }
  }
  try {
    await navigator.clipboard.writeText(link);
    return 'copied';
  } catch {
    return 'failed';
  }
}
