import { FLAT, type Tone } from '../audio/graph.js';
import type { Channel } from '../audio/mixer.js';
import { SEED_MIXES, SEED_STATIONS, DEFAULT_DELAY } from './seed.js';
import type { Mix, MixChannel, Station } from './types.js';

/**
 * The collection and the saved mixes, in the browser.
 *
 * No accounts and no server-side user data — the app has never had either, and adding them for a
 * handful of friends would be the tail wagging the dog. Your stations live in your browser, which
 * also means the server holds nothing about you beyond which streams it relayed.
 *
 * The trade is honest and worth writing down: clearing site data loses your collection, and it does
 * not follow you between devices. M3U export exists for exactly that reason.
 */

const KEY = 'tastyradio.v1';

interface Snapshot {
  version: 1;
  stations: Station[];
  mixes: Mix[];
  seeded: boolean;
}

function emptySnapshot(): Snapshot {
  return { version: 1, stations: [], mixes: [], seeded: false };
}

function load(): Snapshot {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return emptySnapshot();
    const parsed = JSON.parse(raw) as Snapshot;
    if (parsed.version !== 1 || !Array.isArray(parsed.stations)) return emptySnapshot();
    return { ...emptySnapshot(), ...parsed };
  } catch {
    return emptySnapshot();
  }
}

let snapshot = load();
const listeners = new Set<() => void>();

function persist(): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(snapshot));
  } catch {
    // A full quota shouldn't take the app down; the in-memory copy still works for this session.
  }
  for (const listener of listeners) listener();
}

export function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function newId(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

// ---------------------------------------------------------------- stations

export function stations(): Station[] {
  return [...snapshot.stations].sort((a, b) => a.sortOrder - b.sortOrder);
}

export function stationById(id: string): Station | undefined {
  return snapshot.stations.find((station) => station.id === id);
}

export function stationByUrl(url: string): Station | undefined {
  return snapshot.stations.find((station) => station.streamUrl === url);
}

export function addStation(station: Omit<Station, 'id' | 'sortOrder'> & { sortOrder?: number }): Station {
  const existing = stationByUrl(station.streamUrl);
  if (existing) return existing;

  const created: Station = {
    ...station,
    id: newId(),
    sortOrder: station.sortOrder ?? nextSortOrder(),
  };
  snapshot.stations.push(created);
  persist();
  return created;
}

function nextSortOrder(): number {
  return snapshot.stations.reduce((max, station) => Math.max(max, station.sortOrder), -1) + 1;
}

export function updateStation(id: string, patch: Partial<Omit<Station, 'id'>>): void {
  const station = snapshot.stations.find((candidate) => candidate.id === id);
  if (!station) return;
  Object.assign(station, patch);
  persist();
}

export function removeStation(id: string): void {
  snapshot.stations = snapshot.stations.filter((station) => station.id !== id);
  // A mix that has lost a station keeps its other channels rather than vanishing.
  for (const mix of snapshot.mixes) {
    mix.channels = mix.channels.filter((channel) => channel.stationId !== id);
  }
  snapshot.mixes = snapshot.mixes.filter((mix) => mix.channels.length > 0);
  persist();
}

export function reorderStations(orderedIds: string[]): void {
  orderedIds.forEach((id, index) => {
    const station = snapshot.stations.find((candidate) => candidate.id === id);
    if (station) station.sortOrder = index;
  });
  persist();
}

// ---------------------------------------------------------------- mixes

export function mixes(): Mix[] {
  return [...snapshot.mixes].sort((a, b) => b.createdAt - a.createdAt);
}

/**
 * Saves the mix exactly as it currently sounds — faders, mutes and tone.
 *
 * **A channel auditioned from search is collected on the way in.** A saved mix is a list of station
 * rows, so a channel with no row cannot be in one. The Android app used to drop those silently and
 * the mix came back a station short next session; asking to save the mix *is* asking to keep what
 * is in it, so anything not yet in the collection gets added — matched by stream URL, so a station
 * you already have is never duplicated.
 *
 * Saving over a name you already used means update, not duplicate.
 */
export function saveMix(name: string, channels: Channel[]): { stations: number; replaced: boolean; added: number } {
  const cleanName = name.trim();
  if (cleanName === '' || channels.length === 0) return { stations: 0, replaced: false, added: 0 };

  let added = 0;
  const saveable: MixChannel[] = [];
  for (const channel of channels) {
    let station = channel.station;
    if (station.id === '') {
      const existing = stationByUrl(station.streamUrl);
      if (existing) {
        station = existing;
      } else {
        station = addStation({
          name: station.name,
          streamUrl: station.streamUrl,
          imageUrl: station.imageUrl,
          sourceUuid: station.sourceUuid,
          source: station.source,
          tags: station.tags,
          codec: station.codec,
          bitrate: station.bitrate,
          country: station.country,
          language: station.language,
        });
        added++;
      }
    }
    saveable.push({
      stationId: station.id,
      fader: channel.fader,
      muted: channel.muted,
      tone: { ...channel.tone },
    });
  }

  const existing = snapshot.mixes.find((mix) => mix.name === cleanName);
  if (existing) {
    existing.channels = saveable;
    existing.createdAt = Date.now();
  } else {
    snapshot.mixes.push({ id: newId(), name: cleanName, createdAt: Date.now(), channels: saveable });
  }
  persist();
  return { stations: saveable.length, replaced: existing !== undefined, added };
}

export function renameMix(id: string, name: string): void {
  const mix = snapshot.mixes.find((candidate) => candidate.id === id);
  if (!mix || name.trim() === '') return;
  mix.name = name.trim();
  persist();
}

export function deleteMix(id: string): void {
  snapshot.mixes = snapshot.mixes.filter((mix) => mix.id !== id);
  persist();
}

// ---------------------------------------------------------------- first run

/**
 * A fresh browser arrives with the shipped soundscapes already built, so the point of the app is
 * one click away rather than something you have to assemble first.
 *
 * Runs once. Deleting every mix does not bring them back — unlike the phone, where an empty table
 * reseeds — because on the web "I deleted these" should stay deleted through a page reload.
 */
export function seedIfEmpty(): void {
  if (snapshot.seeded) return;
  snapshot.seeded = true;

  if (snapshot.stations.length === 0) {
    for (const seed of SEED_STATIONS) {
      snapshot.stations.push({ ...seed, id: newId() });
    }
  }

  if (snapshot.mixes.length === 0) {
    const now = Date.now();
    SEED_MIXES.forEach((preset, index) => {
      const channels: MixChannel[] = [];
      for (const seed of preset.channels) {
        const station = snapshot.stations.find((candidate) => candidate.streamUrl === seed.streamUrl);
        if (!station) return; // a preset whose stations aren't there is skipped, not half-built
        channels.push({
          stationId: station.id,
          fader: seed.fader,
          muted: false,
          tone: {
            low: seed.toneLow ?? 0,
            mid: seed.toneMid ?? 0,
            high: seed.toneHigh ?? 0,
            reverb: seed.reverb ?? 0,
            delay: seed.delay ?? 0,
            delayMs: seed.delayMs ?? DEFAULT_DELAY,
          },
        });
      }
      if (channels.length !== preset.channels.length) return;
      // Newest-first on the Mixes page, so stagger the timestamps to keep the shipped order.
      snapshot.mixes.push({ id: newId(), name: preset.name, createdAt: now - index, channels });
    });
  }

  persist();
}

export function toneOrFlat(tone: Partial<Tone> | undefined): Tone {
  return { ...FLAT, ...(tone ?? {}) };
}

// ---------------------------------------------------------------- import / export

/** Transistor's own *Export M3U* format, which is how the owner's list arrived in the first place. */
export function exportM3u(): string {
  const lines = ['#EXTM3U', ''];
  for (const station of stations()) {
    lines.push(`#EXTINF:-1,${station.name}`);
    lines.push(station.streamUrl);
    lines.push('');
  }
  return lines.join('\n');
}

export function importStations(entries: { name: string | null; url: string }[]): number {
  let added = 0;
  for (const entry of entries) {
    if (!/^https?:\/\//i.test(entry.url)) continue;
    if (stationByUrl(entry.url)) continue;
    addStation({ name: entry.name?.trim() || hostOf(entry.url), streamUrl: entry.url, source: 'import' });
    added++;
  }
  return added;
}

export function hostOf(url: string): string {
  try {
    return new URL(url).hostname;
  } catch {
    return url;
  }
}

/** Everything, as one file — the backup M3U export can't carry because it has no room for artwork. */
export function exportBackup(): string {
  return JSON.stringify(snapshot, null, 2);
}

export function importBackup(raw: string): boolean {
  try {
    const parsed = JSON.parse(raw) as Snapshot;
    if (parsed.version !== 1 || !Array.isArray(parsed.stations)) return false;
    snapshot = { ...emptySnapshot(), ...parsed, seeded: true };
    persist();
    return true;
  } catch {
    return false;
  }
}
