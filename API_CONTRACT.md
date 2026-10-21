# Wearsic — Server API Contract (v1, as implemented)

This document describes the REST API that the Wearsic Wear OS client actually
speaks. It matches the source-built Ktor + NewPipe Extractor server
(`wearsic-server/src/`, canonical implementation; release ZIPs are generated
from it by CI) and the client parser in
`app/src/main/java/com/wearsic/app/network/WearsicHttpApiClient.kt`.

> Note: earlier revisions of this file described a `/api/v1/...` namespace with
> `tracks[]` arrays. That contract was never implemented; the real surface is
> below. Do not "fix" the client to match old docs — update the docs instead.

---

## Design Philosophy

1. **Lightweight watch client** — extraction, metadata resolving, artwork and
   transcoding all happen server-side.
2. **No versioned namespace (yet)** — endpoints live under `/api/...`. Any
   breaking change should introduce `/api/v2/...` alongside.
3. **Personal use** — optional shared-secret auth; expected on a trusted
   network or tunnel.
4. **Streaming formats** — MP3/AAC/M4A/WebM(Opus) audio with HTTP Range
   request support.

---

## Authentication

- Set `WEARSIC_API_KEY` in the server environment to require a key.
- The watch sends it as an HTTP header on every `/api/*` request:

```
X-Wearsic-Key: <key>
```

- `/health` stays public. Empty/unset env var = open server.

---

## Endpoints

### 1. Health

- **GET** `/health`
- **Response** `200 OK` (self-healing fields are optional — older clients ignore them):

```json
{
  "status": "ok",
  "version": "1.9.7",
  "serverName": "Wearsic Engine",
  "transcoderAvailable": true,
  "extraction": { "successCount": 42, "failureCount": 1, "failureRatePercent": 2, "consecutiveFailures": 0, "lastError": null },
  "canaryHealthy": null,
  "update": { "status": "idle", "latestKnownVersion": null, "lastCheckAtMillis": 0, "lastError": null, "stagedVersion": null }
}
```

Client tolerates missing fields (`status` defaults to `"ok"` when HTTP 200).
`version` comes from `ServerVersion.VERSION` (single source of truth shared
with the Gradle build/JAR name); `transcoderAvailable` is informational and
reports the **live** ffmpeg status for Opus/WebM→AAC transcoding — the
server re-probes a missing binary while running (30 s throttle,
single-flight), so installing ffmpeg later flips it true without a restart;
`extraction`/`canaryHealthy`/`update` expose the self-healing engine
state: a broken extraction is detected via the canary and healed by
auto-installing a checksum-verified newer release when one exists (see
`wearsic-server/README.md`).

### 1-b. Readiness

- **GET** `/ready` — "can Wearsic serve music right now?" The deeper
  counterpart to `/health` (used by `wearsic doctor` and monitoring).
  Deliberately cheap (one indexed DB probe, in-memory counters) and
  exposes no secrets.
- **Response** `200 OK`:

```json
{
  "ready": true,
  "database": true,
  "extractor": true,
  "transcoder": true,
  "engineVersion": "1.9.7"
}
```

`ready` is `database && extractor`; `transcoder` is informational (ffmpeg
presence — missing only affects rare Opus/WebM-only songs). On engines
older than v1.4.0 the route does not exist (404) — treat that as "unknown",
not "not ready".

### 2. Music Search

- **GET** `/api/search?q=<query>`
- **Response** `200 OK`:

```json
{
  "results": [
    {
      "videoId": "3DsD83sJ",
      "title": "Weather with You",
      "uploader": "Crowded House",
      "durationMs": 240000,
      "thumbnailUrl": "https://i.ytimg.com/vi/.../default.jpg"
    }
  ]
}
```

- Max ~10 results (server-side limit).
- The client derives the stream URL itself:
  `{server}/api/stream/{videoId}`.
- Thumbnails are typically `w60-h60`; the client rewrites ytimg/googleusercontent
  URLs to `w544-h544` for crisp artwork.

### 2-b. Prewarm (resolve ahead of playback)

- **GET** `/api/prewarm/{videoId}`
- **Response** `200 OK`: `{ "ready": true }` — the target is resolved and
  cached; the playback request that follows starts without waiting for
  extraction. `404` when the id cannot be matched to a playable source.
- **No audio bytes are transferred.** The server also warms its own CDN
  connection for the track (one ranged byte upstream), never the watch's
  link.
- The watch calls this on tap and for the next queue item. It replaces the
  old `GET /api/stream/{id}` probe with `Range: bytes=0-0`: the proxy runs
  tiny-window requests to their natural EOF, so that probe relayed an entire
  song over a high-latency link (Tailscale Funnel) and competed with the real
  playback request.
- Not cached — each call must actually resolve. Rate-limited separately from
  streams (90/min per client) so warm-ups can never spend the playback
  budget.

### 3. Audio Stream

- **GET** `/api/stream/{videoId}`
- Headers: standard `Range: bytes=start-end` supported (forwarded upstream).
- Response: `200 OK` full body or `206 Partial Content`.
- Content-Type mirrors the upstream audio: `audio/mp4` (YouTube AAC-LC ~128 kbps — the common case, hardware-decoded on the watch), `audio/webm` (Opus), or `audio/aac` when the server transcoded an Opus/WebM-only song with ffmpeg (503 with install guidance if ffmpeg is missing).

### 4. Suggestions

- **GET** `/api/suggestions?q=<prefix>`
- **Response**: `{ "suggestions": ["...", "..."] }` (max ~5).

### 5. Related / Radio

- **GET** `/api/related/{videoId}`
- **Response**: `{ "results": [TrackDto...] }` (max ~10).
- Client filters out items >10 min (full-album mixes).

### 6. Albums

- **GET** `/api/search/albums?q=<query>`
- **Response**: array of album objects:

```json
[ { "id": "<playlist-url>", "name": "...", "uploader": "...", "thumbnailUrl": "..." } ]
```

Note: album `id` is a full playlist URL, not a bare id.

### 7. Playlist by URL

- **GET** `/api/playlist?url=<playlist-or-album-url>`
- **Response**: `{ "id": "...", "name": "...", "tracks": [TrackDto...] }`
- Max ~50 tracks (albums frequently run 15-30 songs; the server serves a
  single YouTube playlist page, so the whole album fits in one response).

### 8. Favorites

- **GET** `/api/favorites` → array of TrackDto.
- **POST** `/api/favorites` with TrackDto JSON body (see below).
- **DELETE** `/api/favorites/{videoId}`.

### 9. Playlists

- **GET** `/api/playlists` → array: `[ { "id", "name", "trackCount", "thumbnailUrl" } ]`
- **POST** `/api/playlists` with `{ "name": "..." }` → PlaylistDto.
- **GET** `/api/playlists/{id}` → `{ "id", "name", "tracks": [TrackDto...] }`
- **POST** `/api/playlists/{id}/tracks` with TrackDto JSON body.
- **DELETE** `/api/playlists/{id}/tracks/{videoId}`
  - Special case: `videoId == "*"` deletes the entire playlist (FK cascade).
    This is how the app's "remove playlist" works. Implemented directly in
    `Database.deletePlaylistTrack`.

### 10. YouTube Cookie Config (bot-challenge workaround)

- **GET** `/api/config/youtube-cookie` → `{ "hasCookie": true|false }`
  — presence only; the cookie value is **never** returned or logged.
- **POST** `/api/config/youtube-cookie` with `{ "cookie": "SID=...; HSID=..." }`
  → `{ "hasCookie": true|false }`. Persists the cookie (`settings` table);
  empty string clears it. Same endpoint the `wearsic cookies` CLI uses.

---

## The Cloudflare front door (a permanent public URL)

This is not an endpoint — it is a *deployment* shape for the whole API above.

- **GET** `https://<worker>.<subdomain>.workers.dev/<any endpoint>`
- The Worker is a transparent proxy: method, path, query, request headers and
  body are forwarded to the current tunnel origin, and the response (status,
  headers and body) is streamed straight back.

### Why it exists

A Cloudflare Quick Tunnel (`cloudflared tunnel --url http://localhost:PORT`)
is the fastest and cheapest public path — the watch reaches Cloudflare's edge
directly, with no Tailscale DERP relay in between. But its hostname is
**random** and changes on every restart, so a watch app cannot remember it.

A Cloudflare Worker in front of the tunnel fixes that: the Worker has a
**permanent** `workers.dev` address, and the CLI re-uploads it (with a new
`ORIGIN` binding) whenever the tunnel hands out a new hostname. The watch only
ever knows the permanent address and is never re-paired.

### Verification probe

`GET /api/frontdoor/probe` is an authenticated, deterministic self-test used
by `wearsic cloudflare verify`. It needs no YouTube extraction and returns no
user data or secrets.

- Through the API auth intercept, a request without the key gets `401`.
- Without `Range`, it returns `200`, `Content-Type:
  application/octet-stream`, `Accept-Ranges: bytes`, `Content-Length: 65536`,
  and a 64 KiB body where byte `i` (zero-based) is `(i * 31 + 7) & 0xFF`.
- `Range: bytes=10-19` returns `206`, `Content-Range: bytes 10-19/65536`,
  and exactly the 10 matching bytes (byte 10 is `61`, byte 19 is `84`).
- An open-ended range (`Range: bytes=65000-`) is clamped to the payload and
  returns the remaining bytes. A range end past the payload is clamped too.
- A malformed, multi-range, or out-of-bounds request returns `416` with
  `Content-Range: bytes */65536`.
- The response must not be cached. It is suitable for checking that auth,
  `Range`, `206`, `Content-Range` and streaming all survive the Worker.

`wearsic cloudflare verify` performs these checks through the permanent URL.
It never treats `/health` alone as proof of playback. To additionally fetch a
real track through the front door, opt in with
`WEARSIC_CF_PLAYBACK_ID=<videoId>`; this requires a working extractor and is
reported separately from the deterministic probe.

### Contract requirements on the Worker

The proxy MUST preserve these, or audio breaks:

- **`Range`** request headers and `Content-Range`/`206` responses. The engine
  answers byte-range audio; dropping `Range` turns every seek into a full
  re-download. `Content-Length` and `Accept-Ranges` are preserved too.
- **Streaming** responses, including bodies without a known length. Buffering
  a whole track in the Worker would add seconds of latency and exceed the
  memory limit on long songs.
- The **`X-Wearsic-Key`** (or whatever the engine's auth header is) request
  header, unchanged. The Worker never stores or invents the secret.
- **Multiple `Set-Cookie`** response headers and other end-to-end headers.
  They must not be folded into one value.
- Bodyless statuses (`204`, `205`, `304`) must carry no body. `HEAD` remains
  bodyless.
- **Redirects** are not followed by the Worker. A same-origin `Location` is
  rewritten to the permanent hostname; an external `Location` is passed
  through unchanged. This prevents the caller from being sent to the random
  tunnel hostname.
- The real client IP is visible as `CF-Connecting-IP`; the Worker forwards it
  in `X-Forwarded-For` along with `X-Forwarded-Proto` and
  `X-Forwarded-Host`.
- **No caching** of any response — a cached track would be served forever and
  the tunnel hostname would stop mattering. The Worker sets `Cache-Control:
  no-store`.
- Hop-by-hop headers (`Connection`, `Transfer-Encoding`, `Host`, …) are
  stripped in both directions: they describe the single hop they arrived on.
- An origin/network failure returns an opaque `502` with a request id; a
  missing/invalid `ORIGIN` binding returns an opaque `500`. Bodies never echo
  the tunnel hostname, request URL or a stack trace.

The implementation is `wearsic-server/wearsic-cloudflare-worker.js` and is
uploaded verbatim — nothing is generated at runtime that cannot be read first.
The contract is covered by `tests/worker-proxy-test.mjs`; the engine probe is
covered by `FrontDoorProbeTest`; the CLI/supervisor behavior is covered by
`tests/cloudflare-tunnel-test.sh` and `tests/cloudflare-e2e-test.sh`.

### Operations

- `wearsic cloudflare [start]` — the one-command setup. It starts the engine
  if stopped, creates a 48-character hex API key only when none is saved
  (an existing key is never replaced), offers authorization, installs
  `cloudflared`, detects the account/subdomain, deploys the Worker, starts the
  Quick Tunnel, waits for a real `live` state, runs the `verify` checks, and
  prints one card with the permanent URL and API key. `--no-verify` skips only
  that final probe.
- `wearsic cloudflare login [--wrangler|--token-stdin]` — optional
  pre-authorization. `--wrangler` uses wrangler's browser/device OAuth;
  `--token-stdin` prompts for a scoped token with hidden input and saves it to
  `.env` (mode 600). In auto mode, an existing wrangler session is preferred
  when available; if npm is present but wrangler is not, the CLI offers a
  one-time wrangler install, then falls back to the hidden prompt.
- `wearsic cloudflare logout` — remove the saved API token. It does not delete
  the deployed Worker or its URL; revoke the token in the dashboard too.
- `wearsic cloudflare status` — the permanent URL, supervisor state, and live
  health plus auth checks *through* the URL. It reports the recorded error
  rather than claiming success when the supervisor is degraded/stopped.
- `wearsic cloudflare verify` — prove auth + `Range`/`206`/`Content-Range`
  streaming through the permanent URL using `/api/frontdoor/probe`.
- `wearsic cloudflare logs [-f]` — recent supervisor activity, with secrets
  redacted. `wearsic cloudflare url` prints just the permanent URL.
- `wearsic cloudflare stop` — stop the tunnel and supervisor. The Worker and
  permanent URL stay deployed; a later `start` reuses them.

Credential resolution is: `CLOUDFLARE_API_TOKEN` from the environment/`.env`,
then the official wrangler OAuth session, then `wearsic cloudflare login`.
`CLOUDFLARE_ACCOUNT_ID` is optional: it is auto-detected via `/accounts` or
`wrangler whoami --json` and persisted. An existing `workers.dev` subdomain is
reused; one is created only when the account has none. The public address
still REQUIRES a `WEARSIC_API_KEY`. Setup creates one only when none exists,
restarts the engine so the key takes effect, and never replaces a saved key.

Deployment retries are bounded exponential backoff with jitter and apply only
to network failures, `429`, and `5xx`. A permanent `4xx` (for example a bad
token or missing Workers permission) stops the supervisor and records
`last_error`; it is never retried forever. The state file tracks the tunnel
`origin` separately from the last successfully deployed `deployed_origin`, so
a failed re-point cannot masquerade as success. The supervisor truncates the
per-tunnel log before each start, waits for the new origin's `/health`, restarts
a dead tunnel, re-points the Worker when the hostname changes, and periodically
re-deploys if the permanent URL fails its health check. A single-instance lock
keeps a second supervisor from starting.

## Terminal presentation (CLI)

The server CLI renders itself; the rules below keep it predictable for
scripts, logs and small screens.

- **The shimmer.** While work is in progress, the active line carries a soft
  highlight band that travels left → right. Every character is blended
  between the base gray `#8A8A85` (138,138,133) and the highlight gray
  `#C9C9C3` (201,201,195) by its distance from the band. One pass takes
  ~1.7s; every frame is redrawn in place every ~90ms with a carriage return
  and a line erase. Truecolor on Termux; on a 256-colour terminal the blend
  is approximated with the gray ramp (base **245**, highlight 253).
- **When it is off.** `NO_COLOR=1`, `WEARSIC_COLOR=never`, a non-TTY stdout
  and `TERM=dumb` all disable it; piped output stays plain and one line per
  message. `WEARSIC_COLOR_DEPTH=24|256` forces a depth.
- **The cursor** is hidden only while the wave is on screen and is restored
  on every exit path: normal exit, an error, and Ctrl+C.
- **The stage machine.** Long operations run as stages — the installer uses
  Download → Install → Configure → Build → Start → Verify → Ready. The
  active stage shimmers; a finished stage collapses into ONE quiet line,
  printed exactly once. Sub-process output is captured internally and is
  only surfaced when a step fails, so a failure is never hidden and success
  is never claimed early.
- **Narrow screens.** The live line is truncated to the terminal width
  (never wrapped) and the panels fall back to stacked rows.

Commands:

- `wearsic setup [--update] [--cloudflare]` — the one-command path for an
  existing install: create the API key when there is none (never replace an
  existing one), start the engine when it is stopped, verify health **and**
  the API key, then show the watch values together. `--update` updates the
  engine first, `--cloudflare` sets up the permanent public URL afterwards.
- `wearsic dashboard [--once]` — a live, in-place view of the running
  server: health, uptime, the LAN address, the Cloudflare front door and
  tunnel state, the API/stream health and the current engine activity.
  `--once` renders a single frame (for scripts and tests); a non-TTY
  stdout always renders one frame.

## Response Compression & Caching (server ≥ v1.9.1)
- **gzip**: JSON responses ≥ 512 bytes are gzip-compressed
  (`Content-Encoding: gzip`) when the client sends `Accept-Encoding`. The
  compressor is allow-listed to `application/json` — audio bytes are never
  recompressed, so Range reads stay byte-exact.
- **Read-path `Cache-Control`** — GET only, and only on 2xx responses (a
  transient 404 is never cached):

  | Paths | Header |
  |---|---|
  | `/api/search`, `/api/search/albums`, `/api/suggestions`, `/api/related/*`, `/api/playlist` | `private, max-age=300` |
  | `/api/favorites`, `/api/playlists`, `/api/playlists/*` | `private, max-age=5` |

  Streams, `/api/config/*`, `/health`, `/ready` and errors carry no cache
  headers (non-cacheable to HTTP caches).
- **Reference client (watch app ≥ 1.9.1)**: a 16 MB OkHttp disk cache serves
  repeat screens; after any favorites/playlist mutation the app sends
  `Cache-Control: no-cache` for 6 s — longer than the 5 s user-state
  window — so a read right after an edit can never return the pre-mutation
  list.

---

## TrackDto Object

Used in search results, favorites, playlists bodies:

| Field | Type | Required | Notes |
| :--- | :--- | :--- | :--- |
| `videoId` | String | Yes | Unique id; also used to build stream URL client-side. |
| `title` | String | Yes | |
| `uploader` | String | Yes | Displayed as artist. |
| `durationMs` | Long | No | Milliseconds; `0` if unknown. |
| `thumbnailUrl` | String | No | Small thumbnail OK; client upgrades resolution. |

The client POSTs exactly these five fields as JSON.

## Compatibility Rules

1. The client parses responses defensively with kotlinx.serialization
   (`ignoreUnknownKeys = true`, `coerceInputValues = true`, missing fields
   defaulted), so servers may append new fields freely.
2. Never rename or remove `videoId` or the `/api/stream/{videoId}` shape within
   this generation of clients; introduce `/api/v2/...` for breaking changes.
3. Artwork: prefer serving pre-downscaled images (~150px) to protect watch
   bandwidth; the client will upscale ytimg URLs itself when needed.

## Error Format

All non-2xx responses carry a JSON body: `{"error": "<human-readable message>"}`.
Statuses: `400` invalid request or malformed body, `401` bad/missing API key,
`404` unknown route or unmatchable video, `502` upstream CDN failure,
`503` rate-limited (or ffmpeg missing for transcode-needing songs),
`500` unexpected — message is generic, details only in the server log.

## Rate Limiting

`GET /api/stream/{id}` is limited per client (API key, else source IP) to a
sustained 30 requests/minute with a burst of 12. Exceeding it yields `503`
with `{"error":"Too many stream requests; slow down and retry shortly"}`.
`GET /api/prewarm/{id}` has its own bucket (90/min, burst 30) so warm-ups can
never consume the playback budget.
Search/suggestions/favorites/playlists are NOT rate-limited.
