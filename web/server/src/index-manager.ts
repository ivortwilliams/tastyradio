import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { pipeline } from 'node:stream/promises';
import { config, INDEX_FILE } from './config.js';
import { StationIndex } from './index-store.js';
import { open } from './net.js';

/**
 * Keeps the station index on disk, and swaps in a newer one when it's published.
 *
 * The corpus is identical for every deployment, so building it per-server would cost radio-browser
 * a 50 MB dump each time for a result nobody differs on. A weekly GitHub Actions job builds it once
 * and publishes it as a release asset; this downloads that. `discovery.md` reached the same
 * conclusion for the phone and called it "a build-time pipeline, not a runtime service".
 *
 * Search stays available throughout: the new file is downloaded beside the old one and only
 * swapped in once it is complete and openable.
 */

let index: StationIndex | null = null;
let refreshing = false;
let lastError: string | null = null;

const stampFile = () => path.join(config.dataDir, 'index-source.txt');

export function current(): StationIndex | null {
  return index;
}

export function status() {
  return {
    ready: index !== null,
    refreshing,
    lastError,
    ...(index ? index.stats() : { total: 0, bySource: [], sizeBytes: 0, builtAt: null, expected: null, complete: false }),
  };
}

function openLocal(): void {
  const file = INDEX_FILE();
  if (!fs.existsSync(file)) return;
  try {
    index?.close();
  } catch {
    /* replacing it anyway */
  }
  try {
    index = new StationIndex(file, { create: false });
    // An index with no rows is a failed download wearing a database's clothes.
    if (index.count() === 0) {
      index.close();
      index = null;
    }
  } catch (error) {
    lastError = `cannot open index: ${(error as Error).message}`;
    index = null;
  }
}

/**
 * Downloads the published index if we don't have it, or if a newer one exists.
 *
 * Freshness is decided by the asset's own `last-modified`, recorded next to the file. There is no
 * version negotiation because there is nothing to negotiate: the newest corpus always wins.
 */
export async function refresh(force = false): Promise<boolean> {
  // An empty INDEX_URL means "the index on disk is the index" — how you develop against a local
  // build without a published release to pull from.
  if (config.indexUrl === '') return false;
  if (refreshing) return false;
  refreshing = true;
  try {
    const known = fs.existsSync(stampFile()) ? fs.readFileSync(stampFile(), 'utf8').trim() : '';

    const { response, status: code, headers } = await open(config.indexUrl, { timeoutMs: 60_000 });
    if (code !== 200) {
      response.resume();
      lastError = `index download returned ${code}`;
      return false;
    }

    const stamp = String(headers['last-modified'] ?? headers.etag ?? '');
    if (!force && stamp !== '' && stamp === known && index !== null) {
      response.resume();
      lastError = null;
      return false;
    }

    fs.mkdirSync(config.dataDir, { recursive: true });
    const download = path.join(config.dataDir, 'station-index.db.incoming');
    fs.rmSync(download, { force: true });

    if (config.indexUrl.endsWith('.gz')) {
      await pipeline(response, zlib.createGunzip(), fs.createWriteStream(download));
    } else {
      await pipeline(response, fs.createWriteStream(download));
    }

    // Prove it opens and has rows *before* it replaces something that works.
    const candidate = new StationIndex(download, { create: false });
    const rows = candidate.count();
    candidate.close();
    if (rows === 0) {
      fs.rmSync(download, { force: true });
      lastError = 'downloaded index was empty';
      return false;
    }

    index?.close();
    index = null;
    for (const suffix of ['', '-wal', '-shm']) {
      fs.rmSync(INDEX_FILE() + suffix, { force: true });
    }
    fs.renameSync(download, INDEX_FILE());
    if (stamp !== '') fs.writeFileSync(stampFile(), stamp);

    openLocal();
    lastError = null;
    console.log(`[index] installed ${rows.toLocaleString()} stations`);
    return true;
  } catch (error) {
    lastError = (error as Error).message;
    console.warn(`[index] refresh failed: ${lastError}`);
    return false;
  } finally {
    refreshing = false;
  }
}

export function start(): void {
  openLocal();
  if (index) {
    console.log(`[index] ${index.count().toLocaleString()} stations on disk`);
  } else {
    console.log('[index] none on disk — downloading');
  }

  void refresh();
  setInterval(() => void refresh(), Math.max(1, config.indexCheckHours) * 60 * 60 * 1000).unref();
}
