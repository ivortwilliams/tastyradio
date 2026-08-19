# Tasty Radio — project guide for Claude

## What this is
**Tasty Radio** plays internet radio with one defining difference from every other radio app: it
plays **several stations at once**, with **independent volume per station**, and can **record the
resulting mix to a file you can share**.

It exists twice, over the same idea:

- **`android/`** — a native Kotlin app. The original, and the one that replaced Transistor on the
  owner's phone.
- **`web/`** — the same desk in a browser, at **https://radio.truthseekersbyo.com**. Added
  2026-08-19. Feature parity: mixing, per-channel tone, recording, and the same search.

Both are described below. **[`docs/design/web.md`](docs/design/web.md)** covers what a browser
forced to be different — read it before touching anything under `web/`.

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
page. Read that before touching search, sync or navigation. **[`docs/design/web.md`](docs/design/web.md)**
is the third: the web version, and the handful of places a browser forced a different answer.

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
- Three-tab navigation: Stations / Search / Settings
- Launcher icon: Millais's *Ophelia* (public domain) — the artwork on the owner's own Tasty Radio
  station, which the app is named after

- **Recording** the mix: `MediaProjection` playback capture of our own UID → AAC → `.m4a` in
  `MediaStore`, named for the stations in it, with a share prompt when it stops. *Verified: a
  1:49 take, AAC-LC 48 kHz stereo, mean volume −14 dB — real audio, not silence.*
- **Search over a local index**: the whole radio-browser corpus on the phone in bundled-SQLite
  FTS5, searched instantly and offline across name/tags/country/language/homepage, with BM25
  ranking multiplied by popularity and reachability. *Verified: 62,466 stations, full sync under
  60 seconds.* Query expansion from tag co-occurrence (PMI, learned from the corpus) plus a
  hand-checked concept map, always shown as removable chips. **▶ auditions a result straight into
  the running mix** without adding it to the collection.

- **Three mixes and ten stations out of the box** (2026-08-18). A fresh install already holds the
  owner's own soundscapes — *Ritual Gregorian* (RadCap ritual ambient under Radio Art's plainsong,
  65% reverb on the chants), *The ULTIMATE Art Bell* (RadCap + Sex Sound Radio + Coast to Coast
  archives, 45% reverb on Art Bell) and *Tasty Radio* on its own — so the point of the app is one
  tap from opening it. Seeds live in [`SeedStations`](app/src/main/java/com/tastyradio/data/SeedStations.kt)
  and [`SeedMixes`](app/src/main/java/com/tastyradio/data/SeedMixes.kt); presets resolve their
  channels by **stream URL**, not row id, and only seed when the mixes table is empty. *Verified on
  a clean install: three `AudioTrack`s from our uid all `state:started`, the reverbed channel's EQ
  lit.*
- **Edit and remove stations**: long-press a row to change name, artwork (device photo picker) or
  stream URL; swipe left to remove, with confirmation.
- **Settings that matter**: large buffer (~60s ahead, on by default), automatic index refresh
  (weekly/daily/off, Wi-Fi + charging via `WorkManager`), M3U export to Downloads.
- **Sync you can check**: station count against radio-browser's own reported total, "complete" vs
  "partial", last-run success/failure with timestamp, change since the previous sync, per-source
  counts, size on disk. *Four consecutive full syncs, all 62,466 against an expected 62,450.*

**Not built** (and not currently wanted): theme picker — the app follows the device, deliberately.
Still open if ever wanted: named **scenes** (a saved set of stations + volumes), the **curated
packs**, and **extra index sources** beyond radio-browser (SomaFM, Icecast/Xiph). Backup/restore
beyond M3U export — images aren't in the export.
See [`docs/design/soundscape.md`](docs/design/soundscape.md#build-order).

### The web version (built 2026-08-19)
**Live at https://radio.truthseekersbyo.com**, feature-complete against the phone: mixing, faders,
the three-band isolator, reverb, delay, recording, the same search, the same seeded stations and
mixes. Code in `web/`, design in [`docs/design/web.md`](docs/design/web.md), operations in
[`web/README.md`](web/README.md).

Verified on production: the shipped *Ritual Gregorian* mix playing both channels at their saved
faders with 65% reverb on the chants, ICY titles arriving on both, and an 8-second take decoding to
stereo 48 kHz at −21.4 dB RMS — real audio, not silence.

**The one thing that forced a server**: a browser cannot put a cross-origin stream through Web
Audio (the graph is tainted and outputs silence), and per-channel EQ, reverb, metering and recording
are all Web Audio. Every stream is relayed same-origin by `web/server/src/proxy.ts`, which also
strips ICY metadata, resolves playlists, rewrites HLS and follows redirects. Read `web.md` before
touching any of it.

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
- ~~3-band EQ~~ — **cut by the owner, 2026-08-17.** It was built (per-player `audiofx.Equalizer`)
  and then removed the same day on their instruction. Don't reintroduce it without being asked.
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
9. **Stretch**: named **scenes** (a saved set of stations + volumes).

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
- **Use the Gradle wrapper** (`android\gradlew.bat`) — it's generated and committed. There's still no
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

### Distribution (set up 2026-08-18)
The app lives at **https://github.com/ivortwilliams/tastyradio** (public, personal account
`ivortwilliams` — *not* the work one). Three things hang off that:

- **The download link to give people**:
  `https://github.com/ivortwilliams/tastyradio/releases/latest/download/TastyRadio.apk`
- **The install page for non-technical friends**: https://ivortwilliams.github.io/tastyradio/
  (GitHub Pages, served from `docs/` on `main`).
- **The app updates itself.** [`Updater`](app/src/main/java/com/tastyradio/update/Updater.kt) reads
  `version.json` from the *latest* release on every launch and offers the new build when
  `versionCode` is higher; it downloads the APK and hands it to the system installer through a
  `FileProvider`. **The asset names must never change** — `TastyRadio.apk` and `version.json` — or
  the `/releases/latest/download/…` URLs baked into old builds stop resolving. *Verified end to end:
  a 0.2 install offered 0.3, downloaded it, installed over itself and kept its data.*

To ship an update: bump `versionCode` **and** `versionName` in `android/app/build.gradle.kts`, then
`.\scripts\release.ps1 -Notes "what changed"` from the repo root. Forgetting `versionCode` means
nobody's phone notices. No CI — the signing key never left this machine, and a one-command script was cheaper than
a pipeline with a secret in it.

**DigitalOcean now hosts the web version** (same account, `ivortawilliams@gmail.com`, token in
`DIGITALOCEAN_ACCESS_TOKEN`): one `s-1vcpu-1gb` droplet in Sydney named `tastyradio`, $6/month, at
`radio.truthseekersbyo.com`. It is *not* used for the APK — GitHub Releases costs nothing, needs no
server, and the CDN is better than a droplet. Deploy details in [`web/README.md`](web/README.md).

**The `radio` A record is the only thing Tasty Radio added to `truthseekersbyo.com`.** That domain
carries a live site and Google Workspace mail; leave the rest of its records alone.

### Building the APK by hand
**From `android/`.** `.\gradlew.bat assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (~38 MB,
universal — every ABI, because a friend's phone is not a known quantity). It is signed with
`tastyradio-release.jks`, whose passwords live in `keystore.properties`; **both are gitignored and
both are irreplaceable** — a build signed with a different key won't install over one already on a
phone, it has to be uninstalled first. `keystore.properties.example` documents the shape. Bump
`versionCode` for each build you hand out.

Sideloading: the recipient opens the link in Chrome, downloads, taps the file, and allows
"install unknown apps" for whichever app is doing the opening. Messenger and WhatsApp both refuse
`.apk` attachments, so it has to be a link.

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
docs/design/web.md           the web version — the proxy, the server-side index, the desk
docs/reference/              Transistor reference screenshots + written breakdown
docs/index.html              the install page (GitHub Pages serves docs/ on main — don't move it)
scripts/release.ps1          one command to build + publish an APK release

web/                         THE WEB VERSION — see docs/design/web.md
  Dockerfile                 one image: client built in, served by the server process
  deploy/                    docker-compose, Caddyfile, provision.sh (run once)
  server/src/
    main.ts                  the whole HTTP surface: one router, node:http, no framework
    proxy.ts                 THE class equivalent — stream relay, ICY strip, playlist/HLS
    net.ts                   outbound HTTP: SSRF guard, pinned DNS, insecureHTTPParser
    index-store.ts           the FTS5 index (same schema and ranking as the phone)
    search.ts / expand.ts    tokenise, expand, rank — ports of SearchRepository/QueryExpander
    indexer.ts               builds the index; runs in GitHub Actions, not on the server
    index-manager.ts         downloads the published index, swaps it in
  client/src/
    audio/graph.ts           ChannelFilters.kt in Web Audio nodes
    audio/mixer.ts           Mixer.kt — the desk
    audio/recorder.ts        MediaRecorder off the master bus
    ui/desk.ts               the channel strips. The thing that makes it look like a desk
    data/store.ts            collection + mixes in localStorage. No accounts, no server data

android/                     THE ANDROID APP
gradle/libs.versions.toml    every dependency version  (under android/)
app/src/main/java/com/tastyradio/  (under android/)
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
- **SQLite doesn't shrink on delete.** Rebuilding the index each sync grew the file 32 → 53 → 71 MB
  while the *content* stayed identical, and Settings shows that number. `VACUUM` needs a
  `wal_checkpoint(TRUNCATE)` **after** it as well as before, or the write-ahead log left behind is
  bigger than the database it just compacted. Steady at 41 MB now.
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
- **Coil's `android.resource://` support is numeric-id only** — verified by disassembling
  `ResourceUriFetcher` in Coil 3.5.0: it takes the last path segment and `toIntOrNull`s it, so the
  `.../drawable/name` form silently falls back to the monogram. Bundled artwork goes in `assets/`
  and is referenced as `file:///android_asset/…`, which is stable across upgrades in a way a baked-in
  resource id is not. That's why Ophelia exists twice in the APK.
- **Station favicons are often transparent PNGs drawn for a light page.** They need an opaque light
  backdrop, and the monogram must be a *fallback* rather than sitting behind the image — otherwise
  it bleeds through and every logo looks dirty.
- **Saving a mix collects what's in it.** A channel auditioned from search carries `id = 0`, and a
  saved mix is a list of station *rows* — so `MixRepository.save` used to filter those channels out
  and the mix came back a station short next session (reported from a real phone, fixed 2026-08-19).
  It now inserts anything not already in the collection, matched by stream URL, which also covers a
  channel auditioned *and* then added with ＋, whose running copy still holds no id.
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

### Learned building the web version (2026-08-19)
- **`ICY 200 OK` is not HTTP and Node's parser knows it.** A large minority of SHOUTcast servers
  answer with that status line instead of `HTTP/1.0 200 OK`, and Node rejects it outright — a chunk
  of the corpus would look like a network failure. `insecureHTTPParser: true` is the equivalent of
  the tolerance ExoPlayer already has.
- **`audio/aacp` makes Chrome refuse a stream it can decode perfectly well.** RadCap — one of the
  owner's own stations, and a channel in a shipped mix — is served with that Shoutcast content type,
  and Chrome fails it with "no supported source was found" purely because of the label. The proxy
  normalises the content type; the bytes are untouched.
- **Node 20's happy-eyeballs calls a custom `lookup` with `all: true` and expects an array back.**
  Answering with the single-address form fails with `ERR_INVALID_IP_ADDRESS` before a byte is sent.
  The custom lookup exists to pin the connection to the address that passed the SSRF check.
- **The stream proxy is an SSRF hole if you let it be.** It takes a URL as a query parameter, so
  without a guard it will fetch `http://169.254.169.254/` and hand over the droplet's cloud-metadata
  credentials. Every hostname is resolved and checked against the private ranges, the connection is
  pinned to the address that passed, and every redirect hop is re-checked.
- **Caddy needs `flush_interval -1` and `read_timeout 0`.** Without the first, audio arrives in
  blocks and server-sent events are held back; without the second, every stream is cut off
  mid-listen. Both are about the same thing: this site's traffic never ends.
- **Recording is *easier* on the web.** No `MediaProjection` consent, no `RECORD_AUDIO`, no
  foreground service type — the mix is already ours, so a `MediaStreamAudioDestinationNode` off the
  master bus into `MediaRecorder` is the whole feature. Chrome supports `audio/mp4;codecs=mp4a.40.2`,
  so the file is the same `.m4a` the phone makes.
- **`web/server/public/` is generated** (Vite builds the client into it) and is gitignored. Do not
  confuse it with `web/client/public/`, which holds real source assets like Ophelia.
