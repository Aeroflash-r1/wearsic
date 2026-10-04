# Wearsic — Server API Contract (v1, as implemented)

This document describes the REST API that the Wearsic Wear OS client actually
speaks. It matches the source-built Ktor + NewPipe Extractor server
(`wearsic-server/src/`, canonical implementation; release ZIPs are generated
from it by CI) and the client parser in
`app/src/main/java/com/example/network/WearsicHttpApiClient.kt`.

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
  "version": "1.6.3",
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
reports whether ffmpeg was found for Opus/WebM→AAC transcoding;
`extraction`/`canaryHealthy`/`update` expose the self-healing engine
state (see `wearsic-server/README.md`).

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
  "engineVersion": "1.6.3"
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
[ { "id": "<playlist-url>", "name": "...", "uploader": "...", "trackCount": 10, "thumbnailUrl": "..." } ]
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
sustained 30 requests/minute with short bursts above that. Exceeding it yields
`503` with `{"error":"Too many stream requests; slow down and retry shortly"}`.
Search/suggestions/favorites/playlists are NOT rate-limited.
