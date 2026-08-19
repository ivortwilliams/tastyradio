import Database from 'better-sqlite3';
import fs from 'node:fs';
import path from 'node:path';
import type { StationRow } from './radiobrowser.js';

/**
 * The station index: every station we know about, searchable.
 *
 * The Android app carries this on the phone because search there has to work offline. On the web
 * it lives on the server instead — one copy for everybody rather than a 41 MB download per browser
 * — but it is deliberately the *same database*, built by the same rules, queried by the same SQL,
 * so the two versions of the app rank results identically.
 *
 * `better-sqlite3` compiles SQLite with FTS5 enabled, which is the whole reason the Android side
 * had to ship its own SQLite: the platform's is built without it.
 */

export interface Hit {
  name: string;
  url: string;
  tags: string;
  country: string;
  language: string;
  codec: string;
  bitrate: number;
  favicon: string;
  uuid: string;
  source: string;
  lastCheckOk: boolean;
  score: number;
}

export interface IndexStats {
  total: number;
  bySource: { source: string; count: number }[];
  sizeBytes: number;
  builtAt: number | null;
  expected: number | null;
  complete: boolean;
}

const SCHEMA = `
CREATE TABLE IF NOT EXISTS station (
    id INTEGER PRIMARY KEY,
    uuid TEXT, name TEXT, url TEXT, tags TEXT,
    country TEXT, countrycode TEXT, state TEXT, language TEXT,
    homepage TEXT, favicon TEXT, codec TEXT,
    bitrate INTEGER, votes INTEGER, clickcount INTEGER, clicktrend INTEGER,
    lastcheckok INTEGER, source TEXT
);

-- The tokenizer chain is FTS5-only: porter to stem, unicode61 to fold diacritics so "Vaticana" is
-- findable by people who don't type accents.
CREATE VIRTUAL TABLE IF NOT EXISTS station_fts USING fts5(
    name, tags, country, state, language, homepage,
    tokenize = "porter unicode61 remove_diacritics 2"
);

CREATE TABLE IF NOT EXISTS tag_count (tag TEXT PRIMARY KEY, n INTEGER);
CREATE TABLE IF NOT EXISTS tag_neighbour (tag TEXT, neighbour TEXT, score REAL);
CREATE INDEX IF NOT EXISTS idx_neighbour ON tag_neighbour(tag);
CREATE INDEX IF NOT EXISTS idx_station_url ON station(url);
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT);
`;

export class StationIndex {
  private db: Database.Database;

  constructor(
    private readonly file: string,
    options: { readonly?: boolean; create?: boolean } = {},
  ) {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    this.db = new Database(file, { readonly: options.readonly ?? false });
    this.db.pragma('journal_mode = WAL');
    this.db.pragma('synchronous = NORMAL');
    if (!options.readonly && (options.create ?? true)) this.db.exec(SCHEMA);
  }

  close(): void {
    this.db.close();
  }

  // ---------------------------------------------------------------- ingest

  clearSource(source: string): void {
    this.db
      .prepare('DELETE FROM station_fts WHERE rowid IN (SELECT id FROM station WHERE source = ?)')
      .run(source);
    this.db.prepare('DELETE FROM station WHERE source = ?').run(source);
  }

  /** Batched inside one transaction: 60k individual inserts take minutes otherwise. */
  insertBatch(rows: StationRow[]): void {
    const station = this.db.prepare(`
      INSERT INTO station (uuid, name, url, tags, country, countrycode, state, language,
        homepage, favicon, codec, bitrate, votes, clickcount, clicktrend, lastcheckok, source)
      VALUES (@uuid,@name,@url,@tags,@country,@countrycode,@state,@language,
        @homepage,@favicon,@codec,@bitrate,@votes,@clickcount,@clicktrend,@lastcheckok,@source)
    `);
    const fts = this.db.prepare(`
      INSERT INTO station_fts (rowid, name, tags, country, state, language, homepage)
      VALUES (?,?,?,?,?,?,?)
    `);

    const insert = this.db.transaction((batch: StationRow[]) => {
      for (const row of batch) {
        const result = station.run(row);
        fts.run(
          result.lastInsertRowid as number,
          row.name,
          row.tags,
          row.country,
          row.state,
          row.language,
          row.homepage,
        );
      }
    });
    insert(rows);
  }

  writeTagCounts(counts: Map<string, number>): void {
    const insert = this.db.prepare('INSERT OR REPLACE INTO tag_count (tag, n) VALUES (?,?)');
    const run = this.db.transaction(() => {
      this.db.prepare('DELETE FROM tag_count').run();
      for (const [tag, n] of counts) insert.run(tag, n);
    });
    run();
  }

  writeNeighbours(neighbours: Map<string, [string, number][]>): void {
    const insert = this.db.prepare('INSERT INTO tag_neighbour (tag, neighbour, score) VALUES (?,?,?)');
    const run = this.db.transaction(() => {
      this.db.prepare('DELETE FROM tag_neighbour').run();
      for (const [tag, list] of neighbours) {
        for (const [neighbour, score] of list) insert.run(tag, neighbour, score);
      }
    });
    run();
  }

  allTagLists(): string[] {
    return this.db
      .prepare("SELECT tags FROM station WHERE tags <> ''")
      .pluck()
      .all() as string[];
  }

  /**
   * Reclaim the space a rebuild leaves behind. SQLite doesn't shrink the file when rows are deleted,
   * so without this the index looks like it grows every refresh. The second checkpoint is not a
   * typo: VACUUM rewrites the whole database through the write-ahead log, and skipping it leaves a
   * `-wal` file bigger than the database it just compacted.
   */
  compact(): void {
    this.db.pragma('wal_checkpoint(TRUNCATE)');
    this.db.exec('VACUUM');
    this.db.pragma('wal_checkpoint(TRUNCATE)');
  }

  // ---------------------------------------------------------------- query

  neighboursOf(tag: string, limit: number): string[] {
    return this.db
      .prepare('SELECT neighbour FROM tag_neighbour WHERE tag = ? ORDER BY score DESC LIMIT ?')
      .pluck()
      .all(tag, limit) as string[];
  }

  knownTag(tag: string): boolean {
    return this.db.prepare('SELECT 1 FROM tag_count WHERE tag = ? LIMIT 1').get(tag) !== undefined;
  }

  popularTags(limit: number): string[] {
    return this.db
      .prepare('SELECT tag FROM tag_count ORDER BY n DESC LIMIT ?')
      .pluck()
      .all(limit) as string[];
  }

  /**
   * Ranking is split deliberately, exactly as on Android: SQLite does the BM25 relevance ordering,
   * and popularity and reachability are folded in afterwards in application code. SQLite's `log()`
   * needs a compile flag not worth depending on, and re-ranking a few hundred rows here is free.
   *
   * Popularity is a multiplier, never a filter — obscure stations are half the point of this app,
   * they just shouldn't outrank the obvious answer to a vague query.
   */
  search(
    match: string,
    limit: number,
    filters: { httpsOnly?: boolean; includeUnreachable?: boolean; country?: string; codec?: string; minBitrate?: number } = {},
  ): Hit[] {
    const where: string[] = ['station_fts MATCH ?'];
    const params: unknown[] = [match];

    if (filters.httpsOnly) where.push("s.url LIKE 'https://%'");
    if (!filters.includeUnreachable) where.push('s.lastcheckok = 1');
    if (filters.country) {
      where.push('s.countrycode = ?');
      params.push(filters.country.toUpperCase());
    }
    if (filters.codec) {
      where.push('UPPER(s.codec) = ?');
      params.push(filters.codec.toUpperCase());
    }
    if (filters.minBitrate) {
      where.push('s.bitrate >= ?');
      params.push(filters.minBitrate);
    }
    params.push(limit);

    const rows = this.db
      .prepare(
        `SELECT s.name, s.url, s.tags, s.country, s.language, s.codec, s.bitrate,
                s.favicon, s.uuid, s.source, s.lastcheckok, s.votes, s.clickcount,
                bm25(station_fts, 10.0, 6.0, 3.0, 3.0, 3.0, 1.0) AS bm
         FROM station_fts
         JOIN station s ON s.id = station_fts.rowid
         WHERE ${where.join(' AND ')}
         ORDER BY bm LIMIT ?`,
      )
      .all(...params) as Record<string, any>[];

    return rows.map((row) => {
      // bm25 is negative, better matches more so; flip it into a positive relevance.
      const relevance = -row.bm;
      const popularity = Math.log(row.clickcount + row.votes + 10);
      const reachability = row.lastcheckok === 1 ? 1.0 : 0.15;
      return {
        name: row.name,
        url: row.url,
        tags: row.tags,
        country: row.country,
        language: row.language,
        codec: row.codec,
        bitrate: row.bitrate,
        favicon: row.favicon,
        uuid: row.uuid,
        source: row.source,
        lastCheckOk: row.lastcheckok === 1,
        score: relevance * popularity * reachability,
      };
    });
  }

  /** Ordered by `clicktrend` — what people are turning on right now. */
  trending(limit: number): Hit[] {
    return this.plainQuery(
      'SELECT * FROM station WHERE lastcheckok = 1 ORDER BY clicktrend DESC, clickcount DESC LIMIT ?',
      limit,
    );
  }

  byTag(tag: string, limit: number): Hit[] {
    const rows = this.db
      .prepare(
        `SELECT s.* FROM station_fts JOIN station s ON s.id = station_fts.rowid
         WHERE station_fts MATCH ? AND s.lastcheckok = 1
         ORDER BY bm25(station_fts, 1.0, 10.0, 1.0, 1.0, 1.0, 1.0), s.clickcount DESC LIMIT ?`,
      )
      .all(`tags : "${tag.replace(/"/g, '')}"`, limit) as Record<string, any>[];
    return rows.map(toHit);
  }

  countries(limit: number): { code: string; name: string; count: number }[] {
    return this.db
      .prepare(
        `SELECT countrycode AS code, MIN(country) AS name, COUNT(*) AS count
         FROM station WHERE countrycode <> '' AND lastcheckok = 1
         GROUP BY countrycode ORDER BY count DESC LIMIT ?`,
      )
      .all(limit) as { code: string; name: string; count: number }[];
  }

  private plainQuery(sql: string, ...params: unknown[]): Hit[] {
    return (this.db.prepare(sql).all(...params) as Record<string, any>[]).map(toHit);
  }

  /** Exact stream-URL lookup, for filling in what a saved station doesn't know about itself. */
  findByUrl(url: string): Hit | null {
    const row = this.db.prepare('SELECT * FROM station WHERE url = ? LIMIT 1').get(url) as
      | Record<string, any>
      | undefined;
    return row ? toHit(row) : null;
  }

  count(): number {
    return this.db.prepare('SELECT COUNT(*) FROM station').pluck().get() as number;
  }

  stats(): IndexStats {
    const total = this.count();
    const bySource = this.db
      .prepare('SELECT source, COUNT(*) AS count FROM station GROUP BY source')
      .all() as { source: string; count: number }[];
    const expected = Number(this.getMeta('expectedStations') ?? '') || null;
    const builtAt = Number(this.getMeta('builtAt') ?? '') || null;
    let sizeBytes = 0;
    for (const suffix of ['', '-wal', '-shm']) {
      try {
        sizeBytes += fs.statSync(this.file + suffix).size;
      } catch {
        /* not there is fine */
      }
    }
    return {
      total,
      bySource,
      sizeBytes,
      builtAt,
      expected,
      complete: expected === null || total >= expected * 0.95,
    };
  }

  putMeta(key: string, value: string): void {
    this.db.prepare('INSERT OR REPLACE INTO meta (key, value) VALUES (?,?)').run(key, value);
  }

  getMeta(key: string): string | null {
    const value = this.db.prepare('SELECT value FROM meta WHERE key = ?').pluck().get(key);
    return typeof value === 'string' ? value : null;
  }
}

function toHit(row: Record<string, any>): Hit {
  return {
    name: row.name,
    url: row.url,
    tags: row.tags,
    country: row.country,
    language: row.language,
    codec: row.codec,
    bitrate: row.bitrate,
    favicon: row.favicon,
    uuid: row.uuid,
    source: row.source,
    lastCheckOk: row.lastcheckok === 1,
    score: 0,
  };
}
