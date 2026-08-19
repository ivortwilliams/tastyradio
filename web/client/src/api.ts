import type { Hit } from './data/types.js';

/** Everything the client asks the server for. One place, so the wire format has one owner. */

export interface SearchResponse {
  hits: Hit[];
  expansions: string[];
  indexReady: boolean;
}

export interface BrowseResponse {
  tags: string[];
  trending: Hit[];
  countries: { code: string; name: string; count: number }[];
}

export interface IndexStatus {
  ready: boolean;
  refreshing: boolean;
  lastError: string | null;
  total: number;
  bySource: { source: string; count: number }[];
  sizeBytes: number;
  builtAt: number | null;
  expected: number | null;
  complete: boolean;
}

export interface ServerConfig {
  needsCode: boolean;
  authed: boolean;
  maxChannels: number;
}

async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(path, { credentials: 'same-origin' });
  if (!response.ok) throw new Error(`${response.status}`);
  return (await response.json()) as T;
}

/**
 * Where a channel's audio actually comes from. Never the station's own URL — see the note in
 * `mixer.ts` about why every stream is relayed.
 */
export function streamUrl(target: string, sid: string, channel: string): string {
  const params = new URLSearchParams({ u: target, sid, ch: channel });
  return `/api/stream?${params.toString()}`;
}

/** Station artwork, relayed and cached, so an `http://` favicon works on an `https://` page. */
export function artworkUrl(target: string): string {
  if (target.startsWith('/')) return target; // bundled, e.g. Ophelia
  return `/api/art?u=${encodeURIComponent(target)}`;
}

export function config(): Promise<ServerConfig> {
  return getJson<ServerConfig>('/api/config');
}

export async function submitCode(code: string): Promise<boolean> {
  const response = await fetch('/api/gate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code }),
    credentials: 'same-origin',
  });
  return response.ok;
}

export function search(
  query: string,
  options: {
    httpsOnly?: boolean;
    includeUnreachable?: boolean;
    country?: string;
    codec?: string;
    minBitrate?: number;
    dropped?: string[];
  } = {},
  signal?: AbortSignal,
): Promise<SearchResponse> {
  const params = new URLSearchParams({ q: query });
  if (options.httpsOnly) params.set('https', '1');
  if (options.includeUnreachable) params.set('unreachable', '1');
  if (options.country) params.set('country', options.country);
  if (options.codec) params.set('codec', options.codec);
  if (options.minBitrate) params.set('minBitrate', String(options.minBitrate));
  if (options.dropped?.length) params.set('drop', options.dropped.join(','));

  return fetch(`/api/search?${params.toString()}`, { signal, credentials: 'same-origin' }).then(
    (response) => response.json() as Promise<SearchResponse>,
  );
}

export function browse(): Promise<BrowseResponse> {
  return getJson<BrowseResponse>('/api/browse');
}

export function browseTag(tag: string): Promise<{ hits: Hit[] }> {
  return getJson<{ hits: Hit[] }>(`/api/browse?tag=${encodeURIComponent(tag)}`);
}

export function indexStatus(): Promise<IndexStatus> {
  return getJson<IndexStatus>('/api/index-status');
}

export interface ResolveResponse {
  ok: boolean;
  reason?: string;
  url?: string;
  name?: string | null;
  contentType?: string;
  playlist?: string[];
  known?: Hit | null;
}

export function resolve(url: string): Promise<ResolveResponse> {
  return getJson<ResolveResponse>(`/api/resolve?u=${encodeURIComponent(url)}`);
}

export async function parsePlaylist(text: string): Promise<{ name: string | null; url: string }[]> {
  const response = await fetch('/api/parse-playlist', {
    method: 'POST',
    body: text,
    credentials: 'same-origin',
  });
  const parsed = (await response.json()) as { entries: { name: string | null; url: string }[] };
  return parsed.entries;
}

/** Feeds radio-browser's popularity signal. Fire and forget; a failure is never worth surfacing. */
export function reportClick(uuid: string): Promise<unknown> {
  return fetch(`/api/click/${encodeURIComponent(uuid)}`, {
    method: 'POST',
    credentials: 'same-origin',
  }).catch(() => undefined);
}

export function forgetChannel(sid: string, channel: string): Promise<unknown> {
  const params = new URLSearchParams({ sid, ch: channel });
  return fetch(`/api/forget?${params.toString()}`, { credentials: 'same-origin' }).catch(() => undefined);
}
