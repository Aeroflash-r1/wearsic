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
# Theme: quiet green. The tag stays dim (grep-able, not shouty); content
# speaks in soft green (114) with bright (157) accents; titles get a
# white→green gradient. Animations off when piped so logs stay clean.
C_RESET='\033[0m'; C_BOLD='\033[1m'; C_PURPLE='\033[38;5;114m'
C_GREEN='\033[1;32m'; C_YELLOW='\033[1;33m'; C_RED='\033[1;31m'; C_DIM='\033[2m'; C_CYAN='\033[38;5;157m'
C_TAG='\033[2m'; C_ACC='\033[38;5;114m'

TTY_OUT=0; [ -t 1 ] && TTY_OUT=1   # animations off when piped/logged

say()  { printf "${C_TAG}[wearsic]${C_RESET} %s\n" "$*"; }
step() { printf "\n${C_TAG}[wearsic]${C_RESET} %s\n" "$(grad_line "━━━ $* ━━━")"; }
ok()   { printf "${C_TAG}[wearsic]${C_RESET}  ${C_GREEN}✓${C_RESET} %s\n" "$*"; }
warn() { printf "${C_TAG}[wearsic]${C_RESET}  ${C_YELLOW}!${C_RESET} %s\n" "$*"; }
die()  { printf "${C_TAG}[wearsic]${C_RESET} ${C_RED}✗ ERROR:${C_RESET} %s\n" "$*" >&2; exit 1; }

# White→green gradient for short titles (256-palette ramp, per character).
grad_line() { # $1 = text
  local text="$1" len i=0 out="" shade ch
  len=${#text}
  if [ "$len" -le 0 ]; then printf ''; return 0; fi
  while [ "$i" -lt "$len" ]; do
    # 255 (white) → 114 (soft green) across the string, quantized so
    # terminals render it as a smooth ramp instead of mud.
    if [ "$len" -le 1 ]; then shade=114;
    else shade=$(( 255 - (255 - 114) * i / (len - 1) )); fi
    if [ "$shade" -gt 200 ]; then shade=255
    elif [ "$shade" -gt 170 ]; then shade=158
    elif [ "$shade" -gt 145 ]; then shade=120
    else shade=114; fi
    ch="$(printf '%s' "$text" | cut -c$((i + 1)))"
    out="${out}\033[38;5;${shade}m${ch}"
    i=$((i + 1))
  done
  printf '%s\033[0m' "$out"
}

# Animated spinner for background work. Non-tty: plain start line, the
# matching ok/fail line on stop (logs stay readable).
SPIN_PID=""; SPIN_MSG=""
spinner_start() { # $1 = message
  SPIN_MSG="$1"
  if [ "$TTY_OUT" != "1" ]; then say "$1…"; return 0; fi
  printf "${C_TAG}[wearsic]${C_RESET}  %s " "$1"
  # Dots drift white→green→white: quiet motion, never a solid block.
  ( while :; do for f in "[38;5;255m⠋" "[38;5;158m⠙" "[38;5;120m⠹" "[38;5;114m⠸" "[38;5;114m⠼" "[38;5;120m⠴" "[38;5;158m⠦" "[38;5;255m⠧" "[38;5;158m⠇" "[38;5;120m⠏"; do printf '\b%s[0m' "$f"; sleep 0.09; done; done ) &
  SPIN_PID=$!
}
spinner_stop() { # $1 = 0 ok / else fail, $2 = done message (default: start message)
  local msg="${2:-$SPIN_MSG}"
  if [ -n "$SPIN_PID" ]; then kill "$SPIN_PID" 2>/dev/null; wait "$SPIN_PID" 2>/dev/null; SPIN_PID=""; fi
  if [ "$TTY_OUT" = "1" ]; then printf '\b'; fi
  if [ "${1:-0}" = "0" ]; then ok "$msg"; else printf "${C_TAG}[wearsic]${C_RESET}  ${C_RED}✗${C_RESET} %s\n" "$msg"; fi
  SPIN_MSG=""
}

# Download with a live █ progress bar (MB + %). Falls back to a spinner
# when the server hides Content-Length. Returns curl's exit status.
download_bar() { # $1 = url, $2 = out file, $3 = label
  local url="$1" out="$2" label="${3:-download}"
  local total=""
  total="$(curl -fsSIL --max-time 15 "$url" 2>/dev/null | grep -i '^content-length:' | tail -1 | tr -d '\r' | awk '{print $2}')"
  case "$total" in ''|*[!0-9]*) total="" ;; esac
  if [ -z "$total" ] || [ "$TTY_OUT" != "1" ]; then
    spinner_start "$label"
    if curl -fL --retry 3 --retry-delay 2 -o "$out" "$url" >/dev/null 2>&1; then
      spinner_stop 0 "$label ($(du -h "$out" 2>/dev/null | cut -f1))"
    else
      spinner_stop 1 "$label"; return 1
    fi
    return 0
  fi
  curl -fL --retry 3 --retry-delay 2 -o "$out" "$url" >/dev/null 2>&1 &
  local pid=$! have=0 pct=0
  while kill -0 "$pid" 2>/dev/null; do
    have="$(wc -c < "$out" 2>/dev/null || echo 0)"
    pct=$(( have * 100 / total ))
    [ "$pct" -gt 100 ] && pct=100
    draw_bar "$label" "$pct" "$have" "$total"
    sleep 0.2
  done
  wait "$pid" || { printf '\n'; return 1; }
  draw_bar "$label" 100 "$total" "$total"
  printf '\n'
  return 0
}

draw_bar() { # $1 label $2 pct $3 have-bytes $4 total-bytes
  local label="$1" pct="$2" have="$3" total="$4" width=18 i bar=""
  local filled=$(( pct * width / 100 ))
  i=0; while [ "$i" -lt "$filled" ]; do bar="${bar}█"; i=$((i + 1)); done
  while [ "$i" -lt "$width" ]; do bar="${bar}░"; i=$((i + 1)); done
  printf "\r${C_TAG}[wearsic]${C_RESET}  %s ${C_CYAN}%s${C_RESET} %3d%%  ${C_DIM}%s / %s${C_RESET}" \
    "$label" "$bar" "$pct" "$(human_bytes "$have")" "$(human_bytes "$total")"
}

human_bytes() { # $1 = bytes -> 12.4M
  local b="${1:-0}"
  case "$b" in ''|*[!0-9]*) b=0 ;; esac
  if [ "$b" -ge 1048576 ]; then awk "BEGIN{printf \"%.1fM\", $b/1048576}";
  elif [ "$b" -ge 1024 ]; then awk "BEGIN{printf \"%.0fK\", $b/1024}";
  else printf '%sB' "$b"; fi
}

# Rounded box for the pairing card / big announcements.
box_top()    { printf "${C_ACC}╭─ %s %s${C_RESET}\n" "$1" "$(box_rule "$1")"; }
box_line()   { printf "${C_ACC}│${C_RESET} %s\n" "$1"; }
box_bottom() { printf "${C_ACC}╰%s╯${C_RESET}\n" "$(box_rule "")"; }
box_rule() { # pad a ── rule to ~52 cols after $1
  local n=$(( 50 - ${#1} )); [ "$n" -lt 4 ] && n=4
  local i=0 s=""; while [ "$i" -lt "$n" ]; do s="${s}─"; i=$((i + 1)); done
  printf '%s' "$s"
}

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

# ── 2. dependencies (runs in the BACKGROUND while we download) ─────────────
step "2/7  Installing dependencies (Java, curl, unzip)…"

PKG_BG=0
PKG_START=$SECONDS
if [ "$IS_TERMUX" = "1" ] && command -v pkg >/dev/null 2>&1; then
  # Skip the (slow) index refresh entirely when everything is present.
  NEED_PKGS=""
  for p in openjdk-17 unzip curl; do
    pkg list-installed 2>/dev/null | grep -q "^${p}/" || NEED_PKGS="$NEED_PKGS $p"
  done
  if [ -z "$NEED_PKGS" ]; then
    ok "openjdk-17, unzip, curl (already installed — no download)"
  else
    # Backgrounded so the server zip downloads in parallel — the two slow
    # network jobs overlap instead of adding up. Status reaped in step 4.
    ( pkg update -y >/dev/null 2>&1
      # shellcheck disable=SC2086
      pkg install -y $NEED_PKGS >/dev/null 2>&1
    ) >"$WORK/pkg.log" 2>&1 &
    PKG_PID=$!
    PKG_BG=1
    say "     downloading packages in the background (Java is ~100MB on first install)…"
  fi
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
  download_bar "$ZIP_URL" "$ZIP" "downloading $(basename "$ZIP_URL")" \
    || die "Download failed: $ZIP_URL"
  [ -s "$ZIP" ] || die "Downloaded file is empty."

  spinner_start "fetching checksum"
  if ! curl -fsSL --retry 2 -o "$SHA" "$SHA_URL" 2>/dev/null || [ ! -s "$SHA" ]; then
    spinner_stop 1 "checksum"; die "No SHA-256 checksum found at $SHA_URL — refusing to install unverified code."
  fi
  spinner_stop 0 "checksum"

  # Accept either "hash" or "hash  filename" formats.
  EXPECTED="$(awk '{print $1; exit}' "$SHA" | tr 'A-F' 'a-f')"
  spinner_start "verifying SHA-256"
  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL="$(sha256sum "$ZIP" | awk '{print $1}')"
  else
    ACTUAL="$(shasum -a 256 "$ZIP" | awk '{print $1}')"
  fi
  if [ "$EXPECTED" != "$ACTUAL" ]; then
    spinner_stop 1 "SHA-256"; die "SHA-256 MISMATCH — the download is corrupt or tampered. Nothing was installed."
  fi
  spinner_stop 0 "SHA-256 verified"

  STAGE="$WORK/stage"
  mkdir -p "$STAGE"
  spinner_start "extracting package"
  if ! unzip -t "$ZIP" >/dev/null 2>&1; then
    spinner_stop 1 "extracting package"; die "The downloaded file is not a valid ZIP (truncated download?)."
  fi
  if ! unzip -oq "$ZIP" -d "$STAGE"; then
    spinner_stop 1 "extracting package"; die "Could not extract the server package."
  fi
  spinner_stop 0 "extracted"
  SRC="$STAGE/wearsic-server"
  [ -d "$SRC" ] || SRC="$STAGE"
fi

# ── reap the background dependency install from step 2 ─────────────────────
if [ "$PKG_BG" = "1" ]; then
  spinner_start "waiting for dependencies (Java, curl, unzip)"
  if wait "$PKG_PID"; then
    spinner_stop 0 "dependencies ready"
  else
    spinner_stop 1 "dependencies"
    tail -5 "$WORK/pkg.log" 2>/dev/null
    die "Failed to install dependencies. Run: pkg update -y && pkg install -y openjdk-17 unzip curl"
  fi
  for p in openjdk-17 unzip curl; do
    if pkg list-installed 2>/dev/null | grep -q "^${p}/"; then
      ok "$p"
    else
      die "Dependency '$p' is still missing after install. Run: pkg install -y $p"
    fi
  done
  PKG_SECS=$(( SECONDS - PKG_START ))
  if [ "$PKG_SECS" -gt 120 ]; then
    warn "dependency download took ${PKG_SECS}s — your mirror may be slow."
    warn "Try a faster one: termux-change-repo  (pick something nearby)"
  fi
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
spinner_start "copying engine into place"
if ! cp -r "$SRC/bin" "$DEST/bin.new"; then
  spinner_stop 1 "copying engine"; die "Could not prepare the new engine (disk full?) — the current install is untouched."
fi
if ! cp -r "$SRC/lib" "$DEST/lib.new"; then
  rm -rf "$DEST/bin.new"
  spinner_stop 1 "copying engine"; die "Could not prepare the new engine (disk full?) — the current install is untouched."
fi
spinner_stop 0 "engine staged"

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
spinner_start "waiting for first boot (cold JVM on phones takes minutes)"
i=0
while [ "$i" -lt 180 ]; do
  health_ok && break
  i=$((i + 1))
  if [ "$((i % 30))" = "0" ]; then
    say "     still booting… (${i}s — JVM warmup + first health check)"
  fi
  sleep 1
done
if health_ok; then
  spinner_stop 0 "server is up"
else
  spinner_stop 1 "server boot"
fi

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
box_top "♪ Wearsic server is ready — connect your watch"
box_line ""
box_line "   ${C_BOLD}Server URL :${C_RESET} $SERVER_URL"
box_line "   ${C_BOLD}API key    :${C_RESET} $EFFECTIVE_KEY"
box_line ""
box_line "   Watch → Wearsic → Settings → Server URL   (paste the URL)"
box_line "                               → API Key        (paste the key)"
box_line ""
box_line "   ${C_DIM}Show these again:  wearsic url    ·   Check the setup:  wearsic doctor${C_RESET}"
box_bottom
printf '\n'
say "Manage it with:  wearsic start | stop | restart | status | logs | health | url | api-key | update | doctor"
