// Wearsic Design Tokens — Figma plugin (JavaScript).
//
// Commands (run from Plugins → Wearsic Design Tokens):
//   1. Install color styles   — creates/updates paint styles from the app palette
//   2. Install text styles    — creates/updates text styles from the app type ramp
//   3. Build starter screens  — creates a 44mm round frame with the Home and
//                                Now Playing skeletons using the installed styles
//
// The token values below mirror the Android theme files:
//   tokens/colors.json, tokens/typography.json, tokens/dimensions.json

// ---------------------------------------------------------------------------
// Tokens (single source for the plugin)
// ---------------------------------------------------------------------------
const COLOR_TOKENS = {
  "Wearsic/Background": "000000",
  "Wearsic/Surface/Low": "141218",
  "Wearsic/Surface": "1C1B1F",
  "Wearsic/Surface/Raised": "2A2831",
  "Wearsic/Surface/Active": "35323A",
  "Wearsic/Outline": "49454F",
  "Wearsic/OnSurface": "E6E0E9",
  "Wearsic/OnSurfaceVariant": "CAC4D0",
  "Wearsic/Text/Muted": "938F99",
  "Wearsic/Primary": "D0BCFF",
  "Wearsic/OnPrimary": "381E72",
  "Wearsic/PrimaryContainer": "4F378B",
  "Wearsic/OnPrimaryContainer": "EADDFF",
  "Wearsic/Secondary": "CCC2DC",
  "Wearsic/OnSecondary": "332D41",
  "Wearsic/SecondaryContainer": "4A4458",
  "Wearsic/OnSecondaryContainer": "E8DEF8",
  "Wearsic/Tertiary": "EFB8C8",
  "Wearsic/OnTertiary": "4A2532",
  "Wearsic/TertiaryContainer": "633B48",
  "Wearsic/OnTertiaryContainer": "FFD8E4",
  "Wearsic/Error": "F2B8C5",
  "Wearsic/OnError": "601410",
  "Wearsic/ErrorContainer": "8C1D18",
  "Wearsic/OnErrorContainer": "F9DEDC",
  "Wearsic/Success": "88D9B8",
  "Wearsic/Warning": "F2C879",
  "Wearsic/Accent/Sky": "9ACBFF",
  "Wearsic/Accent/Mint": "88D9B8",
  "Wearsic/Accent/Peach": "F2B98E",
  "Wearsic/Accent/Rose": "FFB0BE",
  "Wearsic/Accent/Violet": "C4B0FF",
  "Wearsic/Accent/Amber": "F2C879"
};

const TEXT_TOKENS = {
  "Wearsic/Display/Large": { size: 28, line: 34, weight: 700 },
  "Wearsic/Display/Medium": { size: 24, line: 30, weight: 700 },
  "Wearsic/Title/Large": { size: 20, line: 26, weight: 600 },
  "Wearsic/Title/Medium": { size: 16, line: 22, weight: 600 },
  "Wearsic/Title/Small": { size: 14, line: 20, weight: 500 },
  "Wearsic/Body/Large": { size: 14, line: 20, weight: 400 },
  "Wearsic/Body/Medium": { size: 12, line: 16, weight: 400 },
  "Wearsic/Label/Large": { size: 14, line: 20, weight: 600 },
  "Wearsic/Label/Medium": { size: 12, line: 16, weight: 500 },
  "Wearsic/Label/Small": { size: 11, line: 16, weight: 500 }
};

const VIEWPORT = 240; // 44mm round watch ≈ 240dp

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
function hexToRgb(hex) {
  const h = hex.replace("#", "");
  return {
    r: parseInt(h.substring(0, 2), 16) / 255,
    g: parseInt(h.substring(2, 4), 16) / 255,
    b: parseInt(h.substring(4, 6), 16) / 255
  };
}

function weightName(weight) {
  if (weight >= 700) return "Bold";
  if (weight >= 600) return "SemiBold";
  if (weight >= 500) return "Medium";
  return "Regular";
}

function findPaintStyle(name) {
  return figma.getLocalPaintStyles().find((s) => s.name === name) || null;
}

function findTextStyle(name) {
  return figma.getLocalTextStyles().find((s) => s.name === name) || null;
}

function solidPaint(hex) {
  const c = hexToRgb(hex);
  return [{ type: "SOLID", color: { r: c.r, g: c.g, b: c.b } }];
}

// ---------------------------------------------------------------------------
// Command 1 — color styles
// ---------------------------------------------------------------------------
function installColorStyles() {
  let created = 0;
  let updated = 0;
  for (const name of Object.keys(COLOR_TOKENS)) {
    const hex = COLOR_TOKENS[name];
    const existing = findPaintStyle(name);
    if (existing) {
      existing.paints = solidPaint(hex);
      updated++;
    } else {
      const style = figma.createPaintStyle();
      style.name = name;
      style.paints = solidPaint(hex);
      created++;
    }
  }
  figma.notify(`Color styles: ${created} created, ${updated} updated.`);
}

// ---------------------------------------------------------------------------
// Command 2 — text styles
// ---------------------------------------------------------------------------
function installTextStyles() {
  let created = 0;
  let updated = 0;
  for (const name of Object.keys(TEXT_TOKENS)) {
    const t = TEXT_TOKENS[name];
    const existing = findTextStyle(name);
    const style = existing || figma.createTextStyle();
    style.name = name;
    style.fontSize = t.size;
    style.lineHeight = { value: t.line, unit: "PIXELS" };
    style.fontName = { family: "Roboto", style: weightName(t.weight) };
    if (existing) updated++;
    else created++;
  }
  figma.notify(`Text styles: ${created} created, ${updated} updated.`);
}

// ---------------------------------------------------------------------------
// Command 3 — starter screens
// ---------------------------------------------------------------------------
function makeSolidNode(name, x, y, width, height, hex, radius) {
  const node = figma.createRectangle();
  node.name = name;
  node.x = x;
  node.y = y;
  node.resize(width, height);
  node.fills = solidPaint(hex);
  node.cornerRadius = radius || 0;
  return node;
}

function makeText(name, x, y, content, styleName, width) {
  const node = figma.createText();
  node.name = name;
  node.x = x;
  node.y = y;
  node.resize(width || 200, 24);
  node.characters = content;
  node.textAutoResize = "HEIGHT";
  const style = findTextStyle(styleName);
  if (style) node.textStyleId = style.id;
  return node;
}

function buildStarterScreens() {
  const target = figma.currentPage;
  target.name = "Wearsic · Starter";

  // --- Home frame ---
  const home = figma.createFrame();
  home.name = "Home (44mm)";
  home.resize(VIEWPORT, VIEWPORT);
  home.x = 0;
  home.y = 0;
  home.fills = solidPaint(COLOR_TOKENS["Wearsic/Background"]);
  home.clipsContent = true;

  makeText("Title", 20, 16, "Library", "Wearsic/Title/Large", 200);

  const hero = makeSolidNode("Now Playing hero", 16, 52, 208, 64, COLOR_TOKENS["Wearsic/Surface/Raised"], 20);
  home.appendChild(hero);
  makeText("Hero title", 30, 76, "Your next favorite", "Wearsic/Label/Large", 150);

  const tileGap = 8;
  const tileW = (VIEWPORT - 32 - tileGap) / 2;
  const tileH = 72;
  const tileY = 140;
  const tiles = [
    { label: "Downloads", hex: COLOR_TOKENS["Wearsic/Accent/Mint"] },
    { label: "Favorites", hex: COLOR_TOKENS["Wearsic/Accent/Rose"] },
    { label: "Playlists", hex: COLOR_TOKENS["Wearsic/Accent/Sky"] },
    { label: "Albums", hex: COLOR_TOKENS["Wearsic/Accent/Violet"] }
  ];
  tiles.forEach((t, i) => {
    const col = i % 2;
    const row = Math.floor(i / 2);
    const tile = makeSolidNode(t.label, 16 + col * (tileW + tileGap), tileY + row * (tileH + tileGap), tileW, tileH, t.hex, 20);
    home.appendChild(tile);
  });

  // --- Now Playing frame ---
  const np = figma.createFrame();
  np.name = "Now Playing (44mm)";
  np.resize(VIEWPORT, VIEWPORT);
  np.x = 280;
  np.y = 0;
  np.fills = solidPaint(COLOR_TOKENS["Wearsic/PrimaryContainer"]);
  np.clipsContent = true;

  const disc = makeSolidNode("Artwork disc", 82, 48, 76, 76, COLOR_TOKENS["Wearsic/Surface/Raised"], 38);
  np.appendChild(disc);
  makeText("Track title", 40, 140, "Weather with You", "Wearsic/Title/Medium", 160);
  makeText("Artist · time", 40, 168, "Crowded House · 1:04 / 3:40", "Wearsic/Body/Medium", 160);
  makeText("Play", 104, 200, "▶  ⏸", "Wearsic/Display/Medium", 32);

  figma.notify("Starter screens created on page “Wearsic · Starter”.");
}

// ---------------------------------------------------------------------------
// Command dispatch
// ---------------------------------------------------------------------------
switch (figma.command) {
  case "install-colors":
    installColorStyles();
    break;
  case "install-text":
    installTextStyles();
    break;
  case "starter-screens":
    buildStarterScreens();
    break;
  default:
    figma.notify("Choose a command from the plugin menu.");
}

figma.closePlugin();
