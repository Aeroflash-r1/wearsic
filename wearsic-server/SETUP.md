# Wearsic Server — Source Module

This `wearsic-server/` folder is the **canonical server implementation**
(Kotlin/Ktor + NewPipeExtractor), registered in the root
`settings.gradle.kts` as the `:wearsic-server` module alongside `:app`.
Release ZIPs (`wearsic-server-termux-<version>.zip`) are built from this
source by CI — there is no prebuilt jar in the repo.

Dependency versions follow the Termux deployment that the original
compiled server used (Ktor 2.3.12, kotlinx-coroutines 1.7.1,
sqlite-jdbc 3.46.1.0); NewPipeExtractor (v0.26.4) is the one deliberately
movable pin — bumped by the Extractor Hotfix workflow (see below). The
output shape (`bin/` + `lib/`) is what `run-termux.sh` expects.

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
  on the server (503 with the exact `pkg install ffmpeg` guidance if it is
  missing — availability is re-probed while the server runs, so a late
  install heals without a restart).
- **HTTP transport**: NewPipeExtractor's `Downloader` and every other
  outbound call share one tuned Ktor CIO client (12 s/20 s timeouts,
  20 connections) — the default CIO client has NO timeouts.
- **Compression & read-path caching**: JSON responses are gzipped
  (content-type allow-list, 512-byte floor — audio is never recompressed);
  GET read paths carry `Cache-Control: private, max-age=300`
  (search/suggestions/related/playlist) or `private, max-age=5`
  (favorites/playlists), 2xx only — see `API_CONTRACT.md`.
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
  every 6 h; a missing ffmpeg is likewise re-probed while running (30 s
  throttle) — installing it later needs no restart.
- **Errors**: Ktor StatusPages maps every failure to JSON
  (`{"error": "..."}`); malformed bodies answer 400 instead of empty 500s.
- **Auth**: `WEARSIC_API_KEY` compared with `MessageDigest.isEqual`
  (constant-time); startup warns loudly when the server is open.
- **Rate limiting**: `/api/stream` is token-bucket limited per client
  (30/min sustained, small bursts) so an open server isn't a free proxy.
- **Everything else** (routes, response shapes, `TrackDto` fields, the
  `videoId == "*"` deletes-whole-playlist behavior) matches
  `API_CONTRACT.md`, so the Android client needs zero changes.

## Extractor hotfix (when YouTube breaks)

YouTube changing its player is the one failure that recurs; the fix is
almost always a newer NewPipeExtractor — and that is one button press:
**Actions → Extractor Hotfix → Run workflow**
(`.github/workflows/extractor-hotfix.yml`; it must exist on `main` for
GitHub to show the button).

In order, that press:

1. resolves the target ref — the newest upstream release, or an exact one
   typed in (`v0.26.5`, a commit, `master-SNAPSHOT` when upstream merged
   the fix but hasn't released it),
2. rewrites the pin + versions through `scripts/bump-version.py` — an
   all-or-nothing, pattern-exact rewrite of the server pin, `ServerVersion`,
   the app `versionCode`/`versionName`, this file's pin token and the other
   living doc samples,
3. runs the gates: `scripts/tests/bump-version-test.sh`, the full server
   unit suite, and the live dual-path smoke (`LiveExtractorSmokeTest`,
   `WEARSIC_LIVE_SMOKE=1`) that extracts "Me at the zoo" through both
   NewPipeExtractor and the production resolver and pulls real bytes from
   each CDN URL — nothing is pushed when any gate fails,
4. commits, tags `vX.Y.Z`, pushes and dispatches Android CI on the tag,
   which publishes the release every installed server auto-installs.

`dry_run` runs every gate and pushes nothing; `emergency_override` ships
despite a failing live smoke (CI datacenter IPs can hit YouTube bot-walls);
`mode=branch` releases the current tip as-is for fixes to our own resolver
code. The offline suite `scripts/tests/bump-version-test.sh` runs on every
push in CI, so pattern drift fails a normal build — not a release.

## Verified

`./gradlew :wearsic-server:test` runs offline unit/integration tests
(YTM parsing/durations, search fallback, SingleFlight dedup, database CRUD
incl. wildcard playlist deletion, JSON contract, Ktor routes/auth/errors/
rate limit, transcoder plumbing). CI runs them on every push, and the
release pipeline boots the packaged server and asserts `/health` reports
the source version before publishing.
