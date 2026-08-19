import type http from 'node:http';
import crypto from 'node:crypto';
import { open, readAll, assertHttpUrl } from './net.js';

/**
 * Station artwork, relayed and cached.
 *
 * Directory favicons are hotlinked from wherever the station owner put them: half are plain HTTP,
 * which an HTTPS page refuses to load, and plenty are on hosts that are slow, dead, or serving a
 * 3 MB PNG as a 16-pixel icon. Passing them through one small in-memory cache means a station's
 * artwork is fetched from its origin once an hour rather than once per person per page load.
 *
 * The transparent-PNG problem — favicons drawn for a light page, which look filthy on a dark one —
 * is handled in the client's CSS, not here, exactly as the Android app does it.
 */

interface Entry {
  body: Buffer;
  contentType: string;
  fetchedAt: number;
}

const cache = new Map<string, Entry>();
const inFlight = new Map<string, Promise<Entry | null>>();

const MAX_ENTRIES = 400;
const TTL_MS = 60 * 60 * 1000;
const MAX_BYTES = 2 * 1024 * 1024;

const ALLOWED = ['image/png', 'image/jpeg', 'image/gif', 'image/webp', 'image/x-icon', 'image/vnd.microsoft.icon', 'image/svg+xml', 'image/bmp'];

export async function handleArtwork(raw: string, res: http.ServerResponse): Promise<void> {
  let url: string;
  try {
    url = assertHttpUrl(raw).toString();
  } catch {
    res.writeHead(400).end();
    return;
  }

  const key = crypto.createHash('sha1').update(url).digest('hex');
  const cached = cache.get(key);
  if (cached && Date.now() - cached.fetchedAt < TTL_MS) {
    serve(res, cached);
    return;
  }

  let pending = inFlight.get(key);
  if (!pending) {
    pending = fetchArtwork(url).finally(() => inFlight.delete(key));
    inFlight.set(key, pending);
  }

  const entry = await pending;
  if (!entry) {
    // A missing favicon is normal, not an error — the client draws its monogram instead.
    res.writeHead(404, { 'Cache-Control': 'public, max-age=3600' }).end();
    return;
  }
  serve(res, entry);
}

async function fetchArtwork(url: string): Promise<Entry | null> {
  try {
    const { response, status, headers } = await open(url, { timeoutMs: 10_000 });
    const contentType = String(headers['content-type'] ?? '').split(';')[0].trim().toLowerCase();
    if (status !== 200 || !ALLOWED.includes(contentType)) {
      response.resume();
      return null;
    }
    const body = await readAll(response, MAX_BYTES);
    if (body.length === 0) return null;

    const entry: Entry = { body, contentType, fetchedAt: Date.now() };
    const key = crypto.createHash('sha1').update(url).digest('hex');
    if (cache.size >= MAX_ENTRIES) {
      // Crude, and correct enough: drop the oldest insertion. Map iterates in insertion order.
      const oldest = cache.keys().next().value;
      if (oldest !== undefined) cache.delete(oldest);
    }
    cache.set(key, entry);
    return entry;
  } catch {
    return null;
  }
}

function serve(res: http.ServerResponse, entry: Entry): void {
  res.writeHead(200, {
    'Content-Type': entry.contentType,
    'Content-Length': entry.body.length,
    'Cache-Control': 'public, max-age=86400',
  });
  res.end(entry.body);
}
