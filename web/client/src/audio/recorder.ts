import { saveRecording, type Recording } from '../data/recordings.js';

/**
 * Records what the desk is putting out — the whole mix, exactly as heard — into a shareable file.
 *
 * The Android app has to ask the system for a playback capture of its own UID because the mixing
 * happens in the platform. Here the mix is ours: a `MediaStreamAudioDestinationNode` is hung off the
 * master bus for the duration of the take and `MediaRecorder` encodes it. No permission prompt, no
 * consent dialog, and no `RECORD_AUDIO` — the microphone is never anywhere near this.
 *
 * The tap is opened when recording starts and closed when it ends. It used to be connected for the
 * life of the page, which costs a phone real CPU for a feature nobody is using yet.
 *
 * The tap is **post-fader**, as on the phone, so the take is exactly what you heard, clipping and
 * all. Watch for that when several loud channels sum — a single station can already peak at 0 dBFS.
 */

export type RecorderState =
  | { kind: 'idle' }
  | { kind: 'recording'; startedAt: number; title: string }
  | { kind: 'saved'; recording: Recording }
  | { kind: 'failed'; reason: string };

/**
 * AAC in an MP4 first, so a recording made here is the same `.m4a` the phone produces and plays
 * anywhere without explanation. Opus in WebM is the fallback everywhere that can't.
 */
const PREFERRED_TYPES = [
  { mime: 'audio/mp4;codecs=mp4a.40.2', extension: 'm4a' },
  { mime: 'audio/mp4', extension: 'm4a' },
  { mime: 'audio/webm;codecs=opus', extension: 'webm' },
  { mime: 'audio/webm', extension: 'webm' },
  { mime: 'audio/ogg;codecs=opus', extension: 'ogg' },
];

export function pickFormat(): { mime: string; extension: string } | null {
  if (typeof MediaRecorder === 'undefined') return null;
  for (const candidate of PREFERRED_TYPES) {
    if (MediaRecorder.isTypeSupported(candidate.mime)) return candidate;
  }
  return null;
}

export function recordingSupported(): boolean {
  return pickFormat() !== null;
}

/**
 * Where a take comes from: the master bus, tapped only while recording.
 *
 * The `Mixer` is what actually implements this — it is an interface so the recorder doesn't need to
 * know about mixing, and the tap doesn't need to exist until somebody presses record.
 */
export interface RecordBus {
  openRecordTap(): MediaStream;
  closeRecordTap(): void;
}

export class Recorder {
  private recorder: MediaRecorder | null = null;
  private chunks: Blob[] = [];
  private startedAt = 0;
  private title = '';
  private extension = 'webm';
  private readonly listeners = new Set<() => void>();

  state: RecorderState = { kind: 'idle' };

  constructor(private readonly bus: RecordBus) {}

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private changed(): void {
    for (const listener of this.listeners) listener();
  }

  get isRecording(): boolean {
    return this.state.kind === 'recording';
  }

  /**
   * @param title what the mix is, used for the filename — station names, because
   *              `recording_003.webm` tells you nothing about which happy accident you caught
   */
  start(title: string): void {
    if (this.isRecording) return;
    const format = pickFormat();
    if (!format) {
      this.state = { kind: 'failed', reason: 'this browser cannot record audio' };
      this.changed();
      return;
    }

    try {
      this.recorder = new MediaRecorder(this.bus.openRecordTap(), {
        mimeType: format.mime,
        audioBitsPerSecond: 128_000,
      });
    } catch (error) {
      this.bus.closeRecordTap();
      this.state = { kind: 'failed', reason: (error as Error).message };
      this.changed();
      return;
    }

    this.chunks = [];
    this.extension = format.extension;
    this.title = title;
    this.startedAt = Date.now();

    this.recorder.ondataavailable = (event) => {
      if (event.data.size > 0) this.chunks.push(event.data);
    };
    this.recorder.onerror = () => {
      this.bus.closeRecordTap();
      this.state = { kind: 'failed', reason: 'the recorder stopped unexpectedly' };
      this.changed();
    };
    this.recorder.onstop = () => void this.finish(format.mime);

    // A chunk a second, so a tab that dies mid-take has lost a second rather than the lot.
    this.recorder.start(1000);
    this.state = { kind: 'recording', startedAt: this.startedAt, title };
    this.changed();
  }

  stop(): void {
    if (this.recorder && this.recorder.state !== 'inactive') this.recorder.stop();
  }

  /** Dismiss a finished or failed result, so the UI goes back to an idle record button. */
  acknowledge(): void {
    if (this.state.kind !== 'recording') {
      this.state = { kind: 'idle' };
      this.changed();
    }
  }

  private async finish(mime: string): Promise<void> {
    // Off the master bus the moment the take ends: an idle tap is a whole extra sink pulling the
    // graph, which is exactly the sort of thing a phone notices and a laptop doesn't.
    this.bus.closeRecordTap();
    const durationMs = Date.now() - this.startedAt;
    const blob = new Blob(this.chunks, { type: mime });
    this.chunks = [];
    this.recorder = null;

    if (blob.size === 0) {
      this.state = { kind: 'failed', reason: 'nothing was captured' };
      this.changed();
      return;
    }

    const recording: Recording = {
      id: `${this.startedAt}`,
      fileName: `${fileStamp(this.startedAt)} — ${this.title}`.slice(0, 120) + `.${this.extension}`,
      mime,
      durationMs,
      createdAt: this.startedAt,
      sizeBytes: blob.size,
      blob,
    };

    try {
      await saveRecording(recording);
    } catch {
      // Losing the library copy is survivable — the blob is still in hand and downloadable.
    }
    this.state = { kind: 'saved', recording };
    this.changed();
  }
}

/** `2026-08-19 1809`, matching the phone's naming so a folder of both sorts together. */
function fileStamp(at: number): string {
  const date = new Date(at);
  const pad = (value: number) => String(value).padStart(2, '0');
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}${pad(date.getMinutes())}`
  );
}

/** Station names, joined — the whole point of the filename. */
export function titleForMix(names: string[]): string {
  if (names.length === 0) return 'Tasty Radio';
  return sanitise(names.join(' + '));
}

export function sanitise(value: string): string {
  return value.replace(/[\\/:*?"<>|]+/g, ' ').replace(/\s+/g, ' ').trim();
}
