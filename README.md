# Tasty Radio

An internet-radio app **that plays more than one station at a time**.

Curate your own list of stations, play one, and then play another — and it plays *too*. Each
station gets its own volume fader, a three-band isolator, reverb and delay, so you can balance them
into a soundscape — Gregorian chant under techno, a shortwave news bulletin over ambient — and hit
record to capture the mix as a file you can send to a friend.

Finding stations is done over the **whole radio-browser corpus**, matched on tags and country and
language rather than just the station's name. Type `religion` and you get Vatican Radio.

No accounts, no ads, no feed. Inspired by [Transistor](https://github.com/y20k/transistor), which is
the reference for how the rest of it should feel.

## It exists twice

**On the web** — **[radio.truthseekersbyo.com](https://radio.truthseekersbyo.com)**. Nothing to
install; there is one shared access code. The mixer is a proper desk here, because a browser window
is wide enough for one: channel strips side by side with a fader and knobs on each.

**On Android** —
**[⬇ download the APK](https://github.com/ivortwilliams/tastyradio/releases/latest/download/TastyRadio.apk)**,
or open **[the install page](https://ivortwilliams.github.io/tastyradio/)** on the phone itself,
which explains the two taps Android asks for. Android 10 or newer. It is not on the Play Store and
doesn't need to be: the app checks for its own updates and offers them to you, so this is the only
download you have to do by hand.

The two share the seeded stations, the shipped mixes, the search ranking and the DSP constants. They
differ where a phone and a browser genuinely differ — background playback on one side, no install on
the other.

## Status

**Both run, mix, record and search.** Several simultaneous streams with a fader and tone control on
each, recording the mix to a shareable `.m4a`, and search over 62,000 stations that matches on tags
rather than just names — so "religion" finds Radio Vaticana, and ▶ auditions a result straight into
whatever is already playing.

A fresh install (or a fresh browser) already holds three mixes — *Ritual Gregorian*, *The ULTIMATE
Art Bell* and *Tasty Radio* — so the point of the app is one tap from opening it.

The Android app additionally does background playback with a media notification, which a browser tab
cannot.

## Docs

- [`docs/design/soundscape.md`](docs/design/soundscape.md) — **the spec**: multi-station mixing,
  recording, the mixer UI, navigation, build order
- [`docs/design/discovery.md`](docs/design/discovery.md) — search: the local station index, how
  `religion` finds *Radio Vaticana*, and the Search page
- [`docs/design/web.md`](docs/design/web.md) — the web version: why it needs a server, where the
  index lives, and how the desk changes shape
- [`web/README.md`](web/README.md) — running and deploying the web version
- [`CLAUDE.md`](CLAUDE.md) — project guide: stack, feature targets, local environment
- [`docs/reference/`](docs/reference/README.md) — reference screenshots and a screen-by-screen
  breakdown of Transistor, plus where Tasty Radio deliberately diverges

## Building

**The web version** — see [`web/README.md`](web/README.md). In short:

```bash
cd web/server && npm install && npm run build && node dist/main.js
```

Deploying is `git push`; GitHub Actions builds the image and restarts the droplet.

**The Android app** — from `android/`:

```bash
./gradlew.bat assembleDebug
```

To publish an update everyone's phone will be offered, bump `versionCode` and `versionName` in
[`android/app/build.gradle.kts`](android/app/build.gradle.kts), then:

```powershell
.\scripts\release.ps1 -Notes "What changed, in a sentence"
```

which builds the signed APK, writes the `version.json` the in-app updater reads, and publishes both
as a GitHub release. Signing needs `keystore.properties` and the keystore it points at — neither is
in this repo, and a build signed with a different key cannot update one already on a phone.

Needs the Android SDK and a `local.properties` pointing at it (`sdk.dir=…`). Gradle is pinned at
Studio's bundled JBR 21 in `gradle.properties`, because the `java` on this machine's PATH is too old
for AGP. See the *Local environment* section of [`CLAUDE.md`](CLAUDE.md).
