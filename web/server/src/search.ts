import type { Hit, StationIndex } from './index-store.js';
import { expand } from './expand.js';

/** How much better a direct hit is than an expansion-only hit. */
const DIRECT_BOOST = 4.0;

export interface SearchFilters {
  httpsOnly?: boolean;
  includeUnreachable?: boolean;
  country?: string;
  codec?: string;
  minBitrate?: number;
}

export interface SearchResults {
  hits: Hit[];
  /** Shown as removable chips: never expand a query invisibly. */
  expansions: string[];
}

export function tokenise(query: string): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const token of query.toLowerCase().split(/[^\p{L}\p{N}]+/u)) {
    if (token.length < 2 || seen.has(token)) continue;
    seen.add(token);
    out.push(token);
    if (out.length >= 6) break;
  }
  return out;
}

/**
 * FTS5 query syntax. Typed terms get a prefix match so search-as-you-type feels alive; expansions
 * are exact, because a prefix on a guessed synonym is a guess on a guess.
 */
function buildMatchQuery(tokens: string[], expansions: string[]): string {
  const escape = (term: string) => term.replace(/"/g, '');
  const typed = tokens.map((t) => `"${escape(t)}"*`).join(' OR ');
  if (expansions.length === 0) return typed;
  const expanded = expansions.map((t) => `"${escape(t)}"`).join(' OR ');
  return `${typed} OR ${expanded}`;
}

export function search(
  index: StationIndex,
  query: string,
  filters: SearchFilters = {},
  limit = 120,
  /** Expansions the user has removed from the chips. */
  dropped: string[] = [],
): SearchResults {
  const tokens = tokenise(query);
  if (tokens.length === 0) return { hits: [], expansions: [] };
  if (index.count() === 0) return { hits: [], expansions: [] };

  const droppedSet = new Set(dropped.map((d) => d.toLowerCase()));
  const expansions = expand(index, tokens).filter((term) => !droppedSet.has(term));
  const match = buildMatchQuery(tokens, expansions);

  let hits: Hit[];
  try {
    hits = index.search(match, limit * 3, filters);
  } catch {
    // A query FTS5 refuses to parse is a bad query, not a broken server.
    return { hits: [], expansions };
  }

  // A row that matched what you actually typed beats one that only matched an expansion.
  const ranked = hits
    .map((hit) => {
      const haystack = `${hit.name}\n${hit.tags}`.toLowerCase();
      const direct = tokens.some((token) => haystack.includes(token));
      return { hit, score: direct ? hit.score * DIRECT_BOOST : hit.score };
    })
    .sort((a, b) => b.score - a.score)
    .slice(0, limit)
    .map((entry) => entry.hit);

  return { hits: ranked, expansions };
}
