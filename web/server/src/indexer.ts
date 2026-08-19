import fs from 'node:fs';
import path from 'node:path';
import { StationIndex } from './index-store.js';
import { computeNeighbours } from './expand.js';
import * as radioBrowser from './radiobrowser.js';

/**
 * Builds the station index from scratch: pull the corpus, clean it, index it, learn what the tags
 * say about each other, compact.
 *
 * **This does not normally run on the server.** It runs in GitHub Actions once a week and publishes
 * the finished database as a release asset, which every server then downloads — the build-time
 * pipeline `discovery.md` calls for instead of a runtime service. Doing it here would mean every
 * deploy costs radio-browser a 50 MB dump for a result that is identical for everybody.
 *
 * It stays importable by the server so a box with no published index can still bootstrap itself.
 */

export interface BuildProgress {
  phase: string;
  fetched: number;
}

export interface BuildResult {
  file: string;
  total: number;
  expected: number | null;
  complete: boolean;
  tagsWithNeighbours: number;
  sizeBytes: number;
  seconds: number;
}

export async function buildIndex(
  outputFile: string,
  onProgress: (progress: BuildProgress) => void = () => {},
): Promise<BuildResult> {
  const startedAt = Date.now();

  // Build into a temporary file and move it into place at the end. A half-written index is worse
  // than no index, and a server reading the old one should keep reading it until this succeeds.
  const workFile = `${outputFile}.building`;
  for (const suffix of ['', '-wal', '-shm']) {
    fs.rmSync(workFile + suffix, { force: true });
  }
  fs.mkdirSync(path.dirname(outputFile), { recursive: true });

  const index = new StationIndex(workFile);
  const tagCounts = new Map<string, number>();
  let expected: number | null = null;
  let total = 0;

  try {
    onProgress({ phase: 'Contacting radio-browser', fetched: 0 });
    index.clearSource(radioBrowser.SOURCE);

    await radioBrowser.fetchAllFromAnyMirror(
      async (host) => {
        // Ask the server what it has, so "did I get everything?" has an answer.
        expected = await radioBrowser.fetchExpectedCount(host);
        onProgress({ phase: `Contacting ${host}`, fetched: 0 });
      },
      (rows, running) => {
        index.insertBatch(rows);
        for (const row of rows) {
          if (row.tags === '') continue;
          for (const tag of row.tags.split(',')) {
            if (tag !== '') tagCounts.set(tag, (tagCounts.get(tag) ?? 0) + 1);
          }
        }
        total = running;
        onProgress({ phase: 'Downloading stations', fetched: running });
      },
    );

    onProgress({ phase: 'Indexing tags', fetched: total });
    index.writeTagCounts(tagCounts);

    onProgress({ phase: 'Learning tag relationships', fetched: total });
    const tagLists = index.allTagLists().map((tags) => tags.split(','));
    const neighbours = computeNeighbours(tagCounts, tagLists, total);
    index.writeNeighbours(neighbours);

    const built = index.count();
    index.putMeta('builtAt', String(Date.now()));
    index.putMeta('stationCount', String(built));
    if (expected !== null) index.putMeta('expectedStations', String(expected));

    onProgress({ phase: 'Compacting', fetched: total });
    index.compact();

    const stats = index.stats();
    index.close();

    for (const suffix of ['', '-wal', '-shm']) {
      fs.rmSync(outputFile + suffix, { force: true });
    }
    fs.renameSync(workFile, outputFile);

    return {
      file: outputFile,
      total: built,
      expected,
      complete: stats.complete,
      tagsWithNeighbours: neighbours.size,
      sizeBytes: fs.statSync(outputFile).size,
      seconds: Math.round((Date.now() - startedAt) / 1000),
    };
  } catch (error) {
    index.close();
    for (const suffix of ['', '-wal', '-shm']) {
      fs.rmSync(workFile + suffix, { force: true });
    }
    throw error;
  }
}
