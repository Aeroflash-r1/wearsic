#!/data/data/com.termux/files/usr/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# Wearsic server — one-shot Termux installer.
#
# Fresh Termux, one line:
#   pkg install -y curl && \
#     curl -fsSL https://raw.githubusercontent.com/Aeroflash-r1/wearsic/main/wearsic-server/install.sh | bash
#
# It installs the required packages, downloads the newest server release,
# preserves any existing database / API key, wires up auto-start on reboot
# (Termux:Boot) and launches the self-healing supervisor.
#
# Optional environment overrides:
#   WEARSIC_REPO      GitHub owner/repo        (default Aeroflash-r1/wearsic)
#   WEARSIC_DIR       install directory        (default ~/wearsic-server)
#   WEARSIC_API_KEY   set an API key without prompting
#   WEARSIC_NO_START  "1" to install but not start
#   WEARSIC_NO_BOOT   "1" to skip Termux:Boot autostart
# ─────────────────────────────────────────────────────────────────────────────
set -u

REPO="${WEARSIC_REPO:-Aeroflash-r1/wearsic}"
DEST="${WEARSIC_DIR:-$HOME/wearsic-server}"
ZIP="$HOME/wearsic-server-termux.zip"

say()  { printf '\033[1;35m[wearsic]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[wearsic]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[wearsic] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# Random 8-char API key from an unambiguous alphabet (no 0/O, 1/l/I)
# so it stays typeable on a watch keyboard. The alphabet has 31 chars
# (≈40 bits total); the server's per-client rate limit makes brute force
# impractical. For public/tunnel setups, set a longer key with:
# wearsic server api key <key>
# Fresh installs get a key automatically — never invent one by hand.
generate_api_key() {
  local alphabet="23456789abcdefghjkmnpqrstuvwxyz" key="" r n
  n="${#alphabet}"   # 31 — modulo the real length, or index 31 is empty
  while [ "${#key}" -lt 8 ]; do
    r="$(od -An -N1 -tu1 /dev/urandom 2>/dev/null | tr -d ' \n')"
    [ -n "$r" ] || r="$RANDOM"   # od missing/failed: bash fallback
    key="$key${alphabet:$((r % n)):1}"
  done
  printf '%s' "$key"
}

# Best-effort LAN IP of this device (wlan0 first, then any route).
detect_lan_ip() {
  local ip=""
  if command -v ip >/dev/null 2>&1; then
    ip="$(ip -f inet addr show wlan0 2>/dev/null | sed -n 's/.*inet \([0-9.]\+\).*/\1/p' | head -1)"
  fi
  if [ -z "$ip" ] && command -v ifconfig >/dev/null 2>&1; then
    # net-tools prints "inet addr:"; busybox prints "inet ".
    ip="$(ifconfig wlan0 2>/dev/null | sed -n 's/.*inet addr:\([0-9.]\+\).*/\1/p' | head -1)"
    [ -z "$ip" ] && ip="$(ifconfig wlan0 2>/dev/null | sed -n 's/.*inet \([0-9.]\+\).*/\1/p' | head -1)"
  fi
  if [ -z "$ip" ] && command -v hostname >/dev/null 2>&1; then
    ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
  fi
  printf '%s' "$ip"
}

# Write (or replace) WEARSIC_API_KEY in the env file ($1 = key).
write_env_key() {
  if grep -q '^WEARSIC_API_KEY=' "$DEST/.env" 2>/dev/null; then
    sed -i "s|^WEARSIC_API_KEY=.*|WEARSIC_API_KEY=$1|" "$DEST/.env"
  else
    printf '\n# Shared secret — enter the same value in the watch app (Settings → API Key)\nWEARSIC_API_KEY=%s\n' "$1" >> "$DEST/.env"
  fi
}

# Port the server listens on (run-termux.sh reads the same .env).
resolve_port() {
  local p=""
  [ -f "$DEST/.env" ] && p="$(sed -n 's/^PORT=//p' "$DEST/.env" | tail -1 | tr -d '\r\n ')"
  [ -n "$p" ] || p="8080"
  printf '%s' "$p"
}

# The API key in effect after this install: whatever was already in
# .env (preserved above), else WEARSIC_API_KEY from the environment,
# else a freshly generated secure key — so a fresh install is never
# left open, and the user never has to invent one.
resolve_effective_key() {
  local k=""
  [ -f "$DEST/.env" ] && k="$(sed -n 's/^WEARSIC_API_KEY=//p' "$DEST/.env" | tail -1 | tr -d '\r\n ')"
  if [ -z "$k" ] && [ -n "${WEARSIC_API_KEY:-}" ]; then
    k="$WEARSIC_API_KEY"
    write_env_key "$k"
  fi
  if [ -z "$k" ]; then
    k="$(generate_api_key)"
    write_env_key "$k"
    say "Generated a secure API key for you (saved in $DEST/.env)." >&2
  fi
  printf '%s' "$k"
}

# 1 ── Required packages ------------------------------------------------------
say "Installing required packages (openjdk-17, unzip, curl)…"
if command -v pkg >/dev/null 2>&1; then
  pkg update -y >/dev/null 2>&1 || warn "pkg update reported a problem — continuing"
  for p in openjdk-17 unzip curl; do
    if ! pkg list-installed 2>/dev/null | grep -q "^${p}/"; then
      say "  installing $p…"
      pkg install -y "$p" || die "Failed to install '$p'. Run: pkg install -y openjdk-17 unzip curl"
    fi
  done
else
  warn "No 'pkg' command found — assuming packages are already installed."
fi

command -v java  >/dev/null 2>&1 || die "java is missing. Install it with: pkg install -y openjdk-17"
command -v unzip >/dev/null 2>&1 || die "unzip is missing. Install it with: pkg install -y unzip"
command -v curl  >/dev/null 2>&1 || die "curl is missing. Install it with: pkg install -y curl"

# 2 ── Locate the latest release ---------------------------------------------
say "Finding the newest Wearsic server release…"
API="https://api.github.com/repos/${REPO}/releases/latest"
JSON="$(curl -fsSL --retry 2 "$API" 2>/dev/null || true)"

ZIP_URL="$(printf '%s' "$JSON" \
  | grep -o '"browser_download_url"[^"]*"[^"]*wearsic-server-termux[^"]*\.zip"' \
  | head -1 \
  | sed -E 's/.*"(https[^"]*)".*/\1/')"

if [ -z "$ZIP_URL" ]; then
  TAG="$(printf '%s' "$JSON" | grep -o '"tag_name"[^"]*"[^"]*"' | head -1 | sed -E 's/.*"([^"]*)"$/\1/')"
  [ -n "$TAG" ] && ZIP_URL="https://github.com/${REPO}/releases/latest/download/wearsic-server-termux-${TAG}.zip"
fi
[ -n "$ZIP_URL" ] || die "Could not find a release ZIP for ${REPO}. Check your internet connection."

say "Downloading: $ZIP_URL"
curl -fL --retry 3 --retry-delay 2 -o "$ZIP" "$ZIP_URL" || die "Download failed."
unzip -t "$ZIP" >/dev/null 2>&1 || die "The downloaded file is not a valid ZIP (truncated download?)."

# 3 ── Extract (keeping existing data) ---------------------------------------
TMP="$(mktemp -d)"
unzip -oq "$ZIP" -d "$TMP" || die "Could not extract the server package."
SRC="$TMP/wearsic-server"
[ -d "$SRC" ] || SRC="$TMP"
[ -f "$SRC/bin/wearsic-server" ] || die "Package is incomplete (bin/wearsic-server missing)."

mkdir -p "$DEST"
# Preserve the user's database and settings across (re)installs.
[ -f "$DEST/wearsic.db" ] && cp "$DEST/wearsic.db" "$TMP/keep.db"
[ -f "$DEST/.env" ]       && cp "$DEST/.env"       "$TMP/keep.env"

rm -rf "$DEST/bin" "$DEST/lib"
cp -r "$SRC/bin" "$DEST/bin" || die "Could not install bin/"
cp -r "$SRC/lib" "$DEST/lib" || die "Could not install lib/"
[ -f "$SRC/run-termux.sh" ] && cp "$SRC/run-termux.sh" "$DEST/run-termux.sh"
[ -f "$SRC/README.md" ]     && cp "$SRC/README.md"     "$DEST/README.md"
[ -f "$SRC/.env.example" ]  && cp "$SRC/.env.example"  "$DEST/.env.example"

[ -f "$TMP/keep.db" ]  && cp "$TMP/keep.db"  "$DEST/wearsic.db"
[ -f "$TMP/keep.env" ] && cp "$TMP/keep.env" "$DEST/.env"

chmod +x "$DEST/run-termux.sh" "$DEST/bin/wearsic-server" 2>/dev/null || true
say "Installed to $DEST"

# 3b ── Install the `wearsic` command (before $TMP is wiped) ---
# Termux: $PREFIX/bin is on PATH and writable without root.
# Elsewhere: fall back to ~/bin and warn if it is not on PATH.
if [ -f "$SRC/wearsic" ]; then
  if [ -n "${PREFIX:-}" ] && [ -d "${PREFIX}/bin" ] && [ -w "${PREFIX}/bin" ]; then
    CLI_BIN="$PREFIX/bin"
  else
    CLI_BIN="$HOME/bin"
    mkdir -p "$CLI_BIN"
    case ":$PATH:" in
      *":$CLI_BIN:"*) ;;
      *) warn "$CLI_BIN is not on PATH — add it:  export PATH=\"\$PATH:$CLI_BIN\"" ;;
    esac
  fi
  cp "$SRC/wearsic" "$CLI_BIN/wearsic" && chmod +x "$CLI_BIN/wearsic" \
    && say "'wearsic' command installed ($CLI_BIN/wearsic)"
fi

rm -rf "$TMP" "$ZIP"

# 4 ── API key: keep yours, or generate a secure one -----------------------
touch "$DEST/.env"
if [ -n "${WEARSIC_API_KEY:-}" ]; then
  write_env_key "$WEARSIC_API_KEY"
  say "API key written to $DEST/.env (set the same key in the watch app)."
fi
EFFECTIVE_KEY="$(resolve_effective_key)"

# 5 ── Auto-start on reboot (Termux:Boot; Termux only) ----------------------
if [ "${WEARSIC_NO_BOOT:-0}" != "1" ] && [ -n "${TERMUX_VERSION:-}" ]; then
  mkdir -p "$HOME/.termux/boot"
  cat > "$HOME/.termux/boot/start-wearsic.sh" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock
exec "$DEST/run-termux.sh"
EOF
  chmod +x "$HOME/.termux/boot/start-wearsic.sh"
  say "Auto-start installed (needs the Termux:Boot app: https://f-droid.org/packages/com.termux.boot/)"
fi

# 6 ── Pairing details (the two things the watch needs) ----------------------
PORT="$(resolve_port)"
LAN_IP="$(detect_lan_ip)"
if [ -n "$LAN_IP" ]; then
  SERVER_URL="http://${LAN_IP}:${PORT}"
else
  SERVER_URL="http://<phone-IP>:${PORT}   (find it: ifconfig wlan0 | grep inet)"
fi

say ""
say "═══════════════════════════════════════════════════════════"
say "  Wearsic server is ready — connect your watch:"
say ""
say "  Server URL : $SERVER_URL"
say "  API key    : $EFFECTIVE_KEY"
say ""
say "  Watch → Wearsic → Settings → Server URL   (paste the URL)"
say "                           → API Key        (paste the key)"
say "  (same WiFi — or other networks: wearsic server funnel)"
say ""
say "  See these again any time:  wearsic server url"
say "  Manage the server:         wearsic server start | stop | status | logs"
say "═══════════════════════════════════════════════════════════"
say ""

# 7 ── Start ------------------------------------------------------------------
if [ "${WEARSIC_NO_START:-0}" = "1" ]; then
  say "Installed but not started (WEARSIC_NO_START=1)."
  say "Start it with:  wearsic server start"
  exit 0
fi

say "Starting the server. Leave this Termux session running."
say "In a second session, verify with:  curl http://127.0.0.1:${PORT}/health"
cd "$DEST" && exec ./run-termux.sh
