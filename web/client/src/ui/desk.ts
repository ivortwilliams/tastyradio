import { MAX_CHANNELS, type Channel, type Mixer } from '../audio/mixer.js';
import { MAX_DELAY_MS, MIN_DELAY_MS, type Tone } from '../audio/graph.js';
import { Recorder, recordingSupported, titleForMix } from '../audio/recorder.js';
import { formatDuration } from '../data/recordings.js';
import { artwork } from './artwork.js';
import { append, button, el, icon, replace, toast } from './dom.js';
import { knob, type Knob } from './knob.js';

/**
 * The mixing desk.
 *
 * This is where the web version stops being a port and starts being its own thing. The phone has to
 * hide the mixer behind a pill and expand it over the navigation bar, because a phone has one
 * column and the bottom of the screen is already crowded. A browser window is wide, so the desk
 * gets to be an actual desk: channel strips side by side, a vertical fader and three isolator knobs
 * on each, the master section on the right.
 *
 * It stays mounted for the life of the page and patches itself in place. Rebuilding it on every
 * state change would drop the fader out from under a finger mid-drag.
 */

/** Remembers whether the desk is folded away, because on a phone that is a real preference. */
const DESK_OPEN_KEY = 'tastyradio.desk.open';

interface StripParts {
  node: HTMLElement;
  nowPlaying: HTMLElement;
  status: HTMLElement;
  fader: HTMLInputElement;
  meter: HTMLElement;
  mute: HTMLButtonElement;
  knobs: { low: Knob; mid: Knob; high: Knob; reverb: Knob; delay: Knob };
  delayTime: HTMLInputElement;
}

export class Desk {
  readonly node: HTMLElement;
  readonly recorder: Recorder;

  private readonly strips = new Map<string, StripParts>();
  private readonly stripHost: HTMLElement;
  private readonly title: HTMLElement;
  private readonly recordButton: HTMLButtonElement;
  private readonly recordClock: HTMLElement;
  private readonly masterMeter: HTMLElement;
  private readonly masterAnalyser: AnalyserNode;
  private readonly masterBuffer: Uint8Array;
  private meterHandle = 0;

  constructor(
    private readonly mixer: Mixer,
    private readonly onSaveMix: (channels: Channel[]) => void,
    private readonly onAddStation: () => void,
  ) {
    this.recorder = new Recorder(mixer.recordTap.stream);

    this.masterAnalyser = mixer.ctx.createAnalyser();
    this.masterAnalyser.fftSize = 256;
    this.masterAnalyser.smoothingTimeConstant = 0.6;
    mixer.master.connect(this.masterAnalyser);
    this.masterBuffer = new Uint8Array(this.masterAnalyser.fftSize);

    this.title = el('span', { class: 'desk-title', text: 'The desk' });
    this.stripHost = el('div', { class: 'strips' });
    this.recordClock = el('span', { class: 'rec-clock', text: '0:00' });
    this.masterMeter = el('div', { class: 'meter-fill' });

    this.recordButton = button('Record', {
      class: 'rec-button',
      iconName: 'record',
      title: 'Record the mix',
      onClick: () => this.toggleRecording(),
    });

    const collapse = button('', {
      class: 'desk-collapse',
      iconName: 'chevron',
      title: 'Collapse the desk',
      'aria-label': 'Collapse the desk',
    });

    const master = el(
      'div',
      { class: 'master' },
      el('div', { class: 'master-label', text: 'MASTER' }),
      el('div', { class: 'meter meter-master' }, this.masterMeter),
      el(
        'div',
        { class: 'master-buttons' },
        this.recordButton,
        this.recordClock,
        button('Save mix', {
          class: 'ghost',
          iconName: 'save',
          onClick: () => {
            if (this.mixer.channels.length === 0) {
              toast('Nothing playing to save.');
              return;
            }
            this.onSaveMix(this.mixer.channels);
          },
        }),
        button('Stop all', {
          class: 'ghost',
          iconName: 'stop',
          onClick: () => this.mixer.stopAll(),
        }),
      ),
    );

    // The whole header toggles, not just the chevron. On a phone a 26px arrow is a poor target, and
    // collapsing the desk is how you get the screen back for browsing — it should be easy to hit.
    const head = el(
      'header',
      {
        class: 'desk-head',
        role: 'button',
        tabindex: '0',
        'aria-label': 'Show or hide the mixing desk',
      },
      collapse,
      this.title,
      el('div', { class: 'spacer' }),
    );

    const setOpen = (open: boolean) => {
      this.node.dataset.open = String(open);
      head.setAttribute('aria-expanded', String(open));
      collapse.title = open ? 'Collapse the desk' : 'Open the desk';
      try {
        localStorage.setItem(DESK_OPEN_KEY, String(open));
      } catch {
        /* private browsing; the desk just won't remember */
      }
    };

    head.addEventListener('click', () => setOpen(this.node.dataset.open === 'false'));
    head.addEventListener('keydown', (event: KeyboardEvent) => {
      if (event.key !== 'Enter' && event.key !== ' ') return;
      event.preventDefault();
      setOpen(this.node.dataset.open === 'false');
    });

    this.node = el(
      'section',
      { class: 'desk', dataset: { open: 'true' }, 'aria-label': 'The mixing desk' },
      head,
      el('div', { class: 'desk-body' }, this.stripHost, master),
    );

    // Remembered across visits: someone who folds the desk away on a phone means it.
    let remembered: string | null = null;
    try {
      remembered = localStorage.getItem(DESK_OPEN_KEY);
    } catch {
      /* fine */
    }
    setOpen(remembered !== 'false');

    if (!recordingSupported()) {
      this.recordButton.disabled = true;
      this.recordButton.title = 'This browser cannot record audio';
    }

    mixer.subscribe(() => this.render());
    this.recorder.subscribe(() => this.renderRecordState());
    this.render();
    this.startMeters();
  }

  // ---------------------------------------------------------------- rendering

  private render(): void {
    const channels = this.mixer.channels;
    this.title.textContent =
      channels.length === 0
        ? 'The desk — nothing playing'
        : `The desk — ${channels.length} station${channels.length === 1 ? '' : 's'}`;
    this.node.dataset.live = String(channels.length > 0);

    const wanted = new Set(channels.map((channel) => channel.key));
    for (const [key, strip] of this.strips) {
      if (!wanted.has(key)) {
        strip.node.remove();
        this.strips.delete(key);
      }
    }

    for (const channel of channels) {
      let strip = this.strips.get(channel.key);
      if (!strip) {
        strip = this.buildStrip(channel);
        this.strips.set(channel.key, strip);
      }
      this.patchStrip(strip, channel);
    }

    // Keep the DOM order matching the mix order, so strips don't shuffle as channels come and go.
    for (const channel of channels) {
      const strip = this.strips.get(channel.key);
      if (strip) this.stripHost.appendChild(strip.node);
    }

    const empties = this.stripHost.querySelectorAll('.strip-empty');
    empties.forEach((node) => node.remove());
    if (channels.length < MAX_CHANNELS) {
      this.stripHost.appendChild(this.buildEmptyStrip(channels.length === 0));
    }
  }

  private buildEmptyStrip(first: boolean): HTMLElement {
    return el(
      'button',
      {
        class: 'strip strip-empty',
        type: 'button',
        onClick: () => this.onAddStation(),
      },
      icon('plus'),
      el('span', {
        text: first ? 'Play a station to start a mix' : 'Add another station',
      }),
    );
  }

  private buildStrip(channel: Channel): StripParts {
    const { station } = channel;

    const nowPlaying = el('div', { class: 'strip-now' });
    const status = el('div', { class: 'strip-status' });

    const meterFill = el('div', { class: 'meter-fill' });
    const fader = el('input', {
      class: 'fader',
      type: 'range',
      min: '0',
      max: '1',
      step: '0.001',
      value: String(channel.fader),
      'aria-label': `${station.name} volume`,
      onInput: (event: Event) =>
        this.mixer.setFader(channel.key, Number((event.target as HTMLInputElement).value)),
    }) as HTMLInputElement;

    const setTone = (patch: Partial<Tone>) => {
      const current = this.mixer.channel(channel.key)?.tone;
      if (current) this.mixer.setTone(channel.key, { ...current, ...patch });
    };

    const band = (label: string, key: 'low' | 'mid' | 'high') =>
      knob({
        label,
        min: -1,
        max: 1,
        value: channel.tone[key],
        centred: true,
        // The bottom of the travel is silence, not a small number — that is what an isolator is.
        format: (value) => (value <= -0.995 ? 'kill' : value === 0 ? '0' : `${value > 0 ? '+' : ''}${(value * (value > 0 ? 9 : 40)).toFixed(0)}`),
        onChange: (value) => setTone({ [key]: value } as Partial<Tone>),
      });

    const knobs = {
      low: band('LOW', 'low'),
      mid: band('MID', 'mid'),
      high: band('HIGH', 'high'),
      reverb: knob({
        label: 'RVB',
        min: 0,
        max: 1,
        value: channel.tone.reverb,
        format: (value) => (value < 0.005 ? 'off' : `${Math.round(value * 100)}`),
        onChange: (value) => setTone({ reverb: value }),
      }),
      delay: knob({
        label: 'DLY',
        min: 0,
        max: 1,
        value: channel.tone.delay,
        format: (value) => (value < 0.005 ? 'off' : `${Math.round(value * 100)}`),
        onChange: (value) => setTone({ delay: value }),
      }),
    };

    const delayTime = el('input', {
      class: 'delay-time',
      type: 'range',
      min: String(MIN_DELAY_MS),
      max: String(MAX_DELAY_MS),
      step: '10',
      value: String(channel.tone.delayMs),
      title: 'Echo time',
      'aria-label': `${station.name} echo time`,
      onInput: (event: Event) => setTone({ delayMs: Number((event.target as HTMLInputElement).value) }),
    }) as HTMLInputElement;

    const mute = button('', {
      class: 'chip-button',
      iconName: 'mute',
      title: 'Mute',
      'aria-label': `Mute ${station.name}`,
      onClick: () => {
        const current = this.mixer.channel(channel.key);
        if (current) this.mixer.setMuted(channel.key, !current.muted);
      },
    });

    const solo = button('', {
      class: 'chip-button',
      iconName: 'solo',
      title: 'Solo — mute everything else',
      'aria-label': `Solo ${station.name}`,
      onClick: () => this.mixer.soloOnly(channel.key),
    });

    const stop = button('', {
      class: 'chip-button danger',
      iconName: 'cross',
      title: 'Take this station out of the mix',
      'aria-label': `Stop ${station.name}`,
      onClick: () => this.mixer.stop(channel.key),
    });

    // Only ever visible on a phone, where the knobs are folded away to keep each channel one row.
    const tone = button('', {
      class: 'chip-button tone-toggle',
      iconName: 'edit',
      title: 'Tone: EQ, reverb and delay',
      'aria-label': `Tone controls for ${station.name}`,
      onClick: () => {
        const open = node.dataset.tone === 'open';
        node.dataset.tone = open ? 'closed' : 'open';
      },
    });

    const node = el('article', { class: 'strip', dataset: { key: channel.key, tone: 'closed' } });
    append(node, [
      el(
        'header',
        { class: 'strip-head' },
        artwork(station, 34),
        el(
          'div',
          { class: 'strip-names' },
          el('div', { class: 'strip-name', text: station.name, title: station.name }),
          status,
        ),
      ),
      nowPlaying,
      el('div', { class: 'knob-row' }, knobs.low.node, knobs.mid.node, knobs.high.node),
      el(
        'div',
        { class: 'knob-row knob-row-space' },
        knobs.reverb.node,
        knobs.delay.node,
        el('div', { class: 'delay-time-wrap' }, el('span', { class: 'knob-label', text: 'TIME' }), delayTime),
      ),
      el('div', { class: 'fader-row' }, el('div', { class: 'meter' }, meterFill), fader),
      el('footer', { class: 'strip-foot' }, mute, solo, tone, stop),
    ]);

    return {
      node,
      nowPlaying,
      status,
      fader,
      meter: meterFill,
      mute,
      knobs,
      delayTime,
    };
  }

  private patchStrip(strip: StripParts, channel: Channel): void {
    strip.node.dataset.state = channel.state;
    strip.node.dataset.muted = String(channel.muted);
    strip.mute.dataset.on = String(channel.muted);

    if (channel.state === 'failed') {
      const message = channel.error ?? 'failed';
      if (strip.status.dataset.error !== message) {
        strip.status.dataset.error = message;
        replace(
          strip.status,
          el('span', { class: 'strip-error', text: message }),
          button('Retry', {
            class: 'link',
            iconName: 'retry',
            onClick: () => this.mixer.retry(channel.key),
          }),
        );
      }
    } else {
      delete strip.status.dataset.error;
      const label = channel.state === 'connecting' ? 'Connecting…' : describe(channel);
      if (strip.status.textContent !== label) strip.status.textContent = label;
    }

    const now = channel.nowPlaying ?? '';
    if (strip.nowPlaying.textContent !== now) {
      strip.nowPlaying.textContent = now;
      strip.nowPlaying.title = now;
    }
    strip.nowPlaying.dataset.empty = String(now === '');

    if (document.activeElement !== strip.fader && Number(strip.fader.value) !== channel.fader) {
      strip.fader.value = String(channel.fader);
    }
    strip.knobs.low.set(channel.tone.low);
    strip.knobs.mid.set(channel.tone.mid);
    strip.knobs.high.set(channel.tone.high);
    strip.knobs.reverb.set(channel.tone.reverb);
    strip.knobs.delay.set(channel.tone.delay);
    if (document.activeElement !== strip.delayTime) {
      strip.delayTime.value = String(channel.tone.delayMs);
    }
  }

  // ---------------------------------------------------------------- meters

  /**
   * One animation frame loop for every meter, and only while something is playing. Four separate
   * intervals ticking against an idle desk is how a browser tab starts costing battery.
   */
  private startMeters(): void {
    const tick = () => {
      const channels = this.mixer.channels;
      if (channels.length > 0) {
        for (const channel of channels) {
          const strip = this.strips.get(channel.key);
          const live = this.mixer.graphFor(channel.key);
          if (!strip || !live) continue;
          setMeter(strip.meter, live.graph.level(live.buffer));
        }
        setMeter(this.masterMeter, peak(this.masterAnalyser, this.masterBuffer));
      } else {
        setMeter(this.masterMeter, 0);
      }
      this.meterHandle = requestAnimationFrame(tick);
    };
    this.meterHandle = requestAnimationFrame(tick);
  }

  stopMeters(): void {
    cancelAnimationFrame(this.meterHandle);
  }

  // ---------------------------------------------------------------- recording

  private toggleRecording(): void {
    if (this.recorder.isRecording) {
      this.recorder.stop();
      return;
    }
    const channels = this.mixer.channels;
    if (channels.length === 0) {
      toast('Start a station first — there is nothing to record.');
      return;
    }
    this.recorder.start(titleForMix(channels.map((channel) => channel.station.name)));
  }

  private renderRecordState(): void {
    const state = this.recorder.state;
    this.node.dataset.recording = String(state.kind === 'recording');
    this.recordButton.dataset.on = String(state.kind === 'recording');
    const label = this.recordButton.querySelector('span');
    if (label) label.textContent = state.kind === 'recording' ? 'Stop' : 'Record';

    if (state.kind === 'recording') {
      const update = () => {
        if (this.recorder.state.kind !== 'recording') return;
        this.recordClock.textContent = formatDuration(Date.now() - state.startedAt);
        window.setTimeout(update, 500);
      };
      update();
    } else if (state.kind === 'failed') {
      this.recordClock.textContent = '0:00';
      toast(`Recording failed: ${state.reason}`);
      this.recorder.acknowledge();
    } else {
      this.recordClock.textContent = '0:00';
    }
  }
}

function describe(channel: Channel): string {
  const station = channel.station;
  const bits = [station.codec, station.bitrate ? `${station.bitrate}k` : null].filter(Boolean);
  return bits.length > 0 ? bits.join(' · ') : 'On air';
}

function peak(analyser: AnalyserNode, buffer: Uint8Array): number {
  analyser.getByteTimeDomainData(buffer as Uint8Array<ArrayBuffer>);
  let max = 0;
  for (let i = 0; i < buffer.length; i++) {
    const value = Math.abs(buffer[i] - 128) / 128;
    if (value > max) max = value;
  }
  return max;
}

function setMeter(node: HTMLElement, level: number): void {
  // A little compression on the display, so quiet-but-present reads as present.
  const shown = Math.min(1, Math.pow(level, 0.6));
  // A custom property rather than a transform, because the master meter lies on its side on a
  // narrow screen and the axis is the stylesheet's business, not this function's.
  node.style.setProperty('--level', shown.toFixed(3));
  node.dataset.hot = String(level > 0.94);
}
