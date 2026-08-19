import { DEFAULT_DELAY_MS } from '../audio/graph.js';
import type { Station } from './types.js';

/**
 * What a browser arrives with the first time it opens the site — the owner's own stations and the
 * owner's own soundscapes, transcribed from their phone.
 *
 * The point of the app should be one click from opening it, so the ingredients of the shipped mixes
 * sit at the top of the list and the mixes are already built. This is the same seed data the
 * Android app carries, deliberately: the two versions should feel like the same radio.
 *
 * Note what's in here, because it's the test suite for "streaming radio is messy": cleartext
 * `http://` (ABC, Gove FM, Radio Art, RadCap, Art Bell), a `livestream-redirect` URL that 302s
 * elsewhere (Gove FM), non-standard ports (RadCap, Art Bell), and a query token that returns 401 if
 * you drop it (Radio Art). If all of these play, the proxy is right.
 */

export const TASTY_RADIO = 'https://radio.aleph-art.com/listen/tastiest/radio.mp3';
export const RADCAP_RITUAL = 'http://79.120.39.202:8000/darkambient';
export const SEX_SOUND = 'https://sexsoundradio.com:8000/radio.mp3';
export const ART_BELL = 'http://stream.willstare.com:8450/';
export const GREGORIAN_CHANTS =
  'http://air.radioart.online/hGregorian_chants.mp3?dlid=db5ceb796b3f6b3b40cb449ed670f317';

export const SEED_STATIONS: Omit<Station, 'id'>[] = [
  {
    name: 'Tasty Radio',
    streamUrl: TASTY_RADIO,
    // Millais's Ophelia — the artwork on the station itself, and the reason it's the app's icon.
    // Bundled rather than hotlinked: the one station that should never show a placeholder is the
    // one the app is named after.
    imageUrl: '/ophelia.png',
    source: 'manual',
    tags: 'techno,jungle,house,juke,dub,jazz,rock,folk',
    codec: 'MP3',
    bitrate: 320,
    sortOrder: 0,
  },
  {
    name: 'RADCAP: INDUSTRIAL / DARK / RITUAL AMBIENT',
    streamUrl: RADCAP_RITUAL,
    imageUrl: 'http://radcap.ru/stylegraf/ritamb-c.jpg',
    sourceUuid: '0a66e47d-eabc-47ce-83e1-12fe7dcbda7d',
    source: 'radio-browser',
    tags: 'dark ambient,industrial,ritual',
    codec: 'AAC+',
    bitrate: 320,
    country: 'Russia',
    sortOrder: 1,
  },
  {
    name: 'Sex Sound Radio',
    streamUrl: SEX_SOUND,
    // The directory's favicon for this one is a genuine Windows .ico; the site's apple-touch-icon
    // is the same logo as a PNG.
    imageUrl: 'https://sexsoundradio.com/apple-touch-icon.png',
    sourceUuid: '81a9eb5b-2da6-472c-a6c7-1ebe3f3e87c4',
    source: 'radio-browser',
    tags: 'adult,asmr,erotic,explicit,female voices,noise,voices',
    codec: 'MP3',
    bitrate: 128,
    country: 'Moldova',
    sortOrder: 2,
  },
  {
    name: 'The Ultimate Art Bell',
    streamUrl: ART_BELL,
    // No artwork anywhere for this one — the monogram fallback earns its keep.
    sourceUuid: '1125f06b-01ff-4794-b347-e83128ec4a9f',
    source: 'radio-browser',
    tags: 'art bell,coast to coast am,conspiracies,conspiracy theories,paranormal',
    codec: 'MP3',
    bitrate: 32,
    country: 'United States',
    language: 'english',
    sortOrder: 3,
  },
  {
    // The ?dlid= token is not decoration: without it this host answers 401.
    name: 'Radio Art - Gregorian Chants',
    streamUrl: GREGORIAN_CHANTS,
    sortOrder: 4,
  },
  {
    name: 'ABC Radio National',
    streamUrl: 'http://abc.streamguys1.com/live/rnnsw/icecast.audio',
    imageUrl: 'http://www.abc.net.au/core-assets/radionational/favicon-32x32.png',
    sortOrder: 5,
  },
  {
    name: 'Ethereal Radio',
    streamUrl: 'https://s5.radio.co/saac615442/listen',
    imageUrl: 'https://pbs.twimg.com/profile_images/1461828969821581325/I9NhigQp_400x400.jpg',
    sortOrder: 6,
  },
  {
    name: 'Gove FM - Nhulunbuy 106.9',
    streamUrl: 'http://playerservices.streamtheworld.com/api/livestream-redirect/8EAR.mp3',
    imageUrl: 'https://i.ibb.co/7xD4wbj4/274663470-10159198533677763-3441138051921838720-n.jpg',
    sortOrder: 7,
  },
  {
    name: 'Radio Vaticana English',
    streamUrl: 'https://radio.vaticannews.va/stream-en',
    imageUrl:
      'https://media.vaticannews.va/media/content/dam-archive/vaticannews/multimedia/2021/02/09/' +
      '2021.02.09-Logo-Radio-Vaticana.jpg/_jcr_content/renditions/cq5dam.thumbnail.cropped.1000.563.jpeg',
    sortOrder: 8,
  },
  {
    name: 'Resonance 104.4FM',
    streamUrl: 'https://stream.resonance.fm/resonance',
    imageUrl: 'https://www.resonancefm.com/assets/logo-a79206a34394173ba3e41d66a3388f4c.png',
    sortOrder: 9,
  },
];

export interface SeedMixChannel {
  streamUrl: string;
  fader: number;
  reverb?: number;
  delay?: number;
  delayMs?: number;
  toneLow?: number;
  toneMid?: number;
  toneHigh?: number;
}

/**
 * The mixes the site ships with — the reason it exists, already built, so a fresh browser is one
 * click away from two unrelated stations becoming a third thing.
 *
 * Channels point at stations by stream URL rather than by id, because ids are assigned when the
 * collection is seeded and a URL is the one part of a station that doesn't move.
 */
export const SEED_MIXES: { name: string; channels: SeedMixChannel[] }[] = [
  {
    // Ritual ambient under plainsong: the same music twice, eight hundred years apart.
    name: 'Ritual Gregorian',
    channels: [
      { streamUrl: RADCAP_RITUAL, fader: 0.62 },
      // The chants sit above it and in a bigger room than the drone they're over.
      { streamUrl: GREGORIAN_CHANTS, fader: 0.77, reverb: 0.65 },
    ],
  },
  {
    // Late-night conspiracy radio, breathing, and a dark drone under both.
    name: 'The ULTIMATE Art Bell',
    channels: [
      { streamUrl: RADCAP_RITUAL, fader: 0.65 },
      { streamUrl: SEX_SOUND, fader: 0.75 },
      // A 32 kbps phone-in from 1997 wants a room around it.
      { streamUrl: ART_BELL, fader: 0.75, reverb: 0.45 },
    ],
  },
  {
    // Not a soundscape — just the station this app is named after, on its own.
    name: 'Tasty Radio',
    channels: [{ streamUrl: TASTY_RADIO, fader: 0.75 }],
  },
];

export const DEFAULT_DELAY = DEFAULT_DELAY_MS;
