# Tasty Radio — project guide for Claude

## What this is
**Tasty Radio** is a **native Android app written in Kotlin** for listening to internet radio
streams — with one defining difference from every other radio app: it plays **several stations
at once**, with **independent volume per station**, and can **record the resulting mix to a file
you can share**.

It is a personal/hobby project. The owner intends it to **replace Transistor on their phone**.

### The point of the app
> Essentially Transistor, but able to play multiple radios at once and build a soundscape — and
> press a button to record the mix (church music over techno, say) into files to share with
> friends.

**A radio app that is secretly a mixing desk.** Two unrelated stations played together become a
third thing. Everything else in the app exists to make that easy to reach for.

Read **[`docs/design/soundscape.md`](docs/design/soundscape.md)** before doing any design or
implementation work — it is the real spec: the mixer architecture, recording approach, UI, build
order, and risks.

### The reference app
**[Transistor](https://github.com/y20k/transistor)** is the reference for *what the app should
feel like*: a simple, offline-first list of stations you curate yourself, a persistent playback
bar, background playback with a media notification, no accounts, no ads, no algorithmic feed.
Reference screenshots live in [`docs/reference/`](docs/reference/README.md) — read that file
before making UI decisions; it ends with a section on where we deliberately diverge.

We are **not forking Transistor** (decided 2026-08-17). We build fresh in Compose and use it as
a reference only. Its architecture assumes one player and one session, and the mixer is not a
feature we could bolt onto that — it's the middle of the app.

**"Tasty Radio" is also the name of a station in the owner's own station list** — the app is
named after it.

### Status
**Brand new — nothing is built yet.** As of 2026-08-17 this repo contains documentation only.
There is no Gradle project, no source, no module structure. The stack decisions below are
*intended*, not yet implemented. Do not assume any file exists; check first.

## Working preferences (from the owner)
- Hobby project. **Bias toward shipping** — talk → change → working app.
- Don't over-engineer. No enterprise architecture ceremony for a radio player.
- Use the CLIs and tooling directly (`git`, `gh`, `gradlew`, `adb`) rather than handing the
  owner manual steps, wherever possible.
- Work directly on `main` unless told otherwise.

## Intended tech stack (decided-ish, not yet built)
- **Kotlin**, **min SDK 29** (Android 10), target/compile latest stable.
  - *Why 29 and not 24:* `AudioPlaybackCapture`, which the recording feature depends on, does
    not exist below API 29.
- **Jetpack Compose** + **Material 3** — the reference app supports Material You dynamic
  colours, which Compose+M3 gives nearly for free.
- **Media3 (ExoPlayer)** for playback, **one `ExoPlayer` instance per active station** held in a
  pool, behind a **single** `MediaSessionService` + `MediaSession`. The aggregate is presented to
  the session as one logical player via Media3's `SimpleBasePlayer`. Playback must survive the
  app being backgrounded — this is the most important dependency in the project.
- **`MediaProjection` + `AudioPlaybackCapture`** (own UID) for recording, encoded to AAC `.m4a`
  with **`MediaCodec` + `MediaMuxer`**, written to **`MediaStore`**, shared via `ACTION_SEND`.
- **Room** for the local station collection; **DataStore** for settings.
- **radio-browser.info** public API for the "Find Station" search (what the reference app
  uses). No API key required; it asks for a descriptive User-Agent.
- **`android.media.audiofx.Equalizer`** per player for the optional 3-band EQ — best-effort,
  behind a toggle, last on the list.
- Package name: **`com.tastyradio`** unless the owner says otherwise (was TBD; this is the
  working default, cheap to change *before* first commit of the Gradle project and annoying
  after).

## Feature set to aim for
Build order lives in [`docs/design/soundscape.md`](docs/design/soundscape.md#build-order). In
brief:

1. **Station list** — image, name, play button; tap-to-play; persistent playback bar.
2. **Playback via Media3** with a media notification; station name + ICY stream metadata.
3. **The mixer** — multiple concurrent stations, per-station volume/mute, expandable mixer
   sheet. *The reason the app exists.*
4. **Recording** the mix to a shareable `.m4a`. *The second reason.*
5. **Add station**: M3U/PLS import (first — it seeds the owner's real list from Transistor's own
   *Export M3U*), then radio-browser.info search, then direct stream URL.
6. **Edit station** (long-press → edit name/image) and edit the streaming URL.
7. **Settings**: theme, dynamic colours, tap-anywhere-to-play, larger buffer, editing toggles.
8. **Maintenance**: update station images, export M3U, backup/restore the collection.
9. **Stretch**: named **scenes** (a saved set of stations + volumes), 3-band EQ.

## Local environment (verified 2026-08-17)
- **Android Studio is installed**: `C:\Program Files\Android\Android Studio`, with a bundled
  JBR at `...\Android Studio\jbr` (**OpenJDK 21**).
- **There is NO Android SDK on this machine yet** — no `%LOCALAPPDATA%\Android\Sdk`,
  `ANDROID_HOME`/`ANDROID_SDK_ROOT` are unset. Studio has evidently not been run through its
  first-launch setup wizard. **The SDK must be installed before anything can build.**
- **No standalone `gradle`** on PATH and no `~/.gradle` — use the **Gradle wrapper**
  (`gradlew.bat`) once the project is generated. That's the right call anyway.
- **`java` on PATH is JDK 11** (`C:\Program Files\Eclipse Adoptium\jdk-11.0.26.4-hotspot`),
  which is **too old for modern Android Gradle Plugin** (AGP 8.x needs 17+). Point Gradle at
  Studio's JBR 21 instead (`org.gradle.java.home`, or set `JAVA_HOME` for the shell).
- **No `adb`** on PATH — it arrives with the SDK platform-tools; add
  `%LOCALAPPDATA%\Android\Sdk\platform-tools` to PATH after SDK install.
- `git` and `gh` (authenticated) are available.
- Platform: Windows 11. Shell is **PowerShell** primarily; a Bash tool is also available —
  each takes its own syntax.

### Testing targets (decided 2026-08-17)
**Both**: an emulator AVD for fast UI iteration, and the owner's **physical phone** (the one
currently running Transistor) for anything involving audio, mixing, network, notifications,
Bluetooth or headsets. Neither is set up yet — no AVD exists and `adb` isn't installed.
Anything about how the mix actually *sounds* is only true on the phone.

## Repo layout
```
CLAUDE.md                    this file
README.md                    short human-facing intro
docs/design/soundscape.md    THE SPEC — mixer, recording, UI, build order
docs/reference/              Transistor reference screenshots + written breakdown
```
Everything else is yet to be created.

## Gotchas / notes to future-me
- **Streaming radio is messy in practice.** Expect: redirects, HTTP (not HTTPS) stream URLs,
  playlist files (`.m3u`/`.pls`) served where an audio stream was expected, dead stations, and
  wildly varying ICY metadata. Handle these as normal cases, not edge cases.
- **Cleartext HTTP**: many radio streams are plain `http://` (visible in the reference search
  results). Android blocks cleartext by default from API 28 — a network security config
  permitting cleartext will be needed, or streams will silently fail.
- **Playback must be a foreground service.** Doing playback from an Activity/ViewModel will
  appear to work in testing and then get killed in real use.
- **Audio focus must have exactly one owner.** With several players running, per-player focus
  handling makes our own stations duck and fade each other out. Build every player with
  `handleAudioFocus = false` and manage one `AudioFocusRequest` for the whole soundscape at the
  service level. This is the most likely thing to make the app feel broken.
- **`RECORD_AUDIO` is required for recording** even though we never open the microphone —
  playback capture is delivered through `AudioRecord`. Explain that in the UI when asking, or it
  looks like the radio app wants to eavesdrop.
- **`MediaProjection` shows a system consent dialog** every time a projection starts. Hold one
  projection across a whole recording session rather than re-prompting per clip.
- **Foreground service types**: `mediaPlayback` for the player and `mediaProjection` while
  recording, with matching `FOREGROUND_SERVICE_*` permissions on API 34+.
- **A stalled channel must not kill the mix.** Each stream reconnects on its own; losing one
  station of three is survivable and should behave that way.
- **`audiofx` is vendor-implemented and inconsistent.** Query EQ bands, never assume them, and
  accept that the EQ may do nothing on a given device. It's optional by the owner's own framing.
- Becoming-noisy (headphones unplugged) stops **all** channels, from a single receiver.
- Volume sliders need a perceptual curve (roughly `volume = slider³`, or a dB taper) —
  `ExoPlayer.volume` is linear amplitude and feels wrong mapped straight to a fader.
