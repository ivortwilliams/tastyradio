# Tasty Radio

A native Android internet-radio app, written in Kotlin — **that plays more than one station at
a time**.

Curate your own list of stations, tap one, and it plays in the background with proper media
controls. Then tap another, and it plays *too*. Each station gets its own volume fader, so you
can balance them into a soundscape — Gregorian chant under techno, a shortwave news bulletin
over ambient — and hit record to capture the mix as a file you can send to a friend.

Finding stations is done from a **search page that works offline** — public station directories
are downloaded and indexed on the phone, so results are instant, private, and matched on more
than just the station's name. Type `religion` and you get Vatican Radio.

No accounts, no ads, no feed. Inspired by [Transistor](https://github.com/y20k/transistor),
which is the reference for how the rest of the app should feel.

## Install it

**[⬇ Download Tasty Radio for Android](https://github.com/ivortwilliams/tastyradio/releases/latest/download/TastyRadio.apk)**
— or open **[the install page](https://ivortwilliams.github.io/tastyradio/)** on the phone itself,
which explains the two taps Android asks for.

Android 10 or newer. It is not on the Play Store and doesn't need to be: the app checks for its own
updates and offers them to you, so this is the only download you have to do by hand.

## Status

**It runs, it mixes, it records, and it searches.** Per-station faders over several simultaneous
streams, background playback with a media notification, recording the mix to a shareable `.m4a`,
and an offline search over 62,000 stations that matches on tags rather than just names — so
"religion" finds Radio Vaticana, and ▶ auditions a result straight into whatever is already
playing.

A fresh install already holds three mixes — *Ritual Gregorian*, *The ULTIMATE Art Bell* and
*Tasty Radio* — so the point of the app is one tap from opening it.

## Docs

- [`docs/design/soundscape.md`](docs/design/soundscape.md) — **the spec**: multi-station mixing,
  recording, the mixer UI, navigation, build order
- [`docs/design/discovery.md`](docs/design/discovery.md) — search: the local station index, how
  `religion` finds *Radio Vaticana*, and the Search page
- [`CLAUDE.md`](CLAUDE.md) — project guide: intended stack, feature targets, local environment
- [`docs/reference/`](docs/reference/README.md) — reference screenshots and a screen-by-screen
  breakdown of Transistor, plus where Tasty Radio deliberately diverges

## Building

```bash
./gradlew.bat assembleDebug
```

To publish an update everyone's phone will be offered: bump `versionCode` and `versionName` in
[`app/build.gradle.kts`](app/build.gradle.kts), then

```powershell
.\scriptselease.ps1 -Notes "What changed, in a sentence"
```

which builds the signed APK, writes the `version.json` the in-app updater reads, and publishes both
as a GitHub release. Signing needs `keystore.properties` and the keystore it points at — neither is
in this repo, and a build signed with a different key cannot update one already on a phone.

Needs the Android SDK and a `local.properties` pointing at it (`sdk.dir=…`). Gradle is pinned at
Studio's bundled JBR 21 in `gradle.properties`, because the `java` on this machine's PATH is too old
for AGP. See the *Local environment* section of [`CLAUDE.md`](CLAUDE.md).
