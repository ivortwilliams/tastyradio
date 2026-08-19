import http from 'node:http';
import https from 'node:https';
import dns from 'node:dns/promises';
import net from 'node:net';
import { config } from './config.js';

/**
 * Outbound HTTP for a server that fetches URLs strangers typed in.
 *
 * Two things make this more than a `fetch` wrapper:
 *
 * 1. **SSRF has to be closed off.** The stream proxy takes a URL as a query parameter, so without a
 *    guard it will happily fetch `http://169.254.169.254/` and hand the droplet's cloud-metadata
 *    credentials back to whoever asked. Every hostname is resolved first, every resolved address is
 *    checked against the private ranges, and the connection is then *pinned* to the address that
 *    passed — otherwise a hostname that answers twice can pass the check and connect somewhere else.
 *
 * 2. **`insecureHTTPParser`.** A large minority of SHOUTcast servers answer `ICY 200 OK` instead of
 *    `HTTP/1.0 200 OK`. Node's strict parser rejects that outright, which would silently lose a
 *    chunk of the corpus to what looks like a network error. This is the flag ExoPlayer's tolerance
 *    is equivalent to.
 */

export interface OpenOptions {
  headers?: Record<string, string>;
  method?: string;
  timeoutMs?: number;
  /** How many redirects to follow. Radio URLs redirect constantly, including http -> https. */
  maxRedirects?: number;
  signal?: AbortSignal;
}

export interface OpenResult {
  response: http.IncomingMessage;
  /** Where we ended up after redirects — what the caller should treat as the real URL. */
  finalUrl: string;
  status: number;
  headers: http.IncomingHttpHeaders;
}

const PRIVATE_V4 = [
  { net: '0.0.0.0', bits: 8 },
  { net: '10.0.0.0', bits: 8 },
  { net: '100.64.0.0', bits: 10 },
  { net: '127.0.0.0', bits: 8 },
  { net: '169.254.0.0', bits: 16 },
  { net: '172.16.0.0', bits: 12 },
  { net: '192.0.0.0', bits: 24 },
  { net: '192.168.0.0', bits: 16 },
  { net: '198.18.0.0', bits: 15 },
  { net: '224.0.0.0', bits: 4 },
  { net: '240.0.0.0', bits: 4 },
];

function v4ToInt(address: string): number {
  const parts = address.split('.').map(Number);
  return ((parts[0] << 24) >>> 0) + (parts[1] << 16) + (parts[2] << 8) + parts[3];
}

export function isPrivateAddress(address: string): boolean {
  if (net.isIPv4(address)) {
    const value = v4ToInt(address);
    return PRIVATE_V4.some(({ net: base, bits }) => {
      const mask = bits === 0 ? 0 : (0xffffffff << (32 - bits)) >>> 0;
      return (value & mask) >>> 0 === (v4ToInt(base) & mask) >>> 0;
    });
  }
  if (net.isIPv6(address)) {
    const lower = address.toLowerCase();
    if (lower === '::' || lower === '::1') return true;
    // Unique-local, link-local, and anything mapping back into IPv4 space.
    if (lower.startsWith('fc') || lower.startsWith('fd')) return true;
    if (lower.startsWith('fe8') || lower.startsWith('fe9') || lower.startsWith('fea') || lower.startsWith('feb')) return true;
    const mapped = lower.match(/^::ffff:(\d+\.\d+\.\d+\.\d+)$/);
    if (mapped) return isPrivateAddress(mapped[1]);
    return false;
  }
  return true; // not an address we understand — refuse rather than guess
}

export class BlockedUrlError extends Error {}

/**
 * IPv4 first, deliberately. radio-browser's own mirrors are dual-stack and `de1` resolves to an
 * AAAA address that plenty of networks cannot route to — the Android app hit exactly this and set
 * `preferIPv6Addresses=false` for the same reason.
 */
async function resolvePublicAddress(hostname: string): Promise<{ address: string; family: 4 | 6 }> {
  if (net.isIP(hostname)) {
    if (isPrivateAddress(hostname)) throw new BlockedUrlError(`refusing to connect to ${hostname}`);
    return { address: hostname, family: net.isIPv4(hostname) ? 4 : 6 };
  }

  let records: { address: string; family: number }[];
  try {
    records = await dns.lookup(hostname, { all: true, verbatim: false });
  } catch {
    throw new Error(`cannot resolve ${hostname}`);
  }

  const usable = records.filter((r) => !isPrivateAddress(r.address));
  if (usable.length === 0) throw new BlockedUrlError(`refusing to connect to ${hostname}`);

  const preferred = usable.find((r) => r.family === 4) ?? usable[0];
  return { address: preferred.address, family: preferred.family === 6 ? 6 : 4 };
}

export function assertHttpUrl(raw: string): URL {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    throw new BlockedUrlError('not a URL');
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new BlockedUrlError(`refusing protocol ${url.protocol}`);
  }
  return url;
}

/**
 * Opens a URL and hands back the live response, following redirects by hand so that each hop gets
 * the same SSRF check as the first — following them inside the agent would skip the guard.
 */
export async function open(raw: string, options: OpenOptions = {}): Promise<OpenResult> {
  const maxRedirects = options.maxRedirects ?? 5;
  let current = assertHttpUrl(raw);

  for (let hop = 0; hop <= maxRedirects; hop++) {
    const { address, family } = await resolvePublicAddress(current.hostname);
    const transport = current.protocol === 'https:' ? https : http;

    const response = await new Promise<http.IncomingMessage>((resolve, reject) => {
      const request = transport.request(
        {
          protocol: current.protocol,
          hostname: current.hostname,
          port: current.port || (current.protocol === 'https:' ? 443 : 80),
          path: current.pathname + current.search,
          method: options.method ?? 'GET',
          headers: {
            'User-Agent': config.userAgent,
            Accept: '*/*',
            ...options.headers,
            Host: current.host,
          },
          // Pinned to the address that passed the check, closing the DNS-rebinding window.
          //
          // Two shapes, not one: Node 20 turns on happy-eyeballs by default, and that path asks for
          // `all` and expects an array back. Answering it with the single-address form fails with
          // ERR_INVALID_IP_ADDRESS before a single byte is sent.
          lookup: ((_hostname: string, opts: { all?: boolean }, callback: Function) => {
            if (opts && opts.all) callback(null, [{ address, family }]);
            else callback(null, address, family);
          }) as never,
          insecureHTTPParser: true,
          servername: current.hostname,
          signal: options.signal,
        },
        resolve,
      );
      request.setTimeout(options.timeoutMs ?? 20_000, () => {
        request.destroy(new Error('timed out'));
      });
      request.on('error', reject);
      request.end();
    });

    const status = response.statusCode ?? 0;
    const location = response.headers.location;
    if (status >= 300 && status < 400 && location && hop < maxRedirects) {
      response.resume(); // drain, or the socket leaks
      current = assertHttpUrl(new URL(location, current).toString());
      continue;
    }

    return { response, finalUrl: current.toString(), status, headers: response.headers };
  }

  throw new Error('too many redirects');
}

/** Reads a whole response into memory, with a hard ceiling — used for playlists and artwork only. */
export async function readAll(response: http.IncomingMessage, limitBytes: number): Promise<Buffer> {
  const chunks: Buffer[] = [];
  let total = 0;
  for await (const chunk of response) {
    total += chunk.length;
    if (total > limitBytes) {
      response.destroy();
      throw new Error('response too large');
    }
    chunks.push(chunk as Buffer);
  }
  return Buffer.concat(chunks);
}

export async function fetchText(url: string, limitBytes = 512 * 1024, timeoutMs = 15_000): Promise<string> {
  const { response } = await open(url, { timeoutMs });
  const body = await readAll(response, limitBytes);
  return body.toString('utf8');
}
