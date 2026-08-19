import type HlsType from 'hls.js';
import { ChannelGraph, FLAT, type Tone } from './graph.js';
import type { Station } from '../data/types.js';
import { channelKey } from '../data/types.js';
import { streamUrl, forgetChannel, reportClick } from '../api.js';

/**
 * The mixing desk. One `<audio>` element and one signal path per active station.
 *
 * This is the class the whole app exists for, and the one place where Tasty Radio stops resembling
 * every other radio site: a station is not *the* playback, it's a channel on a mix.
 *
 * ## Why the audio goes through our own server
 * Everything interesting here — per-channel EQ, reverb, metering, recording — needs the stream
 * inside Web Audio. A `MediaElementAudioSourceNode` built over a cross-origin stream without CORS
 * headers is tainted and outputs silence, and essentially no radio station sends those headers. So
 * every stream is relayed same-origin by the proxy. That is not a workaround bolted on afterwards;
 * it is the reason a server exists at all.
 */

export type ChannelState = 'connecting' | 'playing' | 'failed';

export interface Channel {
  /**
   * Identifies the channel. Saved stations key on their id; a station that isn't in the collection
   * — a search result being auditioned — has no id, so it keys on its stream URL instead. Keying on
   * the id would make every auditioned station collide with every other.
   */
  key: string;
  station: Station;
  tone: Tone;
  fader: number;
  muted: boolean;
  state: ChannelState;
  /** ICY stream metadata, pushed from the proxy — the current track, when the station sends one. */
  nowPlaying: string | null;
  error: string | null;
}

interface Live {
  channel: Channel;
  element: HTMLAudioElement;
  graph: ChannelGraph;
  hls: HlsType | null;
  watchdog: number | null;
  meterBuffer: Uint8Array;
}

/**
 * Not a hard limit — past four the returns drop and the costs don't: four streams is a steady
 * ~64 KB/s each way, four decoders, four sockets.
 */
export const MAX_CHANNELS = 4;
export const DEFAULT_FADER = 0.75;
/** A quiet default, so an audition slips under the running mix instead of over it. */
export const AUDITION_FADER = 0.4;

/** How long a channel may sit connecting before it's called a failure. */
const STALL_TIMEOUT_MS = 20_000;

export interface Preset {
  station: Station;
  fader: number;
  muted: boolean;
  tone: Tone;
}

export class Mixer {
  readonly ctx: AudioContext;
  readonly master: GainNode;
  /** What the recorder taps. Post-fader, so a take is exactly what you heard. */
  readonly recordTap: MediaStreamAudioDestinationNode;

  private readonly live = new Map<string, Live>();
  private readonly listeners = new Set<() => void>();
  readonly sid: string;

  /**
   * True when the browser refused to start audio because nobody had interacted with the page yet.
   * The site cues its house mix up on landing, so this is the normal state of a first visit.
   */
  blockedByAutoplay = false;

  constructor() {
    this.ctx = new AudioContext({ latencyHint: 'playback' });
    this.master = this.ctx.createGain();
    this.master.gain.value = 1;
    this.recordTap = this.ctx.createMediaStreamDestination();
    this.master.connect(this.ctx.destination);
    this.master.connect(this.recordTap);

    let sid = sessionStorage.getItem('tastyradio.sid');
    if (!sid) {
      sid = Math.random().toString(36).slice(2) + Date.now().toString(36);
      sessionStorage.setItem('tastyradio.sid', sid);
    }
    this.sid = sid;
    this.listenForMetadata();
    this.wireMediaSession();
  }

  // ---------------------------------------------------------------- observation

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private changed(): void {
    for (const listener of this.listeners) listener();
    this.updateMediaSession();
  }

  get channels(): Channel[] {
    return [...this.live.values()].map((entry) => entry.channel);
  }

  isLive(station: Station): boolean {
    return this.live.has(channelKey(station));
  }

  channel(key: string): Channel | undefined {
    return this.live.get(key)?.channel;
  }

  graphFor(key: string): { graph: ChannelGraph; buffer: Uint8Array } | null {
    const entry = this.live.get(key);
    return entry ? { graph: entry.graph, buffer: entry.meterBuffer } : null;
  }

  // ---------------------------------------------------------------- channel control

  /**
   * Adds a station to the mix. `fader` lets an audition start quietly under what's already playing.
   *
   * @returns false if the mix is already full, so the caller can say so.
   */
  play(station: Station, fader = DEFAULT_FADER, tone: Tone = FLAT): boolean {
    const key = channelKey(station);
    if (this.live.has(key)) return true;
    if (this.live.size >= MAX_CHANNELS) return false;

    // Browsers start the context suspended until a gesture. Every route into here is a click.
    void this.ctx.resume();

    const element = new Audio();
    element.preload = 'none';
    element.autoplay = false;
    // The graph is the only route to the speakers; the element's own output is taken over by
    // createMediaElementSource. Leaving this at 1 keeps the fader the single volume control.
    element.volume = 1;

    const graph = new ChannelGraph(this.ctx, element, this.master);
    const channel: Channel = {
      key,
      station,
      tone,
      fader,
      muted: false,
      state: 'connecting',
      nowPlaying: null,
      error: null,
    };
    const entry: Live = {
      channel,
      element,
      graph,
      hls: null,
      watchdog: null,
      meterBuffer: new Uint8Array(graph.analyser.fftSize),
    };
    this.live.set(key, entry);

    graph.setTone(tone);
    graph.setLevel(fader, false);
    this.attachElementEvents(entry);
    this.start(entry);
    this.changed();

    // Feed radio-browser's popularity signal — the thing search ranks on. Fire and forget.
    if (station.sourceUuid) void reportClick(station.sourceUuid);
    return true;
  }

  private start(entry: Live): void {
    const { channel, element } = entry;
    const source = streamUrl(channel.station.streamUrl, this.sid, channel.key);

    entry.hls?.destroy();
    entry.hls = null;

    // Pause before repointing, then load once.
    //
    // ⚠️ Do **not** clear `src` and call `load()` in between. That runs the resource selection
    // algorithm against an empty source, which fires `error` with MEDIA_ERR_SRC_NOT_SUPPORTED — a
    // spurious "no supported source was found" on a stream that is perfectly fine. It cost an
    // evening: RadCap kept dying on the landing mix while the proxy was demonstrably serving it.
    element.pause();

    // Most radio is a plain audio stream. HLS is a real but small minority of the corpus, and
    // hls.js is most of this app's JavaScript, so it is fetched only when something actually needs
    // it rather than by everybody on every visit.
    const looksHls = /\.m3u8(\?|$)/i.test(channel.station.streamUrl);
    const nativeHls = element.canPlayType('application/vnd.apple.mpegurl') !== '';

    if (looksHls && !nativeHls) {
      void this.startHls(entry, source);
    } else {
      element.src = source;
      element.load();
      this.beginPlayback(entry);
    }
    this.armWatchdog(entry);
  }

  private async startHls(entry: Live, source: string): Promise<void> {
    let Hls: typeof HlsType;
    try {
      Hls = (await import('hls.js')).default;
    } catch {
      this.fail(entry, 'could not load the HLS player');
      return;
    }
    // The channel may have been stopped while that was downloading.
    if (!this.live.has(entry.channel.key)) return;

    if (!Hls.isSupported()) {
      this.fail(entry, 'this browser cannot play HLS');
      return;
    }

    const hls = new Hls({ enableWorker: true, lowLatencyMode: false });
    entry.hls = hls;
    hls.on(Hls.Events.ERROR, (_event, data) => {
      if (!data.fatal) return;
      this.fail(entry, data.details ?? 'HLS error');
    });
    hls.loadSource(source);
    hls.attachMedia(entry.element);
    this.beginPlayback(entry);
  }

  private beginPlayback(entry: Live): void {
    void entry.element.play().catch((error: DOMException) => {
      // A rejected play() before any user gesture is the browser's autoplay policy, not a dead
      // station. It is emphatically *not* a failure — the watchdog is stood down and the UI offers
      // a tap, because calling this channel broken would be a lie about a stream we never tried.
      if (error.name === 'NotAllowedError') {
        this.clearWatchdog(entry);
        this.blockedByAutoplay = true;
        this.changed();
        return;
      }
      this.fail(entry, error.message);
    });
  }

  /**
   * Starts everything that the autoplay policy refused. **Must be called from a real user gesture**
   * — that is the entire point of the policy, and a resume() outside one is ignored.
   */
  async unblock(): Promise<void> {
    await this.ctx.resume().catch(() => undefined);
    this.blockedByAutoplay = false;
    for (const entry of this.live.values()) {
      // A full restart rather than another play(): a stream can drop while it sits waiting for
      // somebody to tap, and resuming a dead element hands back a channel that is already broken.
      this.update(entry.channel.key, (channel) => ({ ...channel, state: 'connecting', error: null }));
      this.start(entry);
    }
    this.changed();
  }

  private attachElementEvents(entry: Live): void {
    const { element } = entry;

    element.addEventListener('playing', () => {
      this.clearWatchdog(entry);
      this.update(entry.channel.key, (channel) => ({ ...channel, state: 'playing', error: null }));
    });
    element.addEventListener('waiting', () => {
      this.update(entry.channel.key, (channel) =>
        channel.state === 'failed' ? channel : { ...channel, state: 'connecting' },
      );
    });
    element.addEventListener('stalled', () => this.armWatchdog(entry));
    element.addEventListener('error', () => {
      // hls.js reports its own failures; the element's error during an HLS load is noise.
      if (entry.hls) return;
      // An element with nothing to play is being torn down, not failing.
      if (!element.getAttribute('src')) return;
      this.fail(entry, describeMediaError(element.error));
    });
    // A live stream that "ends" has dropped the connection.
    element.addEventListener('ended', () => this.fail(entry, 'stream ended'));
  }

  /**
   * A stream that connects but never delivers audio would otherwise sit on "connecting" forever,
   * because the browser keeps waiting on a load that isn't erroring — it's just silent. After
   * twenty seconds the channel becomes an honest failure with a retry button instead.
   */
  private armWatchdog(entry: Live): void {
    this.clearWatchdog(entry);
    // Nothing is loading while the autoplay policy holds us; counting down would only produce a
    // false failure on a stream that was never given a chance.
    if (this.blockedByAutoplay) return;
    entry.watchdog = window.setTimeout(() => {
      if (entry.channel.state === 'connecting') this.fail(entry, 'no audio after 20s');
    }, STALL_TIMEOUT_MS);
  }

  private clearWatchdog(entry: Live): void {
    if (entry.watchdog !== null) {
      clearTimeout(entry.watchdog);
      entry.watchdog = null;
    }
  }

  /** One dead station must not take the mix down with it, so a failure stays local to its own row. */
  private fail(entry: Live, reason: string): void {
    this.clearWatchdog(entry);
    this.update(entry.channel.key, (channel) => ({ ...channel, state: 'failed', error: reason }));
  }

  /** Retry a channel that failed — dead stations and flaky ones look identical at first. */
  retry(key: string): void {
    const entry = this.live.get(key);
    if (!entry) return;
    this.update(key, (channel) => ({ ...channel, state: 'connecting', error: null }));
    this.start(entry);
  }

  stop(key: string): void {
    const entry = this.live.get(key);
    if (!entry) return;
    this.clearWatchdog(entry);
    entry.hls?.destroy();
    entry.element.pause();
    // Pointing at nothing is what actually closes the socket; pause alone leaves it open.
    entry.element.removeAttribute('src');
    entry.element.load();
    entry.graph.dispose();
    this.live.delete(key);
    void forgetChannel(this.sid, key);
    this.changed();
  }

  stopAll(): void {
    for (const key of [...this.live.keys()]) this.stop(key);
  }

  toggle(station: Station): boolean {
    const key = channelKey(station);
    if (this.live.has(key)) {
      this.stop(key);
      return true;
    }
    return this.play(station);
  }

  /**
   * Replaces the whole mix in one go. Loading a saved soundscape is "play this instead of what's
   * on", not "add to it" — otherwise recalling a mix while something plays gives you neither.
   */
  load(presets: Preset[]): void {
    this.stopAll();
    for (const preset of presets.slice(0, MAX_CHANNELS)) {
      if (!this.play(preset.station, preset.fader, preset.tone)) continue;
      if (preset.muted) this.setMuted(channelKey(preset.station), true);
    }
  }

  // ---------------------------------------------------------------- faders

  setFader(key: string, fader: number): void {
    const entry = this.live.get(key);
    if (!entry) return;
    const clamped = Math.min(1, Math.max(0, fader));
    entry.channel.fader = clamped;
    entry.graph.setLevel(clamped, entry.channel.muted);
    this.changed();
  }

  setMuted(key: string, muted: boolean): void {
    const entry = this.live.get(key);
    if (!entry) return;
    entry.channel.muted = muted;
    entry.graph.setLevel(entry.channel.fader, muted);
    this.changed();
  }

  setTone(key: string, tone: Tone): void {
    const entry = this.live.get(key);
    if (!entry) return;
    entry.channel.tone = tone;
    entry.graph.setTone(tone);
    this.changed();
  }

  /** Solo is cheap on top of mute and genuinely useful when balancing. */
  soloOnly(key: string): void {
    const others = [...this.live.values()].filter((entry) => entry.channel.key !== key);
    const alreadySoloed = others.every((entry) => entry.channel.muted);
    for (const entry of others) this.setMuted(entry.channel.key, !alreadySoloed);
    this.setMuted(key, false);
  }

  private update(key: string, transform: (channel: Channel) => Channel): void {
    const entry = this.live.get(key);
    if (!entry) return;
    entry.channel = transform(entry.channel);
    this.changed();
  }

  // ---------------------------------------------------------------- now playing

  /**
   * ICY metadata arrives interleaved in the audio bytes, which only the proxy can see, so titles
   * come back over one SSE connection rather than being read here.
   */
  private listenForMetadata(): void {
    const events = new EventSource(`/api/events?sid=${encodeURIComponent(this.sid)}`);
    events.onmessage = (message) => {
      try {
        const { channel, title } = JSON.parse(message.data) as { channel: string; title: string };
        this.update(channel, (existing) => ({ ...existing, nowPlaying: title || null }));
      } catch {
        /* a malformed event is not worth a crash */
      }
    };
  }

  // ---------------------------------------------------------------- one session for the mix

  /**
   * The lock-screen and headset controls should treat the soundscape as a single thing — pressing
   * pause on a headset shouldn't have to choose a station. Same reasoning as the Android app's
   * single `MediaSession` over the aggregate.
   */
  private wireMediaSession(): void {
    if (!('mediaSession' in navigator)) return;
    try {
      navigator.mediaSession.setActionHandler('pause', () => this.stopAll());
      navigator.mediaSession.setActionHandler('stop', () => this.stopAll());
    } catch {
      /* older browsers reject unknown actions */
    }
  }

  private updateMediaSession(): void {
    if (!('mediaSession' in navigator)) return;
    const channels = this.channels;
    if (channels.length === 0) {
      navigator.mediaSession.playbackState = 'none';
      navigator.mediaSession.metadata = null;
      return;
    }
    const names = channels.map((channel) => channel.station.name);
    navigator.mediaSession.playbackState = 'playing';
    navigator.mediaSession.metadata = new MediaMetadata({
      title: channels.length === 1 ? names[0] : `${channels.length} stations`,
      artist: names.join(' · '),
      album: 'Tasty Radio',
    });
  }
}

function describeMediaError(error: MediaError | null): string {
  if (!error) return 'playback failed';
  switch (error.code) {
    case MediaError.MEDIA_ERR_ABORTED:
      return 'aborted';
    case MediaError.MEDIA_ERR_NETWORK:
      return 'network dropped';
    case MediaError.MEDIA_ERR_DECODE:
      return 'could not decode this stream';
    case MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED:
      return 'stream format not supported';
    default:
      return 'playback failed';
  }
}
