import zlib from 'node:zlib';
import { promisify } from 'node:util';
import { open, readAll } from './net.js';

const gunzip = promisify(zlib.gunzip);

/**
 * The radio-browser.info adapter: fetch the corpus, clean it, hand back rows.
 *
 * A direct port of the Android app's `RadioBrowser.kt`, including both things it learned the hard
 * way — see [fetchAll] and [mirrors].
 */

export const SOURCE = 'radio-browser';

/**
 * Big enough to keep the corpus to a handful of requests, small enough to stay a sane response.
 * The whole corpus is seven of these.
 */
const PAGE_SIZE = 10_000;
const PAGE_ATTEMPTS = 3;

const KNOWN_MIRRORS = [
  'de1.api.radio-browser.info',
  'de2.api.radio-browser.info',
  'at1.api.radio-browser.info',
  'nl1.api.radio-browser.info',
];

export interface StationRow {
  uuid: string;
  name: string;
  url: string;
  tags: string;
  country: string;
  countrycode: string;
  state: string;
  language: string;
  homepage: string;
  favicon: string;
  codec: string;
  bitrate: number;
  votes: number;
  clickcount: number;
  clicktrend: number;
  lastcheckok: number;
  source: string;
}

/**
 * The API asks clients to spread load across mirrors rather than hammer one host.
 *
 * Several are returned because a mirror can simply be unreachable — mirrors are dual-stack and an
 * AAAA-only answer is a dead end on a network without IPv6 routing. Callers try them in turn.
 */
export function mirrors(): string[] {
  return [...KNOWN_MIRRORS].sort(() => Math.random() - 0.5);
}

async function getJson(url: string, timeoutMs = 120_000): Promise<unknown> {
  const { response, status } = await open(url, {
    headers: { 'Accept-Encoding': 'gzip', Accept: 'application/json' },
    timeoutMs,
  });
  if (status !== 200) {
    response.resume();
    throw new Error(`HTTP ${status} for ${url}`);
  }
  const raw = await readAll(response, 200 * 1024 * 1024);
  const encoding = String(response.headers['content-encoding'] ?? '');
  const body = encoding.includes('gzip') ? await gunzip(raw) : raw;
  return JSON.parse(body.toString('utf8'));
}

/**
 * How many stations the server thinks it has.
 *
 * This is what makes a sync checkable rather than merely finished: pulling 30,000 when the server
 * says 62,000 means the index is partial, and it should say so instead of quietly claiming success.
 */
export async function fetchExpectedCount(host: string): Promise<number | null> {
  try {
    const stats = (await getJson(`https://${host}/json/stats`, 20_000)) as { stations?: number };
    return typeof stats.stations === 'number' ? stats.stations : null;
  } catch {
    return null;
  }
}

/**
 * Pulls the whole corpus, one page at a time.
 *
 * `/json/stations` silently caps at 1000 rows with no error and no header — ask for everything and
 * you quietly get 1.6% of it while every indicator says success. Paging with explicit
 * `limit`/`offset` is the only way to actually get the corpus.
 */
export async function fetchAll(
  host: string,
  onBatch: (rows: StationRow[], total: number) => void | Promise<void>,
): Promise<number> {
  let offset = 0;
  let total = 0;

  for (;;) {
    let page: unknown[] | null = null;
    let lastError: unknown = null;

    // A page that fails is usually a stumble, not a dead server. Retrying beats throwing away four
    // good pages because page five hiccuped.
    for (let attempt = 0; attempt < PAGE_ATTEMPTS && page === null; attempt++) {
      try {
        page = (await getJson(
          `https://${host}/json/stations?limit=${PAGE_SIZE}&offset=${offset}`,
        )) as unknown[];
      } catch (error) {
        lastError = error;
        if (attempt < PAGE_ATTEMPTS - 1) await new Promise((r) => setTimeout(r, 1000 * (attempt + 1)));
      }
    }
    if (page === null) throw lastError ?? new Error(`page at offset ${offset} failed`);

    const rows = page.map(readStation).filter((row): row is StationRow => row !== null);
    total += rows.length;
    await onBatch(rows, total);

    if (page.length < PAGE_SIZE) return total; // a short page means we've reached the end
    offset += PAGE_SIZE;
  }
}

/** Tries mirrors in turn; a dead mirror shouldn't look like a dead feature. */
export async function fetchAllFromAnyMirror(
  onHost: (host: string) => void | Promise<void>,
  onBatch: (rows: StationRow[], total: number) => void | Promise<void>,
): Promise<{ host: string; total: number }> {
  let lastError: unknown = null;
  for (const host of mirrors()) {
    try {
      await onHost(host);
      const total = await fetchAll(host, onBatch);
      return { host, total };
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError ?? new Error('no radio-browser mirror could be reached');
}

function text(value: unknown): string {
  return typeof value === 'string' ? value.trim() : '';
}

function int(value: unknown): number {
  if (typeof value === 'number' && Number.isFinite(value)) return Math.trunc(value);
  if (typeof value === 'boolean') return value ? 1 : 0;
  if (typeof value === 'string') {
    const parsed = Number.parseInt(value, 10);
    return Number.isFinite(parsed) ? parsed : 0;
  }
  return 0;
}

function readStation(raw: unknown): StationRow | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const station = raw as Record<string, unknown>;

  const name = text(station.name);
  const stream = text(station.url_resolved) || text(station.url);
  if (name === '' || stream === '') return null;

  return {
    uuid: text(station.stationuuid),
    name,
    url: stream,
    tags: cleanTags(text(station.tags)),
    country: text(station.country),
    countrycode: text(station.countrycode),
    state: text(station.state),
    language: text(station.language),
    homepage: text(station.homepage),
    favicon: text(station.favicon),
    codec: text(station.codec),
    bitrate: int(station.bitrate),
    votes: int(station.votes),
    clickcount: int(station.clickcount),
    clicktrend: int(station.clicktrend),
    lastcheckok: int(station.lastcheckok) === 1 ? 1 : 0,
    source: SOURCE,
  };
}

/**
 * Tags in this corpus are a mess: duplicated, cased inconsistently, and sometimes an entire pasted
 * sentence. Anything over 32 characters or more than three words is prose, not a tag, and it would
 * pollute the co-occurrence counts badly.
 *
 * Note what this does *not* do: drop stations. Duplicates are kept deliberately — they carry
 * different tags and different stream URLs, which is extra recall and extra resilience.
 */
export function cleanTags(raw: string): string {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const piece of raw.split(',')) {
    const tag = piece.trim().toLowerCase().replace(/\s+/g, ' ');
    if (tag === '' || tag.length > 32) continue;
    if ((tag.match(/ /g)?.length ?? 0) >= 3) continue;
    if (seen.has(tag)) continue;
    seen.add(tag);
    out.push(tag);
    if (out.length >= 12) break;
  }
  return out.join(',');
}

/**
 * `POST /json/url/{uuid}` when somebody actually plays a station. This is what feeds `clickcount`
 * and `clicktrend` — the popularity signal we rank on — so it makes the commons better for the next
 * app that uses it. Two lines, and entirely fire-and-forget.
 */
export async function reportClick(uuid: string): Promise<void> {
  if (!/^[0-9a-f-]{36}$/i.test(uuid)) return;
  const host = mirrors()[0];
  try {
    const { response } = await open(`https://${host}/json/url/${uuid}`, {
      method: 'GET',
      timeoutMs: 8_000,
    });
    response.resume();
  } catch {
    // The popularity signal is a gift to the commons, not a feature. Never surface a failure.
  }
}
