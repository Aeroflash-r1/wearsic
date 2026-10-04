# Wearsic Server

Standalone Ktor + NewPipe Extractor backend for the Wearsic Wear OS app. This project is intentionally separate from the Android app and can run on an old Android phone through Termux.

## Requirements

- Java 17
- Termux packages: `pkg install openjdk-17 unzip curl`
- `ffmpeg` (the launcher installs it on first start; needed only to transcode rare Opus/WebM-only songs)

No tunnel is required: the watch connects over WiFi, a Tailscale
private network, or a public HTTPS endpoint (Tailscale Funnel via
`wearsic funnel`). See [`../TERMUX_SERVER_GUIDE.md`](../TERMUX_SERVER_GUIDE.md)
for every connection option and the one-line installer.

## Build and run

The Kotlin source in this folder is the **canonical implementation** (registered as the `:wearsic-server` module in the root Gradle build). There is no prebuilt jar anymore — release ZIPs are generated from source by CI.

### From the Git repository (source build)

```bash
./gradlew :wearsic-server:installDist
cd wearsic-server
PORT=8080 WEARSIC_DB_PATH="$PWD/wearsic.db" ./run-termux.sh
```

`run-termux.sh` finds the fresh source build at `build/install/wearsic-server/bin/wearsic-server` automatically. See `SETUP.md` for details.

### From the single-file bundle (`wearsic-server-v<version>.sh`) — simplest

One download contains the ENTIRE server (engine + launcher + CLI):

```bash
pkg install -y openjdk-17
chmod +x wearsic-server-v*.sh
./wearsic-server-v*.sh install     # extracts to ~/wearsic-server, generates an API key
wearsic start                      # one command from here on
```

`wearsic start|stop|restart|status|logs|health|url|api-key|update|doctor`
manages everything. Re-running `install` upgrades in place and keeps
`wearsic.db` + `.env`. `./wearsic-server-v*.sh uninstall` removes it.

### From the ready-made Termux ZIP (`wearsic-server-termux-v<version>.zip`)

1. Install Termux packages: `pkg install openjdk-17 unzip`
2. Allow storage access once: `termux-setup-storage` (then restart Termux if asked)
3. Copy the ZIP from Downloads and extract it:
   ```bash
   cp ~/storage/downloads/wearsic-server-termux.zip ~/
   cd ~
   unzip wearsic-server-termux.zip
   ```
4. Start the server:
   ```bash
   cd ~/wearsic-server
   chmod +x run-termux.sh
   ./run-termux.sh
   ```

The ZIP is self-contained: `run-termux.sh` sits next to `bin/` and `lib/` and launches the server with a tuned heap. Your favorites/playlists are stored in `wearsic.db` next to the script — keep a copy of an old `wearsic.db` if you want to carry data over.

`run-termux.sh` uses G1GC and a 512 MB heap by default. Override `JAVA_OPTS` when the phone has more or less memory. If `ffmpeg` is missing, the launcher attempts `pkg install -y ffmpeg` and **exits non-zero on failure** (the server cannot transcode Opus/WebM-only songs without it).

## Environment

- `PORT` — defaults to `8080`
- `WEARSIC_DB_PATH` — defaults to `wearsic.db`
- `WEARSIC_API_KEY` — optional. If set, every `/api/*` request must include `X-Wearsic-Key` (constant-time comparison); `/health` remains public. When unset the server is OPEN — fine on a private LAN/Tailscale, never on a public tunnel. A warning is printed at startup when open.
- `WEARSIC_YOUTUBE_COOKIE` — optional browser cookie string fallback. Required when YouTube returns `Sign in to confirm that you're not a bot` for the server IP. Keep it private and export it only at runtime. The watch app can also push a cookie at runtime (see below). The cookie is never logged and the API never returns it — only `{"hasCookie":true|false}`.
- `WEARSIC_AUTO_UPDATE` — self-healing engine updates. Defaults to **on**: when extraction starts failing en masse (canary-confirmed engine breakage) or on a slow periodic check, the server fetches a newer `wearsic-server-termux-*.zip` from this project's GitHub Releases, verifies it, stages it, and exits; `run-termux.sh` swaps it in on restart (old build kept as `bin.prev`/`lib.prev` for rollback). Set to `0` to disable and update manually (`wearsic update` or the installer).
- `WEARSIC_STATE_DIR` — directory for staged updates and `update.json`. Defaults to `wearsic-state/` next to the database.

## API

Public:

- `GET /health` — liveness: is the process alive (version, transcoder, extraction/update status)
- `GET /ready` — readiness: can it serve music right now (`{"ready","database","extractor","transcoder","engineVersion"}`;
  no secrets, cheap — safe for monitoring probes)

Authenticated when `WEARSIC_API_KEY` is set:

- `GET /api/search?q=` — maximum 10 results (YouTube Music songs with real videoIds)
- `GET /api/suggestions?q=` — maximum 5 suggestions
- `GET /api/related/{videoId}` — maximum 10 results
- `GET /api/stream/{videoId}` — proxied audio with Range forwarding; prefers M4A/AAC near 128 kbps
- `GET|POST|DELETE /api/favorites[/{videoId}]`
- `GET|POST /api/playlists`
- `GET /api/playlists/{id}`
- `POST|DELETE /api/playlists/{id}/tracks[/{videoId}]`
- `GET /api/playlist?url=` — maximum 50 tracks (full albums)

## Self-healing

The server defends itself against the two failure classes that actually kill music servers:

1. **Runtime rot** (expired CDN URLs, one dead video, stalled sockets): ranged proxying, dead-URL re-resolution (403/404/410 → fresh extraction → retry once), iOS→default InnerTube client fallback, per-request timeouts, and the supervisor's crash/hang restart loop.
2. **Engine rot** (YouTube changes their site and breaks NewPipeExtractor): every extraction is counted. After 6 consecutive failures the server probes a canary video ("Me at the zoo" — effectively permanent). If the canary also fails, the engine is declared broken:
   - with `WEARSIC_AUTO_UPDATE` on (default): a newer GitHub release is downloaded, **SHA-256-verified against the release's published `.zip.sha256`**, integrity-checked (central-directory walk — truncated downloads rejected), strictly extracted (zip-slip, duplicate and unexpected-file protection), staged into `wearsic-state/staging/`, and the process exits so `run-termux.sh` swaps it in transactionally on restart.
   - with it off: a loud log line tells you exactly what to do instead.

Check engine status any time: `curl http://localhost:8080/health | jq .extraction,.canaryHealthy,.update`

## Updates, authenticity and rollback

Auto-update is designed to be safe to run unattended on a phone:

- **Authenticity**: every release publishes `wearsic-server-termux-<tag>.zip`
  **and** `wearsic-server-termux-<tag>.zip.sha256`. The updater verifies the
  downloaded bytes against that checksum (streamed SHA-256) BEFORE staging —
  and **refuses to install any release that ships no checksum at all**.
  Modified, truncated, unsigned or malformed packages are rejected before
  anything on disk is touched. (Signed manifests are not implemented; the
  published checksum is the current authenticity guarantee.)
- **Compatibility**: servers running older releases keep running normally.
  They simply never auto-install a release without a `.sha256` asset; update
  manually with the zip + installer until you are on a release that publishes
  checksums. Nothing is bricked by upgrading.
- **Crash-safe apply** (`run-termux.sh`): the new engine is copied in full to
  `bin.new`/`lib.new` first, then swapped in by directory renames
  (`bin.prev`/`lib.prev` keep the previous build). An interruption at any
  point is detected at the next start: the supervisor either finishes the
  swap or restores the previous known-good engine — it never boots a
  partially copied engine.
- **Rollback**: if a replaced engine never passes startup/health validation,
  the supervisor restores the previous build (at most once per hour — no
  rollback/restart storms) and records why in `wearsic-state/rollback.json`
  (surfaced as `update.rollbackReason` in `/health`).
- **Loop protection**: each target version may be staged at most
  **3 times** (`wearsic-state/update-attempts.json`, survives cleanup).
  After that auto-update refuses and asks for a manual update — a
  stage → apply → rollback cycle can never run forever.
- **Audit trail**: applies and rollbacks are appended to
  `wearsic-state/update-history.log`.

Also authenticated when `WEARSIC_API_KEY` is set:

- `GET /api/search/albums?q=` — album/playlist search (maximum 10 results;
  album `id` is a full playlist URL, feed it to `/api/playlist?url=`)
- `GET /api/config/youtube-cookie` — returns `{"hasCookie": true|false}`
- `POST /api/config/youtube-cookie` — body `{"cookie": "SID=...; HSID=..."}`; saves the cookie in SQLite and applies it to every YouTube request immediately. Send `{"cookie":""}` to clear it.

**Cookie handling**: the cookie is a Google authentication credential. It is
never written to logs, never returned by any endpoint (only `hasCookie`
true/false), and never included in `/health`, `/ready` or `wearsic` output.
Storage limitation: on Termux there is no OS secure-storage API available to
this architecture, so the cookie lives in `wearsic.db` (settings table) or
the `WEARSIC_YOUTUBE_COOKIE` environment variable — the same trust level as
the phone's user account. Protect the phone accordingly; clear the cookie
with `wearsic cookies` + empty value or `POST {"cookie":""}`.

Search requests normalize whitespace, reject queries over 200 characters, coalesce concurrent requests, and filter duplicate/blank video IDs before returning results. Metadata exceptions fall back to YouTube search; coroutine cancellation is propagated rather than mistaken for an upstream failure. Stream warmup is capped to two unique candidates in one active batch, and a new search does not cancel a warmup that foreground playback may be awaiting. Single-flight computations start lazily only after winning registration, preventing duplicate network/extraction work on parallel dispatchers.

`wearsic doctor` reports readiness-specific database/extractor remedies, missing audio conversion, staged engine updates, warning totals, and the next useful management command without exposing credentials.

The server caches search results and resolved stream targets in small bounded in-memory caches (stream targets expire after 1 hour — CDN URLs expire upstream). Search goes to YouTube Music first (official titles/artists with directly playable videoIds); when YTM is unreachable the NewPipeExtractor YouTube search is used as fallback. Legacy surrogate → YouTube video matches from pre-1.5 builds are additionally persisted in SQLite (bounded to 2000 rows, 30-day staleness), so old saved favorites keep replaying after upgrade. SQLite uses WAL mode with `synchronous=NORMAL` for good performance on a phone.

## Errors and rate limiting

Every error is JSON: `{"error": "<message>"}` with a meaningful status — `400` invalid request/malformed body, `401` missing or wrong API key, `404` unknown route/video, `502` upstream CDN failure, `503` rate-limited or missing ffmpeg for transcode-needing songs, `500` unexpected (message is generic; details go to the log).

`GET /api/stream/{id}` is rate-limited per client (API key, else IP): sustained 30 requests/minute with short bursts above that allowed. This protects an open server from becoming a free YouTube proxy; normal playback (a few streams per minute) is unaffected.

Stream extraction is resilient:

- The **iOS Innertube client is tried first** (it is the one that actually works against YouTube's bot wall), falling back to the default client. Because the client choice is a process-global NewPipeExtractor setting, all extractions are serialized behind a mutex — correctness over concurrency, at no practical cost for a personal server.
- NewPipe failures map to clean JSON errors instead of empty 500 responses: `404` when a video is unavailable, `503` for bot/ReCaptcha challenges (with a hint to configure the YouTube cookie), and `502` for other extraction failures.

## Public access (HTTPS)

The watch app already sends `X-Wearsic-Key` automatically once the key
is entered in **Settings → API Key** — nothing to extend. For a public
HTTPS URL, the stable option is Tailscale Funnel from Termux:

```bash
wearsic funnel     # prints https://<phone-name>.<tailnet>.ts.net
```

Public exposure REQUIRES an API key: `wearsic funnel` and
`wearsic public` refuse to run without one (and `funnel` also refuses
to expose a server that is not running and healthy). No key is ever invented
or changed automatically — set one yourself with `wearsic api-key
<your-key>`.

A Cloudflare Tunnel (`cloudflared tunnel --url http://localhost:8080`)
also works but its URL changes on every restart. All options, including
the no-tunnel ones, are in [`../TERMUX_SERVER_GUIDE.md`](../TERMUX_SERVER_GUIDE.md).

## License

The Wearsic server is free software licensed under the **GNU General Public
License, version 3 only** (`SPDX-License-Identifier: GPL-3.0-only`).

"Only" is deliberate: the server is not offered under any later version of the
GPL, and not under the GNU Lesser General Public License. The complete license
text is in [`LICENSE`](./LICENSE) in this directory.

Scope of this license:

- `wearsic-server/` — the server, licensed GPL-3.0-only.
- `app/` — the Wear OS application, licensed **separately** under the Apache
  License, Version 2.0 (see [`../LICENSE`](../LICENSE)). The GPL does not apply
  to the Wear OS application, and the Apache-2.0 license does not apply to the
  server. The two are separate works that talk to each other over HTTP.

Third-party components:

- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) — a
  dependency of the server, licensed under the GNU General Public License,
  version 3. See [`../NOTICE`](../NOTICE).

Other dependencies (Ktor, SQLite JDBC, Logback, Kotlin) remain under their own
licenses; review those before redistributing.
