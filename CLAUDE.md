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
order, and risks. Its companion **[`docs/design/discovery.md`](docs/design/discovery.md)** covers
how stations get *into* the app: the locally-downloaded station index, matching, and the Search
page. Read that before touching search, sync or navigation.

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
**Phases 0–2 are built and verified on the emulator** (2026-08-17). The app runs, plays several
stations at once, and each channel has its own fader.

Working today:
- Gradle/Compose project, `com.tastyradio`, min SDK 29, compile SDK 37.1, builds with `gradlew.bat`
- Station list from Room, seeded on first run with the owner's own six stations
- **The mixer**: up to 4 concurrent `ExoPlayer` channels, per-channel fader/mute/stop/retry,
  one audio-focus owner, one `MediaSession` over the aggregate, media notification, background
  playback. *Verified two simultaneous `AudioTrack`s from our UID, both `started`, surviving the
  app being backgrounded.*
- ICY stream metadata per channel; station artwork from directory favicons, monogram fallback
- Add by URL, and M3U/PLS import
- Three-tab navigation: Stations / Search / Settings (Search and Settings are honest placeholders)

- **Recording** the mix: `MediaProjection` playback capture of our own UID → AAC → `.m4a` in
  `MediaStore`, named for the stations in it, with a share prompt when it stops. *Verified: a
  1:49 take, AAC-LC 48 kHz stereo, mean volume −14 dB — real audio, not silence.*
- **Search over a local index**: the whole radio-browser corpus on the phone in bundled-SQLite
  FTS5, searched instantly and offline across name/tags/country/language/homepage, with BM25
  ranking multiplied by popularity and reachability. *Verified: 62,466 stations, full sync under
  60 seconds.* Query expansion from tag co-occurrence (PMI, learned from the corpus) plus a
  hand-checked concept map, always shown as removable chips. **▶ auditions a result straight into
  the running mix** without adding it to the collection.

Not built: settings and maintenance (phase 5) — theme choice, larger buffer, editing toggles, M3U
export, backup/restore. Also unbuilt from phase 4: the curated packs, extra sources beyond
radio-browser, and the `WorkManager` weekly refresh (sync is manual today).
See [`docs/design/soundscape.md`](docs/design/soundscape.md#build-order).

## Working preferences (from the owner)
- Hobby project. **Bias toward shipping** — talk → change → working app.
- Don't over-engineer. No enterprise architecture ceremony for a radio player.
- Use the CLIs and tooling directly (`git`, `gh`, `gradlew`, `adb`) rather than handing the
  owner manual steps, wherever possible.
- Work directly on `main` unless told otherwise.

## Tech stack (phases 0–2 are built; the rest is intended)
Concrete versions live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml) — AGP 9.3.1,
Gradle 9.7, Kotlin 2.4.10 (**AGP 9 has built-in Kotlin support — applying
`org.jetbrains.kotlin.android` as well is an error**), Compose BOM 2026.08.00, Media3 1.11.0,
Room 2.8.4, Coil 3.5.0, KSP 2.3.11. `compileSdk = 37` + `compileSdkMinor = 1`, because the current
AndroidX versions refuse to compile against 36; `targetSdk` stays 36.

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
- **Search is local, not live.** The whole **radio-browser.info** corpus (~50k stations) is
  downloaded, cleaned and indexed **on the phone** in **SQLite FTS5** (`porter` tokenizer),
  refreshed weekly on Wi-Fi via **`WorkManager`**. Searching is then instant, offline, private
  and rankable by us. No API key required; send a descriptive User-Agent, and
  `POST /json/url/{uuid}` on play to feed the popularity signal we rank on.
  - **Several sources, one adapter each**, all sharing a `source` column: radio-browser first,
    then bundled **curated packs** (ideas/spoken-word, world public broadcasters, community &
    college, cultural), then SomaFM, then Icecast/Xiph. The goal is **maximum recall** across
    music, talk, documentary and philosophy alike.
  - **No deduping — decided, deliberate.** Duplicate entries carry different tags and different
    stream URLs, both of which are extra recall and extra resilience. If results feel repetitive,
    group them *in the UI*; never delete rows at ingest.
  - **Sync must be visible**: a sync-in-progress indicator and a "last synced" time, on the
    Search page and in Settings → Station index (with a per-source breakdown and *Sync now*).
  - ⚠️ **Android has no FTS5 — measured, not assumed.** Framework SQLite on API 36 is 3.44.3 with
    `fts3`/`fts4` only; `fts5` and `bm25()` are both absent. Search therefore depends on
    **`androidx.sqlite:sqlite-bundled`** (SQLite 3.50.1, FTS5 + `porter unicode61` + `bm25` all
    verified working, dependency already added). Room's FTS *annotations* are FTS3/4 only either
    way, so the FTS5 table needs raw SQL in a `RoomDatabase.Callback` or migration. When phase 4
    starts, move Room onto `BundledSQLiteDriver` rather than running two SQLites in one app.
  - ⚠️ **The `porter` stemmer does not bridge `religion`/`religious`** (it does bridge
    `chant`/`chanting` and `worship`/`worshipping`). `religion` finds *Radio Vaticana* via
    multi-field search hitting the literal `religion` tag, not via stemming. Details in
    [`discovery.md`](docs/design/discovery.md#layer-1--fts5-with-the-porter-stemmer-free).
  - **Semantic-ish matching without ML.** `religion` finds *Radio Vaticana* via the `porter`
    stemmer + multi-field search + **tag co-occurrence expansion** computed locally from the
    corpus, plus a small generated concept→tag JSON asset. **No on-device embedding model, no
    ONNX/MediaPipe, no vector database** — considered and rejected 2026-08-17, reasoning in
    [`discovery.md`](docs/design/discovery.md#layer-4--on-device-embeddings-considered-and-rejected-2026-08-17).
  - **No server of our own** (decided 2026-08-17). If derived data ever outgrows the device, the
    answer is a GitHub Actions cron publishing a static file to a Release — a build-time
    pipeline, not a runtime service.
- **Three-tab bottom navigation**: **Stations**, **Search**, **Settings**. Transistor's search is
  a popup dialog; ours is a full page, because discovery here includes browsing, filtering and
  **auditioning a station into the running mix**.
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
   *Export M3U*), then the **Search page** over the local index, then direct stream URL.
6. **Edit station** (long-press → edit name/image) and edit the streaming URL.
7. **Settings**: theme, dynamic colours, tap-anywhere-to-play, larger buffer, editing toggles,
   station-index refresh frequency + "clear index".
8. **Maintenance**: update station images, export M3U, backup/restore the collection.
9. **Stretch**: named **scenes** (a saved set of stations + volumes), 3-band EQ.

## Local environment (verified 2026-08-17)
- **Android Studio is installed**: `C:\Program Files\Android\Android Studio`, with a bundled
  JBR at `...\Android Studio\jbr` (**OpenJDK 21**).
- **The Android SDK is installed** at `%LOCALAPPDATA%\Android\Sdk` (done 2026-08-17 via the
  command-line tools, not Studio's wizard). Installed: `platform-tools` 37.0.1,
  `platforms;android-36` + `android-37.1`, `build-tools;36.1.0` + `37.0.0`, `emulator` 37.1.11,
  `system-images;android-36;google_apis;x86_64`. All licences accepted.
  - `ANDROID_HOME`/`ANDROID_SDK_ROOT` are **not** set as environment variables — the build finds
    the SDK through `local.properties` (`sdk.dir`, machine-local, gitignored). Set them per-shell
    when driving `sdkmanager`/`avdmanager` directly.
  - Note the SDK's new naming: platforms carry **minor versions** now (`android-37.1`), and
    `compileSdk = 37` pairs with `compileSdkMinor = 1`.
- **Use the Gradle wrapper** (`.\gradlew.bat`) — it's generated and committed. There's still no
  standalone `gradle` on PATH, which is fine.
- **`java` on PATH is JDK 11** (`C:\Program Files\Eclipse Adoptium\jdk-11.0.26.4-hotspot`),
  which is **too old for modern Android Gradle Plugin** (AGP 8.x needs 17+). Point Gradle at
  Studio's JBR 21 instead (`org.gradle.java.home`, or set `JAVA_HOME` for the shell).
- **`adb` is installed but not on PATH** — it's at
  `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`. Call it by full path, or add that directory
  to PATH.
- `git` and `gh` (authenticated) are available.
- Platform: Windows 11. Shell is **PowerShell** primarily; a Bash tool is also available —
  each takes its own syntax.

### Testing targets (decided 2026-08-17)
**Both**: an emulator AVD for fast UI iteration, and the owner's **physical phone** (the one
currently running Transistor) for anything involving audio, mixing, network, notifications,
Bluetooth or headsets.

- **AVD `TastyRadio_API36`** exists: Pixel 6 profile, API 36 `google_apis` x86_64, 1080×2400 @
  420dpi, patched for `hw.gpu.mode=swiftshader_indirect` and `hw.keyboard=yes`. Boot it with
  `%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe -avd TastyRadio_API36`.
- **The phone has never been connected.** Nothing has been tested on real hardware yet, and
  **anything about how the mix actually *sounds* is only true there** — the emulator proves streams
  play concurrently, not that the balance is usable. `dumpsys audio` is the trick for verifying
  concurrency without ears: look for two `AudioPlaybackConfiguration` entries on our uid, both
  `state:started`.

## Repo layout
```
CLAUDE.md                    this file
README.md                    short human-facing intro
docs/design/soundscape.md    THE SPEC — mixer, recording, UI, navigation, build order
docs/design/discovery.md     search — local station index, matching, the Search page
docs/reference/              Transistor reference screenshots + written breakdown
gradle/libs.versions.toml    every dependency version
app/src/main/java/com/tastyradio/
  TastyRadioApp.kt           Application: Room, repository, Mixer, Coil loader. No DI framework
  data/                      Station, StationDao, TastyDb, StationRepository,
                             PlaylistParser (M3U/PLS), SeedStations (the owner's six)
  playback/                  Mixer (THE class — player pool, faders, audio focus),
                             SoundscapePlayer (SimpleBasePlayer aggregate), SoundscapeService
  ui/                        MainActivity, RootScreen (3 tabs), StationListScreen, MixerBar,
                             SearchScreen + SettingsScreen (placeholders), AddStationDialog,
                             StationArtwork, Glyphs (hand-drawn — no icon dependency), theme/
```

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

### Learned the hard way while building phases 0–2 (2026-08-17)
- **Media3 won't post the media notification until a `MediaController` binds to the session.**
  `startService()` alone gets you a running service with no notification and no foreground
  promotion — verified on API 36. `MainActivity` connects a controller in `onStart` purely for this;
  the UI itself talks to the `Mixer` directly.
- **Compose's `Icons` are in a separate artifact** (`material-icons-core`), and `Stop`, `VolumeOff`
  and `Record` aren't in it anyway. `ui/Glyphs.kt` draws them on a `Canvas` instead — no dependency,
  and a filled square for *stop* is the honest shape for live radio.
- **Station favicons are often transparent PNGs drawn for a light page.** They need an opaque light
  backdrop, and the monogram must be a *fallback* rather than sitting behind the image — otherwise
  it bleeds through and every logo looks dirty.
- **Channel identity can't be the Room id.** Auditioning a search result plays a station that isn't
  in the collection (`id = 0`), so the `Mixer` keys channels on `Station.channelKey()` —
  `id:<row>` when saved, `url:<stream>` when not. Getting this wrong would make every auditioned
  station collide with every other.
- **`adb shell dumpsys audio`** is how you prove the mixer works without ears: two
  `AudioPlaybackConfiguration` lines on our uid, both `state:started`.
- A stalled stream used to sit in `Connecting…` forever, because ExoPlayer keeps retrying a load
  that isn't erroring — it's just silent. `Mixer` now arms a 20-second watchdog per channel.

### Learned building phases 3–4 (2026-08-17)
- **`/json/stations` silently caps at 1000 rows.** No error, no header. The first sync built a
  998-station index and reported success. Always page with explicit `limit`/`offset`.
- **radio-browser mirrors are dual-stack, and IPv6 can be a dead end** — `de1` resolves to an AAAA
  address the emulator can't route to. Ingest tries mirrors in turn, and the app sets
  `java.net.preferIPv6Addresses=false`.
- **Media3 renders `COMMAND_PLAY_PAUSE` as a pause icon**, which is the wrong promise for live
  radio. A `CommandButton` with `ICON_STOP` in `setMediaButtonPreferences` adds a real stop, and
  needs the custom `SessionCommand` allowed in `MediaSession.Callback.onConnect` or it's ignored.
- **MediaProjection order matters on API 34+**: start the foreground service *with type
  `mediaProjection`* first, and only then call `getMediaProjection()`. The reverse order is refused.
- **The recording is post-fader**, since the system mixes before we capture — the take is exactly
  what you heard. Watch for clipping when several loud channels sum; a single station already
  peaked at 0 dBFS.
- **Search ranking is split deliberately**: SQLite does BM25, then popularity and reachability are
  applied in Kotlin. SQLite's `log()` needs `SQLITE_ENABLE_MATH_FUNCTIONS`, which isn't worth
  depending on, and re-ranking a few hundred rows in Kotlin is free.
