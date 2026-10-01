# Wearsic — Wear OS 6 Music App

**Wearsic** is a lightweight, high-performance, and secure music streaming application engineered specifically for **Wear OS 6** smartwatches, fully optimized for the **Samsung Galaxy Watch7 (44mm)**.

---

## 📜 Architectural Overview

Wearsic adopts a clean, modular Model-View-ViewModel (MVVM) architecture with structured data layers and service boundaries:

```
[ Wear OS Compose Screens ] (Rotary Scroll, M3 Touch Targets)
         │
         ▼
[ WearsicPlayerViewModel ] (Cancellable Coroutine Jobs, StateFlow Engine)
         │
         ▼
[ WearsicPlaybackController ] <══> [ WearsicMediaService ] (MediaSession, ExoPlayer)
         │                                   │
         ▼                                   ▼
[ WearsicDownloadManager ]          [ WearsicStreamDataSource ]
         │                             (in-memory buffering only —
         ▼                              NO persistent disk cache)
[ WearsicDownloadRepository ]              │
(Room SQLite + ONE file per track   [ ExoPlayer ] (memory window)
 under wearsic_downloads/)
         │
         ▼
[ WearsicMusicRepository ] <══> [ WearsicHttpApiClient ]
```

### 1. Presentation & Interaction (Jetpack Compose for Wear OS)
- **Rotary Scroll Input**: Uses a dedicated, zero-allocation custom `wearsicRotaryScroll()` modifier leveraging `FocusRequester` and `dispatchRawDelta` to translate physical crown and touch bezel movements directly into list movements and player seeks.
- **Watch-First Touch Targets**: Primary play/pause and transport controls meet the 48dp Wear OS guideline (`WearsicCircularIconButton`, blob pod 84dp); some secondary inline row actions are intentionally smaller (26–38dp) to keep dense lists usable.
- **Material Design 3 (Vibrant Palette)**: Deep black background (`#000000`), dark charcoal surfaces (`#1C1B1F`), and high-contrast Lavender accents (`#D0BCFF`). All colors/type live as named tokens in `ui/theme/Color.kt` — no scattered hex literals.
- **Signature purple headers**: every screen title sits on a soft lavender→violet glow with the app's gradient accent bar drawn in beneath it (the `WearsicGradient*` tokens) — one identity across all screens.
- **One motion system** (`ui/util/Motion.kt`): springs (not fixed tweens) for every press, staggered entrance animations for screens, and a shared `wearsicClickable` with haptics — all tuned to settle in under 200 ms so a weak watch SoC never reads them as lag.
- **Pixel Watch 4-style player**: blurred, hue-matched artwork backdrop, artwork-tinted control pills (palette extracted on a background thread in `ui/util/ArtworkPalette.kt`), scalloped play blob and glass pills on an arc.
- **Consistent components & states**: screens share canonical empty/loading states (`WearsicEmptyState`, `WearsicLoadingState`), glass pills, headers and song rows, so loading/empty/error moments look identical everywhere.
- **Recently Played is identity-exact**: recents rows are keyed by the stable track ID, never by title — two different recordings that share a title/artist stay separate rows and always replay their exact recording (regression-covered by `RecentPlaybackIdentityTest`).

### 2. Playback Foundation (AndroidX Media3)
- **Single ExoPlayer Instance**: Instantiated inside the lifecycle of `WearsicMediaService` (extending `MediaSessionService`).
- **Natively Integrated MediaSession**: Exposes artwork, title, artist, play/pause, duration, seeks, and navigation directly to Wear OS system tiles, surfaces, and lock screens. Includes a secure `PendingIntent` for quick back-navigation to the main watch application.
- **Audio Attributes**: Custom music profile (`C.AUDIO_CONTENT_TYPE_MUSIC` & `C.USAGE_MEDIA`) utilizing Android's native audio focus system and noisy-headset behavior (`setHandleAudioBecomingNoisy(true)`).

### 3. Persistent Settings (Jetpack DataStore)
- Backed by Jetpack `DataStore<Preferences>`.
- **Fault Tolerance**: Read flows include `.catch` blocks to gracefully fall back to safe default settings if preference files are corrupted on the filesystem.

### 4. Downloads & Local Storage (Room Database & OKHttp)
- **Room SQLite Store**: Tracks ownership per song — one row per track with a single `autoCached` flag: `true` = AUTO (evictable), `false` = MANUAL (permanent).
- **ONE physical file per track**: every unique track maps to exactly one completed file (`wearsic_downloads/<trackId>.m4a`); the Room row decides whether it is AUTO or MANUAL — never two files, never two copies of a song.
- **Isolation**: Downloading bytes are written to `.part` files and atomically renamed to the final `.m4a` only after success; a failed/cancelled download never leaves a `COMPLETED` record.
- **Storage Protection**: StatFs check verifies that at least 15MB of storage remains free before beginning any download.
- **AUTO -> MANUAL promotion is metadata-only**: pressing Download on an auto-cached song flips the flag and reuses the same file — 0 new bytes, no re-download. A Download press during an in-flight AUTO download upgrades that same job. MANUAL is never downgraded by AutoCache.
- **No persistent stream cache**: playback buffers in ExoPlayer memory only. AutoCache (45s-listening deferral, 15/50/100-song configurable cap, oldest-first eviction that never touches MANUAL) is the sole way songs land on disk.
- **Playback-safe deletion**: eviction, Clear auto-saved and individual deletes never cut the AUTO file ExoPlayer currently has open. That file's deletion is DEFERRED and persisted on the Room row (`pendingDeletion` flag, DB v4) — so the intent survives process death and a restart deterministically rediscovers it — then retried automatically once playback releases the file (track change, queue clear, or after startup once the session state is known). A playing song therefore temporarily survives cleanup but never bypasses the 15/50/100 cap forever, and a MANUAL download request racing a deletion always wins (promotion intent is recorded synchronously before any delete can run).
- **Startup reconciliation**: rows left `QUEUED`/`DOWNLOADING` by a killed process are removed (with their orphaned `.part` bytes); legacy `CANCELLED` rows are rescued to `COMPLETED` when a real file exists behind them (ownership preserved) or removed when dead — never touching valid AUTO/MANUAL downloads; legacy `wearsic_playback_cache` directories from older builds are wiped once. All idempotent.

---

## 🌐 Expected Server API Contract

This client is fully hardened to support any standard Ktor/OkHttp endpoint following the schema below.

### 1. Health Verification
- **Route**: `GET /health`
- **Response Model** (self-healing fields are optional and ignored by older clients):
```json
{
  "status": "ok",
  "version": "1.0.0",
  "serverName": "Wearsic Engine",
  "transcoderAvailable": true,
  "extraction": { "successCount": 42, "failureCount": 1, "failureRatePercent": 2, "consecutiveFailures": 0, "lastError": null },
  "canaryHealthy": null,
  "update": { "status": "idle", "latestKnownVersion": null, "lastCheckAtMillis": 0, "lastError": null, "stagedVersion": null }
}
```

### 2. Music Search
- **Route**: `GET /api/search?q={query}`
- **Response Model** (the client derives stream URLs as `{server}/api/stream/{videoId}`):
```json
{
  "results": [
    {
      "videoId": "track_1",
      "title": "Weather with You",
      "uploader": "Crowded House",
      "durationMs": 240000,
      "thumbnailUrl": "https://i.ytimg.com/vi/.../default.jpg"
    }
  ]
}
```

### 3. Media Stream
- **Route**: `GET /api/stream/{videoId}`
- **Response Stream**: Returns `audio/mp4` (YouTube AAC — the common case), `audio/webm` (Opus), or `audio/aac` (server-transcoded Opus/WebM-only songs) with support for HTTP range requests.

---

## 🔒 Security & Hardening Pass

### 1. URL Sanitation & Scheme Enforcement
- Trim and sanitize Server URLs entered by users.
- Validates that schemes must start with `http://` or `https://` via strict `URI` check to prevent local file descriptor exposure. HTTPS is the expected default configuration for all production requests.

### 2. Duplicate Request Prevention
- Throttles connection testing by locking and skipping execution if `ConnectionTestState.Testing` is active.
- Throttles search queries by canceling previous active search coroutines `searchJob?.cancel()`.
- Throttles progress reporting during downloads (every 10% or 500ms) to reduce watch CPU and UI rendering overhead.
- Ignores duplicate track download requests if a download job for that track ID is already active, and upgrades the SAME job when a MANUAL request lands on an in-flight AUTO download (no second HTTP request).

### 3. Clean Error Translation
- Translates raw networking/Media3 exceptions into short, actionable, Wear OS-friendly errors (e.g., "Server connection timed out.", "Host not resolved. Check URL or internet.", "Storage full (<15MB free)").

### 4. Lifecycle & Coroutine Leak Protection
- Releases `MediaController` and cancels the coroutine supervisor scope job inside `WearsicPlaybackController.release()` when screens or ViewModels clear.

### 5. Performance (watch smoothness)
- **Cold-start critical section**: the first frame is a trivial backdrop; the real UI composes on the next frame, so the system splash (and its ANR window) is never gated on building ViewModels, repositories and the nav graph.
- **No recomposition from position ticks**: the navigation host holds playback/download states as raw `State`s and exposes `derivedStateOf` projections that zero the ticking fields — the 1 Hz position tracker used to recompose all 12 reachable screens (~1800 no-op emissions/hour, now zero).
- **Watch-tuned image loading**: Coil's memory cache capped at 10% of the watch heap, 64 MB disk cache, and row thumbnails requested at 112 px with a `remember`ed request (no load restarts on press redraws).
- **Leaner playback buffers**: ExoPlayer buffer halved to 15 s/30 s (the largest heap allocation the player holds) — still covers a track transition plus network jitter.
- **Stream reads never hard-cut a song**: the stream data source clears OkHttp's whole-call timeout and uses a 60 s per-read inactivity timeout, so songs longer than the base client's 45 s call budget no longer stop mid-playback.

### 6. Relaunch Reliability (no more stuck splash on reopen)
- **Swiping the app away no longer tears down the media session**: playback keeps running in the background (Wear OS media notification), and the next app launch connects to a live session instantly instead of inheriting a half-released one.
- **Self-healing media service**: if the service is ever alive with a missing session, `onGetSession` rebuilds the player + session instead of returning `null` (which used to wedge every future `MediaController.connect()` and hang the splash).
- **Bounded session connect**: `buildAsync` has a 15s watchdog — a future that never resolves is released and reconnects are scheduled, so the app never waits forever; startup storage reconciliation uses the same bound.
- **Crash containment**: a transient Room/IO error in the playback-state collector is logged and skipped instead of crash-looping the app on every relaunch (covered by `RelaunchWithDataTest`).

---

## 🗺️ Completed Milestones

- [x] **Milestone 1**: Wear OS 6 UI Foundation & Styling
- [x] **Milestone 2**: AndroidX Media3 Audio Playback Engine
- [x] **Milestone 3**: Server/API Client & Persistent Datastore Settings
- [x] **Milestone 4**: Caching & Room Local SQLite Downloads Store
- [x] **Milestone 5**: Native Wear OS Media Integration, Rotary Input & Layout Optimization
- [x] **Milestone 6**: Reliability, Security & Production Hardening
- [x] **Milestone 7**: Final Release & Daily-Use Validation

---

## 📡 API Contract & Server Architecture

The Wearsic watch application is a **lightweight streaming client**. To protect the watch's battery, processor, and cellular data consumption:
- All heavy work — metadata search (YouTube Music-first), YouTube extraction via NewPipeExtractor, and on-the-fly ffmpeg transcoding — happens in the **Wearsic Ktor server** (`wearsic-server/`, the canonical source implementation).
- The watch communicates with the server via the stable HTTP API documented in [API_CONTRACT.md](./API_CONTRACT.md).
- To run the server on a spare Android phone with Termux, follow [TERMUX_SERVER_GUIDE.md](./TERMUX_SERVER_GUIDE.md).

---

## 🛠️ How to Build & Run Tests

### Compile Project
```bash
./gradlew assembleDebug
```

### Run Test Suites
```bash
./gradlew :app:testDebugUnitTest   # app Robolectric tests
./gradlew :wearsic-server:test     # server unit/integration tests
```

---

## 🤖 CI / Releases (GitHub Actions)

`.github/workflows/android.yml` runs on every push/PR:

1. **test** — app Robolectric tests **plus** `:wearsic-server:test` (a broken
   server cannot merge silently while Android tests pass).
2. **build-debug** — unsigned debug APK uploaded as a workflow artifact.
3. **release** *(tag pushes only, `v*`)* — signed release APK **and** a fresh
   source-built server ZIP (`wearsic-server-termux-<tag>.zip`) attached to a
   GitHub Release; the packaged server is booted and its `/health` version is
   verified before publishing.

One-time setup for releases — add these repository **secrets**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 my-upload-key.jks` output |
| `STORE_PASSWORD` | Keystore password |
| `KEY_PASSWORD` | Key password |

Create a keystore locally with:

```bash
keytool -genkeypair -v -keystore my-upload-key.jks -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

Then cut a release:

```bash
git tag v1.1.0 && git push origin v1.1.0
```
