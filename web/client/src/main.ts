import './styles.css';
import * as api from './api.js';
import { Mixer } from './audio/mixer.js';
import { download, share, sharingAvailable, formatDuration } from './data/recordings.js';
import {
  decodeMix,
  fetchShortMix,
  payloadIn,
  SHARE_PATH,
  SHORT_PATH,
  shortLinkIn,
  type SharedMix,
} from './data/share.js';
import * as store from './data/store.js';
import { Desk } from './ui/desk.js';
import { addStationDialog, gateDialog, sharedMixDialog } from './ui/dialogs.js';
import { button, el, relativeTime, replace, toast } from './ui/dom.js';
import { mixesPage, saveCurrent } from './ui/mixes.js';
import { recordingsPage } from './ui/recordings.js';
import { searchPage } from './ui/search.js';
import { stationsPage } from './ui/stations.js';

/**
 * Tasty Radio, on the web.
 *
 * The same idea as the Android app — several stations at once, a fader on each, and a record button
 * — with the layout freed from a phone. Read `docs/design/web.md` for what changed and why.
 */

type Tab = 'stations' | 'search' | 'mixes' | 'recordings';

const TABS: { id: Tab; label: string }[] = [
  { id: 'stations', label: 'Stations' },
  { id: 'search', label: 'Search' },
  { id: 'mixes', label: 'Mixes' },
  { id: 'recordings', label: 'Recordings' },
];

async function boot(): Promise<void> {
  const config = await api.config().catch(() => null);
  if (config?.needsCode && !config.authed) {
    gateDialog(() => void start());
    return;
  }
  await start();
}

async function start(): Promise<void> {
  store.seedIfEmpty();

  // Answered before anything reads the hash, because a mix link lives in the same place the tab
  // name does — and because what arrived decides whether the house mix gets cued at all.
  const arrived = await incomingMix();

  const mixer = new Mixer();
  const page = el('main', { class: 'page-host', id: 'page' });
  // Mixes is the front page. The point of this app is two unrelated stations becoming a third
  // thing, and that is what a mix is — a list of stations is the ingredients, not the dish.
  let tab: Tab = (location.hash.slice(1) as Tab) || 'mixes';
  if (!TABS.some((candidate) => candidate.id === tab)) tab = 'mixes';

  const desk = new Desk(
    mixer,
    () => void saveCurrent(mixer, rerender),
    () => {
      // The empty strip is an invitation; the obvious place it leads is the collection.
      if (store.stations().length === 0) addStationDialog(rerender);
      else go('stations');
    },
  );

  const nav = el('nav', { class: 'tabs', role: 'tablist' });
  const tabButtons = new Map<Tab, HTMLButtonElement>();
  for (const entry of TABS) {
    const node = button(entry.label, {
      class: 'tab',
      role: 'tab',
      onClick: () => go(entry.id),
    });
    tabButtons.set(entry.id, node);
    nav.appendChild(node);
  }

  const indexNote = el('div', { class: 'index-note' });

  const header = el(
    'header',
    { class: 'top' },
    el(
      'a',
      { class: 'brand', href: '#stations' },
      el('img', { src: '/ophelia.png', alt: '', width: '34', height: '34' }),
      el(
        'span',
        {},
        el('b', { text: 'Tasty Radio' }),
        el('small', { text: 'several stations at once' }),
      ),
    ),
    nav,
    indexNote,
  );

  document.body.append(header, page, desk.node);

  function go(next: Tab): void {
    tab = next;
    if (location.hash.slice(1) !== next) history.replaceState(null, '', `#${next}`);
    rerender();
  }

  function rerender(): void {
    for (const [id, node] of tabButtons) {
      node.dataset.current = String(id === tab);
      node.setAttribute('aria-selected', String(id === tab));
    }
    replace(page, renderTab());
  }

  function renderTab(): HTMLElement {
    switch (tab) {
      case 'search':
        return searchPage(mixer, rerender);
      case 'mixes':
        return mixesPage(mixer, rerender);
      case 'recordings':
        return recordingsPage();
      default:
        return stationsPage(mixer, rerender);
    }
  }

  window.addEventListener('hashchange', () => {
    const next = location.hash.slice(1) as Tab;
    if (TABS.some((candidate) => candidate.id === next) && next !== tab) go(next);
  });

  // The station list marks what is currently in the mix, so playing something has to repaint it.
  // Search and Mixes rebuild on their own actions; only the live markers come from here.
  let repaint = 0;
  mixer.subscribe(() => {
    if (tab !== 'stations' && tab !== 'mixes') return;
    window.clearTimeout(repaint);
    repaint = window.setTimeout(rerender, 60);
  });
  store.subscribe(() => {
    if (tab === 'stations' || tab === 'mixes') rerender();
  });

  desk.recorder.subscribe(() => {
    const state = desk.recorder.state;
    if (state.kind !== 'saved') return;
    offerRecording(state.recording, () => go('recordings'));
    desk.recorder.acknowledge();
  });

  // Radio streams do not survive a reload, and closing the tab mid-recording loses the take.
  window.addEventListener('beforeunload', (event) => {
    if (desk.recorder.isRecording) {
      event.preventDefault();
      event.returnValue = '';
    }
  });

  // A handle on the desk from the browser console. The interesting state in this app is audio state,
  // and the only way to look at a running graph is to be holding it.
  (window as unknown as { tastyRadio: unknown }).tastyRadio = { mixer, desk, store };

  void showIndexStatus(indexNote);
  rerender();
  if (arrived) openSharedMix(arrived, mixer, rerender);
  else cueTheHouseMix(mixer, rerender);
}

/**
 * A mix somebody sent, read out of the address bar.
 *
 * Two shapes, one landing. A long `/m#…` link carries the whole mix in the fragment and never
 * touches the server at all; a short `/s/<id>#<key>` one fetches a blob the server cannot read and
 * opens it with the key from the fragment. Either way the useful half travelled privately.
 *
 * The URL is emptied either way — a mix is something you either keep or don't, and leaving the link
 * in the address bar (and in their history, and in whatever they bookmark) serves nobody.
 */
async function incomingMix(): Promise<SharedMix | null> {
  const path = location.pathname.replace(/\/$/, '');

  // A short link: the mix is on the server, the key to it is in the fragment, and neither half is
  // any use without the other. Same landing as a long link once it's open.
  if (path.startsWith(`${SHORT_PATH}/`)) {
    const short = shortLinkIn(location.href);
    const shared = short ? await fetchShortMix(short.id, short.key) : null;
    history.replaceState(null, '', '/#mixes');
    if (!shared) toast('That mix link has expired or is damaged — ask whoever sent it to send it again.');
    return shared;
  }

  if (path !== SHARE_PATH && !path.startsWith(`${SHARE_PATH}/`)) return null;
  const payload = payloadIn(location.href);
  if (payload === null) {
    history.replaceState(null, '', '/#mixes');
    return null;
  }

  const shared = await decodeMix(payload);
  history.replaceState(null, '', '/#mixes');
  if (!shared) toast('That mix link is damaged — ask whoever sent it to send it again.');
  return shared;
}

/**
 * Puts a shared mix on the desk, cued, and asks whether to keep it.
 *
 * Loading it is not keeping it. Someone else's stations do not join your collection because you
 * clicked a link — that only happens if you answer the dialog with **Keep it**, which then goes
 * through the same `saveMix` the Save button uses, so the stations come along exactly as they do
 * for a channel auditioned out of search.
 */
function openSharedMix(shared: SharedMix, mixer: Mixer, rerender: () => void): void {
  mixer.load(
    shared.channels.map((channel) => ({
      station: channel.station,
      fader: channel.fader,
      muted: channel.muted,
      tone: channel.tone,
    })),
  );
  rerender();

  sharedMixDialog(shared, {
    onPlay: () => void mixer.unblock(),
    onKeep: () => {
      // Never on top of a mix you already have: every browser starts with the same three shipped
      // soundscapes, so a friend's "Ritual Gregorian" must not quietly replace yours.
      const name = store.availableMixName(shared.name);
      const result = store.saveMix(name, mixer.channels);
      void mixer.unblock();
      rerender();
      const renamed = name === shared.name ? '' : ` as "${name}"`;
      toast(
        result.stations === 0
          ? 'Could not keep that mix.'
          : `Kept "${shared.name}"${renamed}${result.added > 0 ? ` · ${result.added} new station${result.added === 1 ? '' : 's'}` : ''}.`,
      );
    },
  });
}

/** What plays when you land. The soundscape the app was built to demonstrate. */
const HOUSE_MIX = 'Ritual Gregorian';

/**
 * Puts the house mix on the desk the moment somebody arrives, and starts it if the browser lets us.
 *
 * **It usually won't**, and that is not a bug to fix but a rule to work with: browsers refuse to
 * play audio until the visitor has interacted with the page, which is the correct default for the
 * web and the reason nobody's laptop screams at them when they open a link. Chrome relaxes it for
 * sites you return to often, so this genuinely does autoplay for regulars.
 *
 * Either way the mix is *loaded* — faders, reverb and all — so the fallback is one tap on a curtain
 * rather than a hunt for the play button.
 */
function cueTheHouseMix(mixer: Mixer, rerender: () => void): void {
  const mix = store.mixes().find((candidate) => candidate.name === HOUSE_MIX) ?? store.mixes()[0];
  if (!mix) return;

  const presets = mix.channels
    .map((channel) => ({ channel, station: store.stationById(channel.stationId) }))
    .filter((entry) => entry.station !== undefined)
    .map((entry) => ({
      station: entry.station!,
      fader: entry.channel.fader,
      muted: entry.channel.muted,
      tone: store.toneOrFlat(entry.channel.tone),
    }));
  if (presets.length === 0) return;

  mixer.load(presets);
  rerender();

  // Watch for the refusal rather than guessing how long it takes to arrive — a slow machine or a
  // slow first byte would make any fixed delay wrong, in one direction or the other.
  mixer.subscribe(() => {
    if (mixer.blockedByAutoplay) showCurtain(mixer, mix.name);
  });
  // Belt and braces: some browsers simply leave the context suspended without rejecting play().
  window.setTimeout(() => {
    if (mixer.ctx.state === 'suspended') showCurtain(mixer, mix.name);
  }, 600);
}

/**
 * The one tap that starts the sound, for browsers that won't do it unasked.
 *
 * Any gesture anywhere will do — the curtain is a large obvious target rather than a small button,
 * because the thing being asked for is "touch the page", not "find the control".
 */
function showCurtain(mixer: Mixer, mixName: string): void {
  if (document.querySelector('.curtain')) return;

  const curtain = el(
    'button',
    { class: 'curtain', type: 'button', 'aria-label': `Start ${mixName}` },
    el('img', { class: 'curtain-logo', src: '/ophelia.png', alt: '' }),
    el('span', { class: 'curtain-name', text: mixName }),
    el('span', { class: 'curtain-hint', text: 'cued up — tap anywhere to play' }),
  );

  const start = () => {
    curtain.remove();
    void mixer.unblock();
  };
  curtain.addEventListener('click', start);
  // A keyboard visitor has already interacted by the time they tab to it.
  curtain.addEventListener('keydown', (event: KeyboardEvent) => {
    if (event.key === 'Enter' || event.key === ' ') start();
  });

  // If audio starts on its own after all — a returning visitor, or a slow resume — get out of
  // the way rather than making them dismiss a curtain over something already playing.
  const unsubscribe = mixer.subscribe(() => {
    if (!mixer.blockedByAutoplay && mixer.ctx.state === 'running') {
      curtain.remove();
      unsubscribe();
    }
  });

  document.body.appendChild(curtain);
  curtain.focus();
}

/**
 * The moment a recording stops is the moment you want to send it, so the share sheet is offered
 * there and then rather than filed away for later.
 */
function offerRecording(recording: Parameters<typeof download>[0], onSeeAll: () => void): void {
  const dialog = el(
    'dialog',
    { class: 'dialog' },
    el(
      'form',
      { method: 'dialog' },
      el('h2', { text: 'Recorded' }),
      el('p', { class: 'dialog-text', text: recording.fileName }),
      el('p', {
        class: 'dialog-note',
        text: `${formatDuration(recording.durationMs)} · kept in this browser until you download it.`,
      }),
      el('audio', { controls: true, class: 'rec-player', src: URL.createObjectURL(recording.blob) }),
      el(
        'menu',
        { class: 'dialog-actions' },
        button('All recordings', {
          class: 'ghost',
          onClick: () => onSeeAll(),
        }),
        sharingAvailable()
          ? button('Share', {
              class: 'ghost',
              iconName: 'share',
              onClick: async (event: Event) => {
                event.preventDefault();
                if (!(await share(recording))) toast('Sharing is not available here — download it instead.');
              },
            })
          : null,
        button('Download', {
          class: 'primary',
          iconName: 'download',
          onClick: (event: Event) => {
            event.preventDefault();
            download(recording);
          },
        }),
        button('Done', { class: 'ghost', value: 'close' }),
      ),
    ),
  ) as HTMLDialogElement;

  document.body.appendChild(dialog);
  dialog.addEventListener('close', () => dialog.remove());
  dialog.showModal();
}

/**
 * Sync must be visible: a background job silently mutating the thing you search is what makes an
 * app feel untrustworthy, especially when results change and you can't tell why.
 */
async function showIndexStatus(node: HTMLElement): Promise<void> {
  try {
    const status = await api.indexStatus();
    if (!status.ready) {
      replace(node, el('span', { class: 'index-warn', text: 'station index downloading…' }));
      setTimeout(() => void showIndexStatus(node), 15_000);
      return;
    }
    const built = status.builtAt ? ` · built ${relativeTime(status.builtAt)}` : '';
    const partial = status.complete ? '' : ' · partial';
    replace(
      node,
      el('span', {
        class: 'index-ok',
        text: `${status.total.toLocaleString()} stations${built}${partial}`,
        title: status.expected ? `radio-browser reported ${status.expected.toLocaleString()}` : '',
      }),
    );
  } catch {
    replace(node, el('span', { class: 'index-warn', text: 'index unavailable' }));
  }
}

void boot();
