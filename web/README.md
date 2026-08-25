# Tasty Radio, on the web

The same desk as the Android app, in a browser: several radio stations at once, a fader and a
three-band isolator on each, reverb and delay, and a record button.

**Live at https://radio.truthseekersbyo.com** (one shared access code).

The design and the reasoning live in **[`../docs/design/web.md`](../docs/design/web.md)** — read that
first. This file is how to run it.

---

## Shape

One process. The client is built into static files and served by the same server that proxies the
audio, so there is one container and one thing to restart.

```
client/     Vite + TypeScript, no framework. Builds into server/public/
server/     node:http + better-sqlite3. The proxy, the search API, the static files
deploy/     docker-compose.yml, Caddyfile, provision.sh
Dockerfile  builds both halves into one image
```

**`client/public/assetlinks.json` is served at `/.well-known/assetlinks.json`** (routed in
`server/src/main.ts`, before the SPA fallback swallows it). It carries the Android release key's
SHA-256 fingerprint, and it is what lets a shared mix link — `/m#…` — open the phone app instead of
the browser. **Re-sign the APK with a different key and this file has to change**, or phones quietly
stop claiming links. Nothing looks broken when that happens, which is the problem.

**Short mix links go through here too.** `POST /api/mix` takes a blob and hands back an id;
`GET /api/mix/<id>` gives it back, and sits in front of the access gate because what it returns is
ciphertext — the key is in the link's fragment and never reaches us. One SQLite table in `DATA_DIR`,
no expiry, 40 new links an hour per address. See [`server/src/mixstore.ts`](server/src/mixstore.ts)
and [`docs/design/web.md`](../docs/design/web.md#the-short-link-added-2026-08-26).

**The stream proxy is the reason a server exists at all.** A browser cannot put a cross-origin
stream through Web Audio — the graph is tainted and outputs silence — and per-channel EQ, reverb,
metering and recording are all Web Audio. It also fixes ICY metadata, playlist files, HLS, redirects
and cleartext HTTP on the way through. See `docs/design/web.md`.

---

## Running it locally

Two terminals. The server first:

```bash
cd web/server && npm install && npm run build && node dist/main.js
```

Then the client, which proxies `/api` to it:

```bash
cd web/client && npm install && npm run dev
```

Open http://localhost:5173.

### You need a station index

Search is empty without one. Either let the server download the published one (the default — it
pulls from the GitHub release on boot), or build your own:

```bash
cd web/server && npm run build && node dist/indexer-cli.js data/station-index.db
```

That takes about a minute and pulls ~50 MB from radio-browser, so prefer the published one unless
you are changing the indexer. Set `INDEX_URL=` (empty) to stop the server replacing a local build
with the published copy.

### Environment

| Variable | Default | What |
|---|---|---|
| `PORT` | `8080` | |
| `DATA_DIR` | `./data` | Where the station index and the short-link store live |
| `ACCESS_CODE` | *(empty)* | One shared password. **Empty means the site is open — and so is the stream proxy.** |
| `SESSION_SECRET` | derived from the code | Signs the access cookie |
| `INDEX_URL` | the GitHub release asset | Empty disables refresh and uses whatever is on disk |
| `MAX_CONCURRENT_STREAMS` | `48` | Ceiling across everybody, so one tab can't eat the box |

There is a handle on the running desk from the browser console — `window.tastyRadio` gives you
`{ mixer, desk, store }`. The interesting state in this app is audio state, and the only way to look
at a running graph is to be holding it.

---

## Deploying

`git push` to `main` with anything under `web/` changed. GitHub Actions builds the image, pushes it
to GHCR, ships the compose files to the droplet, restarts, and then checks the site actually came
back — failing the run and dumping container logs if it didn't.

The droplet itself was made once by [`deploy/provision.sh`](deploy/provision.sh): one
`s-1vcpu-1gb` in Sydney, $6/month, Docker and Caddy, 1 GB of swap, firewall on. Re-running it is
safe; every step checks before it creates.

Secrets the deploy needs, already set on the repo: `DROPLET_HOST`, `SITE_HOST`, `DROPLET_SSH_KEY`.
The access code lives in `/opt/tastyradio/.env` on the droplet and nowhere else — change it there
and `docker compose up -d`.

### The station index

A [weekly GitHub Actions cron](../.github/workflows/station-index.yml) rebuilds the whole
radio-browser corpus into SQLite FTS5 and publishes it as a release asset on the fixed
`station-index` tag. Every server downloads that on boot and daily after.

Building it per-server would cost radio-browser a 50 MB pull each deploy for a result identical
everywhere. `discovery.md` called this shot years before there was a web version: *a build-time
pipeline, not a runtime service.*

Run it by hand with `gh workflow run "Station index"`. It **refuses to publish a partial index** —
`/json/stations` silently caps at 1000 rows, and the first Android sync shipped a 998-station index
while reporting success.

---

## Watching it

```bash
ssh -i ~/.ssh/tastyradio_deploy root@<droplet> 'cd /opt/tastyradio && docker compose logs -f --tail 50'
```

`https://radio.truthseekersbyo.com/healthz` answers `{"ok":true,"index":62574}` without a code, which
is what the deploy workflow polls.

---

## Costs

$6/month, all in. **Transfer is the number that matters**, not CPU: a listener with four stations up
pulls a steady ~64 KB/s through the proxy, so the included 1 TB is roughly 4,000 listener-hours a
month. GitHub Actions, GHCR and the release hosting are free at this scale.
