/**
 * One channel's signal path: the Web Audio version of the Android app's `ChannelFilters`.
 *
 * Three things, in signal order, matching the Kotlin exactly:
 *
 * 1. **A three-band isolator.** Not a shelving EQ — shelves *tilt* the sound and even at −12 dB you
 *    can still plainly hear the band you were trying to remove. The signal is split with
 *    Linkwitz-Riley crossovers (two cascaded Butterworth sections per side, which is what LR4 is)
 *    and each band is scaled, so a band at the bottom of its travel is multiplied by zero and is
 *    genuinely gone.
 * 2. **A delay.** Feedback echo, from a slapback to a long wash.
 * 3. **A reverb.** A convolver rather than the Kotlin's Freeverb — see [makeImpulseResponse].
 *
 * The constants are lifted from `ChannelFilters.kt` deliberately: a mix saved on the phone and
 * loaded here should sound like the same mix.
 */

/** Where the bands meet. Bass/body and body/air, roughly where a DJ isolator puts them. */
export const CROSSOVER_LOW_HZ = 250;
export const CROSSOVER_HIGH_HZ = 3000;
export const Q_BUTTERWORTH = 0.707;

/** Boost available at the top of a band's travel. */
export const MAX_BOOST_DB = 9;
/** How far down the band falls before the bottom of the travel kills it outright. */
export const CUT_RANGE_DB = 40;

export const MIN_DELAY_MS = 60;
export const MAX_DELAY_MS = 1500;
export const DEFAULT_DELAY_MS = 400;

/** Enough repeats to be a texture, never enough to run away. */
const DELAY_MIN_FEEDBACK = 0.15;
const DELAY_FEEDBACK_RANGE = 0.55;

/** How much dry signal a full-wet reverb pulls back, so the source stays present. */
const DRY_DUCK = 0.4;

export interface Tone {
  /** Band positions, −1 (killed) … 0 (unity) … +1 (boosted). */
  low: number;
  mid: number;
  high: number;
  /** 0…1 wet amounts. */
  reverb: number;
  delay: number;
  delayMs: number;
}

export const FLAT: Tone = { low: 0, mid: 0, high: 0, reverb: 0, delay: 0, delayMs: DEFAULT_DELAY_MS };

export function isFlat(tone: Tone): boolean {
  return tone.low === 0 && tone.mid === 0 && tone.high === 0 && tone.reverb === 0 && tone.delay === 0;
}

/**
 * Position to linear gain. The bottom of the travel is **silence**, not a small number: that's the
 * whole point of an isolator, and −40 dB is still audible on a loud stream.
 */
export function bandGain(position: number): number {
  if (position <= -0.995) return 0;
  if (position < 0) return Math.pow(10, (position * CUT_RANGE_DB) / 20);
  return Math.pow(10, (position * MAX_BOOST_DB) / 20);
}

/**
 * A fader taper. `GainNode.gain` is linear amplitude, and a slider mapped straight onto it feels
 * broken — everything useful bunches into the top of the travel. Cubing approximates a real fader:
 * half way up lands around −18 dB, which is where half way up should be.
 */
export function amplitudeFor(fader: number): number {
  const f = Math.min(1, Math.max(0, fader));
  return f * f * f;
}

/**
 * A synthesised room, reused by every channel.
 *
 * The Kotlin runs Freeverb — parallel comb filters into series allpasses — because it has to write
 * its own DSP. A browser already has a convolution engine, so this hands it decayed noise instead,
 * which is the same idea done properly and costs less. One buffer is shared across channels; the
 * per-channel wet gain is what the reverb control actually moves.
 */
function makeImpulseResponse(ctx: BaseAudioContext, seconds = 2.6, decay = 2.6): AudioBuffer {
  const rate = ctx.sampleRate;
  const length = Math.floor(rate * seconds);
  const buffer = ctx.createBuffer(2, length, rate);

  for (let channel = 0; channel < 2; channel++) {
    const data = buffer.getChannelData(channel);
    // A few milliseconds of near-silence first, so the tail reads as a room rather than a smear.
    const preDelay = Math.floor(rate * 0.012);
    let smoothed = 0;
    for (let i = 0; i < length; i++) {
      if (i < preDelay) {
        data[i] = 0;
        continue;
      }
      const t = (i - preDelay) / (length - preDelay);
      const noise = Math.random() * 2 - 1;
      // A one-pole lowpass on the noise stands in for air absorption: the tail darkens as it dies.
      smoothed = smoothed * 0.55 + noise * 0.45;
      data[i] = smoothed * Math.pow(1 - t, decay);
    }
  }
  return buffer;
}

let sharedImpulse: AudioBuffer | null = null;
function impulse(ctx: BaseAudioContext): AudioBuffer {
  if (!sharedImpulse || sharedImpulse.sampleRate !== ctx.sampleRate) {
    sharedImpulse = makeImpulseResponse(ctx);
  }
  return sharedImpulse;
}

function crossover(ctx: AudioContext, type: BiquadFilterType, frequency: number): BiquadFilterNode {
  const filter = ctx.createBiquadFilter();
  filter.type = type;
  filter.frequency.value = frequency;
  filter.Q.value = Q_BUTTERWORTH;
  return filter;
}

/**
 * The nodes for one channel, from the media element to the master bus.
 *
 * Tone is applied *before* the fader, which is the order a mixing desk uses: shaping a channel
 * shouldn't change how loud it sits in the mix.
 */
export class ChannelGraph {
  readonly source: MediaElementAudioSourceNode;
  readonly analyser: AnalyserNode;

  private readonly lowGain: GainNode;
  private readonly midGain: GainNode;
  private readonly highGain: GainNode;
  private readonly toneSum: GainNode;

  private readonly delayNode: DelayNode;
  private readonly feedback: GainNode;
  private readonly delayWet: GainNode;
  private readonly afterDelay: GainNode;

  private readonly convolver: ConvolverNode;
  private readonly reverbWet: GainNode;
  private readonly dry: GainNode;
  private readonly out: GainNode;

  private readonly fader: GainNode;

  private delayActive = false;
  private reverbActive = false;

  constructor(
    private readonly ctx: AudioContext,
    element: HTMLMediaElement,
    destination: AudioNode,
  ) {
    this.source = ctx.createMediaElementSource(element);

    // --- three-band isolator. Two cascaded sections per side make each crossover LR4.
    const lowA = crossover(ctx, 'lowpass', CROSSOVER_LOW_HZ);
    const lowB = crossover(ctx, 'lowpass', CROSSOVER_LOW_HZ);
    const midHighA = crossover(ctx, 'highpass', CROSSOVER_LOW_HZ);
    const midHighB = crossover(ctx, 'highpass', CROSSOVER_LOW_HZ);
    const midLowA = crossover(ctx, 'lowpass', CROSSOVER_HIGH_HZ);
    const midLowB = crossover(ctx, 'lowpass', CROSSOVER_HIGH_HZ);
    const highA = crossover(ctx, 'highpass', CROSSOVER_HIGH_HZ);
    const highB = crossover(ctx, 'highpass', CROSSOVER_HIGH_HZ);

    this.lowGain = ctx.createGain();
    this.midGain = ctx.createGain();
    this.highGain = ctx.createGain();
    this.toneSum = ctx.createGain();

    this.source.connect(lowA).connect(lowB).connect(this.lowGain).connect(this.toneSum);
    this.source
      .connect(midHighA)
      .connect(midHighB)
      .connect(midLowA)
      .connect(midLowB)
      .connect(this.midGain)
      .connect(this.toneSum);
    this.source.connect(highA).connect(highB).connect(this.highGain).connect(this.toneSum);

    // --- delay. Dry passes straight through; the echo is added on top, as in the Kotlin.
    this.delayNode = ctx.createDelay(MAX_DELAY_MS / 1000);
    this.delayNode.delayTime.value = DEFAULT_DELAY_MS / 1000;
    this.feedback = ctx.createGain();
    this.feedback.gain.value = DELAY_MIN_FEEDBACK;
    this.delayWet = ctx.createGain();
    this.delayWet.gain.value = 0;
    this.afterDelay = ctx.createGain();

    this.toneSum.connect(this.afterDelay);
    this.delayNode.connect(this.feedback).connect(this.delayNode);
    this.delayNode.connect(this.delayWet).connect(this.afterDelay);

    // --- reverb
    this.convolver = ctx.createConvolver();
    this.convolver.buffer = impulse(ctx);
    this.reverbWet = ctx.createGain();
    this.reverbWet.gain.value = 0;
    this.dry = ctx.createGain();
    this.dry.gain.value = 1;
    this.out = ctx.createGain();

    this.afterDelay.connect(this.dry).connect(this.out);
    this.reverbWet.connect(this.out);

    // --- fader, meter, master
    this.fader = ctx.createGain();
    this.fader.gain.value = 0;
    this.analyser = ctx.createAnalyser();
    this.analyser.fftSize = 256;
    this.analyser.smoothingTimeConstant = 0.6;

    this.out.connect(this.fader).connect(this.analyser).connect(destination);
  }

  setTone(tone: Tone): void {
    const now = this.ctx.currentTime;
    const ramp = 0.02; // short, so a knob feels immediate without zipper noise

    this.lowGain.gain.setTargetAtTime(bandGain(tone.low), now, ramp);
    this.midGain.gain.setTargetAtTime(bandGain(tone.mid), now, ramp);
    this.highGain.gain.setTargetAtTime(bandGain(tone.high), now, ramp);

    const delayOn = tone.delay > 0.005;
    const reverbOn = tone.reverb > 0.005;

    // Convolution and a feedback loop both cost real CPU with four channels running, so an effect
    // at zero is disconnected rather than merely silent — the same bookkeeping the Kotlin does with
    // its `delayActive` / `reverbActive` flags, for the same reason.
    if (delayOn !== this.delayActive) {
      if (delayOn) this.toneSum.connect(this.delayNode);
      else {
        this.toneSum.disconnect(this.delayNode);
        this.delayNode.disconnect();
        this.delayNode.connect(this.feedback).connect(this.delayNode);
        this.delayNode.connect(this.delayWet).connect(this.afterDelay);
      }
      this.delayActive = delayOn;
    }
    if (reverbOn !== this.reverbActive) {
      if (reverbOn) this.afterDelay.connect(this.convolver).connect(this.reverbWet);
      else {
        this.afterDelay.disconnect(this.convolver);
        this.convolver.disconnect();
      }
      this.reverbActive = reverbOn;
    }

    const seconds = Math.min(MAX_DELAY_MS, Math.max(MIN_DELAY_MS, tone.delayMs)) / 1000;
    this.delayNode.delayTime.setTargetAtTime(seconds, now, 0.05);
    this.feedback.gain.setTargetAtTime(DELAY_MIN_FEEDBACK + tone.delay * DELAY_FEEDBACK_RANGE, now, ramp);
    this.delayWet.gain.setTargetAtTime(tone.delay, now, ramp);

    this.reverbWet.gain.setTargetAtTime(tone.reverb, now, ramp);
    this.dry.gain.setTargetAtTime(1 - tone.reverb * DRY_DUCK, now, ramp);
  }

  setLevel(fader: number, muted: boolean): void {
    const target = muted ? 0 : amplitudeFor(fader);
    // A ramp rather than a jump: a fader snapped to a new value clicks.
    this.fader.gain.setTargetAtTime(target, this.ctx.currentTime, 0.015);
  }

  /** Peak level, 0…1, for the meter. */
  level(buffer: Uint8Array): number {
    this.analyser.getByteTimeDomainData(buffer as Uint8Array<ArrayBuffer>);
    let peak = 0;
    for (let i = 0; i < buffer.length; i++) {
      const value = Math.abs(buffer[i] - 128) / 128;
      if (value > peak) peak = value;
    }
    return peak;
  }

  dispose(): void {
    try {
      this.source.disconnect();
      this.toneSum.disconnect();
      this.afterDelay.disconnect();
      this.convolver.disconnect();
      this.delayNode.disconnect();
      this.out.disconnect();
      this.fader.disconnect();
      this.analyser.disconnect();
    } catch {
      /* already torn down */
    }
  }
}
