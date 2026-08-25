import Database from 'better-sqlite3';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { config } from './config.js';

/**
 * Short mix links: `radio.truthseekersbyo.com/s/<id>#<key>`.
 *
 * The original link carries the whole mix in the fragment — see `client/src/data/share.ts` — which
 * is private and needs no server, and is about 380 characters for two channels. Pasted into a chat
 * it is a paragraph, and people don't send paragraphs. This is the same mix in 67 characters.
 *
 * **The server still cannot read it.** The client encrypts the payload with a key it generates and
 * keeps in the fragment, and posts only the ciphertext; what lands here is a blob and an id. That
 * was the price of a short link and it turned out not to be one: the property `share.ts` insists on
 * — no row anybody can read, nothing in an access log worth having — survives, because the half
 * that matters never arrives. Lose the fragment and the row is 300 bytes of noise, to us as much as
 * to anyone else.
 *
 * There is no expiry. A mix is 300-odd bytes; ten thousand of them is three megabytes, and a link
 * that dies six months after you sent it is worse than any disk this would ever cost.
 */

const SCHEMA = `
CREATE TABLE IF NOT EXISTS mix (
    id TEXT PRIMARY KEY,
    blob BLOB NOT NULL,
    created INTEGER NOT NULL,
    opened INTEGER NOT NULL DEFAULT 0
);
`;

/** 48 bits of id. Unguessable enough on its own, and useless without the key in the fragment. */
const ID_BYTES = 6;

/** A four-channel mix with artwork URLs is under a kilobyte; this is a wall, not a target. */
export const MAX_BLOB_BYTES = 16 * 1024;

let db: Database.Database | null = null;

function open(): Database.Database {
  if (db) return db;
  fs.mkdirSync(config.dataDir, { recursive: true });
  const handle = new Database(path.join(config.dataDir, 'mixes.db'));
  handle.pragma('journal_mode = WAL');
  handle.pragma('synchronous = NORMAL');
  handle.exec(SCHEMA);
  db = handle;
  return handle;
}

/**
 * Stores one encrypted mix and returns its id.
 *
 * Identical bytes are never posted twice — every share generates a fresh key and nonce — so there
 * is nothing to deduplicate and no way to tell two shares of the same mix apart. That is the point.
 */
export function put(blob: Buffer): string {
  const handle = open();
  const insert = handle.prepare('INSERT INTO mix (id, blob, created) VALUES (?, ?, ?)');

  // Retrying on collision rather than trusting 48 bits blindly: it costs one statement and means
  // the id can stay short.
  for (let attempt = 0; attempt < 5; attempt++) {
    const id = crypto.randomBytes(ID_BYTES).toString('base64url');
    try {
      insert.run(id, blob, Date.now());
      return id;
    } catch (error) {
      if (!String((error as Error).message).includes('UNIQUE')) throw error;
    }
  }
  throw new Error('could not allocate a mix id');
}

export function get(id: string): Buffer | null {
  if (!/^[A-Za-z0-9_-]{4,32}$/.test(id)) return null;
  const handle = open();
  const row = handle.prepare('SELECT blob FROM mix WHERE id = ?').get(id) as { blob: Buffer } | undefined;
  if (!row) return null;
  handle.prepare('UPDATE mix SET opened = opened + 1 WHERE id = ?').run(id);
  return row.blob;
}

export function stats(): { mixes: number; sizeBytes: number } {
  const handle = open();
  const row = handle.prepare('SELECT COUNT(*) AS n, COALESCE(SUM(LENGTH(blob)), 0) AS bytes FROM mix').get() as {
    n: number;
    bytes: number;
  };
  return { mixes: row.n, sizeBytes: row.bytes };
}
