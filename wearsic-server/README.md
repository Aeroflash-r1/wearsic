# Wearsic Server

Standalone Ktor + NewPipe Extractor backend for the Wearsic Wear OS app. This project is intentionally separate from the Android app and can run on an old Android phone through Termux.

## Requirements

- Java 17
- Termux packages: `pkg install openjdk-17 unzip curl`
- `ffmpeg` (the launcher installs it on first start; needed only to transcode rare Opus/WebM-only songs)

No tunnel is required: the watch connects over WiFi, a Tailscale
private network, or a public HTTPS endpoint (Tailscale Funnel via
`wearsic server funnel`). See [`../TERMUX_SERVER_GUIDE.md`](../TERMUX_SERVER_GUIDE.md)
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
wearsic server start               # one command from here on
```

`wearsic server start|stop|restart|status|logs|health|url|ip` manages
everything. Re-running `install` upgrades in place and keeps `wearsic.db` +
`.env`. `./wearsic-server-v*.sh uninstall` removes it.

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
- `WEARSIC_AUTO_UPDATE` — self-healing engine updates. Defaults to **on**: when extraction starts failing en masse (canary-confirmed engine breakage) or on a slow periodic check, the server fetches a newer `wearsic-server-termux-*.zip` from this project's GitHub Releases, verifies it, stages it, and exits; `run-termux.sh` swaps it in on restart (old build kept as `bin.bak`/`lib.bak` for rollback). Set to `0` to disable and update manually.
- `WEARSIC_STATE_DIR` — directory for staged updates and `update.json`. Defaults to `wearsic-state/` next to the database.

## API

Public:

- `GET /health`

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
   - with `WEARSIC_AUTO_UPDATE` on (default): the newest newer GitHub release ZIP is downloaded, integrity-verified (central-directory walk; truncated downloads are rejected), zip-slip-checked, staged into `wearsic-state/staging/`, and the process exits so `run-termux.sh` applies it atomically on restart (previous build kept as `.bak`).
   - with it off: a loud log line tells you exactly what to do instead.

Check engine status any time: `curl http://localhost:8080/health | jq .extraction,.canaryHealthy,.update`

Also authenticated when `WEARSIC_API_KEY` is set:

- `GET /api/search/albums?q=` — album/playlist search (maximum 10 results;
  album `id` is a full playlist URL, feed it to `/api/playlist?url=`)
- `GET /api/config/youtube-cookie` — returns `{"hasCookie": true|false}`
- `POST /api/config/youtube-cookie` — body `{"cookie": "SID=...; HSID=..."}`; saves the cookie in SQLite and applies it to every YouTube request immediately. Send `{"cookie":""}` to clear it.

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
wearsic server funnel     # prints https://<phone-name>.<tailnet>.ts.net
```

A Cloudflare Tunnel (`cloudflared tunnel --url http://localhost:8080`)
also works but its URL changes on every restart. All options, including
the no-tunnel ones, are in [`../TERMUX_SERVER_GUIDE.md`](../TERMUX_SERVER_GUIDE.md).
