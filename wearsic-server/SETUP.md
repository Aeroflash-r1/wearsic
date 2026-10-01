# Wearsic Server — Source Module

This `wearsic-server/` folder is the **canonical server implementation**
(Kotlin/Ktor + NewPipeExtractor), registered in the root
`settings.gradle.kts` as the `:wearsic-server` module alongside `:app`.
Release ZIPs (`wearsic-server-termux-<version>.zip`) are built from this
source by CI — there is no prebuilt jar in the repo.

Dependency versions follow the Termux deployment that the original
compiled server used (Ktor 2.3.12, NewPipeExtractor v0.26.4,
kotlinx-coroutines 1.7.1, sqlite-jdbc 3.46.1.0), and the output shape
(`bin/` + `lib/`) is what `run-termux.sh` expects.

## Build

```bash
./gradlew :wearsic-server:build        # compile + assemble
./gradlew :wearsic-server:installDist  # produce build/install/wearsic-server/{bin,lib}
```

`run-termux.sh` already looks for
`build/install/wearsic-server/bin/wearsic-server` as its second discovery
path, so after an `installDist` the server can be started from the source
build directly:

```bash
cd wearsic-server
./run-termux.sh
```

## Database compatibility

The DDL in `Database.kt` mirrors the schema of the deployed `wearsic.db`
(favorites / playlists / playlist_tracks with `ON DELETE CASCADE` /
settings), so an existing database keeps working — `CREATE TABLE IF NOT
EXISTS` is a no-op on those tables and the `settings` table (used to
persist the YouTube cookie) is additive.

## Behaviour notes

- **Search**: YouTube Music-first (official titles/artists/durations with
  directly playable videoIds); the NewPipeExtractor YouTube search runs
  only as fallback when YTM is unreachable. Top results' streams are
  pre-resolved in the background so taps play instantly.
- **Stream resolution**: iOS-spoofed client first, default client as
  fallback — and a client that yields nothing playable also falls through
  (the two clients parse stream tables differently). `setFetchIosClient`
  is process-global, so extractions are serialized behind a mutex.
- **Audio profile**: AAC-LC ~128 kbps preferred (hardware-decoded on the
  watch's SoC); rare Opus/WebM-only songs are transcoded to AAC by ffmpeg
  on the server (503 with install guidance if ffmpeg is missing).
- **HTTP transport**: NewPipeExtractor's `Downloader` and every other
  outbound call share one tuned Ktor CIO client (12 s/20 s timeouts,
  20 connections) — the default CIO client has NO timeouts.
- **Cookie handling**: read from `WEARSIC_YOUTUBE_COOKIE` on boot (env
  wins), falling back to the value persisted in SQLite, updatable at
  runtime via `POST /api/config/youtube-cookie` — persisted so it
  survives restarts. Never logged, never echoed by the API.
- **Concurrency/memory**: per-key `SingleFlight` deduplication (entries
  removed on completion), atomic `getOrPut` on bounded LRU caches with
  TTL'd stream targets, and zip verification that streams through the
  file instead of loading whole ~30 MB packages into memory.
- **Self-healing**: extraction failures are counted; a canary-confirmed
  broken engine downloads, verifies and stages the newest release, and
  the supervisor applies it on restart (see the README's
  Self-healing section). Health is checked every 60 s; update discovery
  every 6 h.
- **Errors**: Ktor StatusPages maps every failure to JSON
  (`{"error": "..."}`); malformed bodies answer 400 instead of empty 500s.
- **Auth**: `WEARSIC_API_KEY` compared with `MessageDigest.isEqual`
  (constant-time); startup warns loudly when the server is open.
- **Rate limiting**: `/api/stream` is token-bucket limited per client
  (30/min sustained, small bursts) so an open server isn't a free proxy.
- **Everything else** (routes, response shapes, `TrackDto` fields, the
  `videoId == "*"` deletes-whole-playlist behavior) matches
  `API_CONTRACT.md`, so the Android client needs zero changes.

## Verified

`./gradlew :wearsic-server:test` runs offline unit/integration tests
(YTM parsing/durations, search fallback, SingleFlight dedup, database CRUD
incl. wildcard playlist deletion, JSON contract, Ktor routes/auth/errors/
rate limit, transcoder plumbing). CI runs them on every push, and the
release pipeline boots the packaged server and asserts `/health` reports
the source version before publishing.
