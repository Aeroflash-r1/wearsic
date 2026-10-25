# Wearsic Server

Standalone Ktor + NewPipe Extractor backend for the Wearsic Wear OS app. This project is intentionally separate from the Android app and can run on an old Android phone through Termux.

## Requirements

- Java 17
- Termux packages: `pkg install openjdk-17 unzip curl`
- `ffmpeg` (the launcher installs it in the background on first start; needed only to transcode rare Opus/WebM-only songs — the running server re-probes for a missing binary, so a late install heals without a restart)

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

`wearsic start|stop|restart|status|logs|health|ready|url|ip|api-key|sideload|pair|update|version|doctor|port|funnel|cloudflare|public|cookies`
manages everything (`wearsic help` lists all commands). The default server
port is `8080`; change it with `wearsic port 9000` (1024–65535, saved to
`.env`, applied on the next `wearsic restart`) — the front door, `url`,
`pair`, `status` and `doctor` all follow the configured port. Re-running
`install` upgrades in place and keeps `wearsic.db` + `.env`.
`./wearsic-server-v*.sh uninstall` removes it.

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

`run-termux.sh` uses G1GC and a 512 MB heap by default. Override `JAVA_OPTS` when the phone has more or less memory. If `ffmpeg` is missing, the launcher starts `pkg install -y ffmpeg` **in the background** (AAC songs play immediately; a failure is loud in the log with the exact fix command). The running server also re-probes for a missing binary — 30 s throttle, single-flight — so conversion turns on with no restart the moment `ffmpeg` appears.

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

The server defends itself against the three failure classes that actually kill music servers:

1. **Runtime rot** (expired CDN URLs, one dead video, stalled sockets): ranged proxying, dead-URL re-resolution (403/404/410 → fresh extraction → retry once), iOS→default InnerTube client fallback, per-request timeouts, and the supervisor's crash/hang restart loop.
2. **Engine rot** (YouTube changes their site and breaks NewPipeExtractor): every extraction is counted. After 6 consecutive failures the server probes a canary video ("Me at the zoo" — effectively permanent). If the canary also fails, the engine is declared broken:
   - with `WEARSIC_AUTO_UPDATE` on (default): a newer GitHub release is downloaded, **SHA-256-verified against the release's published `.zip.sha256`**, integrity-checked (central-directory walk — truncated downloads rejected), strictly extracted (zip-slip, duplicate and unexpected-file protection), staged into `wearsic-state/staging/`, and the process exits so `run-termux.sh` swaps it in transactionally on restart.
   - with it off: a loud log line tells you exactly what to do instead.
3. **Tooling drift** (a boot probe that failed while the phone was busy, or `ffmpeg` installed after the server started): availability is re-probed on demand while the server runs — one probe per 30 s, single-flighted across parallel requests — and flips conversion ON the moment the binary appears, no restart needed. The 503 for a transcode-needing song names the exact install command.

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

### Cutting an extractor hotfix (maintainers)

The recurring emergency — YouTube breaks extraction and upstream
NewPipeExtractor ships (or merges) a fix — is one button press:
**Actions → Extractor Hotfix → Run workflow**
(`.github/workflows/extractor-hotfix.yml`; the file must exist on `main`
for GitHub to show the button). One press:

1. resolves the target ref — the newest upstream release, or an exact one
   typed in (`v0.26.5`, a commit, `master-SNAPSHOT` when the fix is merged
   upstream but unreleased),
2. bumps the pin + version through `scripts/bump-version.py` — an
   all-or-nothing rewrite of the pin, `ServerVersion`, the app
   `versionCode`/`versionName` and this repo's living doc samples
   (`wearsic-server/SETUP.md` included), keyed to exact patterns,
3. runs the gates — `scripts/tests/bump-version-test.sh` (offline test of
   the bump), the full server unit suite, and `LiveExtractorSmokeTest`
   (`WEARSIC_LIVE_SMOKE=1`): a live dual-path smoke that extracts "Me at
   the zoo" through both NewPipeExtractor and the production resolver and
   pulls real bytes from each CDN URL. Nothing is pushed when a gate
   fails,
4. commits, tags `vX.Y.Z`, pushes and dispatches Android CI on the tag,
   which publishes the release every installed server auto-installs.

`dry_run` proves the whole pipeline without shipping anything;
`emergency_override` ships despite a failing live smoke (CI datacenter IPs
can hit YouTube bot-walls) — recorded as a warning; set the optional
`YT_COOKIE` repo secret (the same cookie string as `wearsic cookies`) to
make the smoke cookie-backed instead; `mode=branch` releases the current
tip as-is for fixes to our own resolver code.

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

## Response compression & read-path caching

Since v1.9.1 the server shapes its responses for the watch's disk cache and
for relayed (Funnel) links:

- **gzip for JSON** via a content-type allow-list with a 512-byte floor.
  Audio is never recompressed — streams stay byte-exact for Range/seek.
- **`Cache-Control` on GET read paths**, only on 2xx responses so a transient
  404 is never cached:

  | Paths | Header |
  |---|---|
  | `/api/search`, `/api/search/albums`, `/api/suggestions`, `/api/related/*`, `/api/playlist` | `private, max-age=300` |
  | `/api/favorites`, `/api/playlists`, `/api/playlists/*` | `private, max-age=5` |

  Streams, config, `/health`, `/ready` and errors send no cache headers.

The watch client pairs this with a 6 s `Cache-Control: no-cache` burst after
favorites/playlist mutations — longer than the 5 s user-state window — so a
post-edit read can never be served the pre-mutation list.

## Errors and rate limiting

Every error is JSON: `{"error": "<message>"}` with a meaningful status — `400` invalid request/malformed body, `401` missing or wrong API key, `404` unknown route/video, `502` upstream CDN failure, `503` rate-limited or missing ffmpeg for transcode-needing songs, `500` unexpected (message is generic; details go to the log).

`GET /api/stream/{id}` is rate-limited per client (API key, else IP): sustained 30 requests/minute with short bursts above that allowed. This protects an open server from becoming a free YouTube proxy; normal playback (a few streams per minute) is unaffected.

Stream extraction is resilient:

- The **iOS Innertube client is tried first** (it is the one that actually works against YouTube's bot wall), falling back to the default client. Because the client choice is a process-global NewPipeExtractor setting, all extractions are serialized behind a mutex — correctness over concurrency, at no practical cost for a personal server.
- NewPipe failures map to clean JSON errors instead of empty 500 responses: `404` when a video is unavailable, `503` for bot/ReCaptcha challenges (with a hint to configure the YouTube cookie), and `502` for other extraction failures.

## The terminal (shimmer + stages + dashboard)

The CLI is a small bash program that renders itself. Three pieces carry the
whole experience:

- **The shimmer** — a soft highlight band travels left → right across the
  active line. Every character is blended between the base gray `#8A8A85`
  and the highlight gray `#C9C9C3` by its distance from the band, so the
  effect is a wave of light rather than a blinking highlight. One pass takes
  ~1.7s and every frame is redrawn in place every ~90ms. Truecolor in
  Termux; a 256-colour gray ramp (base **245**, highlight 253) elsewhere;
  `NO_COLOR`, a non-TTY stdout and `TERM=dumb` turn it off. The cursor is
  hidden only while the wave is on screen and is always restored — normal
  exit, error, or Ctrl+C.
- **The stage machine** — the installer walks **Download → Install →
  Configure → Build → Start → Verify → Ready**. The active stage shimmers;
  a finished stage collapses into one quiet line, printed exactly once.
  Sub-process output is captured and only shown when a step fails.
- **Smooth shimmer** — the wave is tuned for a smooth glide: 22 frames per
  left→right pass over ~2.0s with a wider 40% band (softer edges), instead
  of a coarser 19-frame/30% band that could read as stepping/blinking.
- **The dashboard** — `wearsic dashboard` renders health, uptime, the LAN
  address, the Cloudflare front door and tunnel state, the API/stream health
  and the current engine activity, and refreshes the frame in place.

The shimmer core lives in [`lib/wearsic-shimmer.sh`](lib/wearsic-shimmer.sh)
and is embedded byte-identically in `wearsic` and in the repository's
`install.sh` (the installer is piped straight from GitHub and cannot source
the CLI). `bash lib/sync-shimmer.sh` re-inserts it into both, and
`tests/shimmer-test.sh` fails when the two copies drift apart.

```bash
wearsic setup                 # configure, start, verify, show the watch values
wearsic setup --update        # …and update the engine first
wearsic setup --cloudflare    # …and set up the permanent public URL
wearsic dashboard             # live view (Ctrl+C to exit)
wearsic dashboard --once      # one frame (for scripts and tests)
```

## Public access (HTTPS)

The watch app already sends `X-Wearsic-Key` automatically once the key
is entered in **Settings → API Key** — nothing to extend. For a public
HTTPS URL, the stable option is Tailscale Funnel from Termux:

```bash
wearsic funnel     # prints https://<phone-name>.<tailnet>.ts.net
```

Public exposure REQUIRES an API key: `wearsic funnel` and
`wearsic public` refuse to run without one (and `funnel` also refuses
to expose a server that is not running and healthy). Set your own with
`wearsic api-key <your-key>`, or let `wearsic cloudflare` create one only
when none exists; it never replaces an existing key.

The permanent no-domain option is `wearsic cloudflare`. A Quick Tunnel's
random `trycloudflare.com` hostname changes on restart, so the CLI deploys a
reviewed Cloudflare Worker in front of it at
`https://wearsic.<subdomain>.workers.dev`. The Worker streams responses and
preserves Range/206, `Content-Range`, the API key, multiple `Set-Cookie`
headers, and same-origin redirects; it never buffers a track, and it passes
the engine's `Cache-Control` (private max-age) through on JSON discovery
endpoints so the watch's disk cache still works over the permanent URL.

Setup is one command:

```bash
wearsic cloudflare
```

It starts the engine if it is stopped, creates a 48-character API key only
when no key is saved, authorizes Cloudflare, installs `cloudflared`, deploys
and verifies the Worker, then prints a single card with the permanent URL and
API key for **Wearsic → Settings**. Existing keys, `.env`, server data and
the database are preserved.

**Native Termux note**: the GitHub cloudflared build is glibc and cannot run
on Termux's bionic libc — the CLI now installs the Termux package
(`pkg install cloudflared`) automatically and uses it for the tunnel. When a
tunnel process dies immediately, the CLI reports the real cloudflared error
line (e.g. DNS/CA bind or token rejection) instead of silently minting new
tunnels and burning the quick-tunnel rate limit. To override the binary used,
set `CLOUDFLARED_PATH`.

For an explicit pre-authorization, `wearsic cloudflare login` opens a
wrangler-compatible OAuth flow in the browser and needs NO wrangler/npm
install: the default is the device link flow (open the link, approve, done)
and `--browser` gives the PKCE flow which also works on networks that
bot-challenge the device endpoint on some mobile ISPs. The session is stored
in `.wearsic-state/cloudflare-auth.json` (mode 600) and its refresh token
re-mints the short-lived access token automatically, so a login never goes
stale. Fallbacks: `--wrangler` (official wrangler session, if installed) or
`--token-stdin` for a scoped token (Workers Scripts:Edit + Account
Settings:Read, never the Global API Key) saved to `.env` with mode 600. An
existing `CLOUDFLARE_API_TOKEN` in the environment/.env or a wrangler session
also works and takes precedence until it is rejected. The account id and
`workers.dev` subdomain are detected automatically; the subdomain is created
only when the account has none. To use your own API key, run
`wearsic api-key <your-key>` first; setup keeps it.

`wearsic cloudflare status` reports live health and auth through the permanent
URL; `verify` proves auth plus Range/206 streaming with
`/api/frontdoor/probe` (not just `/health`); `logs` redacts secrets; `url`
prints it; and `stop` leaves the Worker and URL deployed. A real-track probe is
opt-in with `WEARSIC_CF_PLAYBACK_ID`. Deployment retries use bounded
exponential backoff with jitter for network/`429`/`5xx`; permanent `4xx`
errors stop the supervisor. A restarted tunnel is detected and the Worker is
re-pointed automatically. All options, including the no-tunnel ones, are in
[`../TERMUX_SERVER_GUIDE.md`](../TERMUX_SERVER_GUIDE.md).

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
