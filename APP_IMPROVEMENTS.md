# Wearsic App — Improvement Pass

This file records the app-quality pass (UI, correctness and feature work) done
on top of v1.9.15, plus a roadmap of larger improvements that are intentionally
left for a separate, focused effort.

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
- **Player — the disc now spins with the song.** One slow revolution per
  track, read from the same animated progress as the ring in the layer phase
  (zero extra recompositions), and it freezes when paused.
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

## Roadmap (follow-up work — not in this pass)

- **Type-safe navigation** (move route strings to a typed route graph).
- **Baseline profiles / startup profiling** — measure, then add a Baseline
  Profile module for faster cold start on the watch SoC.
- **Offline mode surfaced** — when the server is unreachable, switch the
  library UI to show downloaded content first instead of failing online loads.
- **Queue reorder** (move-to-top / move-to-bottom) via the queue row action
  sheet.
- **Dynamic colour option** — offer "theme from artwork" as a user setting
  (the player already extracts artwork colours; extending to the whole app
  should be opt-in for battery).
- **Accessibility audit** — full TalkBack pass across every screen and custom
  component.
- **Replace hardcoded sp sizes with typography tokens** where screens still
  use literals, so future theme changes stay consistent.
