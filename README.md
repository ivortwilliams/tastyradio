# Tasty Radio

A native Android internet-radio app, written in Kotlin — **that plays more than one station at
a time**.

Curate your own list of stations, tap one, and it plays in the background with proper media
controls. Then tap another, and it plays *too*. Each station gets its own volume fader, so you
can balance them into a soundscape — Gregorian chant under techno, a shortwave news bulletin
over ambient — and hit record to capture the mix as a file you can send to a friend.

No accounts, no ads, no feed. Inspired by [Transistor](https://github.com/y20k/transistor),
which is the reference for how the rest of the app should feel.

## Status

**Scaffolding stage.** This repo currently holds documentation only — no Gradle project or
source yet.

## Docs

- [`docs/design/soundscape.md`](docs/design/soundscape.md) — **the spec**: multi-station mixing,
  recording, the mixer UI, build order
- [`CLAUDE.md`](CLAUDE.md) — project guide: intended stack, feature targets, local environment
- [`docs/reference/`](docs/reference/README.md) — reference screenshots and a screen-by-screen
  breakdown of Transistor, plus where Tasty Radio deliberately diverges

## Building

Not yet buildable. The Android SDK still needs to be installed on this machine — see the
*Local environment* section of [`CLAUDE.md`](CLAUDE.md).
