import fs from 'node:fs';
import path from 'node:path';
import { config } from './config.js';
import type { StationIndex } from './index-store.js';

/**
 * Turns what you typed into what we search for.
 *
 * This is the layer that makes the app find *Radio Vaticana* when you type `religion` — and, more
 * usefully, find things when the word in your head was never the word in the station's name.
 *
 * Two sources of expansion, cheapest first:
 *
 * 1. **Tag co-occurrence learned from the corpus.** Stations tagged `gregorian` are overwhelmingly
 *    also tagged `chant` and `sacred`; that's a fact about how radio is actually labelled, and
 *    better evidence than any general model's opinion about word meanings.
 * 2. **A hand-checked concept map** in `assets/concepts.json`, for words the corpus never contains.
 *    `philosophy` isn't a radio tag anywhere, so co-occurrence has nothing to attach it to.
 *
 * Expansions are always handed back to the client and always shown as removable chips. Invisible
 * fuzzy matching reads as a broken app; a visible, editable one reads as help.
 */

let conceptsCache: Map<string, string[]> | null = null;

function concepts(): Map<string, string[]> {
  if (conceptsCache) return conceptsCache;
  const map = new Map<string, string[]>();
  try {
    const raw = fs.readFileSync(path.join(config.assetsDir, 'concepts.json'), 'utf8');
    for (const [key, value] of Object.entries(JSON.parse(raw) as Record<string, unknown>)) {
      if (key.startsWith('_') || !Array.isArray(value)) continue;
      map.set(key, value.filter((v): v is string => typeof v === 'string'));
    }
  } catch {
    /* no concept map is a degraded search, not a broken one */
  }
  conceptsCache = map;
  return map;
}

/** @returns expansion terms for these query tokens, in priority order, excluding the tokens themselves. */
export function expand(index: StationIndex, tokens: string[], limit = 8): string[] {
  if (tokens.length === 0) return [];
  const seen = new Set(tokens);
  const out: string[] = [];

  // The concept map first: it's hand-checked, so it's the most trustworthy signal we have.
  for (const token of tokens) {
    for (const tag of concepts().get(token) ?? []) {
      if (seen.has(tag)) continue;
      seen.add(tag);
      out.push(tag);
      if (out.length >= limit) return out;
    }
  }

  // Then what the corpus itself says goes together.
  for (const token of tokens) {
    if (!index.knownTag(token)) continue;
    for (const neighbour of index.neighboursOf(token, limit)) {
      if (seen.has(neighbour)) continue;
      seen.add(neighbour);
      out.push(neighbour);
      if (out.length >= limit) return out;
    }
  }
  return out;
}

/** Tags rarer than this are noise for co-occurrence purposes. */
const MIN_TAG_COUNT = 25;
/** Bounds the pair explosion: a station with 30 tags would otherwise contribute 435 pairs. */
const MAX_TAGS_PER_STATION = 6;
/** A pair seen fewer times than this is a coincidence, not a relationship. */
const MIN_PAIR_COUNT = 5;
const NEIGHBOURS_PER_TAG = 8;

/**
 * Pointwise mutual information over tag pairs: `log( p(a,b) / (p(a)·p(b)) )`.
 *
 * This is counting, not machine learning — a 1990s information-retrieval technique that works
 * genuinely well on short tag data. One pass over the corpus, seconds of work, and the result is a
 * synonym dictionary *learned from the actual data*, surfacing relationships nobody would have
 * thought to write down.
 */
export function computeNeighbours(
  tagCounts: Map<string, number>,
  tagLists: Iterable<string[]>,
  totalStations: number,
): Map<string, [string, number][]> {
  const vocabulary = new Map<string, number>();
  for (const [tag, n] of tagCounts) if (n >= MIN_TAG_COUNT) vocabulary.set(tag, n);
  if (vocabulary.size === 0 || totalStations === 0) return new Map();

  const pairCounts = new Map<string, number>();
  for (const tags of tagLists) {
    const kept: string[] = [];
    for (const tag of tags) {
      if (!vocabulary.has(tag) || kept.includes(tag)) continue;
      kept.push(tag);
      if (kept.length >= MAX_TAGS_PER_STATION) break;
    }
    for (let i = 0; i < kept.length; i++) {
      for (let j = i + 1; j < kept.length; j++) {
        const a = kept[i];
        const b = kept[j];
        const key = a < b ? `${a} ${b}` : `${b} ${a}`;
        pairCounts.set(key, (pairCounts.get(key) ?? 0) + 1);
      }
    }
  }

  const scored = new Map<string, [string, number][]>();
  const n = totalStations;
  for (const [key, count] of pairCounts) {
    if (count < MIN_PAIR_COUNT) continue;
    const separator = key.indexOf(' ');
    const a = key.slice(0, separator);
    const b = key.slice(separator + 1);
    const countA = vocabulary.get(a);
    const countB = vocabulary.get(b);
    if (countA === undefined || countB === undefined) continue;
    const pmi = Math.log((count * n) / (countA * countB));
    if (pmi <= 0) continue; // no better than chance
    push(scored, a, [b, pmi]);
    push(scored, b, [a, pmi]);
  }

  for (const [tag, list] of scored) {
    list.sort((x, y) => y[1] - x[1]);
    scored.set(tag, list.slice(0, NEIGHBOURS_PER_TAG));
  }
  return scored;
}

function push(map: Map<string, [string, number][]>, key: string, value: [string, number]): void {
  const existing = map.get(key);
  if (existing) existing.push(value);
  else map.set(key, [value]);
}
