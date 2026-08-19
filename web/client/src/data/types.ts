import type { Tone } from '../audio/graph.js';

/**
 * A station you have chosen to keep. Curated, not browsed — once it's here, it's yours.
 *
 * `streamUrl` is deliberately editable and shown to the user: this audience is trusted with the
 * plumbing, exactly as in the Android app and in Transistor before it.
 */
export interface Station {
  /** Empty for a station that isn't in the collection — a search result being auditioned. */
  id: string;
  name: string;
  streamUrl: string;
  imageUrl?: string;
  sortOrder: number;
  /**
   * Carried from whichever directory this came from. `POST /json/url/{uuid}` on play is what feeds
   * radio-browser's clickcount — the popularity signal search ranks on.
   */
  sourceUuid?: string;
  /** Which directory: `radio-browser`, `manual`, `import`. */
  source?: string;
  tags?: string;
  codec?: string;
  bitrate?: number;
  country?: string;
  language?: string;
}

/**
 * Identifies a channel on the desk. Saved stations key on their id; a station that isn't in the
 * collection has no id, so it keys on its stream URL instead. Getting this wrong would make every
 * auditioned station collide with every other.
 */
export function channelKey(station: Station): string {
  return station.id !== '' ? `id:${station.id}` : `url:${station.streamUrl}`;
}

/** A saved soundscape: which stations, at what levels, with what tone. */
export interface Mix {
  id: string;
  name: string;
  createdAt: number;
  channels: MixChannel[];
}

export interface MixChannel {
  stationId: string;
  fader: number;
  muted: boolean;
  tone: Tone;
}

/** A search result, as the server returns it. */
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

/** A hit, as a station you could play. Not saved until you press ＋. */
export function stationFromHit(hit: Hit): Station {
  return {
    id: '',
    name: hit.name,
    streamUrl: hit.url,
    imageUrl: hit.favicon || undefined,
    sortOrder: 0,
    sourceUuid: hit.uuid || undefined,
    source: hit.source || 'radio-browser',
    tags: hit.tags || undefined,
    codec: hit.codec || undefined,
    bitrate: hit.bitrate || undefined,
    country: hit.country || undefined,
    language: hit.language || undefined,
  };
}
