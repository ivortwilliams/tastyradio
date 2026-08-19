import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));

/** `dist/` at runtime, so the package root is one up. */
export const SERVER_ROOT = path.resolve(here, '..');

function env(name: string, fallback: string): string {
  const value = process.env[name];
  return value === undefined || value === '' ? fallback : value;
}

export const config = {
  port: Number(env('PORT', '8080')),
  host: env('HOST', '0.0.0.0'),

  /**
   * Where the station index and any cached artwork live. A volume in production so the 41 MB index
   * survives a redeploy — rebuilding it is a minute of somebody else's bandwidth, not ours to spend
   * casually.
   */
  dataDir: env('DATA_DIR', path.join(SERVER_ROOT, 'data')),

  /** The built client. Served as static files by this same process; there is no second container. */
  clientDir: env('CLIENT_DIR', path.join(SERVER_ROOT, 'public')),

  assetsDir: path.join(SERVER_ROOT, 'assets'),

  /**
   * One shared password for everybody, or empty for an open site.
   *
   * This exists because the stream proxy relays audio on request: without a gate it is an open
   * relay, and the bandwidth bill is ours. It is deliberately not an account system — the app has
   * never had accounts and this shouldn't either.
   */
  accessCode: env('ACCESS_CODE', ''),

  /** Signs the access cookie. Generated per-deploy if unset, which logs everyone out on restart. */
  sessionSecret: env('SESSION_SECRET', ''),

  /**
   * Where a prebuilt station index is published. Building the corpus takes a minute of CPU and
   * ~50 MB of radio-browser's bandwidth, so a GitHub Actions cron does it once a week for everyone
   * and the server just downloads the result — the build-time pipeline `discovery.md` calls for
   * rather than a runtime service.
   */
  indexUrl: env(
    'INDEX_URL',
    'https://github.com/ivortwilliams/tastyradio/releases/download/station-index/station-index.db.gz',
  ),

  /** How often to look for a newer published index. */
  indexCheckHours: Number(env('INDEX_CHECK_HOURS', '24')),

  /** radio-browser.info asks for a descriptive User-Agent, and so do plenty of stream hosts. */
  userAgent: env('USER_AGENT', 'TastyRadio/1.0 (web; https://github.com/ivortwilliams/tastyradio)'),

  /** Matches the Android app: past four the returns drop and the costs don't. */
  maxChannels: 4,

  /** Ceiling on simultaneous proxied streams across everybody, so one tab can't eat the droplet. */
  maxConcurrentStreams: Number(env('MAX_CONCURRENT_STREAMS', '48')),
} as const;

export const INDEX_FILE = () => path.join(config.dataDir, 'station-index.db');
