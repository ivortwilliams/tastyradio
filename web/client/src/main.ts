import './styles.css';
import * as api from './api.js';
import { Mixer } from './audio/mixer.js';
import { download, share, sharingAvailable, formatDuration } from './data/recordings.js';
import * as store from './data/store.js';
import { Desk } from './ui/desk.js';
import { addStationDialog, gateDialog } from './ui/dialogs.js';
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

  const mixer = new Mixer();
  const page = el('main', { class: 'page-host', id: 'page' });
  let tab: Tab = (location.hash.slice(1) as Tab) || 'stations';
  if (!TABS.some((candidate) => candidate.id === tab)) tab = 'stations';

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
