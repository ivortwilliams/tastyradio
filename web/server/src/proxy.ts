import type http from 'node:http';
import { config } from './config.js';
import { open, readAll, assertHttpUrl, BlockedUrlError } from './net.js';
import { publishNowPlaying } from './events.js';

/**
 * The stream proxy — the piece that makes a browser mixing desk possible at all.
 *
 * On Android the mixer just points four ExoPlayers at four URLs. In a browser it cannot: to put a
 * stream through Web Audio (which is what per-channel EQ, reverb and recording *are*) the audio has
 * to reach a `MediaElementAudioSourceNode` untainted, and a cross-origin stream without CORS
 * headers — which is essentially all of them — taints it and yields silence. A page on HTTPS also
 * can't touch the `http://` URLs a large share of the corpus uses.
 *
 * So every stream is relayed through here, same-origin. Four other messes come out in the wash:
 *
 * - **ICY metadata** is parsed server-side and pushed to the browser over SSE, which is how the web
 *   version gets the same "now playing" line the Android app reads off the stream.
 * - **Playlists served where audio was expected** (`.m3u`, `.pls`) are resolved before the browser
 *   ever sees them.
 * - **HLS** playlists are rewritten so their segments come back through here too.
 * - **Redirects**, including cross-protocol ones, are followed by hand.
 */

let activeStreams = 0;

const PLAYLIST_CONTENT_TYPES = [
  'audio/x-mpegurl',
  'application/x-mpegurl',
  'audio/mpegurl',
  'application/vnd.apple.mpegurl',
  'audio/x-scpls',
  'application/pls+xml',
];

function looksLikePlaylist(url: string, contentType: string): boolean {
  const type = contentType.toLowerCase().split(';')[0].trim();
  if (PLAYLIST_CONTENT_TYPES.includes(type)) return true;
  const path = (() => {
    try {
      return new URL(url).pathname.toLowerCase();
    } catch {
      return '';
    }
  })();
  return path.endsWith('.m3u') || path.endsWith('.m3u8') || path.endsWith('.pls');
}

function isHls(body: string): boolean {
  return body.includes('#EXT-X-') || body.includes('#EXTINF');
}

/**
 * What a browser will actually accept, from what the station said.
 *
 * Shoutcast and Icecast hand out content types no browser has ever recognised. `audio/aacp` is the
 * worst of them — it is ordinary ADTS AAC, and Chrome refuses it outright with "no supported source
 * was found" purely because of the label. RadCap, one of the owner's own stations, is served this
 * way, so this is not a hypothetical.
 *
 * Relabelling is safe in a way that transcoding would not be: the bytes are untouched, and only the
 * name we put on them changes.
 */
function normaliseContentType(raw: string): string {
  const type = raw.toLowerCase().split(';')[0].trim();
  switch (type) {
    case 'audio/aacp':
    case 'audio/x-aac':
    case 'audio/aac':
      return 'audio/aac';
    case 'audio/mp3':
    case 'audio/x-mpeg':
    case 'audio/mpg':
      return 'audio/mpeg';
    case 'audio/ogg':
    case 'application/ogg':
      return 'audio/ogg';
    case 'audio/flac':
    case 'audio/x-flac':
      return 'audio/flac';
    case '':
    case 'application/octet-stream':
    case 'text/plain':
      // Unlabelled is nearly always MP3 in this corpus, and the browser sniffs from the bitstream
      // anyway once it has a type it is willing to try.
      return 'audio/mpeg';
    default:
      return type;
  }
}

/** First playable URL out of an M3U or PLS. Both formats are line-based and forgiving. */
export function firstUrlFromPlaylist(body: string): string | null {
  for (const rawLine of body.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (line === '' || line.startsWith('#')) continue;
    // PLS: File1=http://…
    const pls = line.match(/^File\d*\s*=\s*(.+)$/i);
    const candidate = pls ? pls[1].trim() : line;
    if (/^https?:\/\//i.test(candidate)) return candidate;
  }
  return null;
}

/**
 * Rewrites an HLS playlist so every URI in it comes back through this proxy.
 *
 * Without this the browser would fetch segments straight from the origin — cross-origin, often over
 * plain HTTP — and we would be back to a tainted graph and blocked mixed content.
 */
function rewriteHls(body: string, baseUrl: string, proxyPath: string): string {
  const absolute = (uri: string) => {
    try {
      return `${proxyPath}?u=${encodeURIComponent(new URL(uri, baseUrl).toString())}`;
    } catch {
      return uri;
    }
  };

  return body
    .split(/\r?\n/)
    .map((line) => {
      const trimmed = line.trim();
      if (trimmed === '') return line;
      if (trimmed.startsWith('#')) {
        // Keys and init segments hide their URLs in an attribute rather than on their own line.
        return line.replace(/URI="([^"]+)"/g, (_match, uri: string) => `URI="${absolute(uri)}"`);
      }
      return absolute(trimmed);
    })
    .join('\n');
}

/**
 * ICY metadata is interleaved into the audio: `metaint` bytes of audio, one length byte, then that
 * many sixteen-byte blocks of text, repeating. Browsers cannot decode a stream with it embedded, so
 * it is stripped here and the title published separately.
 */
class IcyStripper {
  private remainingAudio: number;
  private metaRemaining = 0;
  private metaChunks: Buffer[] = [];
  private readingLength = false;

  constructor(
    private readonly metaint: number,
    private readonly onTitle: (title: string) => void,
  ) {
    this.remainingAudio = metaint;
  }

  push(chunk: Buffer, emit: (audio: Buffer) => void): void {
    let offset = 0;
    while (offset < chunk.length) {
      if (this.remainingAudio > 0) {
        const take = Math.min(this.remainingAudio, chunk.length - offset);
        emit(chunk.subarray(offset, offset + take));
        offset += take;
        this.remainingAudio -= take;
        if (this.remainingAudio === 0) this.readingLength = true;
        continue;
      }

      if (this.readingLength) {
        this.metaRemaining = chunk[offset] * 16;
        offset += 1;
        this.readingLength = false;
        this.metaChunks = [];
        if (this.metaRemaining === 0) this.remainingAudio = this.metaint;
        continue;
      }

      const take = Math.min(this.metaRemaining, chunk.length - offset);
      this.metaChunks.push(chunk.subarray(offset, offset + take));
      offset += take;
      this.metaRemaining -= take;
      if (this.metaRemaining === 0) {
        this.flushMetadata();
        this.remainingAudio = this.metaint;
      }
    }
  }

  private flushMetadata(): void {
    const text = Buffer.concat(this.metaChunks).toString('utf8').replace(/\0+$/, '');
    this.metaChunks = [];
    const match = text.match(/StreamTitle='(.*?)';/s) ?? text.match(/StreamTitle='(.*)$/s);
    if (!match) return;
    const title = match[1].trim();
    if (title !== '') this.onTitle(title);
  }
}

export interface StreamRequest {
  url: string;
  /** Browser session, so now-playing can be routed back to the right tab. */
  sid?: string;
  /** Which channel of that tab's mix. Absent for HLS segments, which carry no metadata. */
  channel?: string;
}

export async function handleStream(
  request: StreamRequest,
  res: http.ServerResponse,
  proxyPath = '/api/stream',
): Promise<void> {
  if (activeStreams >= config.maxConcurrentStreams) {
    res.writeHead(503, { 'Content-Type': 'text/plain' }).end('too many streams open');
    return;
  }

  let target: string;
  try {
    target = assertHttpUrl(request.url).toString();
  } catch (error) {
    res.writeHead(400, { 'Content-Type': 'text/plain' }).end(String((error as Error).message));
    return;
  }

  const wantsMetadata = Boolean(request.channel);
  const controller = new AbortController();
  res.on('close', () => controller.abort());

  // A playlist can point at another playlist. Three hops is generous; a loop is a broken station.
  for (let hop = 0; hop < 3; hop++) {
    let opened;
    try {
      opened = await open(target, {
        headers: wantsMetadata ? { 'Icy-MetaData': '1' } : {},
        timeoutMs: 20_000,
        signal: controller.signal,
      });
    } catch (error) {
      if (controller.signal.aborted) return;
      const blocked = error instanceof BlockedUrlError;
      if (!res.headersSent) {
        res
          .writeHead(blocked ? 403 : 502, { 'Content-Type': 'text/plain' })
          .end(blocked ? 'refused' : `upstream failed: ${(error as Error).message}`);
      }
      return;
    }

    const { response, finalUrl, status, headers } = opened;
    if (status !== 200) {
      response.resume();
      if (!res.headersSent) {
        res.writeHead(502, { 'Content-Type': 'text/plain' }).end(`upstream returned ${status}`);
      }
      return;
    }

    const contentType = String(headers['content-type'] ?? '');

    if (looksLikePlaylist(finalUrl, contentType)) {
      let body: string;
      try {
        body = (await readAll(response, 2 * 1024 * 1024)).toString('utf8');
      } catch {
        if (!res.headersSent) res.writeHead(502).end('playlist too large');
        return;
      }

      if (isHls(body)) {
        // HLS is handed to the browser as HLS — hls.js on the far side turns it back into audio.
        res.writeHead(200, {
          'Content-Type': 'application/vnd.apple.mpegurl',
          'Cache-Control': 'no-store',
        });
        res.end(rewriteHls(body, finalUrl, proxyPath));
        return;
      }

      const next = firstUrlFromPlaylist(body);
      if (!next) {
        if (!res.headersSent) res.writeHead(502).end('playlist contained no stream');
        return;
      }
      target = next;
      continue;
    }

    // A real audio stream. Relay it.
    activeStreams++;
    const done = () => {
      activeStreams--;
    };

    const metaint = Number(headers['icy-metaint'] ?? 0);
    const icyName = String(headers['icy-name'] ?? '');

    res.writeHead(200, {
      'Content-Type': normaliseContentType(contentType),
      'Cache-Control': 'no-store, no-cache',
      'Accept-Ranges': 'none',
      Connection: 'close',
      ...(icyName ? { 'X-Icy-Name': encodeURIComponent(icyName) } : {}),
    });

    if (metaint > 0 && request.sid && request.channel) {
      const stripper = new IcyStripper(metaint, (title) => {
        publishNowPlaying(request.sid!, request.channel!, title);
      });
      response.on('data', (chunk: Buffer) => {
        stripper.push(chunk, (audio) => {
          if (!res.write(audio)) response.pause();
        });
      });
      res.on('drain', () => response.resume());
    } else {
      response.pipe(res);
    }

    response.on('end', () => {
      done();
      res.end();
    });
    response.on('error', () => {
      done();
      res.destroy();
    });
    res.on('close', () => {
      done();
      response.destroy();
    });
    return;
  }

  if (!res.headersSent) res.writeHead(502).end('playlist redirect loop');
}

/**
 * Resolves a URL a person typed or a playlist they imported, without playing it — what the station
 * name is, whether it is actually audio, where it ends up after redirects.
 */
export async function resolveStation(
  raw: string,
): Promise<{ url: string; name: string | null; contentType: string; playlist: string[] } | null> {
  let target = assertHttpUrl(raw).toString();

  for (let hop = 0; hop < 3; hop++) {
    const { response, finalUrl, status, headers } = await open(target, {
      headers: { 'Icy-MetaData': '1' },
      timeoutMs: 15_000,
    });
    if (status !== 200) {
      response.resume();
      return null;
    }
    const contentType = String(headers['content-type'] ?? '');

    if (looksLikePlaylist(finalUrl, contentType)) {
      const body = (await readAll(response, 2 * 1024 * 1024)).toString('utf8');
      if (isHls(body)) {
        return { url: finalUrl, name: null, contentType, playlist: [] };
      }
      // An imported .m3u can hold a whole station list, which is the point of M3U import.
      const entries = parsePlaylistEntries(body);
      if (entries.length > 1) {
        return { url: finalUrl, name: null, contentType, playlist: entries.map((e) => e.url) };
      }
      const next = firstUrlFromPlaylist(body);
      if (!next) return null;
      target = next;
      continue;
    }

    response.destroy(); // we only wanted the headers
    const icyName = String(headers['icy-name'] ?? '').trim();
    return {
      url: finalUrl,
      name: icyName === '' || icyName === '-' ? null : icyName,
      contentType,
      playlist: [],
    };
  }
  return null;
}

/** Every entry in an M3U/PLS, with `#EXTINF` names where they exist. Used by playlist import. */
export function parsePlaylistEntries(body: string): { name: string | null; url: string }[] {
  const out: { name: string | null; url: string }[] = [];
  let pendingName: string | null = null;
  const plsTitles = new Map<string, string>();

  for (const rawLine of body.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (line === '') continue;

    const title = line.match(/^Title(\d+)\s*=\s*(.+)$/i);
    if (title) {
      plsTitles.set(title[1], title[2].trim());
      continue;
    }
    const file = line.match(/^File(\d+)\s*=\s*(.+)$/i);
    if (file) {
      out.push({ name: plsTitles.get(file[1]) ?? null, url: file[2].trim() });
      continue;
    }
    if (line.startsWith('#EXTINF')) {
      const comma = line.indexOf(',');
      pendingName = comma >= 0 ? line.slice(comma + 1).trim() : null;
      continue;
    }
    if (line.startsWith('#')) continue;
    if (/^https?:\/\//i.test(line)) {
      out.push({ name: pendingName, url: line });
      pendingName = null;
    }
  }

  // PLS titles arrive before or after their File lines depending on who wrote the file.
  return out.map((entry, i) => ({
    name: entry.name ?? plsTitles.get(String(i + 1)) ?? null,
    url: entry.url,
  }));
}
