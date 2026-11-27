# Wearsic Server API Contract

Version: 1.0.0 · Auth: optional `X-API-Key` when `WEARSIC_API_KEY` is set.
Health/version pins (kept live for the release bumper):

```json
{ "status": "ok", "version": "1.0.0", "engineVersion": "1.0.0" }
```

All endpoints are `application/json`. `TrackDto` shape:

```json
{ "videoId": "…", "title": "…", "uploader": "…", "durationMs": 0,
  "thumbnailUrl": "…", "albumName": "…", "uploadAgeText": "3 months ago",
  "streamUrl": "…", "mimeType": "audio/mp4" }
```

`albumName` and `uploadAgeText` are optional (null when the source does not carry them); `uploadAgeText` is raw YouTube text ("17y ago") used only by the recommendation engine's recency boost.

## Health & status

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Liveness + extraction/update stats |
| GET | `/ready` | Readiness (ffmpeg, extraction health) |
| GET | `/diag` | Verbose diagnostics |
| GET | `/` | Small landing/status page |
| GET | `/frontdoor/probe` | Cloudflare front-door probe |

## Music

| Method | Path | Purpose |
|---|---|---|
| GET | `/search?q=&limit=&offset=` | Search tracks (limit 1–25, default 15) |
| GET | `/suggestions?q=` | Live search suggestions |
| GET | `/related/{videoId}` | Related tracks |
| GET | `/search/albums?q=` | Search albums |
| GET | `/albums/resolve?trackId=` | Resolve a track's album |
| GET | `/lyrics?videoId=` | Lyrics (synced when available) |
| GET | `/playlist?url=` | Import a public playlist by URL |
| GET | `/prewarm/{videoId}` | Resolve + warm the CDN edge (lean-only) |
| GET | `/stream/{videoId}` | Audio stream (partial content supported) |

### POST `/api/recommendations` — "Made for you"

Request:

```json
{
  "seedTrackIds": ["…"],
  "topArtists": ["…"],
  "excludeTrackIds": ["…"],
  "limit": 25,
  "refreshNonce": 0,
  "genres": ["chill"]
}
```

- `limit` 0 → engine default 25; positive values clamp to 100.
- `refreshNonce` increments on manual refresh; the server rotates the seed
  pool so each refresh returns different picks.
- `genres` are optional taste hints (chill/focus/workout/party/jazz/
  throwback/sad/love/happy/…).
- Response: `{ "results": [TrackDto…] }`.
- Optional AI re-rank: if `WEARSIC_AI_API_KEY` is configured the candidate
  pool is re-ordered (never expanded) by the provider. Failures fall back
  to the classic ranking.

### POST `/api/ai/playlist` — AI-curated playlist

Request: `{ "name": "…", "genres": [], "seedTrackIds": [], "topArtists": [], "limit": 15 }`.
Response: `{ "name": "…", "tracks": [TrackDto…], "aiUsed": true|false }`.

Creates and persists the playlist. Works without an AI key (classic picks).

## Favorites & playlists

| Method | Path | Purpose |
|---|---|---|
| GET | `/favorites` | List favorites |
| POST | `/favorites` | Add favorite (body: TrackDto) |
| DELETE | `/favorites/{videoId}` | Remove favorite |
| GET | `/playlists` | List playlists |
| POST | `/playlists` | Create playlist `{ "name": "…" }` |
| GET | `/playlists/{id}` | Playlist tracks |
| POST | `/playlists/{id}/tracks` | Add track |
| DELETE | `/playlists/{id}/tracks/{videoId}` | Remove track |

### POST `/api/mood`

Request: `{ "mood": "chill", "seedTrackIds": [], "excludeTrackIds": [] }`.
Response: `{ "tracks": [TrackDto…] }`.

## YouTube cookie (bot-challenge workaround)

- GET `/config/youtube-cookie` → `{ "configured": true|false }`
- POST `/config/youtube-cookie` body `{ "cookie": "…" }` (persisted;
  requires auth)

## Rate limiting

Read endpoints share a metadata bucket (120/min, burst 40). Streams and
prewarm use separate buckets. Over-limit returns `503`; retry after a
backoff.

## Errors

```json
{ "error": "human-readable message" }
```

HTTP 4xx for bad input/auth, 404 for missing resources, 502/503 for
upstream failures.
