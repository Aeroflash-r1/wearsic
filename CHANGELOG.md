# Changelog

All notable changes are documented here. Format roughly follows
[Keep a Changelog](https://keepachangelog.com/).

## [1.0.0] — 2026-11-23

### Added
- Wearsic 1.0.0 — first public release: a modern music streaming app for
  Wear OS with a self-hosted server bridging the watch to YouTube.
- Aurora Nocturne dark OLED theme + optional white ("light") theme in
  Settings, tuned for 40/44 mm round watch faces.
- Now Playing: centered artwork-colored play button with progress ring,
  plain aligned prev/next, edge volume bar with 10% steps, tap-to-seek
  synced lyrics.
- Home: "Made for this moment" hero, Browse pills, selectable Shuffle.
- Mix: genre-personalized "Made for you" with refresh + Show more.
- Albums: personalized "Albums for you" shelf.
- Queue: radio appends, clear keeps the current song, completed songs are
  removed automatically.
- Server: single-flight stream resolution, retries, visitor-id refresh,
  cancellation-aware extraction, optional AI re-ranker (any
  OpenAI-compatible provider, throttled) and `POST /api/ai/playlist`.
- Cloudflare Worker front door for a permanent public server URL.
- Legal & community docs: TOS, Privacy, Contributing, Security.
