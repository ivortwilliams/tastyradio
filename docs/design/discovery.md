# Discovery design — the local station index and the Search page

How Tasty Radio finds stations. This is the second design document, after
[`soundscape.md`](soundscape.md). That one is about the app's reason for existing; this one is
about getting stations *into* it.

---

## The problem, stated properly

Transistor's "Find Station" dialog queries radio-browser.info live and appears to search
**station names only**. Type `church` and you get stations with "church" in the name. Type
`religion` and you get nothing — even though Vatican Radio is in the database several times
over, tagged `catholic, christian, religion`.

**This is a vocabulary problem, not a coverage problem.** The station you want is called *Radio
Vaticana*. There is zero string overlap with what you typed. More stations don't fix it; more
*fields* and better *matching* do.

So the design goal is not "have the biggest list". It's: **the word in your head finds the
station, even when it's not the word in the station's name.**

---

## The decision: pull it all down, search on the phone

**Tasty Radio downloads the whole station corpus, processes it locally, and searches locally.**
No live API call per keystroke, and no server of our own.

### Why local

- **Instant.** Search-as-you-type with no network round trip, no debounce anxiety, no spinner.
- **Offline.** Browse and search on a plane, on the tube, on bad mobile data.
- **Unlimited.** No rate limits, so we can fan a single query across five fields and merge.
- **Ours to rank.** We control scoring completely instead of accepting the API's ordering.
- **Private.** What you search for never leaves the phone. Given the app is for layering
  Gregorian chant over techno, that's a feature, not a slogan.
- **Analysable.** Having the whole corpus in one place is what makes the co-occurrence work
  below possible at all.

### Why not a server of our own

It was considered and rejected. A server buys two things: hot-fresh data (unnecessary — station
lists move slowly) and heavyweight compute (unnecessary — see the co-occurrence section). It
costs hosting, uptime obligation on a hobby project, a privacy surface, and a single point of
failure that quietly kills discovery in eighteen months when nobody's paying the bill.

If some future derived dataset is ever too expensive to compute on-device, the answer is a
**GitHub Actions cron job publishing a static file to a GitHub Release**, which the app fetches.
A build-time pipeline, not a runtime service. Nothing to keep alive.

---

## Sources

**The goal is maximum recall.** The owner's interests span everything from all kinds of music to
talk radio, documentary and philosophy. One database will not cover that — so pull from several,
keep a `source` column on every row, and let them pile up. **More rows is the point.**

| Source | Size | Priority | Notes |
|---|---|---|---|
| **radio-browser.info** | ~50k | **1 — primary** | Open API, no key, community-maintained. The bulk of everything |
| **Curated packs**, bundled in the APK | ~300–500 | **2** | Hand-checked. See below — this is where spoken-word radio actually lives |
| **SomaFM** | ~30 | **3 — free win** | Publishes a machine-readable channel list with direct stream URLs. Tiny, reliable, well-curated music |
| **Icecast / Xiph directory** (`dir.xiph.org`) | few thousand | **4** | A genuinely *different* population — small, self-hosted, weird. Verify a usable machine-readable feed exists before depending on it |
| **Community M3U/JSON collections on GitHub** | varies wildly | **5** | Real coverage, real junk. Only worth it with a named, maintained source — check commit recency before trusting one |
| Radio Garden / TuneIn | huge | **no** | No open API. Products, not sources |

Order of implementation is the priority column. Ship radio-browser + curated packs, then add
sources one at a time as separate ingest adapters. **Adding a source must never be a rewrite** —
one interface, one adapter per source, one `source` column.

### The curated packs

Worth emphasising, because for the owner's stated interests these probably matter *more* than
the 50k-station database.

Community tagging is weakest on exactly the things the owner wants most. Music genres are tagged
reasonably well. **Talk, ideas, documentary and philosophy are tagged `talk` and `news` and
nothing else** — the vocabulary to find them barely exists in the corpus, so no amount of clever
matching will surface them. The fix isn't search, it's curation.

Suggested packs, each a small hand-checked JSON asset:

| Pack | What's in it | Why |
|---|---|---|
| **Ideas & spoken word** | BBC Radio 4 / World Service, ABC Radio National, France Culture, CBC Ideas, RTÉ, DW, SBS, RNZ | *France Culture is literally a philosophy radio station.* This is the pack that answers the "philosophy" case, and no database will |
| **World public broadcasters** | ORF, YLE, NRK, RTVE, NPR/PRX members, NHK World, ABC, CBC | Patchily represented and badly tagged in community databases despite being the most listened-to stations on earth |
| **Community & college radio** | WFMU, Resonance 104.4FM, NTS, Rinse, KEXP, campus stations | The home of genuinely strange programming — talk, experimental music, one-person shows |
| **Cultural / experimental music** | SomaFM channels, ambient/drone/shortwave/numbers-station streams | Directly feeds the soundscape use case — these are the layers |

These need no network, work on first launch, and are browsable from the Search page's empty
state with no query typed at all.

---

## Sync

**First run:** download the full corpus, process, build the index. Show honest progress — this
is a one-off multi-megabyte download and pretending otherwise is worse than saying so. The
bundled curated pack means the app is usable *while* this happens.

**Thereafter:** a `WorkManager` periodic job.

- **Default: weekly, Wi-Fi only, while charging.** Not daily. Station data changes slowly and a
  ~10MB pull on mobile data is rude.
- **Settings offers daily**, and a **"Refresh now"** button for the impatient.
- Constraints are real constraints — if there's no Wi-Fi for two weeks, the app searches
  fine on stale data. Staleness is not an error state.

### Rough sizes (verify against reality, these are estimates)

| | |
|---|---|
| Full corpus, raw JSON | ~50 MB |
| Same, gzipped over the wire (request `Accept-Encoding: gzip`) | ~8–12 MB |
| After stripping to fields we keep, in SQLite | ~10 MB |
| Plus the FTS5 index | ~+10–15 MB |

**Open question:** does radio-browser expose a "changed since" endpoint or a downloadable dump?
If so, incremental refresh gets the weekly cost to near zero. Check before building the full-pull
path as permanent.

### Sync must be visible

**Requirement, not a nice-to-have: the user can always see that a sync is happening, and when
the last one finished.** A background job silently mutating the thing you search is the kind of
opacity that makes an app feel untrustworthy — especially when results change and you don't know
why.

**Sync state is a first-class, observable thing** (a `StateFlow` off the sync repository, not a
boolean buried in the worker):

| State | Shown as |
|---|---|
| **Never synced** | Search page: "Station index not downloaded yet — [Download now]". Curated packs still work |
| **Syncing** | A determinate progress bar where possible (rows fetched / rows indexed), with the current source named: *"Syncing radio-browser… 22,400 stations"* |
| **Synced** | Relative time — *"Last synced 3 hours ago"* — with the absolute timestamp on tap |
| **Stale** | Same, but flagged once past ~2× the refresh interval: *"Last synced 3 weeks ago"* |
| **Failed** | The actual reason (no network / server error / cancelled), the last-good sync time, and **[Retry]**. Never a silent failure, never a bare "error" |

**Where it appears:**

- **Settings → Station index** — the full picture: last sync (absolute + relative), total station
  count, **a per-source breakdown of counts and last-sync time**, refresh frequency, index size
  on disk, **[Sync now]**, and **[Clear index]**.
- **Search page** — a slim, non-blocking status line at the top of the empty state. Progress
  while syncing, last-sync time when idle, the error and a retry when failed. It must **not**
  block searching: the old index stays fully usable while a new sync runs.
- **First run only** — a proper visible progress state, because a multi-megabyte download with no
  explanation is the worst possible first impression.

**Never a modal, never a blocking spinner over the whole app.** Sync is background work; it gets
a status line, not a roadblock.

Persist per-source: `lastSyncStartedAt`, `lastSyncFinishedAt`, `lastSyncResult`, `stationCount`.
That's what makes the breakdown and the honest error messages possible.

### Being a good citizen

- **Descriptive `User-Agent`** — `TastyRadio/1.0` — the API asks for this.
- **Server discovery via DNS on `all.api.radio-browser.info`**, picking a random mirror rather
  than hammering one.
- **`POST /json/url/{uuid}` when the user actually plays a station.** This is what feeds
  `clickcount` and `clicktrend` — the popularity signal we rank on. Two lines, and it makes the
  commons better for the next app that uses it.

---

## Processing: the data is messy, clean it on the way in

Well-documented problems with the corpus: duplicate stations, duplicated tags, and "tags" that
are entire pasted sentences. Handle these as normal cases.

### We do not dedupe. Decided.

**No dedupe pass. Not within radio-browser, not across sources.** Every row that comes down is
kept. This is a deliberate decision, not an omission, and it isn't laziness — it's better for
what this app is for:

- **Duplicates carry different tags.** The same station submitted three times often has three
  different tag sets. Merging picks one and throws away the others — which throws away exactly
  the vocabulary that layer 2 and layer 3 need to find it. A "duplicate" is free extra recall.
- **Duplicates carry different stream URLs.** One is dead, one is a mirror, one is the AAC feed
  and one is the 320k MP3. Keeping all four means a station stays playable when the "best" one
  goes down.
- **Deduping is guessy.** URL normalisation and name-matching both produce false merges, and a
  false merge silently deletes a station you wanted. The failure mode of *not* deduping is
  visible and mild; the failure mode of deduping is invisible and permanent.
- **Recall is the goal.** More rows is the point.

**If results ever feel repetitive, fix it in the UI, never in the data** — collapse
near-identical names into one result card showing "4 streams" with the alternates expandable.
That's a presentation choice, reversible, and it keeps every row searchable. Do not reintroduce
a dedupe pass at ingest.

**Clean tags.** Split on comma, trim, lowercase, collapse whitespace. Drop anything longer than
~32 characters or more than 3 words — that's a sentence, not a tag. Drop empties and duplicates.

**Drop the obviously dead.** `lastcheckok = 0` stations are hidden by default, behind a
"show unreachable stations" toggle rather than deleted — the check is a heuristic and it is
sometimes wrong about a station you personally know works.

**Keep only what we need.** uuid, name, stream URL(s), codec, bitrate, tags, country +
countrycode, state, language, homepage, favicon, votes, clickcount, clicktrend, lastcheckok,
geo lat/long, source.

---

## Matching: how `religion` finds `Radio Vaticana`

Layers, cheapest first. **Layers 1, 2, 3 and 3.5 ship. Layer 4 is decided against** — the
reasoning is kept below so nobody re-opens it from scratch.

### Layer 1 — FTS5 with the `porter` stemmer *(free)*

One virtual table over `name + tags + country + state + language + homepage`, with
`tokenize = "porter unicode61 remove_diacritics 2"`.

The Porter stemmer collapses `chant`/`chanting` and `worship`/`worshipping` to a shared token for
nothing. Diacritic folding means `Vaticana` is findable by people who don't type accents.

> ### ⚠️ Corrected 2026-08-17 — the stemmer does *not* bridge `religion`/`religious`
>
> Measured against a row tagged `catholic,religion,christian,gregorian,chant,worship`, in FTS5 with
> `tokenize = "porter unicode61 remove_diacritics 2"`:
>
> | Query | Result |
> |---|---|
> | `religion` | **match** (the literal tag) |
> | **`religious`** | **no match** |
> | `chant` / `chanting` | match / match |
> | `worship` / `worshipping` | match / match |
>
> Porter strips `-ous` (`religious` → `religi`) but only strips `-ion` after `s` or `t`, so
> `religion` stays whole. Different stems, no bridge. Two of the three examples this document
> originally claimed do work; the headline one does not.
>
> **This doesn't break the design — it relocates the credit.** `religion` finds Radio Vaticana
> because [layer 2](#layer-2--search-every-field-at-once-free) searches the `tags` field where that
> exact tag lives, not because of stemming. Layer 1 is worth having; it just does less than stated.
>
> **Consequence worth acting on:** stemming covers less vocabulary than assumed, so layers 3 and 3.5
> carry more of the load. Specifically, deferring the concept→tag table until "real queries show
> it's needed" now looks optimistic — `religious`, `spiritual` and `prayer` all miss a station
> tagged `religion` today, and that table is the only cheap fix.

> **Note for implementation:** Room's annotations only cover FTS3/FTS4. An FTS5 table needs raw
> SQL in a migration or a `RoomDatabase.Callback`.

> ### ⚠️ Corrected 2026-08-17 — Android has no FTS5. Measured, not assumed.
>
> This document originally said "Android's bundled SQLite has FTS5 well before our API 29 floor,
> so it's available". **That is wrong**, and it's load-bearing: both this layer and the [BM25
> ranking formula](#ranking) depend on FTS5.
>
> Probed from inside the app on the API 36 emulator (`SQLiteDatabase.create(null)`):
>
> | | |
> |---|---|
> | Framework SQLite version | 3.44.3 |
> | `fts3`, `fts4` | available |
> | **`fts5`** | **`no such module: fts5`** |
> | **`bm25()`** | **unavailable — it is FTS5-only** |
>
> Android's SQLite is compiled without FTS5 and always has been. The `sqlite3` shell on the device
> agrees, but that isn't evidence either way — the probe above is the library the app links.
>
> **The fix, also verified: ship our own SQLite.** `androidx.sqlite:sqlite-bundled:2.7.0` gives
> **SQLite 3.50.1**, where `CREATE VIRTUAL TABLE … USING fts5(…, tokenize = "porter unicode61
> remove_diacritics 2")` and `bm25(t, 10.0, 6.0)` both work exactly as this document assumes. The
> dependency is already added. Costs ~1–2 MB of native library per ABI, and buys identical search
> behaviour on every device instead of whatever each OEM compiled.
>
> **Decide when phase 4 starts:** the collection database currently uses the framework SQLite via
> Room's default. Rather than run two SQLite implementations in one app, move Room onto
> `BundledSQLiteDriver` for everything at that point.
>
> The FTS4 fallback is worse than it looks, which is why it isn't the recommendation: no `bm25()`
> (ranking would have to be computed by hand from `matchinfo()`), and FTS3/4 cannot chain
> tokenizers — `porter` and `unicode61` are alternatives there, so stemming and diacritic folding
> become mutually exclusive.

### Layer 2 — search every field at once *(free)*

`religion` now hits the **`religion` tag** and Vatican Radio appears. This alone fixes most
"nothing came up" moments, and it's the single highest-value change over Transistor's behaviour.

Field weights for BM25, roughly: `name` 10 · `tags` 6 · `country`/`state` 3 · `language` 3 ·
`homepage` 1. Tune on real queries.

### Layer 3 — tag co-occurrence expansion *(cheap, local, no model)*

**This is the one that does the real work**, and it's why having the whole corpus on-device
matters.

Every station carries a tag list. Tags that describe the same thing appear together: `church`
with `religion`, `catholic`, `gospel`, `worship`; `gregorian` with `chant`, `sacred`,
`classical`. Count the co-occurrences, compute PMI, keep each tag's top ~8 neighbours above a
threshold in a `tag_neighbour` table.

**Cost:** ~50k stations × ~5 tags each ≈ 500k pair increments. Seconds of Kotlin, once per sync.
~10k tags × 8 neighbours = 80k rows of storage. This is a *1990s information-retrieval
technique*, not machine learning, and on short-tag data it works genuinely well.

**What you get:** a synonym dictionary **learned from the actual data**, with no hand-curation
and no model — and it surfaces relationships nobody would have thought to write down.

**Query time:** look up the typed terms in the tag vocabulary (exact, then prefix). Found →
pull the neighbours, add them to the FTS query at reduced weight (~0.3 of the original terms).

**Its limit, honestly:** it can only relate words that exist somewhere in the corpus. A term
radio-browser has never seen has nothing to attach to. That's the gap layer 3.5 fills.

**UX rule — make the expansion visible.** Show it as removable chips under the search field:

```
religion    [also: christian ×] [catholic ×] [gospel ×] [worship ×]
```

Unexplained fuzzy matching feels like the app is malfunctioning. A visible, editable expansion
feels like the app is helping — and lets the user steer it.

### Layer 3.5 — a generated concept→tag table *(~50 KB, ships)*

Layer 3 can only relate words the corpus already contains. Layer 3.5 covers words it doesn't,
for about 1% of the effort of layer 4 — by using AI **at authoring time instead of on the
device**.

**Generate a `concept → tags` map once, offline, as a JSON asset in the APK.** Roughly 200
concepts covering what humans actually type at a radio app: `religion`, `meditation`, `worship`,
`prayer`, `sacred`, `liturgy`, `chill`, `study`, `sleep`, `driving`, `protest`, `old`, `weird`…

```json
{ "religion": ["christian", "catholic", "gospel", "worship", "religious",
               "islamic", "quran", "buddhist", "spiritual", "church",
               "hymn", "gregorian", "chant"] }
```

Two rules that make it work:

1. **Generate it against the real `/json/tags` vocabulary**, so every target is a tag that
   actually returns stations. A synonym pointing at nothing is worse than no synonym.
2. **Hand-check it.** It's ~200 lines. An hour of reading, and it stays correct forever.

**Why this beats an embedding model at the same job:** no model, no inference, no runtime cost,
no similarity threshold to tune, no 27 MB. And it's **inspectable** — if `religion` maps
somewhere stupid, you open the file and fix the line. You cannot do that to a vector space.

Merged with layer 3 at query time: table hits first, co-occurrence neighbours after, both shown
in the same removable chips.

### Layer 4 — on-device embeddings *(considered and rejected, 2026-08-17)*

**Decision: we are not doing this.** Recorded in full so it doesn't get re-litigated.

The numbers, which are genuinely fine:

- `all-MiniLM-L6-v2`, int8-quantised ONNX: ~22 MB, plus ONNX Runtime Mobile ~5 MB. Or MediaPipe's
  Text Embedder task (`com.google.mediapipe:tasks-text`), which is ~30 lines of Kotlin and ships
  its own tokenizer.
- Query embedding ~10 ms. Embedding the ~10k-term tag vocabulary: one background job, 1–3 min,
  ~3.8 MB of int8 vectors. Brute-force cosine over 10k vectors is single-digit milliseconds — no
  ANN index, no vector database.

So the engineering is **not** the problem. Three other things are:

1. **Sentence-embedding models are trained on sentences; single words are their weakest case.**
   Asked how close `religion` is to `church`, a model says "fairly close" — and says the same of
   `politics`, `culture`, `philosophy`, `community`. Single-token embedding space is mushy, and
   you end up tuning a similarity threshold forever with no principled place to put it.
2. **Layer 3 is better evidence for this specific job.** It isn't reasoning about English; it's
   reporting that on *this corpus*, stations tagged `church` are overwhelmingly also tagged
   `religion`. Ground truth about how radio stations are actually labelled beats a general
   model's opinion about word meanings.
3. **It duplicates layer 3.5.** A 27 MB runtime dependency whose job is generating synonyms,
   versus a 50 KB table of synonyms generated once. Same output, minus the dependency.

Where it would genuinely win: **multi-word natural-language queries** ("calm music for reading
late at night") and vocabulary absent from the corpus. Both real — but nobody types sentences
into a radio app, and 3.5 covers the second one.

**If this is ever revisited**, the two non-negotiables are: (a) never hand-roll a WordPiece/BPE
tokenizer in Kotlin — use MediaPipe's bundled models or fuse the tokenizer into the ONNX graph at
export; and (b) put it behind a **settings toggle switching between expansion strategies**
(co-occurrence / table / embeddings / all), so "is this actually better?" becomes a thing you
test in ten minutes rather than an argument.

### Ranking

```
score = bm25(weighted fields)
      × log10(clickcount + votes + 10)     // popularity
      × (lastcheckok ? 1.0 : 0.15)         // reachability
```

Popularity as a multiplier, not a filter — obscure stations are half the point of this app, they
just shouldn't outrank the obvious answer to a vague query.

---

## The Search page

**Search is a full page with its own tab, not a dialog.** This is a deliberate divergence from
Transistor, whose "Find Station" is a popup over a dimmed station list. A dialog is right for
"add the thing I already know the name of". It's wrong for browsing, filtering, comparing, and
auditioning — which is what discovery actually is.

### Empty state — browsing is discovery too

An empty search page is a wasted screen, and this is where the local index pays off, because all
of this is instant and offline:

- **Sync status line** — see [Sync must be visible](#sync-must-be-visible)
- **Tag chips** by popularity — `jazz` `ambient` `gregorian` `talk` `shortwave` `drone`
- **Browse by country**, with station counts
- **Popular now** — ordered by `clicktrend`
- **Recently added**
- **The curated packs** — ideas & spoken word, world public broadcasters, community & college,
  cultural/experimental. Browsable with no query typed

Transistor's dialog cannot do any of this. It's a straightforward win from the architecture.

### Results

One card per station, keeping Transistor's honesty about the plumbing:

- station name, with **codec + bitrate** in parentheses
- **tags**, small — they explain *why* this result matched
- country / language
- the **raw stream URL**, shown directly, ellipsised
- **➕ Add** — puts it in your collection
- **▶ Audition** — see below

### Filters

A collapsible row: codec, minimum bitrate, country, language, HTTPS-only, show-unreachable, and
**source**. All local, all instant.

### Audition — play it *into the mix* before adding

The mixer-native idea, and the thing no other radio app has a reason to build. Tapping ▶ on a
search result starts it **as a channel in the current soundscape**, at a low default volume,
without adding it to the collection. You hear it *over what's already playing* — which is the
only way to know whether it belongs there.

Dismiss it and it's gone. Tap ➕ and it stays.

This makes the Search page part of the instrument rather than a filing cabinet, and it's the
best argument for search being a real page: an audition needs somewhere to live that isn't a
modal over the top of everything.

### Searching your own stations

The same field also searches the **local collection first**, shown in a separate section above
the corpus results. Once the list is 40 stations long you'll want it, and it's free.

---

## Navigation: three tabs

The app gains a **bottom navigation bar** with three destinations:

| Tab | What |
|---|---|
| **Stations** | The collection. The home screen. Transistor's list |
| **Search** | Discovery, browsing, audition, add |
| **Settings** | Preferences, mixer options, maintenance |

Consequences worth recording:

- The `+ Add new station` and `⚙ Settings` pill buttons at the end of Transistor's list **go
  away** — both are now tabs. The station list gets simpler, which is right.
- Direct-URL entry and **M3U import** live on the Search page, as a secondary action ("Add by
  URL", "Import playlist") rather than a separate screen.

### The vertical budget problem

Three things now want the bottom of the screen: the nav bar, the collapsed mixer pill, and the
expanded mixer sheet. Stacking a nav bar under a playback pill is a known way to make an app
feel cramped.

**Decision: the mixer sheet expands *over* the navigation bar**, not above it. Collapsed pill
sits above the nav; expanded, it takes the nav's space too. Nav returns when it collapses.
Watch this on the real phone — it's a layout risk, not a solved problem.

---

## Where this lands in the build order

Discovery is **phase 4** in [`soundscape.md`](soundscape.md#build-order) — after the mixer and
recording. Deliberately. The owner's real station list arrives via **M3U import in phase 1**,
straight out of Transistor's own *Export M3U*, so the app has real data on day one and does not
need search to be useful.

Within phase 4, the order is:

1. radio-browser sync + processing + FTS5 index (layers 1 and 2), **with the visible sync state
   from day one** — the multi-field search alone is the big win
2. The Search page, browsing, filters, add-by-URL, Settings → Station index
3. The **curated packs** — cheap, no network, and the only route to the spoken-word/philosophy
   material. Arguably should be first
4. Tag co-occurrence expansion (layer 3) + the expansion chips
5. Additional sources, one adapter at a time: SomaFM, then Icecast/Xiph, then anything else
6. Audition-into-the-mix
7. The generated concept→tag table (layer 3.5) — only once real queries show it's needed

Ship 1 and 2 and evaluate. It's entirely possible that multi-field search plus the curated pack
quietly solves the problem and layer 3 is the last thing anyone needs to build.

---

## Risks worth watching

- **First-run download size.** A multi-megabyte pull before the app feels ready is the worst
  possible first impression. The bundled curated pack is the mitigation: the app works
  immediately, the index arrives in the background.
- **Index build time on a slow phone.** 50k rows into FTS5 is not free. Do it off the main
  thread, in a `WorkManager` job, and make partial results usable.
- **Storage growth.** ~25 MB of index on top of the app. Fine, but show it in Settings with a
  "clear index" option — users who never search shouldn't silently carry it.
- **Corpus quality.** Dead stations, wrong tags, sentence-tags. Clean tags on ingest, and accept
  that some results will still be junk. Showing the raw stream URL, as Transistor does, lets the
  user judge for themselves.
- **Repetitive results**, since we deliberately don't dedupe. Watch for it in real use. If it
  bites, the fix is **display grouping only** — see the no-dedupe section. Never delete rows.
- **Multiple sources multiplying the mess.** Each new source is a new ingest adapter with its own
  quirks and its own junk. Add them one at a time, keep the `source` column visible in Settings,
  and be willing to drop a source that turns out to be more noise than signal.
- **Over-expansion.** Aggressive synonym expansion turns every query into mush. Keep the
  neighbour count low (~8), the weight low (~0.3), and the chips removable. This is the failure
  mode to watch for in layers 3 and 3.5 both.
- **Cleartext HTTP.** Many stream URLs are plain `http://`. The network security config must
  permit cleartext or search results will be findable and unplayable — the worst combination.
