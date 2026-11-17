# Wearsic 2.0 — Solar Ember Overhaul: Development Tracker

Source blueprint: `/sdcard/Download/wearsic ui overhaul plan.md` (read in full 2026-11-17).

This file tracks execution of the blueprint. Each phase has a compile + test gate;
watch verification happens when the watch is charged & connected.

## Guiding constraints (from blueprint + prior session decisions)
- Pure `#000000` OLED canvas. Neon Coral `#FF6B57` primary, Warm Amber `#FFB347`
  secondary, obsidian surfaces `#120F10` / `#1B1617` / `#282020`.
- Dynamic artwork accent becomes an **optional** Settings override (NOT default-on).
- Fonts: Outfit display, Plus Jakarta Sans body, JetBrains Mono tabular (all bundled).
- Adaptive: round/square + size tiers (Compact <192, Standard 192–224, Large ≥225dp).
- Preserve all ViewModel/repository/DB/API contracts, testTags, and deep-link routes.
- Zero-recomposition animation work in draw/layer phases; battery-first.
- Tag/release on `wearsic` only, never `wearsic-dev`.

## Phases

### Phase 0 — Foundation: design tokens + adaptive spec  [DONE ✅]
- [x] Blueprint read in full; codebase & API availability assessed
- [x] Color.kt: Solar Ember palette + semantic statuses + accent catalog
- [x] Type.kt: Outfit display font (Plus Jakarta body, JetBrains Mono tabular)
- [x] Theme.kt: default accent = Coral; dynamic optional; scheme derives from accent
- [x] Prefs: dynamic accent default back to OFF (opt-in override)
- [x] Navigation fallback accent: Coral
- [x] New `WearAdaptiveSpec.kt`: isScreenRound + size tier + chord-safe insets via CompositionLocal
- [x] New `WearsicMotion.kt`: spring specs (button squish 0.94f, pager, haptics)
- [x] Gate: compile + app/server unit tests green

### Phase 1 — Horizon Hub navigation shell  [DONE ✅]
- [x] `WearsicHubScreen` now hosts HorizontalPagerScaffold → HorizontalPager (3 pages)
      + AnimatedPage; page indicator handled by the scaffold. Mix stays a pushed route.
- [x] Home/Library/Search reused as the three pages (no duplicated UI); shortcut taps
      animate to a page instead of pushing a route; all deep-link routes preserved.
- [x] Rotary page scrolling disabled so crown scrolls the active page's list.
- [x] `onPlayTrackWithRelated` wired so hub search results keep the related queue.
- [x] Gate: compile + local unit tests green (Robolectric CUJs run on CI x86_64).
- NOTE: migrating the three page lists from ScalingLazyColumn → TransformingLazyColumn
  is folded into Phase 2 (alongside the page UI redesign).

### Phase 2 — Three hub pages  [IN PROGRESS — solar-ember styling applied]
- [x] Home: Solar Ember capsule search, amber Mix / tonal Library shortcuts,
      coral "Shuffle favorites" primary CTA, Settings affordance; existing
      hero/art shelves/Most Played preserved.
- [x] Library: Downloads tile now amber + saved-count badge (mono), Search pill
      coral, Favorites/Shuffle + offline shelf preserved.
- [x] Search: idle Mood Wave Chips row added (wired to real mood mixes); existing
      capsule search + voice mic + tri-mode + shimmer + Load More preserved.
- [ ] Remaining: TransformingLazyColumn + SurfaceTransformation migration,
      richer expressive hero tiles, bezel EdgeButtons. (Deferred to a follow-up
      pass — the existing ScalingLazyColumn scrolling/gestures are verified.)
- [ ] Gate: compile + unit tests green; watch visual verification pending charge.

### Phase 3 — Now Playing overhaul  [PARTIAL — player was already modern]
- [x] Full-bleed art backdrop + artwork aura already present (ArtworkBackdrop).
- [x] Expressive transport: play button now morphs circle↔squircle via shared
      spring (layer-only). Skip targets ≥48dp already.
- [x] Pull-up lyrics + actions page + rotary volume HUD preserved untouched.
- [ ] Perimeter bezel arc progress/scrub (round/square) — DEFERRED: the existing
      disc ring works and is verified; a bezel arc cannot be visually verified
      while the watch is offline/low-battery. Revisit with live hardware.
- [ ] Lyrics hint pill — not added; the quiet icon was a deliberate prior fix.
- [ ] Gate: compile + unit tests green; watch visual verification pending.

### Phase 4 — Secondary screens & unified polish  [DONE via tokens]
- [x] All secondary screens inherit Solar Ember automatically (shared theme
      scheme / typography / surfaces) — no per-screen hardcoding of old palette.
- [x] Server dashboard already uses semantic tokens: Healthy=emerald,
      Warning=amber, Offline=rose (matches blueprint).
- [x] Settings swatch grid uses the updated WearsicAppColorChoices (Solar Coral
      signature first); default accent = Solar Coral.
- [ ] Gate: watch spot-check pending (watch offline / low battery).

### Phase 5 — Verification & release
- [ ] Full CI (Robolectric on x86_64), fast APK, watch visual pass.
- [ ] Unit tests updated for new nav.
- [ ] Version bump (2.0.0), docs pins, tag/release on `wearsic` only.
- [ ] Sync `wearsic-dev`.

## Log
- 2026-11-17: Blueprint read. Phase 0 implemented & gated (compile + tests green).
- 2026-11-17: Starting Phase 1 — Horizon Hub navigation shell.
- 2026-11-17: Phases 0–3 (partial) + 4 implemented; full CI green (4/4 jobs) at
  commit 5ece808. APK at /tmp/opencode/solar/Wearsic-v1.9.50.apk.
- 2026-11-17: Watch offline / battery was 6% — all visual verification blocked.
- 2026-11-17: LIVE feedback pass (battery 50%):
  - Play/pause button reverted to ALWAYS circular (user rejected squircle morph).
  - Removed in-app mic from Search (keyboard already has one).
  - Home Settings pill inset to stop bezel clipping.
  - Server profile rows: outer padding (no bezel clip), bold name + mono-bold URL;
    fixed port bug — HTTPS public hosts (Workers/Vercel/…) never get a stray
    ":port" on apply/save; port dialog shows "Standard (HTTPS)" instead of 8080.
  - LIBRARY PAGE REMOVED from the pager (now 2 pages: Home/Search). Its functions
    (Downloads/Playlists/Albums/Artists/Favorites/offline shelf) folded into
    Home's new Browse section. Robolectric CUJ tests updated for new testTags.
- 2026-11-17: Full CI green (4/4) at 35f877b after test updates for the
  2-page pager. APK at /tmp/opencode/solar2/Wearsic-v1.9.50.apk.
- NOTE: In Robolectric the Home Browse tile click did not reach Downloads
  (likely pager/layer touch quirk in the test harness) — must verify on hardware.
- NEXT: watch port → install APK → verify 2-page pager, Home Browse tiles,
  circular play button, no mic, clean server profiles → then perimeter arc
  (deferred), version bump 2.0.0 + release (tag wearsic only), sync wearsic-dev.
