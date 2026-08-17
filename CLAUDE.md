# Tasty Radio — project guide for Claude

## What this is
**Tasty Radio** is a **native Android app written in Kotlin** for listening to internet
radio streams. It is a personal/hobby project.

The design target is **[Transistor](https://github.com/y20k/transistor)** — a FOSS Android
radio app. Transistor is the reference for *what the app should feel like*: a simple,
offline-first list of stations you curate yourself, a persistent playback bar, background
playback with a media notification, and no accounts, no ads, no algorithmic feed. Reference
screenshots of Transistor live in [`docs/reference/`](docs/reference/README.md) — read that
file before making UI decisions.

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
- **Kotlin**, min SDK ~24, target/compile latest stable.
- **Jetpack Compose** + **Material 3** — the reference app supports Material You dynamic
  colours, which Compose+M3 gives nearly for free.
- **Media3 (ExoPlayer)** for stream playback + `MediaSessionService` for background playback,
  the media notification, and lockscreen/Bluetooth/headset controls. This is the single most
  important dependency — playback must survive the app being backgrounded.
- **Room** for the local station collection; **DataStore** for settings.
- **radio-browser.info** public API for the "Find Station" search (what the reference app
  uses). No API key required; it asks for a descriptive User-Agent.
- Package name: TBD (suggest `com.tastyradio` or similar) — **not yet decided**.

## Feature set to aim for (from the reference screenshots)
Core, in rough build order:
1. Station list — image, name, play button; tap-to-play; persistent playback bar at the bottom.
2. Playback via Media3 with a media notification; shows station name + ICY stream metadata
   (current track) when the stream provides it.
3. Add station: by search (radio-browser.info), by direct stream URL, by M3U/PLS import.
4. Edit station (long-press → edit name/image) and edit the streaming URL.
5. Settings: theme, dynamic colours, tap-anywhere-to-play, larger buffer.
6. Maintenance: update station images, export M3U, backup/restore the station collection.

See [`docs/reference/README.md`](docs/reference/README.md) for the detailed screen-by-screen
breakdown these come from.

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

### No device/emulator is configured yet
Nothing is known about how the owner wants to test (physical phone over USB/wifi vs. an
emulator AVD). The reference screenshots come from a physical Android phone. **Ask before
assuming.**

## Repo layout
```
CLAUDE.md              this file
README.md              short human-facing intro
docs/reference/        Transistor reference screenshots + written breakdown
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
- Audio focus, becoming-noisy (headphones unplugged), and network loss/reconnect all need
  handling for the app to feel non-broken.
