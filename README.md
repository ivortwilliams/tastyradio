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

## Status

**It runs, and it mixes.** The station list, per-station faders, background playback with a media
notification, artwork, and M3U/PLS import all work — verified on an emulator playing two stations
at once. Recording is next.

Not built yet: recording the mix, station search, and most settings.

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

Needs the Android SDK and a `local.properties` pointing at it (`sdk.dir=…`). Gradle is pinned at
Studio's bundled JBR 21 in `gradle.properties`, because the `java` on this machine's PATH is too old
for AGP. See the *Local environment* section of [`CLAUDE.md`](CLAUDE.md).
