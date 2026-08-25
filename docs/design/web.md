# Web design — the same desk, in a browser

The third design document, after [`soundscape.md`](soundscape.md) (why the app exists) and
[`discovery.md`](discovery.md) (how stations get into it). This one is about the **web version**:
what it is, what had to change, and what it deliberately does differently.

> **Read the other two first.** Nothing here re-argues the mixer, recording or search. Those
> decisions carry over unchanged; this document only covers where a browser forced a different
> answer to the same question.

---

## What it is

`https://radio.truthseekersbyo.com` — the same idea as the Android app, in a browser:

- Several radio stations playing **at once**, with an independent fader on each
- Per-channel **three-band isolator, reverb and delay**
- **Recording** the mix to a shareable `.m4a`
- The same **search** over the whole radio-browser corpus, with the same ranking
- The same collection, the same saved mixes, the same ten seeded stations and three seeded mixes

Feature parity was the brief, and it is met. What changed is the shape, not the scope.

---

## The one thing that forced a server

The phone version has no server and never needed one: `ExoPlayer` points at a URL and plays it.

**A browser cannot do that.** Everything interesting in this app — per-channel EQ, reverb,
metering, recording — happens inside Web Audio, and to get a stream into Web Audio you need a
`MediaElementAudioSourceNode` over the `<audio>` element. That node is **tainted** if the media is
cross-origin without CORS headers, and a tainted node outputs **silence**. Essentially no radio
station sends CORS headers; they have no reason to.

There is a second, equally fatal problem: a page served over HTTPS cannot load `http://` media, and
a large share of the corpus is plain HTTP. The reference list in
[`SeedStations`](../../web/client/src/data/seed.ts) is half cleartext.

So: **every stream is relayed through our own server, same-origin.** This is not a workaround bolted
on afterwards; it is the reason a server exists at all, and it is the single biggest structural
difference between the two versions.

Four other long-standing messes get solved on the way through, in
[`proxy.ts`](../../web/server/src/proxy.ts):

| Mess | What the proxy does |
|---|---|
| **ICY metadata** | Requests `Icy-MetaData: 1`, strips the interleaved metadata out of the audio (browsers can't decode a stream with it embedded), and pushes `StreamTitle` to the tab over SSE. This is how the web version gets the same now-playing line the phone reads off the stream. |
| **Playlists served where audio was expected** | `.m3u` / `.pls` are resolved before the browser ever sees them, up to three hops. |
| **HLS** | The playlist is rewritten so its segments come back through the proxy too, then handed to `hls.js`. Without rewriting, the segments would be fetched cross-origin and we'd be back to a tainted graph. |
| **Redirects, including cross-protocol** | Followed by hand, so every hop gets the same SSRF check as the first. |

### Two things learned building the proxy

**`ICY 200 OK` is not HTTP, and Node's parser knows it.** A large minority of SHOUTcast servers
answer with `ICY 200 OK` instead of `HTTP/1.0 200 OK`. Node's strict parser rejects it outright,
which would silently lose a chunk of the corpus to what looks like a network error.
`insecureHTTPParser: true` is the equivalent of the tolerance ExoPlayer already has.

**`audio/aacp` makes Chrome refuse a stream it can decode perfectly well.** RadCap — one of the
owner's own stations, and a channel in a shipped mix — is served as `audio/aacp`, a Shoutcast
content type no browser recognises. Chrome fails it with "no supported source was found" purely
because of the label. The proxy normalises the content type; the bytes are untouched. Found by
playing the seeded mix and watching one strip go red.

### SSRF is a real risk here, not a theoretical one

The proxy takes a URL as a query parameter. Without a guard it would happily fetch
`http://169.254.169.254/` and hand the droplet's cloud-metadata credentials to whoever asked.

[`net.ts`](../../web/server/src/net.ts) resolves every hostname first, checks every resolved address
against the private ranges, and then **pins the connection to the address that passed** — otherwise
a hostname that answers differently on the second lookup can pass the check and connect somewhere
else. Every redirect hop is re-checked.

> ⚠️ **Node 20's happy-eyeballs calls a custom `lookup` with `all: true` and expects an array back.**
> Answering with the single-address form fails with `ERR_INVALID_IP_ADDRESS` before a byte is sent.
> Cost an hour; worth writing down.

---

## Where the index lives

On the phone, the whole 34 MB station index sits on the device, because search there has to work
offline. On the web that would be a 34 MB download per browser, which is absurd.

**The index lives on the server: one copy for everybody.** It is deliberately the *same database* —
same FTS5 schema, same `porter unicode61 remove_diacritics 2` tokenizer, same BM25 field weights
(`name` 10 · `tags` 6 · country/state/language 3 · `homepage` 1), same popularity × reachability
multipliers applied outside SQLite, same PMI tag co-occurrence, same hand-checked `concepts.json`.
A query typed into either version ranks the same way.

`better-sqlite3` compiles SQLite with FTS5 enabled, which is the whole reason the Android side had
to ship its own SQLite: the platform's is built without it.

### Built once a week, by a robot, for everybody

Building the corpus costs a 50 MB pull from a volunteer-run API and a minute of CPU, and the result
is byte-identical for every deployment. Doing it per-server, on every deploy, would be rude to
radio-browser and pointless for us.

So a **GitHub Actions cron builds it weekly and publishes it as a release asset**, and the server
downloads that on boot and daily thereafter
([`index-manager.ts`](../../web/server/src/index-manager.ts)).

This is not an invention. `discovery.md` already called this shot, for the phone:

> If some future derived dataset is ever too expensive to compute on-device, the answer is a
> **GitHub Actions cron job publishing a static file to a GitHub Release**, which the app fetches.
> A build-time pipeline, not a runtime service. Nothing to keep alive.

The indexer refuses to publish a partial index, because
[the first Android sync built a 998-station index and reported success](discovery.md#sync).

*Measured 2026-08-19: 62,573 stations, 53 seconds, 33.7 MB, 576 tags with learned neighbours.*

---

## Where everything else lives: your browser

**No accounts, and no user data on the server.** The collection, the saved mixes and the recordings
all live in the browser — `localStorage` for the first two, IndexedDB for the blobs. The app has
never had accounts and this is not the thing that should introduce them, and it means the server
holds nothing about you beyond which streams it relayed.

The trade is honest and stated in the UI: **clearing site data loses your collection**, and it does
not follow you between devices. M3U export exists for exactly that reason, and every recording gets
a download button.

---

## Recording: easier here, for once

This is the one place the web version has it *better*.

Android has to ask the system for an `AudioPlaybackCapture` of its own UID, which means a
`MediaProjection` consent dialog every session, the `RECORD_AUDIO` permission even though the
microphone is never opened, a `mediaProjection` foreground service type, and an API 29 floor.

Here the mix is already ours. A `MediaStreamAudioDestinationNode` hangs off the master bus and
`MediaRecorder` encodes it. No permission prompt, no consent dialog, no microphone anywhere near it.

The tap is **post-fader**, as on the phone, so the take is exactly what you heard — clipping and
all. Format preference is AAC in MP4 first, so a recording made here is the same `.m4a` the phone
produces; Opus in WebM where that isn't available.

*Verified: a 6-second take decoded to stereo 48 kHz, −17 dB RMS, −2.6 dB peak — real audio, not
silence. The phone's equivalent check was −14 dB.*

---

## The DSP, ported

[`graph.ts`](../../web/client/src/audio/graph.ts) is `ChannelFilters.kt` in Web Audio nodes, with
the constants carried across deliberately so a mix saved on the phone sounds like the same mix here:

| | Kotlin | Web |
|---|---|---|
| Isolator | Hand-written biquads, two cascaded sections per crossover | `BiquadFilterNode`, two cascaded sections per crossover |
| Crossovers | 250 Hz / 3 kHz, Q 0.707 | identical |
| Band gain | 0 at the bottom of travel, −40 dB range, +9 dB boost | identical |
| Delay | Circular buffer, feedback `0.15 + amount × 0.55` | `DelayNode` + feedback `GainNode`, same numbers |
| Fader taper | `volume = fader³` | identical |
| **Reverb** | **Freeverb — parallel combs into series allpasses** | **`ConvolverNode` with a synthesised impulse response** |

The reverb is the one deliberate divergence. The Kotlin runs Freeverb because it has to write its
own DSP; a browser already has a convolution engine, so it gets decayed noise with a short pre-delay
and a one-pole lowpass standing in for air absorption. One buffer is shared across channels.

**Consequence, stated honestly:** on the phone the reverb *amount* also opens up the room size. Here
it moves the wet level only. In practice that is most of what you hear when you turn a reverb up,
and swapping convolver buffers mid-stream cuts the tail audibly, which would be worse. If it ever
matters, the fix is a small set of impulse responses crossfaded rather than switched.

An effect at zero is **disconnected**, not merely silenced — the same bookkeeping the Kotlin does
with its `delayActive` / `reverbActive` flags, and for the same reason: four convolvers and four
feedback loops running against silence is real CPU.

*Verified: flat 0.44 → all three bands killed **0.0000** → low killed 0.34 → flat again 0.59. The
isolator kills to exact zero, which is the whole point of an isolator and the thing a shelving EQ
cannot do.*

---

## The layout: the desk gets to be a desk

This is where the web version stops being a port.

A phone has one column and a crowded bottom edge — nav bar, collapsed pill, expanded sheet all
competing, which `soundscape.md` flags as a real layout risk. So the phone hides the mixer behind a
pill and expands it over the navigation.

**A browser window is wide.** So the desk is an actual desk, pinned to the bottom and always
visible: one channel strip per station, side by side, each with artwork, name, live track, three
isolator knobs, reverb and delay, a vertical fader, a meter, and mute / solo / stop. The master
section sits on the right with the record button, the clock, save-mix and stop-all.

The knobs are real knobs — drag vertically, wheel, arrow keys, double-click to reset, shift for
fine. Three horizontal sliders stacked in a strip is how you make a desk look like a settings page.

Four tabs rather than three: **Stations · Search · Mixes · Recordings**. Recordings earns a tab here
because there is no system files app to hand them off to.

On a narrow screen the master section drops below the strips and the strips scroll sideways. A desk
you swipe along is still a desk.

### What stayed exactly the same

- **Play adds to the mix rather than replacing it.** One click to layer. If that ever takes four
  taps, nobody builds soundscapes.
- **▶ on a search result auditions into the running mix** at a low fader, without adding it to the
  collection. Dismiss it and it's gone; press ＋ and it stays.
- **Saving a mix collects what's in it** — an auditioned channel has no row of its own, and
  [the phone used to drop those silently](../../CLAUDE.md), so anything not already in the
  collection gets added, matched by stream URL.
- **Channel identity is `id:` or `url:`**, never the row id alone, or every auditioned station would
  collide with every other.
- **A stalled channel gets a 20-second watchdog** and becomes an honest failure with a retry button,
  rather than sitting on "connecting" forever. Losing one channel of three never takes the mix down.
- **Expansion chips are visible and removable.** Unexplained fuzzy matching reads as a broken app.
- **The raw stream URL is shown**, as Transistor does. This audience is trusted with the plumbing.
- **No theme picker.** It follows the device, deliberately.

---

## Hosting

One `s-1vcpu-1gb` droplet in Sydney, **$6/month**, 1 TB of transfer included. Docker Compose with
Caddy in front for automatic TLS.

**Transfer is the number that matters**, not CPU or RAM. A listener with four stations up pulls a
steady ~64 KB/s through the proxy, so 1 TB is roughly 4,000 listener-hours a month. A handful of
friends will not come close. This is also why App Platform was rejected: its load balancer is a poor
fit for hours-long streaming responses, and bandwidth overage is billed per GiB.

Two Caddy settings carry the whole thing: `flush_interval -1` (no response buffering, or audio
arrives in blocks and SSE events are held back) and `read_timeout 0` (or every stream is cut off
mid-listen).

The image is built by GitHub Actions and pulled from GHCR — a 1 GB droplet should not spend its
afternoon compiling TypeScript and a native SQLite binding on every deploy.

### One shared password

The stream proxy relays audio to whoever asks, so an open one is an open relay and the bandwidth is
billed to the owner. `ACCESS_CODE` is one password for everybody, entered once, remembered in a
signed cookie derived from the code itself — so changing the code logs everyone out, and *not*
changing it means a restart doesn't.

Not an account system. Deliberately.

---

## Sharing a mix: the same link on both sides

Added 2026-08-20, and the one feature where the browser was the easy half. A mix is a handful of
URLs and floats, so the whole thing goes in a link — `https://radio.truthseekersbyo.com/m#<payload>`
— with the payload in the **fragment**, which browsers never send to a server. Nothing is stored
here, nothing expires, and the same link opens the phone app.

[`data/share.ts`](../../web/client/src/data/share.ts) and
[`share/MixLink.kt`](../../android/app/src/main/java/com/tastyradio/share/MixLink.kt) write and read
the same bytes: raw-deflated JSON, base64url, one marker character in front. `CompressionStream`
with `deflate-raw` is the browser's name for what `Deflater(level, nowrap = true)` writes — plain
`deflate` writes a zlib header the other side refuses, and the failure looks like a corrupt link
rather than a mismatch. The full reasoning is in
[`soundscape.md`](soundscape.md#sharing-a-mix-built-2026-08-20).

The server's other part in this is `/.well-known/assetlinks.json`, served before the SPA fallback
gets it, carrying the Android release key's fingerprint so a tapped link opens the app without an
"open with?" dialog. It is public even when the access code is on, because Google's verifier is not
going to type a password.

### The short link (added 2026-08-26)

A two-channel mix is 376 characters of link and a four-channel one is worse. It works, and nobody
sends it — a wall of base64 doesn't look like something you'd click. So there is a second shape:

```
https://radio.truthseekersbyo.com/s/<id>#<key>       67 characters
```

The client encrypts the payload above with **AES-GCM** under a key it generates, posts the
ciphertext to `POST /api/mix`, and puts the key in the **fragment**. So the server stores a blob it
cannot read, indexed by an id that is no use without the other half of the link — the privacy the
long form was built around survives being shortened, which is the only reason this was worth
building rather than a plain redirect table.

- [`mixstore.ts`](../../web/server/src/mixstore.ts) is the whole store: one SQLite table in the
  `/data` volume, 48 bits of id, no expiry. A mix is 300-odd bytes; ten thousand is three megabytes.
- `GET /api/mix/<id>` sits **in front of the access gate** — what it returns is ciphertext, so
  there is nothing there to protect, and behind the gate the page would have to pass a password
  before it could learn what it was opening.
- `POST /api/mix` is behind the gate and rate limited to 40/hour per address. An endpoint that
  stores what you send it and hands back a URL is a pastebin if you let it be.
- **Everything falls back to the long link.** No `crypto.subtle`, no network, gate turned on with a
  phone that has no cookie for it — `shortMixLink` returns the `/m#…` form and the feature quietly
  degrades to what it was. The long form stays exactly because it needs nothing and nobody.
- Android claims `/s/` as an App Link alongside `/m`. Builds older than that hand it to the browser,
  which opens the same mix on the web desk: a longer way round, not a break.

*Verified 2026-08-26: the real client's Share button produced a 67-character link, opening it put
both channels of Ritual Gregorian back on the desk at 62/77% with the 65% reverb; and the ciphertext
crosses platforms in both directions — a browser-made link decrypted and inflated by the JVM, a
JVM-made one opened by the browser.*

## What the web version does not have

Written down so nobody looks for it:

- **Background playback.** A phone keeps a foreground service alive; a browser tab does not. Close
  the tab and the music stops. `navigator.mediaSession` gives lock-screen and headset controls over
  the aggregate — the same "one session for the whole soundscape" idea as `SoundscapePlayer` — but
  it cannot survive the tab.
- **Audio focus.** The browser and OS handle ducking; there is no `AudioFocusRequest` to own. The
  becoming-noisy receiver has no web equivalent either.
- **Automatic index refresh settings.** The server refreshes on its own schedule; there is nothing
  per-user to configure.
- **The in-app updater.** A web page is always the current version — that is the whole point.

## Still open

- **Country and codec filters** exist in the search API but have no UI yet.
- **Curated packs** are unbuilt here, as on the phone.
- **Backup/restore** is M3U export plus a JSON snapshot in `store.ts`; there is no import UI for the
  latter yet.
