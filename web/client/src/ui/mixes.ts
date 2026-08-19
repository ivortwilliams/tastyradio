import type { Mixer, Preset } from '../audio/mixer.js';
import * as store from '../data/store.js';
import { artwork } from './artwork.js';
import { confirmDialog, promptDialog } from './dialogs.js';
import { button, el, toast } from './dom.js';

/**
 * Saved soundscapes.
 *
 * A combination you stumbled on is worth nothing if you can't get back to it, and this is cheap to
 * store — a handful of ids and floats. It is also where the shipped mixes live, so a browser that
 * has never been here before is one click from hearing what the app is for.
 */
export function mixesPage(mixer: Mixer, rerender: () => void): HTMLElement {
  const mixes = store.mixes();

  return el(
    'div',
    { class: 'page' },
    el(
      'header',
      { class: 'page-head' },
      el(
        'div',
        {},
        el('h1', { text: 'Mixes' }),
        el('p', {
          class: 'page-sub',
          text: 'A saved set of stations, with their faders and tone exactly where you left them.',
        }),
      ),
      el(
        'div',
        { class: 'page-actions' },
        button('Save what is playing', {
          class: 'primary',
          iconName: 'save',
          onClick: () => void saveCurrent(mixer, rerender),
        }),
      ),
    ),
    mixes.length === 0
      ? el('p', { class: 'empty', text: 'No saved mixes. Start some stations, balance them, then save.' })
      : el('div', { class: 'mix-grid' }, ...mixes.map((mix) => mixCard(mix, mixer, rerender))),
  );
}

export async function saveCurrent(mixer: Mixer, rerender: () => void): Promise<void> {
  const channels = mixer.channels;
  if (channels.length === 0) {
    toast('Nothing playing to save.');
    return;
  }
  const suggested = channels.map((channel) => channel.station.name).join(' + ');
  const name = await promptDialog('Save this mix', 'Call it', suggested.slice(0, 60));
  if (!name) return;

  const result = store.saveMix(name, channels);
  if (result.stations === 0) {
    toast('Could not save that mix.');
    return;
  }
  // A channel auditioned from search has no row of its own; saving the mix is what keeps it.
  const extra = result.added > 0 ? ` (${result.added} new station${result.added === 1 ? '' : 's'} kept)` : '';
  toast(`${result.replaced ? 'Updated' : 'Saved'} "${name}"${extra}.`);
  rerender();
}

function mixCard(mix: ReturnType<typeof store.mixes>[number], mixer: Mixer, rerender: () => void): HTMLElement {
  const stations = mix.channels
    .map((channel) => ({ channel, station: store.stationById(channel.stationId) }))
    .filter((entry): entry is { channel: typeof entry.channel; station: NonNullable<typeof entry.station> } =>
      entry.station !== undefined,
    );

  const art = el('div', { class: 'mix-art' }, ...stations.slice(0, 3).map((entry) => artwork(entry.station, 40)));

  const lines = stations.map((entry) => {
    const tone: string[] = [];
    if (entry.channel.tone.reverb > 0.005) tone.push(`${Math.round(entry.channel.tone.reverb * 100)}% reverb`);
    if (entry.channel.tone.delay > 0.005) tone.push(`${Math.round(entry.channel.tone.delay * 100)}% delay`);
    const shaped = [entry.channel.tone.low, entry.channel.tone.mid, entry.channel.tone.high].some((v) => v !== 0);
    if (shaped) tone.push('EQ');
    return el(
      'li',
      {},
      el('span', { class: 'mix-station', text: entry.station.name }),
      el('span', { class: 'mix-level', text: `${Math.round(entry.channel.fader * 100)}%` }),
      tone.length > 0 ? el('span', { class: 'mix-tone', text: tone.join(' · ') }) : null,
    );
  });

  return el(
    'article',
    { class: 'mix-card' },
    art,
    el('h3', { class: 'mix-name', text: mix.name }),
    el('ul', { class: 'mix-list' }, ...lines),
    el(
      'div',
      { class: 'mix-actions' },
      button('Play this mix', {
        class: 'primary',
        iconName: 'play',
        onClick: () => {
          const presets: Preset[] = stations.map((entry) => ({
            station: entry.station,
            fader: entry.channel.fader,
            muted: entry.channel.muted,
            tone: store.toneOrFlat(entry.channel.tone),
          }));
          if (presets.length === 0) {
            toast('That mix has lost its stations.');
            return;
          }
          // Loading a mix is "play this instead of what's on", not "add to it" — otherwise
          // recalling a mix while something plays gives you neither.
          mixer.load(presets);
          rerender();
        },
      }),
      button('', {
        class: 'chip-button',
        iconName: 'edit',
        title: 'Rename',
        'aria-label': `Rename ${mix.name}`,
        onClick: async () => {
          const name = await promptDialog('Rename mix', 'Call it', mix.name);
          if (name) {
            store.renameMix(mix.id, name);
            rerender();
          }
        },
      }),
      button('', {
        class: 'chip-button danger',
        iconName: 'cross',
        title: 'Delete',
        'aria-label': `Delete ${mix.name}`,
        onClick: async () => {
          if (await confirmDialog('Delete mix', `Delete "${mix.name}"? The stations stay in your collection.`)) {
            store.deleteMix(mix.id);
            rerender();
          }
        },
      }),
    ),
  );
}
