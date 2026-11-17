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

### Phase 1 — Horizon Hub navigation shell
- [ ] `HorizonHubScreen` with AppScaffold → HorizontalPagerScaffold (Home/Library/Search)
  + AnimatedPage + page dots; Mix remains a secondary destination.
- [ ] Wire as start destination in `WearsicNavigation`, preserve routes/testTags.
- [ ] Rotary focus scoping between pager and per-page lists.
- [ ] Gate: compile + tests + CUJ journey tests.

### Phase 2 — Three hub pages
- [ ] Home: Live Spotlight hero, Mix/Shuffle ButtonGroup, recent art strip,
      Most Played rows, Settings EdgeButton.
- [ ] Library: adaptive Favorites/Downloads tiles, Playlists/Albums/Artists nav,
      offline shelf, EdgeButton.
- [ ] Search: capsule search + voice mic, scope ButtonGroup, mood wave chips,
      shimmer results, Load More EdgeButton.
- [ ] Gate: compile + watch visual verification.

### Phase 3 — Now Playing overhaul
- [ ] Full-bleed art + dual scrim + radial Coral/Amber aura.
- [ ] Perimeter arc progress/scrub (circle on round, rounded-rect on square),
      draw-phase only, haptic ticks, live preview + commit on release.
- [ ] Morphing transport (circle↔squircle), ≥48dp targets, adaptive art sizing.
- [ ] Lyrics pill + pull-up lyrics + actions page + rotary volume HUD preserved.
- [ ] Gate: compile + watch visual verification (all gestures).

### Phase 4 — Secondary screens & unified polish
- [ ] Solar Ember tokens across Queue / Downloads / Storage / Playlists /
      Albums / Artists / Favorites / Mix / Volume / Server / Settings.
- [ ] Status cards (Healthy/Warning/Offline), accent swatch grid (Solar Coral default).
- [ ] Gate: compile + watch spot-check.

### Phase 5 — Verification & release
- [ ] Full CI (Robolectric on x86_64), fast APK, watch visual pass.
- [ ] Unit tests updated for new nav.
- [ ] Version bump (2.0.0), docs pins, tag/release on `wearsic` only.
- [ ] Sync `wearsic-dev`.

## Log
- 2026-11-17: Blueprint read. Phase 0 implemented & gated (compile + tests green).
- 2026-11-17: Starting Phase 1 — Horizon Hub navigation shell.
