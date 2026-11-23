# Wearsic Design Tokens — Figma plugin

Design the Wearsic UI in Figma with **exactly the tokens the Android app
uses**. Install the styles once, design on a 44mm round frame, and every
color/type choice you make maps 1:1 to the Kotlin theme.

## Install (30 seconds)

1. Open **Figma Desktop** (the plugin API needs the desktop app).
2. Menu → **Plugins → Development → New plugin…**
3. Choose **“Link existing plugin”** and select this folder
   (`figma-plugin/`, the one containing `manifest.json`).
4. The plugin now appears under **Plugins → Wearsic Design Tokens**.

## Commands

| Command | What it does |
|---|---|
| **1 · Install Wearsic color styles** | Creates/updates Figma paint styles for the whole app palette (background, surfaces, primary/secondary/tertiary roles, accents, status colors) — names match the Kotlin tokens. |
| **2 · Install Wearsic text styles** | Creates/updates Figma text styles for the full type ramp (Display/Title/Body/Label). |
| **3 · Build starter screens** | Creates a new page with a 44mm **Home** and **Now Playing** frame using the installed styles — your starting canvas. |

## Designing on the watch

- **Frame size**: 240 × 240 px (= 44mm round viewport). Keep interactive
  controls inside the middle ~180px vertical band; the curved edges clip.
- **Touch targets**: at least 44px.
- **Use styles, not raw hex**: pick the installed styles so a future token
  change (we can push new tokens after a design review) re-skins your art.

## Token source of truth

| Figma style prefix | Android file |
|---|---|
| `Wearsic/...` colors | `app/src/main/java/com/wearsic/app/ui/theme/Color.kt` |
| `Wearsic/...` text | `app/src/main/java/com/wearsic/app/ui/theme/Type.kt` |
| Dimensions (JSON) | `app/src/main/java/com/wearsic/app/ui/theme/WearsicDimens.kt` |

Raw copies live in `tokens/colors.json`, `tokens/typography.json`,
`tokens/dimensions.json`.

## Syncing design → code

When a design is approved:

1. **Colors** → update `Color.kt` hex values (same names).
2. **Typography** → update `Type.kt` sizes/weights.
3. **Layout/component changes** → describe on the design (or chat us the
   names), and we implement the Compose changes in the app.

The plugin keeps the *style names* stable, so swapping values later never
breaks your Figma artboards.
