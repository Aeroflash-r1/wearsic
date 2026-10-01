#!/data/data/com.termux/files/usr/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# Wearsic server — one-command installer.
#
#   curl -fsSL https://raw.githubusercontent.com/Aeroflash-r1/wearsic/main/install.sh | bash
#
# One line does everything:
#   · detects Termux + ARM64 and installs the dependencies (Java, curl, unzip)
#   · downloads the latest PREBUILT server release
#   · VERIFIES it against the release's published SHA-256 checksum
#   · installs it with staged, crash-safe directory swaps (with rollback)
#   · creates the config + a secure API key (your existing ones are preserved)
#   · installs the `wearsic` command on your PATH
#   · starts the self-healing server and runs a health check
#
# Re-running it upgrades in place. It NEVER deletes wearsic.db, .env, API
# keys, downloads/ or wearsic-state/ — a failed update rolls back to the
# previous engine and cannot leave a broken server behind.
#
# Optional environment overrides:
#   WEARSIC_REPO      GitHub owner/repo        (default Aeroflash-r1/wearsic)
#   WEARSIC_DIR       install directory        (default ~/wearsic-server)
#   WEARSIC_API_KEY   set the API key without prompting
#   WEARSIC_ZIP_URL   install this release zip instead of the latest
#   WEARSIC_SHA_URL   its .sha256 (default: WEARSIC_ZIP_URL + ".sha256")
#   WEARSIC_NO_START  "1" to install but not start
#   WEARSIC_NO_BOOT   "1" to skip Termux:Boot autostart
# ─────────────────────────────────────────────────────────────────────────────
set -u

REPO="${WEARSIC_REPO:-Aeroflash-r1/wearsic}"
DEST="${WEARSIC_DIR:-$HOME/wearsic-server}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ── output helpers ──────────────────────────────────────────────────────────
C_RESET='\033[0m'; C_BOLD='\033[1m'; C_PURPLE='\033[1;35m'
C_GREEN='\033[1;32m'; C_YELLOW='\033[1;33m'; C_RED='\033[1;31m'; C_DIM='\033[2m'

say()  { printf "${C_PURPLE}[wearsic]${C_RESET} %s\n" "$*"; }
step() { printf "${C_PURPLE}[wearsic]${C_RESET} ${C_BOLD}%s${C_RESET}\n" "$*"; }
ok()   { printf "${C_PURPLE}[wearsic]${C_RESET}  ${C_GREEN}✓${C_RESET} %s\n" "$*"; }
warn() { printf "${C_PURPLE}[wearsic]${C_RESET}  ${C_YELLOW}!${C_RESET} %s\n" "$*"; }
die()  { printf "${C_PURPLE}[wearsic]${C_RESET} ${C_RED}ERROR:${C_RESET} %s\n" "$*" >&2; exit 1; }

# ── small utilities ─────────────────────────────────────────────────────────

# Random 8-char API key from an unambiguous alphabet (no 0/O, 1/l/I) so it
# stays typeable on a watch keyboard. The alphabet has 31 chars (~40 bits);
# the server's per-client rate limit makes brute force impractical. For
# public/tunnel setups set a longer key with: wearsic api-key <key>
generate_api_key() {
  local alphabet="23456789abcdefghjkmnpqrstuvwxyz" key="" r n
  n="${#alphabet}"
  while [ "${#key}" -lt 8 ]; do
    r="$(od -An -N1 -tu1 /dev/urandom 2>/dev/null | tr -d ' \n')"
    [ -n "$r" ] || r="$RANDOM"
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
    ip="$(ifconfig wlan0 2>/dev/null | sed -n 's/.*inet addr:\([0-9.]\+\).*/\1/p' | head -1)"
    [ -z "$ip" ] && ip="$(ifconfig wlan0 2>/dev/null | sed -n 's/.*inet \([0-9.]\+\).*/\1/p' | head -1)"
  fi
  if [ -z "$ip" ] && command -v hostname >/dev/null 2>&1; then
    ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
  fi
  printf '%s' "$ip"
}

env_value() {  # $1 = key name, $2 = file (default $DEST/.env)
  local f="${2:-$DEST/.env}"
  [ -f "$f" ] || return 0
  sed -n "s/^$1=//p" "$f" | tail -1 | tr -d '\r\n'
}

write_env_value() {  # $1 = key, $2 = value, $3 = file (default $DEST/.env)
  local f="${3:-$DEST/.env}"
  touch "$f"
  if grep -q "^$1=" "$f" 2>/dev/null; then
    sed -i "s|^$1=.*|$1=$2|" "$f"
  else
    printf '%s=%s\n' "$1" "$2" >> "$f"
  fi
}

# The API key in effect after this install: whatever is already in .env
# (always preserved), else WEARSIC_API_KEY from the environment, else a
# freshly generated secure key — a fresh install is never left open.
resolve_effective_key() {
  local k
  k="$(env_value WEARSIC_API_KEY)"
  if [ -z "$k" ] && [ -n "${WEARSIC_API_KEY:-}" ]; then
    k="$WEARSIC_API_KEY"
    write_env_value WEARSIC_API_KEY "$k"
    ok "API key written to $DEST/.env (from WEARSIC_API_KEY)" >&2
  fi
  if [ -z "$k" ]; then
    k="$(generate_api_key)"
    write_env_value WEARSIC_API_KEY "$k"
    ok "Generated a secure API key for you (saved in $DEST/.env)" >&2
  fi
  # Only the key on stdout (this runs in a command substitution) — all
  # messages above go to stderr so they cannot pollute the key value.
  printf '%s' "$k"
}

port() { local p; p="$(env_value PORT)"; [ -n "$p" ] || p="8080"; printf '%s' "$p"; }

health_ok() { curl -sf -m 5 "http://127.0.0.1:$(port)/health" >/dev/null 2>&1; }

# ── 1. environment ──────────────────────────────────────────────────────────
step "1/7  Checking your device…"

IS_TERMUX=0
case "${PREFIX:-}" in */com.termux/*) IS_TERMUX=1 ;; esac
[ -n "${TERMUX_VERSION:-}" ] && IS_TERMUX=1
ARCH="$(uname -m 2>/dev/null || echo unknown)"
case "$ARCH" in
  aarch64|arm64) ARCH_LABEL="ARM64" ;;
  armv7l|armv8l) ARCH_LABEL="ARM32" ;;
  x86_64)        ARCH_LABEL="x86_64" ;;
  *)             ARCH_LABEL="$ARCH" ;;
esac

if [ "$IS_TERMUX" = "1" ]; then
  ok "Termux on Android ($ARCH_LABEL) — native, no root needed"
else
  warn "Termux not detected ($ARCH_LABEL) — continuing (the server is a plain JVM app)"
fi

# ── 2. dependencies ─────────────────────────────────────────────────────────
step "2/7  Installing dependencies (Java, curl, unzip)…"

if [ "$IS_TERMUX" = "1" ] && command -v pkg >/dev/null 2>&1; then
  pkg update -y >/dev/null 2>&1 || warn "pkg update reported a problem — continuing"
  for p in openjdk-17 unzip curl; do
    if ! pkg list-installed 2>/dev/null | grep -q "^${p}/"; then
      say "     installing $p…"
      pkg install -y "$p" >/dev/null 2>&1 || die "Failed to install '$p'. Run: pkg install -y openjdk-17 unzip curl"
    fi
    ok "$p"
  done
else
  for c in java unzip curl; do
    command -v "$c" >/dev/null 2>&1 \
      || die "'$c' is missing. Install it first (Debian/Ubuntu: sudo apt-get install -y openjdk-17-jre-headless unzip curl)"
    ok "$c found"
  done
fi

command -v sha256sum >/dev/null 2>&1 || command -v shasum >/dev/null 2>&1 \
  || die "sha256sum is missing (install coreutils)"

# ── 3. locate the payload ────────────────────────────────────────────────
# When this script runs from an extracted release package (it sits next to
# bin/wearsic-server), the payload is already here — install it directly,
# no download. Piped from GitHub or run from a checkout: download the
# latest release instead.
SCRIPT_PATH="${BASH_SOURCE[0]:-}"
LOCAL_PAYLOAD=""
if [ -n "$SCRIPT_PATH" ] && [ -f "$SCRIPT_PATH" ]; then
  _here="$(CDPATH= cd -- "$(dirname -- "$SCRIPT_PATH")" 2>/dev/null && pwd)"
  for _cand in "$_here/bin/wearsic-server" "$_here/wearsic-server/bin/wearsic-server"; do
    if [ -f "$_cand" ]; then
      LOCAL_PAYLOAD="$(dirname -- "$(dirname -- "$_cand")")"
      break
    fi
  done
fi

ZIP_URL="${WEARSIC_ZIP_URL:-}"
SHA_URL="${WEARSIC_SHA_URL:-}"
if [ -n "$LOCAL_PAYLOAD" ]; then
  step "3/7  Using the local package next to this installer…"
  ok "$(basename "$LOCAL_PAYLOAD") (no download needed)"
elif [ -z "$ZIP_URL" ]; then
  step "3/7  Finding the latest Wearsic server release…"
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
  [ -n "$ZIP_URL" ] || die "Could not find a release for ${REPO}. Check your internet connection and try again."
  [ -n "$SHA_URL" ] || SHA_URL="${ZIP_URL}.sha256"
  ok "$(basename "$ZIP_URL")"
else
  step "3/7  Using the provided package URL…"
  [ -n "$SHA_URL" ] || SHA_URL="${ZIP_URL}.sha256"
  ok "$(basename "$ZIP_URL")"
fi

# ── 4. download + VERIFY (never install unverified bytes) ───────────────────
SRC=""
if [ -n "$LOCAL_PAYLOAD" ]; then
  step "4/7  Verifying the local package…"
  SRC="$LOCAL_PAYLOAD"
  ok "complete engine found next to this installer"
else
  step "4/7  Downloading and verifying…"

  ZIP="$WORK/wearsic-server.zip"
  SHA="$WORK/wearsic-server.zip.sha256"
  curl -fL --retry 3 --retry-delay 2 -o "$ZIP" "$ZIP_URL" \
    || die "Download failed: $ZIP_URL"
  [ -s "$ZIP" ] || die "Downloaded file is empty."
  ok "downloaded ($(du -h "$ZIP" | cut -f1))"

  if ! curl -fsSL --retry 2 -o "$SHA" "$SHA_URL" 2>/dev/null || [ ! -s "$SHA" ]; then
    die "No SHA-256 checksum found at $SHA_URL — refusing to install unverified code."
  fi

  # Accept either "hash" or "hash  filename" formats.
  EXPECTED="$(awk '{print $1; exit}' "$SHA" | tr 'A-F' 'a-f')"
  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL="$(sha256sum "$ZIP" | awk '{print $1}')"
  else
    ACTUAL="$(shasum -a 256 "$ZIP" | awk '{print $1}')"
  fi
  if [ "$EXPECTED" != "$ACTUAL" ]; then
    die "SHA-256 MISMATCH — the download is corrupt or tampered. Nothing was installed."
  fi
  ok "SHA-256 verified"

  STAGE="$WORK/stage"
  mkdir -p "$STAGE"
  unzip -t "$ZIP" >/dev/null 2>&1 || die "The downloaded file is not a valid ZIP (truncated download?)."
  unzip -oq "$ZIP" -d "$STAGE" || die "Could not extract the server package."
  SRC="$STAGE/wearsic-server"
  [ -d "$SRC" ] || SRC="$STAGE"
fi

# ── 5. stage + validate the package before touching anything ────────────────
step "5/7  Preparing the update…"

# The package must look like a complete engine before we touch the active
# installation (same check the supervisor's updater uses).
[ -f "$SRC/bin/wearsic-server" ] || die "Package is incomplete (bin/wearsic-server missing) — nothing was changed."
[ -n "$(ls -A "$SRC/lib" 2>/dev/null)" ] || die "Package is incomplete (lib/ empty) — nothing was changed."
[ -f "$SRC/run-termux.sh" ] || die "Package is incomplete (run-termux.sh missing) — nothing was changed."
ok "package validated (engine, launcher, libraries)"

# ── 6. crash-safe install (staged swap + rollback) ──────────────────────────
step "6/7  Installing to $DEST…"

# Preserve existing data across (re)installs. These files are NEVER deleted:
# the swap only replaces bin/ and lib/ — the code, never your state.
[ -f "$DEST/wearsic.db" ] && ok "existing database found — keeping wearsic.db"
[ -f "$DEST/.env" ]       && ok "existing configuration found — keeping .env (and its API key)"
[ -d "$DEST/downloads" ]  && ok "existing downloads found — keeping downloads/"

mkdir -p "$DEST" "$DEST/wearsic-state" "$DEST/downloads"

# Prepare COMPLETE replacement dirs first. Nothing active is touched until
# both copies exist (an interruption here = nothing happened).
rm -rf "$DEST/bin.new" "$DEST/lib.new"
cp -r "$SRC/bin" "$DEST/bin.new" || die "Could not prepare the new engine (disk full?) — the current install is untouched."
cp -r "$SRC/lib" "$DEST/lib.new" || { rm -rf "$DEST/bin.new"; die "Could not prepare the new engine (disk full?) — the current install is untouched."; }

# Swap by rename: the previous engine is kept as bin.prev/lib.prev for
# rollback until the new one passes its health check (step 7).
rm -rf "$DEST/bin.prev" "$DEST/lib.prev"
if [ -d "$DEST/bin" ]; then
  mv "$DEST/bin" "$DEST/bin.prev" && mv "$DEST/lib" "$DEST/lib.prev" \
    || { rm -rf "$DEST/bin.new" "$DEST/lib.new"; die "Swap failed — the current install is untouched."; }
fi
mv "$DEST/bin.new" "$DEST/bin" && mv "$DEST/lib.new" "$DEST/lib" \
  || die "Swap failed midway — run 'wearsic update' or reinstall to recover."
[ -f "$DEST/bin/wearsic-server" ] || die "Engine swap did not complete — run 'wearsic update' to recover."

# Launcher + supporting files (code only — never data).
for f in run-termux.sh .env.example README.md; do
  [ -f "$SRC/$f" ] && cp "$SRC/$f" "$DEST/$f"
done
chmod +x "$DEST/run-termux.sh" "$DEST/bin/wearsic-server" 2>/dev/null || true
ok "engine installed (previous engine kept for rollback)"

# Config + API key. .env is only ever created or key-updated — never reset,
# and an existing custom PORT/DB path is kept exactly as the user set it.
touch "$DEST/.env"
[ -n "$(env_value PORT)" ] || write_env_value PORT "${PORT:-8080}"
[ -n "$(env_value WEARSIC_DB_PATH)" ] || write_env_value WEARSIC_DB_PATH "wearsic.db"
EFFECTIVE_KEY="$(resolve_effective_key)"

# Install the `wearsic` command (from the staged package, before $WORK is
# wiped). Termux: $PREFIX/bin is on PATH and writable without root.
if [ -f "$SRC/wearsic" ]; then
  if [ -n "${PREFIX:-}" ] && [ -d "${PREFIX}/bin" ] && [ -w "${PREFIX}/bin" ]; then
    CLI_BIN="$PREFIX/bin"
  else
    CLI_BIN="$HOME/.local/bin"
    mkdir -p "$CLI_BIN"
    case ":$PATH:" in
      *":$CLI_BIN:"*) ;;
      *) warn "$CLI_BIN is not on PATH — add it:  export PATH=\"\$PATH:$CLI_BIN\"" ;;
    esac
  fi
  cp "$SRC/wearsic" "$DEST/wearsic" 2>/dev/null || true
  cp "$SRC/wearsic" "$CLI_BIN/wearsic" && chmod +x "$CLI_BIN/wearsic" "$DEST/wearsic" 2>/dev/null \
    && ok "'wearsic' command installed ($CLI_BIN/wearsic)"
fi

# Auto-start on reboot (Termux:Boot; Termux only).
if [ "${WEARSIC_NO_BOOT:-0}" != "1" ] && [ "$IS_TERMUX" = "1" ]; then
  mkdir -p "$HOME/.termux/boot"
  cat > "$HOME/.termux/boot/start-wearsic.sh" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock
exec "$DEST/run-termux.sh"
EOF
  chmod +x "$HOME/.termux/boot/start-wearsic.sh"
  ok "auto-start on reboot installed (Termux:Boot)"
fi

# ── 7. start + health check ─────────────────────────────────────────────────
if [ "${WEARSIC_NO_START:-0}" = "1" ]; then
  say ""
  say "Installed but not started (WEARSIC_NO_START=1)."
  say "Start it with:  wearsic start"
  exit 0
fi

step "7/7  Starting the server…"

# Restart cleanly on upgrades so the new engine actually runs.
if health_ok; then
  say "     restarting into the new engine…"
  [ -x "$DEST/wearsic" ] && bash "$DEST/wearsic" stop >/dev/null 2>&1
  pkill -f "com.wearsic.server.ApplicationKt" 2>/dev/null
  sleep 1
fi

( cd "$DEST" && nohup bash "$DEST/run-termux.sh" >/dev/null 2>&1 & )
say "     waiting for the health check…"
i=0
while [ "$i" -lt 60 ]; do
  health_ok && break
  i=$((i + 1))
  sleep 1
done

if ! health_ok; then
  warn "The server did not become healthy in time."
  warn "Check the logs:  wearsic logs"
  exit 1
fi

# The new engine is healthy — the rollback copy is no longer needed.
rm -rf "$DEST/bin.prev" "$DEST/lib.prev"
ok "server healthy on port $(port)"

READY="$(curl -sf -m 5 "http://127.0.0.1:$(port)/ready" 2>/dev/null || true)"
if printf '%s' "$READY" | grep -q '"ready":true'; then
  ok "ready to serve music"
elif [ -n "$READY" ]; then
  warn "engine reports not-ready yet — run 'wearsic doctor' for details"
fi

# ── pairing card ────────────────────────────────────────────────────────────
LAN_IP="$(detect_lan_ip)"
if [ -n "$LAN_IP" ]; then
  SERVER_URL="http://${LAN_IP}:$(port)"
else
  SERVER_URL="http://<phone-IP>:$(port)"
fi

printf '\n'
printf "${C_PURPLE}[wearsic]${C_RESET} ${C_BOLD}═══════════════════════════════════════════════════════════${C_RESET}\n"
printf "${C_PURPLE}[wearsic]${C_RESET} ${C_BOLD}  Wearsic server is ready — connect your watch:${C_RESET}\n"
printf "${C_PURPLE}[wearsic]${C_RESET}\n"
printf "${C_PURPLE}[wearsic]${C_RESET}   Server URL : %s\n" "$SERVER_URL"
printf "${C_PURPLE}[wearsic]${C_RESET}   API key    : %s\n" "$EFFECTIVE_KEY"
printf "${C_PURPLE}[wearsic]${C_RESET}\n"
printf "${C_PURPLE}[wearsic]${C_RESET}   Watch → Wearsic → Settings → Server URL   (paste the URL)\n"
printf "${C_PURPLE}[wearsic]${C_RESET}                            → API Key        (paste the key)\n"
printf "${C_PURPLE}[wearsic]${C_RESET}\n"
printf "${C_PURPLE}[wearsic]${C_RESET}   ${C_DIM}Show these again:  wearsic url    ·   Check the setup:  wearsic doctor${C_RESET}\n"
printf "${C_PURPLE}[wearsic]${C_RESET} ${C_BOLD}═══════════════════════════════════════════════════════════${C_RESET}\n"
printf '\n'
say "Manage it with:  wearsic start | stop | restart | status | logs | health | url | api-key | update | doctor"
