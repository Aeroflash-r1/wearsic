# Wearsic Server

The self-hosted backend for the Wearsic watch app. A small Ktor
(HTTP/2) service that bridges the watch to YouTube — search, metadata,
streaming, favorites, playlists, lyrics and recommendations.

- License: GPL-3.0-only (see `LICENSE`)

## Run

Build and run from source:

```bash
./gradlew :wearsic-server:installDist
./run-termux.sh          # auto-heal supervisor (Termux)
```

Or run the dist directly:

```bash
build/install/wearsic-server/bin/wearsic-server
```

Default port `8080` (override `PORT`). See
[TERMUX_SERVER_GUIDE.md](../TERMUX_SERVER_GUIDE.md) for phone setup.

## Configuration (env)

| Variable | Meaning | Default |
|---|---|---|
| `PORT` | Listen port | 8080 |
| `WEARSIC_HOST` | Bind host | 0.0.0.0 |
| `WEARSIC_DB_PATH` | SQLite path | `wearsic.db` |
| `WEARSIC_STATE_DIR` | Update/state dir | `<db dir>/wearsic-state` |
| `WEARSIC_API_KEY` | Require this key on API calls | unset (open) |
| `WEARSIC_REQUIRE_KEY` | Fail startup if no API key | unset |
| `WEARSIC_YOUTUBE_COOKIE` | YouTube session cookie | unset |
| `WEARSIC_AUTO_UPDATE` | Auto-update engine | 1 |
| `WEARSIC_AI_API_KEY` | OpenAI-compatible key (optional) | unset |
| `WEARSIC_AI_BASE_URL` | AI provider base URL | `https://api.openai.com/v1` |
| `WEARSIC_AI_MODEL` | AI model | `gpt-4o-mini` |

## Audio pipeline

- `/stream/{videoId}` resolves a playable URL (lean InnerTube clients
  first, then NewPipeExtractor fallback), then proxies it to the watch.
- Streams are relayed, not stored. Downloads happen on the watch.
- ffmpeg (if present) transcodes Opus/WebM-only tracks.

## Reliability notes

- **Single-flight** stream resolution keyed by `(videoId, allowExtraction)`
  so a background prewarm never 404s a foreground tap.
- `NOT_PLAYABLE` markers are short-lived (1 min) and never served from
  cache hits.
- YTMusic/InnerTube POSTs retry with exponential backoff + jitter.
- The visitor ID is refreshed on `LOGIN_REQUIRED` (not just TTL).
- The optional AI re-ranker is bounded (4 s), cached (15 min), and never
  expands the candidate pool.

## Tests

```bash
./gradlew :wearsic-server:test
```

## API

See [API_CONTRACT.md](../API_CONTRACT.md).
