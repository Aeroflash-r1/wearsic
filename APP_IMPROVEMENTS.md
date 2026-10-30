# Wearsic App — Improvement Pass

This file records the app-quality pass (UI, correctness and feature work) done
on top of v1.9.15, plus a roadmap of larger improvements that are intentionally
left for a separate, focused effort. App + server CLI changes are current as
of v1.9.39.

## Done in this pass

### Correctness / bug fixes
- **Playlists screen — nested clickable removed.** `PlaylistRow` wrapped
  `WearsicSongRow` in a second `combinedClickable`, which competed with the
  row's internal clickable (double-tap/long-press ambiguity). Long-press now
  flows through the row's own `onLongClick` (single source of interaction).
- **Downloads — individual delete now confirms.** Deleting a completed
  download removes the only offline copy; it now gets an explicit confirm
  dialog (same visual language as Clear All) instead of deleting instantly.
- **Queue — stable row identity.** The queue now renders as one list keyed
  by absolute queue index instead of re-keying the "up next" slice on every
  track advance; rows keep their state and don't re-animate when playback
  advances.

### UI polish
- **Downloads — live progress ring.** In-progress download cards now show a
  circular progress ring drawn in the draw phase (no recomposition per %).
- **Library rows — "saved offline" indicator.** Favorites / playlist / artist
  rows show a small sage dot when the track has a completed local file, so
  offline availability is visible at a glance. Powered by a single derived
  set in the navigation host (no per-row flow subscriptions).
- **Consistent haptics.** Row action buttons now use the same light
  `TextHandleMove` tick as every other tappable control (they previously used
  the heavier long-press tick).
- **Player — page dots moved to the screen edge.** The Now Playing / Actions
  page indicator now hugs the outer curved edge (outside the scaffold insets)
  instead of floating mid-screen.
- **Player — the disc spins with the song (optional).** One slow revolution
  per track, read from the same animated progress as the ring in the layer
  phase (zero extra recompositions), freezing when paused. New Settings →
  Appearance → "Rotating Artwork" toggle (default on) lets users turn the
  motion off.
- **Player — hue-matched "aurora" glow.** A soft radial tint from the artwork
  accent sits behind the disc so the scrim reads as depth instead of a flat
  black void.
- **Screen identity medallions.** Every screen header now carries a small
  radial-tinted icon medallion in its section accent (Library, Search,
  Downloads, Queue, Playlists, Favorites, Artists, Albums, Storage, Engine,
  Settings, Sound), so screens read as distinct places rather than identical
  lists with a coloured bar.

### New features
- **Download all (album / playlist).** Playlist/album detail now has a
  "Download all / Download remaining" pill. Idempotent: already-saved tracks
  are skipped, and the count reflects only what is missing. Wiring:
  `DownloadsViewModel.startDownloadBatch(...)`.
- **Queue reorder.** Every Up Next row now has a ⋯ action (and long-press)
  opening a menu: Move to top / Move to bottom / Remove. Backed by
  `WearsicPlaybackController.moveQueueItem(...)` (Media3 `moveMediaItem`),
  which keeps the current track playing and recomputes the current index.
  Manual edits clear the retained pre-shuffle order so shuffle-off still
  restores the original list.
- **Offline surface.** The library tracks server reachability
  (`LibraryViewModel.serverReachable`, driven by network-shaped failures of
  actual requests). When the server is unreachable, the home screen shows an
  "Offline — showing saved music" banner and a Saved-offline section with the
  completed downloads, playable directly — the app stays useful with no
  network.
- **Dynamic accent (opt-in).** New Settings → Appearance → "Dynamic Accent"
  toggle (default off). When on, the app-wide accent follows the current
  track's artwork hue — the player hoists its extracted palette via
  `LocalWearsicAccent`, which the primary/secondary buttons, play buttons,
  chips, toggles, search cursor/borders, download ring and dashboard now read
  instead of the fixed lavender. Falls back to lavender when there is no
  artwork.
- **Sleep-timer / crossfade rows no longer clip at the edge.** Both option
  rows (five chips each) overflow the ~240dp round viewport; the last chip
  was cut off at the curved edge. Both rows are now horizontally scrollable
  (`LazyRow`), so every option stays reachable.
- **Play all / Shuffle all.** Favorites gets "Play all" + "Shuffle" pills;
  Downloads gets "Play all" (the whole offline library). Shuffle uses the
  new `WearsicPlayerViewModel.playTracksShuffled(...)`.
- **Retry pills.** Search and Albums error states now offer an inline Retry
  pill that re-runs the same query.
- **Server profiles (multi-server switching).** Settings → Server Profiles:
  save the current server as a named profile, tap any profile to switch URL +
  API key instantly (e.g. Home-LAN ↔ Cloudflare front door), delete with
  confirmation. Persisted as JSON in DataStore; applying reuses the existing
  validated writers so the HTTP client mirrors the key immediately.
- **High-standard Wear OS design pass.** Library no longer reads as a menu:
  it is now a content-forward home (hero → search → recently played →
  "Browse" 2×2 destination grid with count badges → artists/settings row).
  New `WearsicActionTile` component (gradient icon medallion + label + count
  badge) replaces stacked full-width pills across the home, Favorites
  (play-all/shuffle-all) and Downloads (play-all). Albums get larger 96dp
  artwork; artists get gradient medallions; playlist/album detail headers no
  longer show the track count; Downloads header shows total offline size.

### Server / CLI (v1.9.18)
- **`wearsic port [n]`** — show or set the server port (1024–65535, saved to
  `.env`, applied on restart); supported as `wearsic port` and
  `wearsic server port`. The engine, supervisor, `url`, `pair`, `status` and
  the Cloudflare/funnel front doors all follow it.
- **Cloudflare on native Termux** — the CLI now installs and uses the Termux
  `cloudflared` package (bionic-compatible) instead of the glibc GitHub build
  that died seconds after launch; `CLOUDFLARED_PATH` still overrides.
- **Tunnel early-death diagnostic** — a tunnel that exits immediately now
  reports the real cloudflared error line and backs off instead of silently
  re-minting tunnels and burning the quick-tunnel rate limit.
- **Worker cache-control pass-through** — the front-door Worker preserves the
  engine's `Cache-Control` on JSON discovery endpoints (search/playlists/
  favorites), restoring the watch's disk-cache speed over the permanent URL.
- **Shimmer smoothness** — 22 frames per pass (~2.0s) with a 40% band, so
  the installer/CLI wave reads as a smooth glide rather than a stepping/
  blinking highlight (constants remain within the shimmer test suite).

## Roadmap (follow-up work — not in this pass)

- **Type-safe navigation** (move route strings to a typed route graph).
- **Baseline profiles / startup profiling** — measure, then add a Baseline
  Profile module for faster cold start on the watch SoC.
- **Accessibility audit** — full TalkBack pass across every screen and custom
  component.
- **Replace hardcoded sp sizes with typography tokens** where screens still
  use literals, so future theme changes stay consistent.
