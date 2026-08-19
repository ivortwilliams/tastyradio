import * as api from '../api.js';
import { AUDITION_FADER, type Mixer } from '../audio/mixer.js';
import * as store from '../data/store.js';
import { stationFromHit, type Hit } from '../data/types.js';
import { artwork } from './artwork.js';
import { button, el, replace, toast } from './dom.js';

/**
 * Search — a full page rather than a dialog, deliberately.
 *
 * Transistor's "Find Station" is a popup over a dimmed list, which is right for "add the thing I
 * already know the name of" and wrong for browsing, comparing and **auditioning a station into the
 * running mix**. An audition needs somewhere to live that isn't a modal over the top of everything.
 *
 * All the interesting behaviour is on the server — multi-field FTS5, tag co-occurrence expansion,
 * BM25 scaled by popularity and reachability — so this page's whole job is to type at it, show what
 * came back, and be honest about the expansion it did.
 */

interface State {
  query: string;
  dropped: Set<string>;
  httpsOnly: boolean;
  includeUnreachable: boolean;
  expansions: string[];
}

export function searchPage(mixer: Mixer, rerender: () => void): HTMLElement {
  const state: State = {
    query: '',
    dropped: new Set(),
    httpsOnly: false,
    includeUnreachable: false,
    expansions: [],
  };

  const results = el('div', { class: 'rows' });
  const chips = el('div', { class: 'chips' });
  const statusLine = el('p', { class: 'search-status' });
  const localResults = el('div', { class: 'rows rows-local' });
  const localHead = el('h2', { class: 'section-head', text: 'In your collection' });

  const input = el('input', {
    class: 'search-input',
    type: 'search',
    placeholder: 'Try: gregorian · shortwave · religion · philosophy · drone',
    autocomplete: 'off',
    'aria-label': 'Search stations',
  }) as HTMLInputElement;

  let inFlight: AbortController | null = null;
  let debounce = 0;

  async function run(): Promise<void> {
    const query = state.query.trim();

    // Your own stations first. Free, and once the list is forty long you want it.
    const mine = query
      ? store
          .stations()
          .filter((station) =>
            `${station.name} ${station.tags ?? ''}`.toLowerCase().includes(query.toLowerCase()),
          )
      : [];
    replace(localHead, `In your collection (${mine.length})`);
    localHead.hidden = mine.length === 0;
    replace(localResults);
    for (const station of mine) {
      localResults.appendChild(
        resultRow(
          {
            name: station.name,
            url: station.streamUrl,
            tags: station.tags ?? '',
            country: station.country ?? '',
            language: station.language ?? '',
            codec: station.codec ?? '',
            bitrate: station.bitrate ?? 0,
            favicon: station.imageUrl ?? '',
            uuid: station.sourceUuid ?? '',
            source: station.source ?? 'manual',
            lastCheckOk: true,
            score: 0,
          },
          mixer,
          rerender,
          true,
        ),
      );
    }

    if (query.length < 2) {
      replace(results);
      replace(chips);
      await showEmptyState();
      return;
    }

    inFlight?.abort();
    inFlight = new AbortController();
    statusLine.textContent = 'Searching…';

    let response: api.SearchResponse;
    try {
      response = await api.search(
        query,
        {
          httpsOnly: state.httpsOnly,
          includeUnreachable: state.includeUnreachable,
          dropped: [...state.dropped],
        },
        inFlight.signal,
      );
    } catch (error) {
      if ((error as Error).name === 'AbortError') return;
      statusLine.textContent = 'Search failed.';
      return;
    }

    if (!response.indexReady) {
      statusLine.textContent = 'The station index is still downloading on the server — try again in a minute.';
      replace(results);
      return;
    }

    state.expansions = response.expansions;
    renderChips();

    statusLine.textContent =
      response.hits.length === 0
        ? 'Nothing matched. Try a broader word — the index searches names, tags, country and language.'
        : `${response.hits.length} station${response.hits.length === 1 ? '' : 's'}`;

    replace(results);
    for (const hit of response.hits) {
      results.appendChild(resultRow(hit, mixer, rerender, false));
    }
  }

  /**
   * The expansion, always visible and always removable.
   *
   * Unexplained fuzzy matching feels like the app is malfunctioning. A visible, editable expansion
   * feels like the app is helping — and lets you steer it.
   */
  function renderChips(): void {
    replace(chips);
    if (state.expansions.length === 0 && state.dropped.size === 0) return;
    chips.appendChild(el('span', { class: 'chips-label', text: 'also searching' }));
    for (const term of state.expansions) {
      chips.appendChild(
        button(term, {
          class: 'chip',
          title: `Stop searching for "${term}"`,
          onClick: () => {
            state.dropped.add(term);
            void run();
          },
        }),
      );
    }
    if (state.dropped.size > 0) {
      chips.appendChild(
        button('reset', {
          class: 'chip chip-reset',
          onClick: () => {
            state.dropped.clear();
            void run();
          },
        }),
      );
    }
  }

  const empty = el('div', { class: 'browse' });

  /**
   * An empty search page is a wasted screen, and this is where a local index pays off: tags,
   * countries and what's trending are all one query away and none of them need a network round trip
   * to a third party.
   */
  async function showEmptyState(): Promise<void> {
    if (empty.childElementCount > 0) return;
    statusLine.textContent = '';
    try {
      const browse = await api.browse();
      replace(
        empty,
        el('h2', { class: 'section-head', text: 'Popular tags' }),
        el(
          'div',
          { class: 'chips' },
          ...browse.tags.map((tag) =>
            button(tag, {
              class: 'chip chip-tag',
              onClick: () => {
                input.value = tag;
                state.query = tag;
                void run();
              },
            }),
          ),
        ),
        el('h2', { class: 'section-head', text: 'Popular now' }),
        el('div', { class: 'rows' }, ...browse.trending.map((hit) => resultRow(hit, mixer, rerender, false))),
      );
    } catch {
      replace(empty, el('p', { class: 'empty', text: 'The station index is not ready yet.' }));
    }
  }

  input.addEventListener('input', () => {
    state.query = input.value;
    state.dropped.clear();
    window.clearTimeout(debounce);
    // The index is local to the server and answers in milliseconds; this is just to avoid firing a
    // request per keystroke on a slow connection.
    debounce = window.setTimeout(() => void run(), 140);
  });

  const filters = el(
    'div',
    { class: 'filters' },
    checkbox('HTTPS only', false, (on) => {
      state.httpsOnly = on;
      void run();
    }),
    checkbox('Include unreachable', false, (on) => {
      state.includeUnreachable = on;
      void run();
    }),
  );

  void showEmptyState();

  return el(
    'div',
    { class: 'page' },
    el(
      'header',
      { class: 'page-head' },
      el(
        'div',
        {},
        el('h1', { text: 'Search' }),
        el('p', { class: 'page-sub', text: 'Every station radio-browser knows about, searched by name, tag, country and language.' }),
      ),
    ),
    input,
    filters,
    chips,
    statusLine,
    localHead,
    localResults,
    results,
    empty,
  );
}

/**
 * One result. The stream URL is shown raw, as Transistor does — this audience is trusted with the
 * plumbing, and a visible URL is how you judge a result the tags lied about.
 */
function resultRow(hit: Hit, mixer: Mixer, rerender: () => void, owned: boolean): HTMLElement {
  const station = stationFromHit(hit);
  const saved = store.stationByUrl(hit.url);
  const playable = saved ?? station;
  const live = mixer.isLive(playable);

  const meta = [
    hit.codec || null,
    hit.bitrate ? `${hit.bitrate} kbps` : null,
    hit.country || null,
    hit.language || null,
  ]
    .filter(Boolean)
    .join(' · ');

  return el(
    'article',
    { class: 'row', dataset: { live: String(live), dead: String(!hit.lastCheckOk) } },
    artwork({ name: hit.name, imageUrl: hit.favicon || undefined }, 46),
    el(
      'div',
      { class: 'row-body' },
      el('div', { class: 'row-name', text: hit.name }),
      el('div', { class: 'row-meta', text: meta }),
      hit.tags ? el('div', { class: 'row-tags', text: hit.tags.split(',').slice(0, 6).join(' · ') }) : null,
      el('div', { class: 'row-url', text: hit.url, title: hit.url }),
      !hit.lastCheckOk ? el('div', { class: 'row-warn', text: 'last check failed — may not play' }) : null,
    ),
    el(
      'div',
      { class: 'row-actions' },
      owned || saved
        ? el('span', { class: 'row-owned', text: 'in your collection' })
        : button('', {
            class: 'chip-button',
            iconName: 'plus',
            title: 'Add to your collection',
            'aria-label': `Add ${hit.name}`,
            onClick: () => {
              store.addStation({
                name: hit.name,
                streamUrl: hit.url,
                imageUrl: hit.favicon || undefined,
                sourceUuid: hit.uuid || undefined,
                source: hit.source,
                tags: hit.tags || undefined,
                codec: hit.codec || undefined,
                bitrate: hit.bitrate || undefined,
                country: hit.country || undefined,
                language: hit.language || undefined,
              });
              toast(`${hit.name} added.`);
              rerender();
            },
          }),
      button('', {
        class: 'row-play',
        iconName: live ? 'stop' : 'play',
        // The mixer-native idea, and the thing no other radio site has a reason to build: this
        // starts the station *as a channel in the running mix*, quietly, without keeping it. You
        // hear it over what's already playing, which is the only way to know if it belongs there.
        title: live ? 'Take out of the mix' : 'Audition into the mix',
        'aria-label': `${live ? 'Stop' : 'Audition'} ${hit.name}`,
        onClick: () => {
          if (live) {
            mixer.stop(mixerKeyFor(playable));
            rerender();
            return;
          }
          if (!mixer.play(playable, saved ? undefined : AUDITION_FADER)) {
            toast('The desk is full — four stations at once is the limit.');
          }
        },
      }),
    ),
  );
}

function mixerKeyFor(station: { id: string; streamUrl: string }): string {
  return station.id !== '' ? `id:${station.id}` : `url:${station.streamUrl}`;
}

function checkbox(label: string, initial: boolean, onChange: (on: boolean) => void): HTMLElement {
  const input = el('input', { type: 'checkbox', checked: initial }) as HTMLInputElement;
  input.addEventListener('change', () => onChange(input.checked));
  return el('label', { class: 'filter' }, input, el('span', { text: label }));
}
