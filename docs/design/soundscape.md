# Soundscape design — the thing that makes Tasty Radio not Transistor

This is the design document for the feature the whole app exists for: **playing several radio
stations at the same time, at volumes you control independently, and recording the result to a
file you can send to a friend.**

Everything Transistor does, Tasty Radio should also do. This document is only about the part
Transistor doesn't do.

> **Companion document:** [`discovery.md`](discovery.md) covers how stations get *into* the app
> — the local station index, semantic-ish matching, and the Search page. Read it before touching
> search, sync or navigation.

---

## The vision, in the owner's words

> Build on Transistor (which is quite minimal) but with the ability to play multiple stations
> at once, and adjust the volumes on them independently. Also possibly a 3-band EQ on highs,
> mids and lows — the EQ is less important, only if it's easy. Replace Transistor on my phone
> with essentially the same thing, but able to play multiple radios at once and build a
> soundscape. It would be cool if I could press a button to start recording the audio — like
> if there's a nice mix of church music and techno from different stations — and record those
> into files I can share with friends.

So: **a radio app that is secretly a mixing desk.** Two stations that have nothing to do with
each other, played together, become a third thing. The app's job is to make that easy to reach
for, easy to balance, and easy to keep.

The name is apt, incidentally — the owner's own station list (Radio Vaticana, Radio Art –
Gregorian, Ethereal Radio, Resonance 104.4FM, Tasty Radio) is already half a soundscape
waiting to be layered.

---

## What this changes architecturally

Transistor, and almost every media app, is built on **one player, one session, one thing
playing**. That assumption is load-bearing all the way down: audio focus, the media
notification, the playback bar, the data model. Tasty Radio breaks it deliberately.

The core shift: **a station is not "the" playback, it's a channel on a mixer.**

```
Station A ──► ExoPlayer A ──► volume A ─┐
Station B ──► ExoPlayer B ──► volume B ─┼──► Android audio mixer ──► speaker
Station C ──► ExoPlayer C ──► volume C ─┘            │
                                                     └──► playback capture ──► AAC ──► .m4a
```

We do **not** write our own audio mixer (see [Recording](#recording) for why we don't need to).
The system mixes our players for us; we shape each channel before it gets there, and we record
the system's mix of our own app.

### One player per station

Each active station gets its own `ExoPlayer` instance, held in a pool inside the playback
service. Per-station volume is then simply `player.volume` — that part genuinely is as easy as
it sounds.

**Soft cap: 4 concurrent stations.** Not a hard technical limit, but past four the returns
drop and the costs don't: four streams at 128 kbps is ~64 KB/s of sustained mobile data, four
codec instances, four sockets, four buffers. Make the cap a constant so it's easy to raise.

### Audio focus must have exactly one owner

This is the first thing that will break if we're careless. If every `ExoPlayer` manages its own
audio focus, they will fight each other — each new player requesting focus can duck or pause
the others, and our own stations will fade each other out.

**Rule: construct every player with `handleAudioFocus = false` and manage
`AudioFocusRequest` once, at the service level, for the soundscape as a whole.** Focus lost →
stop everything. Focus ducked → duck the master, not one channel.

Same logic for `ACTION_AUDIO_BECOMING_NOISY` (headphones unplugged): one receiver, stops all
channels.

### One MediaSession, not one per station

The media notification, lockscreen, Bluetooth buttons and headset controls should treat the
soundscape as a single thing. Pressing pause on a headset should not have to choose a station.

Media3's `SimpleBasePlayer` exists for exactly this: implement a small `SoundscapePlayer` that
presents the mix as one logical player, fanning `play`/`stop` out to the pool underneath and
reporting an aggregate state. Attach the single `MediaSession` to that.

Notification content: station count and names when several are playing ("3 stations · Radio
Vaticana, Tasty Radio, Resonance"), a **stop-all** action, and a **record toggle**.

---

## Per-station volume

`ExoPlayer.volume` takes linear amplitude, `0f`–`1f`. A slider mapped straight to it feels
wrong — most of the useful range bunches up at the bottom.

Map the slider perceptually instead: roughly `volume = slider³`, or a proper dB taper
(`volume = 10^(dB/20)` over a −60…0 dB range with a hard zero at the bottom). Get this right
early; it's the difference between the mixer feeling good and feeling broken.

Per-channel **mute** and **solo** are cheap to add on top and genuinely useful when balancing.

---

## The 3-band EQ — ❌ cut by the owner, 2026-08-17

**Built, then removed the same day on the owner's instruction.** It worked as described below
(per-player `audiofx.Equalizer`, three bands found by querying centre frequencies), and it is gone.
Don't reintroduce it without being asked. The reasoning below is kept only so nobody re-derives it.

<details>
<summary>Original design, for the record</summary>

### The 3-band EQ (low priority, only if easy)

Marked explicitly as optional by the owner. Two routes:

1. **`android.media.audiofx.Equalizer` per player**, attached to each `ExoPlayer`'s
   `audioSessionId`. This is the easy route — a handful of lines per channel. Caveat: `audiofx`
   is implemented by the device vendor and is **inconsistent in practice**; band counts and
   centre frequencies vary, and on some OEM builds it's flaky or silently does nothing. Query
   the bands rather than assuming, and treat the whole feature as best-effort.
2. **A custom Media3 `AudioProcessor`** doing three biquad filters in the audio pipeline.
   Reliable and identical on every device, but it's real DSP work.

**Do (1), gate it behind a settings toggle, and only bother once the mixer and recording both
work.** If (1) proves useless on the owner's actual phone, drop the feature rather than
escalating to (2) — it was never the point of the app.

Per-station EQ is the interesting version (dulling the highs on one stream so another sits on
top of it), and it comes free with route (1) since each player has its own audio session.

</details>

---

## Recording

**Approach: `AudioPlaybackCapture` via `MediaProjection`, capturing our own app's UID.**

Android will hand us a mixed PCM stream of our own app's playback. The system has already done
the mixing and applied our per-channel volumes, so what we record is exactly what the user
hears. This is a few dozen lines instead of a software mixer, which is why it won.

```kotlin
// Sketch, not final code.
val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
    .addMatchingUid(Process.myUid())   // our own playback only
    .build()

val record = AudioRecord.Builder()
    .setAudioPlaybackCaptureConfig(config)
    .setAudioFormat(/* 48 kHz, stereo, 16-bit PCM */)
    .build()
```

Then `MediaCodec` (AAC-LC, ~128 kbps) → `MediaMuxer` → `.m4a`. Roughly 1 MB per minute, which
is shareable over anything. Don't write WAV — it's ~11 MB/minute for no benefit here.

### The costs of this approach, written down honestly

- **A system consent dialog appears when a recording session starts.** Unavoidable;
  `MediaProjection` always asks. Mitigate by holding one projection for as long as the user
  keeps recording things, rather than tearing it down and re-prompting per clip.
- **Requires min SDK 29** (Android 10). This is why the project's min SDK is 29 and not 24.
- **Requires the `RECORD_AUDIO` runtime permission**, even though we never open the microphone,
  because capture is delivered through `AudioRecord`. Worth a sentence of explanation in the UI
  when we ask, or it looks like the radio app wants to listen to you.
- **Foreground service types**: `mediaPlayback` for the player, plus `mediaProjection` while
  recording, with the matching `FOREGROUND_SERVICE_*` permissions on API 34+. Verify on a real
  device whether API 34+ additionally demands the `microphone` type when `AudioRecord` is live.
- Our own app's audio must remain capturable — that's the default for apps targeting API 29+,
  so just *don't* set `android:allowAudioPlaybackCapture="false"`.

### Where recordings go

Write to `MediaStore` — `Environment.DIRECTORY_RECORDINGS` on API 31+, otherwise `Music/`
— under a `Tasty Radio/` subfolder. That makes files visible to other apps with no storage
permission at all, and share is then `ACTION_SEND` with the `MediaStore` URI.

Default filename from the mix: `2026-08-17 1809 — Radio Vaticana + Tasty Radio.m4a`. The
station names in the filename are the whole point; a folder of `recording_003.m4a` tells you
nothing about which happy accident you caught.

### Recording UI

A record button in the mixer, elapsed time while running, and a visible indicator that
recording is live. When it stops, offer **Share** and **Play** immediately — the moment you
want to send it to a friend is the moment it stops, not later.

### One note on the files themselves

These are personal recordings off the radio, shared with friends — the tape-off-the-radio case.
Worth being aware that the streams belong to their broadcasters if anything ever grows beyond
that; it's the owner's call and not the app's business to police.

---

## UI: the mixer

This is where Tasty Radio visibly stops being Transistor. Transistor's bottom pill shows *the*
station. Ours has to show *several*, with a fader each.

### Navigation: three tabs

Transistor has no navigation at all — the list is the app, with Settings and Add-station as
pill buttons at the end of it, and search as a popup dialog. Tasty Radio uses a **bottom
navigation bar with three destinations**:

| Tab | What |
|---|---|
| **Stations** | The collection. The home screen |
| **Search** | Discovery — see [`discovery.md`](discovery.md#the-search-page) |
| **Settings** | Preferences, mixer options, maintenance |

Search earns a whole page rather than a dialog because discovery here means browsing,
filtering, comparing and **auditioning a station into the running mix** — none of which fits in
a modal. The `+ Add new station` and `⚙ Settings` pills at the end of Transistor's list go away;
both are tabs now.

**The vertical budget is the risk.** Nav bar, collapsed mixer pill, and expanded mixer sheet all
want the bottom of the screen. Decision: the **collapsed pill sits above the nav bar, and the
expanded mixer sheet covers it**, with the nav returning on collapse. Verify on the real phone.

### The screens

**Station list (home)** — as Transistor: artwork, name, play button, divider. But rows for
currently-playing stations are visually marked as live, so the list doubles as an overview of
what's in the mix.

**The playback pill becomes a mixer.** Collapsed, it stays close to Transistor's pill: artwork
(stacked or the first channel's), a line like "3 stations", the live track metadata of one
channel, master stop, record. **Expanded** (tap or drag up) it becomes the mixing desk: one row
per active channel with artwork, station name, current track metadata, a volume fader, mute,
and a stop-this-channel button — plus master stop and the record control.

Adding a station to a running mix is just tapping play on another row. That gesture staying
trivial is what makes the app fun; if layering takes four taps, nobody builds soundscapes.

**Scenes (stretch, but the natural payoff)** — save a named set of stations and their volumes
("Sunday Morning Gregorian + Techno"), restore it with one tap. Cheap to store (it's a list of
station IDs and floats), and it turns a lucky combination into something you keep. Deliberately
*after* recording in the build order, but the feature this design is begging for.

---

## Build order

Sequenced so the app becomes usable early and each phase is testable on a real phone.

> **Status 2026-08-17: phases 0, 1 and 2 are built and verified on the emulator.** Two concurrent
> `AudioTrack`s from our UID, both `state:started`, per-channel faders, one audio-focus owner, media
> notification, and playback surviving the app being backgrounded. Not yet tried on the phone —
> which is the only place the *balance* can actually be judged. Phase 3 (recording) is next.

| Phase | What | Why here |
|---|---|---|
| **0** ✅ | Toolchain: install Android SDK, point Gradle at Studio's JBR 21, generate the Gradle/Compose project, get "hello world" onto both the emulator and the phone | Nothing is buildable until the SDK exists |
| **1** ✅ | Room station model, station list UI, **M3U import**, single-station playback through `SoundscapePlayer` + `MediaSessionService`, collapsed playback bar | M3U import first means we seed the owner's real station list straight out of Transistor's own *Export M3U* — real data on day one, no typing URLs |
| **2** ✅ | **The mixer**: player pool, N concurrent stations, per-channel volume/mute, expanded mixer sheet, single audio-focus owner | The reason the app exists |
| **3** ✅ | **Recording**: MediaProjection consent, capture, AAC/`MediaMuxer` encode, MediaStore output, share sheet | The second reason the app exists |
| **4** ✅ | **Discovery** — three-tab navigation, the multi-source local station index (visible sync, clean, FTS5), the **Search page**, curated packs, add-by-URL, station artwork. Detail in [`discovery.md`](discovery.md) | Convenience, once the app is already worth using — M3U import in phase 1 means search isn't blocking |
| **5** | Settings (theme, dynamic colour, larger buffer, editing toggles), maintenance (export M3U, backup/restore), **scenes**, **3-band EQ** | Polish and the optional extras |

Phase 1 + 2 is the point at which Transistor can come off the phone.

---

## Risks worth watching

- **Audio focus fratricide** — covered above; the single most likely thing to make the app feel
  broken. Design for it in phase 2, don't retrofit it.
- **Buffering N streams on mobile data** — one stalling stream shouldn't stop the others; each
  channel reconnects independently. A stall in one channel of a soundscape is survivable, and
  the app should treat it that way rather than tearing the mix down.
- **Battery** — N decoders and N sockets, indefinitely, is a genuinely heavier load than a
  single-stream radio app. Nothing to fix, just don't be surprised.
- **Device-dependent `audiofx`** — the EQ may simply not work properly on a given phone. It's
  optional; let it be optional.
- **Bottom-of-screen congestion** — nav bar plus mixer pill plus expanded sheet. See the
  navigation section above; the resolution is provisional until it's been felt on real hardware.
- **Discovery risks** (first-run index download, index build time, corpus quality) are listed
  separately in [`discovery.md`](discovery.md#risks-worth-watching).
- **`MediaProjection` consent friction** — if the dialog-per-session becomes annoying in real
  use, the fallback is building our own software mixer (all channels into one custom
  `AudioSink`, recording taken from the same buffers). That removes the dialog and the SDK 29
  floor at the cost of real audio-engineering work. Not now; noted for later.
