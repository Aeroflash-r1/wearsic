# Wearsic — Wear OS music app

**Wearsic** is a lightweight music-streaming app for Wear OS smartwatches
(optimized for round faces, 40–44 mm). Audio is streamed from YouTube
through a **self-hosted server** that you run on a phone or any Linux
machine — no cloud service, no subscription.

- App: Apache-2.0 (see [LICENSE](LICENSE))
- Server: GPL-3.0-only (see [wearsic-server/LICENSE](wearsic-server/LICENSE))

## Quick start

**1. Run the server** (Termux on your phone, or any Linux box):

```bash
curl -fsSL https://github.com/Aeroflash-r1/wearsic/releases/download/v1.0.0/wearsic-server-v1.0.0.sh -o wearsic-server.sh
chmod +x wearsic-server.sh && ./wearsic-server.sh install
wearsic start
wearsic url   # shows the LAN address + API key (open server? the key is blank)
```

Health sample (version pins are kept live for the release bumper):

```json
{
  "status": "ok",
  "version": "1.0.0",
}
```

See [TERMUX_SERVER_GUIDE.md](TERMUX_SERVER_GUIDE.md) for the full setup,
pairing, port and Cloudflare-funnel instructions.

**2. Install the watch app** — grab `Wearsic-v1.0.0.apk` from the
[latest release](https://github.com/Aeroflash-r1/wearsic/releases/latest)
and sideload it (`wearsic sideload v1.0.0`, or `adb install -r`).

**3. Pair** — open the app → Settings → Server URL + API key (or push the
> The API key is **optional**: `wearsic setup` leaves the server open on your
> LAN (no auto-created key). Set one any time with `wearsic api-key <key>`.
pairing file with `wearsic pair`). Done — search and play.

## Features

- **Home** — "Made for this moment" hero that becomes the Now Playing card
  while a track is loaded; Browse pills (Downloads, Playlists, Albums,
  Artists); selectable Shuffle favorites
- **Mix** — taste-ranked "Made for you" with refresh and "Show more"
  (paginated via `limit`; a manual refresh rotates picks so you see
  different songs)
- **Albums** — search plus a personalized "Albums for you" shelf
- **Queue** — drag-to-reorder, clear keeps the current song, completed
  songs are removed automatically, radio appends instead of replacing
- **Now Playing** — centered play button with a progress ring, plain
  prev/next, a vertical volume bar from the bottom edge, synced lyrics
- **Design** — "Aurora Nocturne" dark OLED theme with an optional **white**
  theme in Settings; " Plus Jakarta Sans / Outfit / JetBrains Mono
- **Offline** — downloaded tracks play with no network

## Architecture

```
Wear OS app (Compose, Media3, Room, DataStore)
        │  /api/* (search, stream, favorites, playlists, lyrics…)
        ▼
Self-hosted server (Ktor, NewPipeExtractor, SQLite)
        │
        ▼
YouTube / YouTube Music
```

- **App** (`app/`): Jetpack Compose, Wear Compose Material3, Media3/ExoPlayer
- **Server** (`wearsic-server/`): Ktor 2.x, NewPipeExtractor, SQLite JDBC
- Optional **AI re-ranker**: any OpenAI-compatible provider can re-rank
  recommendations (see server README; disabled by default)

## Build & test

```bash
./gradlew :app:assembleDebug          # app APK
./gradlew :wearsic-server:installDist # server distribution
./gradlew :app:testDebugUnitTest :wearsic-server:test
```

## Releasing (tag)

```bash
git tag v1.0.0 && git push origin v1.0.0
```

Installable server zip: `WEARSIC_ZIP_URL=https://github.com/Aeroflash-r1/wearsic/releases/download/v1.0.0/wearsic-server-termux-v1.0.0.zip`
(see the release assets; auto-update tools rely on these pins).
> Note: watch pairing was added in v1.0.0.

## Documentation

- [API contract](API_CONTRACT.md)
- [Server setup (Termux)](TERMUX_SERVER_GUIDE.md)
- [Server README](wearsic-server/README.md)
- [Terms of Service](TERMS_OF_SERVICE.md) · [Privacy](PRIVACY_POLICY.md)
- [Contributing](CONTRIBUTING.md) · [Code of Conduct](CODE_OF_CONDUCT.md) · [Security](SECURITY.md)

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for version history.

## License

The app is Apache-2.0; the server is GPL-3.0-only. Third-party licenses are
noted in [NOTICE](NOTICE).
