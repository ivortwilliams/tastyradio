import type { Mixer } from '../audio/mixer.js';
import * as store from '../data/store.js';
import type { Station } from '../data/types.js';
import { artwork } from './artwork.js';
import { addStationDialog, confirmDialog, editStationDialog } from './dialogs.js';
import { button, el, icon, toast } from './dom.js';

/**
 * The collection — the home page, and the closest thing here to Transistor's list.
 *
 * The one difference that matters: a row's play button *adds* to the mix rather than replacing it.
 * Tapping play on a second station while a first one is running is the entire gesture the app is
 * built around, and it has to stay one click.
 */
export function stationsPage(mixer: Mixer, rerender: () => void): HTMLElement {
  const stations = store.stations();

  const list = el('div', { class: 'rows' });
  for (const station of stations) {
    list.appendChild(stationRow(station, mixer, rerender));
  }

  return el(
    'div',
    { class: 'page' },
    el(
      'header',
      { class: 'page-head' },
      el('div', {}, el('h1', { text: 'Stations' }), el('p', { class: 'page-sub', text: `${stations.length} in your collection` })),
      el(
        'div',
        { class: 'page-actions' },
        button('Add station', { class: 'primary', iconName: 'plus', onClick: () => addStationDialog(rerender) }),
        button('Export M3U', {
          class: 'ghost',
          iconName: 'download',
          title: 'The same format Transistor exports, so your list travels',
          onClick: () => downloadText('tasty-radio.m3u', store.exportM3u()),
        }),
      ),
    ),
    stations.length === 0
      ? el('p', { class: 'empty', text: 'No stations yet. Add one by URL, import an M3U, or find something in Search.' })
      : list,
  );
}

function stationRow(station: Station, mixer: Mixer, rerender: () => void): HTMLElement {
  const live = mixer.isLive(station);

  const play = button('', {
    class: 'row-play',
    iconName: live ? 'stop' : 'play',
    title: live ? 'Take out of the mix' : 'Add to the mix',
    'aria-label': `${live ? 'Stop' : 'Play'} ${station.name}`,
    onClick: () => {
      if (!mixer.toggle(station)) {
        toast(`The desk is full — four stations at once is the limit.`);
      }
    },
  });

  const meta = [station.codec, station.bitrate ? `${station.bitrate} kbps` : null, station.country]
    .filter(Boolean)
    .join(' · ');

  return el(
    'article',
    { class: 'row', dataset: { live: String(live) } },
    artwork(station, 46),
    el(
      'div',
      { class: 'row-body' },
      el('div', { class: 'row-name', text: station.name }),
      el('div', { class: 'row-meta', text: meta || store.hostOf(station.streamUrl) }),
      station.tags ? el('div', { class: 'row-tags', text: station.tags.split(',').slice(0, 5).join(' · ') }) : null,
    ),
    el(
      'div',
      { class: 'row-actions' },
      live ? el('span', { class: 'live-dot', title: 'In the mix' }) : null,
      button('', {
        class: 'chip-button',
        iconName: 'edit',
        title: 'Edit',
        'aria-label': `Edit ${station.name}`,
        onClick: () => editStationDialog(station, rerender),
      }),
      button('', {
        class: 'chip-button danger',
        iconName: 'cross',
        title: 'Remove from the collection',
        'aria-label': `Remove ${station.name}`,
        onClick: async () => {
          if (await confirmDialog('Remove station', `Remove ${station.name} from your collection?`, 'Remove')) {
            store.removeStation(station.id);
            rerender();
          }
        },
      }),
      play,
    ),
  );
}

export function downloadText(fileName: string, text: string): void {
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = el('a', { href: url, download: fileName }) as HTMLAnchorElement;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}

export { icon };
