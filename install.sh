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
trap 'shim_clear_live; shim_cursor_show; shim_cleanup; rm -rf "$WORK"' EXIT
# Ctrl+C (and a polite TERM) must never leave a hidden cursor behind.
trap 'shim_clear_live; shim_cursor_show; shim_cleanup; exit 130' INT
trap 'shim_clear_live; shim_cursor_show; shim_cleanup; exit 143' TERM

# ── output helpers ──────────────────────────────────────────────────────────
# Theme: quiet green. The tag stays dim (grep-able, not shouty); content
# speaks in soft green (114) with bright (157) accents; titles get a
# white→green gradient. Animations off when piped so logs stay clean.
# Real escape sequences (not backslash text): the same variables are used in
# printf FORMAT strings and in plain text (box bodies), so they must be the
# actual bytes.
C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_PURPLE=$'\033[38;5;114m'
C_GREEN=$'\033[1;32m'; C_YELLOW=$'\033[1;33m'; C_RED=$'\033[1;31m'; C_DIM=$'\033[2m'; C_CYAN=$'\033[38;5;157m'
C_TAG=$'\033[2m'; C_ACC=$'\033[38;5;114m'

TTY_OUT=0; [ -t 1 ] && TTY_OUT=1   # animations off when piped/logged

# Real terminal width — the CLI's exact probe. Bash only sets COLUMNS in
# INTERACTIVE shells and does not export it, so a `curl | bash` run used to
# fall back to 80 and draw 76-wide boxes on a 55-column phone: every border
# wrapped and the right edge looked corrupted. Probe COLUMNS (if present),
# then the terminal itself, then 80; export the result so every drawing
# step (shim line, download bar, panels) agrees on one width.
term_cols() {
  local c="${COLUMNS:-}"
  case "$c" in
    ''|*[!0-9]*) if command -v tput >/dev/null 2>&1; then c="$(tput cols 2>/dev/null || true)"; fi ;;
  esac
  case "$c" in
    ''|*[!0-9]*) if command -v stty >/dev/null 2>&1; then c="$(stty size 2>/dev/null | awk '{print $2}')"; fi ;;
  esac
  case "$c" in ''|*[!0-9]*) c=80 ;; esac
  [ "$c" -lt 34 ] && c=34
  printf '%s' "$c"
}
COLS="$(term_cols)"
COLUMNS="$COLS"
export COLUMNS

say()  { printf "${C_TAG}[wearsic]${C_RESET} %s\n" "$*"; }
ok()   { printf "${C_TAG}[wearsic]${C_RESET}  ${C_GREEN}✓${C_RESET} %s\n" "$*"; }
warn() { printf "${C_TAG}[wearsic]${C_RESET}  ${C_YELLOW}!${C_RESET} %s\n" "$*"; }
die()  { shim_clear_live; shim_cursor_show; printf "${C_TAG}[wearsic]${C_RESET} ${C_RED}✗ ERROR:${C_RESET} %s\n" "$*" >&2; exit 1; }


# ── WSHIM-CORE-BEGIN ───────────────────────────────────────────────────────
# Shimmer: a soft highlight band travels left → right across the text.
#
# Every character is blended between the base gray and the highlight gray by
# its distance from the band, so the effect reads as a wave of light rather
# than a blinking highlight. The band leaves the text on the right and
# re-enters on the left, so the loop is seamless.
#
#   base      #8A8A85  (138, 138, 133)
#   highlight #C9C9C3  (201, 201, 195)
#
# One pass takes ~1.7s; every frame is redrawn in place every ~90ms. On a
# 256-colour terminal the blend is approximated with the gray ramp
# (245 → 253). NO_COLOR, a non-TTY stdout and TERM=dumb turn it off, and the
# cursor is always restored (normal exit, error, or Ctrl+C).
#
# This block is byte-identical in `wearsic` and `install.sh`: the installer is
# a standalone bootstrap script that cannot source the CLI.
# tests/shimmer-test.sh fails when the two copies drift apart.
SHIM_BASE_R=138; SHIM_BASE_G=138; SHIM_BASE_B=133
SHIM_HI_R=201;   SHIM_HI_G=201;   SHIM_HI_B=195
SHIM_256_BASE=245; SHIM_256_HI=253
SHIM_FRAME_S=0.09
SHIM_LOOP_FRAMES=19
SHIM_BAND_PCT=30
SHIM_ESC="$(printf '\033')"
SHIM_DEPTH=0
SHIM_CURSOR_HIDDEN=0
SHIM_LIVE_ACTIVE=0
SHIM_PROGRESS_HOOK=""
SHIM_LIVE_TEXT=""
SHIM_LIVE_SHORT=""
SHIM_STATUS_FILE=""
SHIM_STAGE_NAME=""
SHIM_STAGE_DETAIL=""

# The shimmer palette is deliberately gray and self-contained: the file's own
# theme (green/cyan/lavender) stays out of the animated area so the wave
# reads as light, not as colour.
shim_palette() {
  SHIM_DIM="${SHIM_ESC}[2m"
  SHIM_OK="${SHIM_ESC}[38;5;120m"
  SHIM_WARN="${SHIM_ESC}[38;5;179m"
  SHIM_ERR="${SHIM_ESC}[38;5;203m"
  SHIM_RESET="${SHIM_ESC}[0m"
}

# 0 = off, 24 = truecolor, 256 = the gray ramp.
shim_depth() {
  [ "${TTY_OUT:-0}" = "1" ] || { printf '0'; return 0; }
  [ -z "${NO_COLOR:-}" ] || { printf '0'; return 0; }
  case "${WEARSIC_COLOR:-auto}" in never) printf '0'; return 0 ;; esac
  case "${TERM:-}" in dumb) printf '0'; return 0 ;; esac
  case "${WEARSIC_COLOR_DEPTH:-auto}" in
    0) printf '0'; return 0 ;;
    24) printf '24'; return 0 ;;
    256) printf '256'; return 0 ;;
  esac
  case "${COLORTERM:-}" in truecolor|24bit) printf '24'; return 0 ;; esac
  # Termux's terminal is a truecolor terminal even though TERM says 256.
  case "${PREFIX:-}" in */com.termux/*) printf '24'; return 0 ;; esac
  [ -n "${TERMUX_VERSION:-}" ] && { printf '24'; return 0; }
  case "${TERM:-}" in *truecolor*|*direct*) printf '24'; return 0 ;; esac
  case "${TERM:-}" in *256color*) printf '256'; return 0 ;; esac
  if command -v tput >/dev/null 2>&1; then
    local n
    n="$(tput colors 2>/dev/null || echo 0)"
    case "$n" in ''|*[!0-9]*) n=0 ;; esac
    [ "$n" -ge 256 ] && { printf '256'; return 0; }
  fi
  printf '0'
}

shim_init() {
  shim_palette
  SHIM_DEPTH="$(shim_depth)"
}

# Smooth falloff: 100 at the band centre → 0 at the band edge.
shim_intensity() { # $1 = distance, $2 = half-width (0..100 out)
  local d="$1" half="$2" x=0
  [ "$half" -le 0 ] && { printf '0'; return 0; }
  [ "$d" -ge "$half" ] && { printf '0'; return 0; }
  x=$(( (half - d) * 100 / half ))
  printf '%s' $(( x * x * (300 - 2 * x) / 10000 ))
}

# Blend 0..100 between the base gray and the highlight gray.
shim_blend_rgb() { # $1 = 0..100 → "r g b"
  local t="$1"
  printf '%s %s %s' \
    $(( SHIM_BASE_R + (SHIM_HI_R - SHIM_BASE_R) * t / 100 )) \
    $(( SHIM_BASE_G + (SHIM_HI_G - SHIM_BASE_G) * t / 100 )) \
    $(( SHIM_BASE_B + (SHIM_HI_B - SHIM_BASE_B) * t / 100 ))
}

# The same blend on the 256-colour gray ramp (245 ≈ #8A8A85, 253 ≈ #C9C9C3).
shim_blend_256() { # $1 = 0..100 → palette index
  printf '%s' $(( SHIM_256_BASE + (SHIM_256_HI - SHIM_256_BASE) * $1 / 100 ))
}

# Render $1 with the band centred at $2 (half-width $3). The blend maths is
# inlined here on purpose: a command substitution per character would fork
# ~60 processes per frame and the animation would stutter.
shim_render() { # $1 = text, $2 = centre, $3 = half-width
  local text="$1" centre="$2" half="$3" i=0 n=${#1} ch d t r g b out=""
  if [ "$SHIM_DEPTH" != "24" ] && [ "$SHIM_DEPTH" != "256" ]; then
    printf '%s' "$text"
    return 0
  fi
  while [ "$i" -lt "$n" ]; do
    ch="${text:$i:1}"
    d=$(( i - centre )); [ "$d" -lt 0 ] && d=$(( -d ))
    if [ "$d" -ge "$half" ]; then
      t=0
    else
      t=$(( (half - d) * 100 / half ))
      t=$(( t * t * (300 - 2 * t) / 10000 ))
    fi
    if [ "$SHIM_DEPTH" = "24" ]; then
      r=$(( SHIM_BASE_R + (SHIM_HI_R - SHIM_BASE_R) * t / 100 ))
      g=$(( SHIM_BASE_G + (SHIM_HI_G - SHIM_BASE_G) * t / 100 ))
      b=$(( SHIM_BASE_B + (SHIM_HI_B - SHIM_BASE_B) * t / 100 ))
      out="${out}${SHIM_ESC}[38;2;${r};${g};${b}m${ch}"
    else
      out="${out}${SHIM_ESC}[38;5;$(( SHIM_256_BASE + (SHIM_256_HI - SHIM_256_BASE) * t / 100 ))m${ch}"
    fi
    i=$(( i + 1 ))
  done
  printf '%s%s' "$out" "$SHIM_RESET"
}

shim_width() { # room for the live line
  local w="${COLS:-${COLUMNS:-80}}"
  case "$w" in ''|*[!0-9]*) w=80 ;; esac
  [ "$w" -lt 24 ] && w=24
  printf '%s' $(( w - 4 ))
}

# Draw the live line, replacing whatever is on the current line.
shim_draw_live() { # $1 = frame index
  local frame="$1" text="$SHIM_LIVE_TEXT" n half centre maxw
  [ "$SHIM_DEPTH" = "0" ] && return 0
  [ "$TTY_OUT" = "1" ] || return 0
  n=${#text}
  [ "$n" -le 0 ] && return 0
  maxw="$(shim_width)"
  # A label wider than the terminal WRAPS, and "\r" can only erase the last
  # wrapped line — the leftover fragments then pile up (the "Cloudflare
  # waiting for the tunnel + the Worker front…" repetition users saw on a
  # narrow Termux screen). Prefer the short label when there is one.
  if [ "$n" -gt "$maxw" ] && [ -n "${SHIM_LIVE_SHORT:-}" ]; then
    text="$SHIM_LIVE_SHORT"
    n=${#text}
  fi
  if [ "$n" -gt "$maxw" ]; then
    text="${text:0:$(( maxw - 1 ))}…"
    n=${#text}
  fi
  half=$(( n * SHIM_BAND_PCT / 100 / 2 ))
  [ "$half" -lt 3 ] && half=3
  centre=$(( -half + (n + 2 * half) * frame / SHIM_LOOP_FRAMES ))
  printf '\r%s[K' "$SHIM_ESC"
  shim_render "$text" "$centre" "$half"
  SHIM_LIVE_ACTIVE=1
}

# Erase the live line (only if one is on screen).
shim_clear_live() {
  [ "${SHIM_LIVE_ACTIVE:-0}" = "1" ] || return 0
  SHIM_LIVE_ACTIVE=0
  [ "$TTY_OUT" = "1" ] || return 0
  printf '\r%s[K' "$SHIM_ESC"
}

shim_cursor_hide() {
  [ "$TTY_OUT" = "1" ] || return 0
  [ "$SHIM_CURSOR_HIDDEN" = "1" ] && return 0
  printf '%s[?25l' "$SHIM_ESC"
  SHIM_CURSOR_HIDDEN=1
}

shim_cursor_show() {
  [ "$SHIM_CURSOR_HIDDEN" = "1" ] || return 0
  printf '%s[?25h' "$SHIM_ESC"
  SHIM_CURSOR_HIDDEN=0
}

# Update the live text from a file (the worker writes progress there).
shim_status_file() {
  if [ -z "$SHIM_STATUS_FILE" ]; then
    SHIM_STATUS_FILE="$(mktemp "${TMPDIR:-/tmp}/wearsic-shim-status.XXXXXX" 2>/dev/null)" || SHIM_STATUS_FILE=""
  fi
  printf '%s' "$SHIM_STATUS_FILE"
}

shim_status() { # $1 = text
  local f
  f="$(shim_status_file)"
  [ -n "$f" ] || return 0
  printf '%s\n' "$1" > "$f" 2>/dev/null || true
}

shim_cleanup() {
  [ -n "$SHIM_STATUS_FILE" ] && rm -f "$SHIM_STATUS_FILE" 2>/dev/null
  SHIM_STATUS_FILE=""
}

# 12.4M / 900K — small, quiet byte formatting for the live line.
shim_bytes() { # $1 = bytes
  local b="${1:-0}"
  case "$b" in ''|*[!0-9]*) b=0 ;; esac
  if [ "$b" -ge 1048576 ]; then awk "BEGIN{printf \"%.1fM\", $b/1048576}";
  elif [ "$b" -ge 1024 ]; then awk "BEGIN{printf \"%.0fK\", $b/1024}";
  else printf '%sB' "$b"; fi
}

# Fork-free byte/rate/time helpers for the BAR (the old path forked a
# subshell per frame — `wc` + two `awk`s at ~11 fps read as stutter on a
# phone; these do pure integer math and return through $SHIM_FMT / $SHIM_MS).
shim_fmt_bytes() { # $1 = bytes -> SHIM_FMT = "4.2M" / "900K" / "12B"
  local b="${1:-0}"
  case "$b" in ''|*[!0-9]*) b=0 ;; esac
  if [ "$b" -ge 1048576 ]; then
    local h=$(( b / 1048576 )) l=$(( (b % 1048576) * 10 / 1048576 ))
    SHIM_FMT="${h}.${l}M"
  elif [ "$b" -ge 1024 ]; then
    SHIM_FMT="$(( b / 1024 ))K"
  else
    SHIM_FMT="${b}B"
  fi
}

shim_fmt_rate() { # $1 = bytes/s -> SHIM_FMT = "5.2MB/s" / "480KB/s"
  local r="${1:-0}" w f
  case "$r" in ''|*[!0-9]*) r=0 ;; esac
  if [ "$r" -ge 1048576 ]; then
    w=$(( r / 1048576 )); f=$(( (r % 1048576) * 10 / 1048576 ))
    SHIM_FMT="${w}.${f}MB/s"
  elif [ "$r" -ge 1024 ]; then
    w=$(( r / 1024 )); f=$(( (r % 1024) * 10 / 1024 ))
    SHIM_FMT="${w}.${f}KB/s"
  else
    SHIM_FMT="${r}B/s"
  fi
}

shim_now_ms() { # SHIM_MS = milliseconds (bash 5's $EPOCHREALTIME; 0 if absent)
  local r="${EPOCHREALTIME:-}" sec sub
  if [ -z "$r" ]; then SHIM_MS=0; return 0; fi
  case "$r" in
    *.**) sec="${r%%.*}"; sub="${r#*.}" ;;
    *)    sec="$r";      sub="" ;;
  esac
  case "$sec" in ''|*[!0-9]*) sec=0 ;; esac
  case "$sub" in ''|*[!0-9]*) sub=0 ;; esac
  sub="${sub:0:6}"
  SHIM_MS=$(( sec * 1000 + 10#$sub / 1000 ))
}

# Animate until the job ($1) exits. A progress hook ($SHIM_PROGRESS_HOOK) is
# called before every frame, so a download can report its real percentage.
# A hook may paint its OWN frame (the download bar) and set SHIM_HOOK_DREW —
# the generic shimmer then sits out that tick instead of overpainting.
shim_animate() { # $1 = pid
  local pid="$1" frame=0 t=""
  shim_cursor_hide
  while kill -0 "$pid" 2>/dev/null; do
    if [ -n "$SHIM_PROGRESS_HOOK" ]; then
      SHIM_HOOK_DREW=0
      "$SHIM_PROGRESS_HOOK" 2>/dev/null || true
    else
      SHIM_HOOK_DREW=0
    fi
    if [ "${SHIM_HOOK_DREW:-0}" = "1" ]; then
      # The hook already put a line on screen; remember that a live line
      # exists so shim_clear_live erases it later.
      SHIM_LIVE_ACTIVE=1
    else
      if [ -n "$SHIM_STATUS_FILE" ] && [ -s "$SHIM_STATUS_FILE" ]; then
        t="$(cat "$SHIM_STATUS_FILE" 2>/dev/null || true)"
        [ -n "$t" ] && SHIM_LIVE_TEXT="$t"
      fi
      shim_draw_live "$frame"
    fi
    sleep "$SHIM_FRAME_S"
    frame=$(( (frame + 1) % SHIM_LOOP_FRAMES ))
  done
  shim_cursor_show
}

# Wait for a condition while the live line shimmers.
# $1 = label, $2 = timeout in seconds, $3 = shell test command
shim_wait() { # label limit test-command [short-label]
  local label="$1" limit="$2" test="$3" i=0 max step
  case "$limit" in ''|*[!0-9]*) limit=30 ;; esac
  if [ "$SHIM_DEPTH" = "0" ] || [ "$TTY_OUT" != "1" ]; then
    # No animation: poll once a second, exactly like the old spinner.
    max="$limit"; step=1
  else
    max=$(( limit * 10 )); step=0
  fi
  SHIM_LIVE_TEXT="$label"
  SHIM_LIVE_SHORT="${4:-}"
  shim_cursor_hide
  while [ "$i" -lt "$max" ]; do
    shim_draw_live $(( i % SHIM_LOOP_FRAMES ))
    if eval "$test" >/dev/null 2>&1; then
      shim_clear_live
      shim_cursor_show
      return 0
    fi
    if [ "$step" = "0" ]; then sleep "$SHIM_FRAME_S"; else sleep 1; fi
    i=$(( i + 1 ))
  done
  shim_clear_live
  shim_cursor_show
  return 1
}

# The tail of a captured log — used when a command fails, so a failure is
# never silently swallowed.
shim_log_tail() { # $1 = log file, $2 = number of lines
  local f="$1" n="${2:-10}"
  [ -s "$f" ] || return 0
  printf '  %s%s%s\n' "$SHIM_DIM" "last $n lines:" "$SHIM_RESET" >&2
  tail -n "$n" "$f" 2>/dev/null | sed 's/^/  /' >&2
}

# Run a command with the live line shimmering. The output is captured, not
# sprayed over the terminal; on failure the tail is surfaced.
# Usage: shim_run "<live text>" <cmd> [args...]
shim_run() { # $1 = live text, rest = command
  local text="$1"; shift
  local log rc=0
  log="$(mktemp "${TMPDIR:-/tmp}/wearsic-shim.XXXXXX" 2>/dev/null)" || log="/dev/null"
  SHIM_LIVE_TEXT="$text"
  if [ "$SHIM_DEPTH" != "0" ] && [ "$TTY_OUT" = "1" ]; then
    "$@" >"$log" 2>&1 &
    local pid=$!
    shim_animate "$pid"
    wait "$pid"; rc=$?
    shim_clear_live
  else
    "$@" >"$log" 2>&1
    rc=$?
  fi
  [ "$rc" = "0" ] || shim_log_tail "$log" 12
  [ "$log" = "/dev/null" ] || rm -f "$log" 2>/dev/null
  return "$rc"
}

# Download helpers: the caller starts the transfer, the animation loop asks
# for progress, and the caller waits for the result. The transfer itself is
# quiet — only the size is polled, so the terminal shows one clean line.
SHIM_FETCH_OUT=""; SHIM_FETCH_PREFIX=""; SHIM_FETCH_TOTAL=""; SHIM_FETCH_PID=""

shim_fetch_start() { # $1 = url, $2 = out file, $3 = live text prefix
  SHIM_FETCH_OUT="$2"; SHIM_FETCH_PREFIX="$3"
  SHIM_FETCH_TOTAL="$(curl -fsSIL --max-time 15 "$1" 2>/dev/null \
    | grep -i '^content-length:' | tail -1 | tr -d '\r' | awk '{print $2}')"
  case "$SHIM_FETCH_TOTAL" in ''|*[!0-9]*) SHIM_FETCH_TOTAL="" ;; esac
  # Fresh rate/paint state per download — a second fetch must not
  # inherit the previous one's sliding window or repaint guard.
  SHIM_F_PREV_MS=0; SHIM_F_PREV_B=0
  SHIM_F_RATE=0
  SHIM_F_PAINT_B=""; SHIM_F_PAINT_MS=0
  SHIM_F_START_MS=0; SHIM_F_START_B=0
  # No timeout on this curl = a stalled carrier connection wedges the
  # caller in `wait` forever (a live phone ran `wearsic update` for 20+
  # minutes with 0% CPU while the front door churned). A stall must make
  # curl EXIT: --speed-limit/--speed-time abort a transfer that stops
  # delivering data, --connect-timeout/--max-time cap the rest. 1800 s is
  # generous for a ~30 MB zip on a slow hotspot; healthy links finish in
  # a minute or two. shim_fetch_wait adds a shell-side ceiling too.
  curl -fL --retry 3 --retry-delay 2 \
    --connect-timeout 20 --max-time 1800 \
    --speed-limit 1024 --speed-time 30 \
    -o "$2" "$1" >/dev/null 2>&1 &
  SHIM_FETCH_PID=$!
}

# The animation hook: the download bar paints ITSELF here instead of pushing
# text into the generic shimmer. Why: the old path re-rendered gray letters
# character-by-character every frame with several forks, which on a phone
# read as blinking. This frame is a SINGLE atomic `\r\e[K` write with a green
# gradient fill, a partial-cell head (▏▎…▉) so the bar climbs smoothly, a
# fixed-width tail (no wobble), and a byte rate from $EPOCHREALTIME — zero
# forks per frame. Sets SHIM_HOOK_DREW=1 so shim_animate skips the shimmer
# for this tick (and remembers a live line is on screen for its cleanup).
shim_fetch_progress() {
  SHIM_HOOK_DREW=1
  [ "${TTY_OUT:-0}" = "1" ] || { SHIM_HOOK_DREW=0; return 0; }
  local have=0 cells=24 pct=0 filled=0 prog=0 frac=0 i=0
  local bar="" track="" tc="" fillcol pc
  local line pp havetxt tottext ratetxt label budget plain
  [ -n "$SHIM_FETCH_PID" ] || return 0
  if [ -e "$SHIM_FETCH_OUT" ]; then
    have="$(wc -c < "$SHIM_FETCH_OUT" 2>/dev/null)" || have=0
  fi
  case "$have" in ''|*[!0-9]*) have=0 ;; esac

  # Byte rate over a sliding ~2-frame window; the first frame just seeds it.
  shim_now_ms
  if [ "${SHIM_F_PREV_MS:-0}" = "0" ]; then
    SHIM_F_PREV_MS="$SHIM_MS"; SHIM_F_PREV_B="$have"
    SHIM_F_START_MS="$SHIM_MS"; SHIM_F_START_B=0
  elif [ "$SHIM_MS" -gt "${SHIM_F_PREV_MS:-0}" ] && [ "$have" != "${SHIM_F_PREV_B:-0}" ]; then
    SHIM_F_RATE=$(( ( have - SHIM_F_PREV_B ) * 1000 / ( SHIM_MS - SHIM_F_PREV_MS ) ))
    [ "${SHIM_F_RATE:-0}" -lt 0 ] && SHIM_F_RATE=0
    SHIM_F_PREV_MS="$SHIM_MS"; SHIM_F_PREV_B="$have"
  fi
  # Repaint only when the picture moved, or half a second passed (stalls
  # stay alive on screen without burning terminal I/O).
  if [ "$have" != "${SHIM_F_PAINT_B:-}" ] \
     || [ -z "${SHIM_F_PAINT_MS:-}" ] \
     || [ "$(( SHIM_MS - SHIM_F_PAINT_MS ))" -ge 500 ]; then
    SHIM_F_PAINT_B="$have"; SHIM_F_PAINT_MS="$SHIM_MS"
  else
    return 0
  fi

  shim_fmt_rate "${SHIM_F_RATE:-0}"; ratetxt="$SHIM_FMT"
  case "$SHIM_DEPTH" in
    24|256) tc="${SHIM_ESC}[38;5;240m"; pc="" ;;
    *)      tc=""; pc="" ;;
  esac

  if [ -n "$SHIM_FETCH_TOTAL" ] && [ "$SHIM_FETCH_TOTAL" -gt 0 ]; then
    pct=$(( have * 100 / SHIM_FETCH_TOTAL )); [ "$pct" -gt 100 ] && pct=100
    prog=$(( have * cells * 100 / SHIM_FETCH_TOTAL ))
    [ "$prog" -gt $(( cells * 100 )) ] && prog=$(( cells * 100 ))
    filled=$(( prog / 100 )); frac=$(( prog % 100 ))
    while [ "$i" -lt "$filled" ]; do
      case "$SHIM_DEPTH" in
        256) fillcol="${SHIM_ESC}[38;5;$(( 114 + i * 43 / (cells - 1) ))m" ;;
        24)  pc=$(( i * 100 / (cells - 1) ))
             fillcol="${SHIM_ESC}[38;2;$(( 104 + 86 * pc / 100 ));$(( 232 + 18 * pc / 100 ));$(( 150 + 55 * pc / 100 ))m" ;;
        *)   fillcol="" ;;
      esac
      bar="${bar}${fillcol}█"
      i=$(( i + 1 ))
    done
    if [ "$frac" -gt 0 ] && [ "$filled" -lt "$cells" ]; then
      hb=$(( (frac * 7 + 50) / 100 )); [ "$hb" -eq 0 ] && hb=1
      # Byte-safe partial-cell head: case mapping instead of substring
      # slicing — a C-locale bash counts BYTES in ${var:$n:1}, which would
      # slice a middle byte out of ▌ and corrupt the frame.
      case "$hb" in
        1) hc='▏' ;; 2) hc='▎' ;; 3) hc='▍' ;; 4) hc='▌' ;;
        5) hc='▋' ;; 6) hc='▊' ;; 7) hc='▉' ;; *) hc="" ;;
      esac
      case "$SHIM_DEPTH" in
        256) bar="${bar}${SHIM_ESC}[38;5;$(( 114 + filled * 43 / (cells - 1) ))m${hc}" ;;
        24)  pc=$(( filled * 100 / (cells - 1) ))
             bar="${bar}${SHIM_ESC}[38;2;$(( 104 + 86 * pc / 100 ));$(( 232 + 18 * pc / 100 ));$(( 150 + 55 * pc / 100 ))m${hc}" ;;
        *)   bar="${bar}${hc}" ;;
      esac
      i=$(( filled + 1 ))
    else
      i="$filled"
    fi
    while [ "$i" -lt "$cells" ]; do
      track="${track}${tc}░"
      i=$(( i + 1 ))
    done
    case "$pct" in [0-9]) pp="  $pct" ;; [0-9][0-9]) pp=" $pct" ;; *) pp="$pct" ;; esac
    shim_fmt_bytes "$have"; havetxt="$SHIM_FMT"
    shim_fmt_bytes "$SHIM_FETCH_TOTAL"; tottext="$SHIM_FMT"
    case "$SHIM_DEPTH" in
      24|256) bar="${bar}${tc}${track}${SHIM_ESC}[0m" ;;
      *)      bar="${bar}${track}" ;;
    esac
    line="${SHIM_FETCH_PREFIX}  [${bar}]${pp}%"
    core=$(( ${#SHIM_FETCH_PREFIX} + 31 ))
  else
    shim_fmt_bytes "$have"; havetxt="$SHIM_FMT"
    line="${SHIM_FETCH_PREFIX}  ${havetxt}"
    core=$(( ${#SHIM_FETCH_PREFIX} + 2 + ${#havetxt} ))
  fi

  # Phone-width safety (portrait Termux ≈ 55 cols): the prefix + bar + % are
  # sacred; the size pair survives next; the rate is the first to be shed;
  # the prefix itself is truncated only as a last resort. One line per frame
  # — a wrapped frame is what looked like blinking.
  budget="${COLUMNS:-80}"
  case "$budget" in ''|*[!0-9]*) budget=80 ;; esac
  [ "$budget" -lt 24 ] && budget=24
  local szkeep=0 rtkeep=0 cut nlen
  if [ -n "${tottext:-}" ]; then
    szlen="$(( 5 + ${#havetxt} + 3 + ${#tottext} ))"
    rtlen=$(( 5 + ${#ratetxt} ))
    if [ $(( core + szlen + rtlen )) -le "$budget" ]; then
      szkeep=1; rtkeep=1; core=$(( core + szlen + rtlen ))
    elif [ $(( core + szlen )) -le "$budget" ]; then
      szkeep=1; core=$(( core + szlen ))
    fi
    [ "$szkeep" = "1" ] && line="${line}  ·  ${havetxt} / ${tottext}"
    [ "$rtkeep" = "1" ] && line="${line}  ·  ${ratetxt}"
  else
    rtlen=$(( 5 + ${#ratetxt} ))
    if [ $(( core + rtlen )) -le "$budget" ]; then
      rtkeep=1; core=$(( core + rtlen ))
    fi
    [ "$rtkeep" = "1" ] && line="${line}  ·  ${ratetxt}"
  fi
  if [ "$core" -gt "$budget" ]; then
    cut=$(( core - budget + 1 ))
    nlen=${#SHIM_FETCH_PREFIX}
    [ "$cut" -gt $(( nlen - 1 )) ] && cut=$(( nlen - 1 ))
    [ "$cut" -lt 0 ] && cut=0
    line="…${line:$cut}"
  fi
  printf '\r%s[K%s' "$SHIM_ESC" "$line"
}

# The last frame: refill the bar at 100%, park a ✓ on it, then a newline so
# the stage's own summary lands on a fresh line. Failures clear the line
# instead — the stage machinery speaks there.
# The last frame: refill the bar at 100% and park a green ✓ on it, then a
# newline so the stage's own summary lands on a fresh line. The width budget
# sheds sizes, speed and seconds the same way the live bar does, so the
# finish never wraps on a narrow phone. Failures wipe the line instead —
# the stage machinery speaks there.
shim_fetch_done() {
  local have=0 rate=0 secs=0 i=0 fc f tc="" bar="" cells=24 line core cut nlen
  local havetxt tottext="" ratetxt budget shown=0 szlen
  [ "${TTY_OUT:-0}" = "1" ] || return 0
  [ -n "$SHIM_FETCH_OUT" ] || return 0
  if [ -e "$SHIM_FETCH_OUT" ]; then
    have="$(wc -c < "$SHIM_FETCH_OUT" 2>/dev/null)" || have=0
  fi
  case "$have" in ''|*[!0-9]*) have=0 ;; esac
  shim_now_ms
  if [ "${SHIM_F_START_MS:-0}" != "0" ] && [ "$SHIM_MS" -gt "$SHIM_F_START_MS" ]; then
    rate=$(( have * 1000 / ( SHIM_MS - SHIM_F_START_MS ) ))
    secs=$(( ( SHIM_MS - SHIM_F_START_MS + 500 ) / 1000 ))
  fi
  shim_fmt_rate "$rate"; ratetxt="$SHIM_FMT"
  shim_fmt_bytes "$have"; havetxt="$SHIM_FMT"
  budget="${COLUMNS:-80}"
  case "$budget" in ''|*[!0-9]*) budget=80 ;; esac
  [ "$budget" -lt 24 ] && budget=24
  if [ -n "$SHIM_FETCH_TOTAL" ] && [ "$SHIM_FETCH_TOTAL" -gt 0 ]; then
    shim_fmt_bytes "$SHIM_FETCH_TOTAL"; tottext="$SHIM_FMT"
    while [ "$i" -lt "$cells" ]; do
      case "$SHIM_DEPTH" in
        256) f="${SHIM_ESC}[38;5;$(( 114 + i * 43 / (cells - 1) ))m" ;;
        24)  fc=$(( i * 100 / (cells - 1) ))
             f="${SHIM_ESC}[38;2;$(( 104 + 86 * fc / 100 ));$(( 232 + 18 * fc / 100 ));$(( 150 + 55 * fc / 100 ))m" ;;
        *)   f="" ;;
      esac
      bar="${bar}${f}█"
      i=$(( i + 1 ))
    done
    case "$SHIM_DEPTH" in 24|256) bar="${bar}${SHIM_ESC}[0m" ;; esac
    line="${SHIM_FETCH_PREFIX}  [${bar}] 100%"
    core=$(( ${#SHIM_FETCH_PREFIX} + 2 + 26 + 5 ))
  else
    line="${SHIM_FETCH_PREFIX}  ${havetxt}"
    core=$(( ${#SHIM_FETCH_PREFIX} + 2 + ${#havetxt} ))
  fi
  # The ✓ is the payoff — always shown; the stats yield to the width.
  line="${line}  ${SHIM_OK:-}✓${SHIM_RESET:-}"
  core=$(( core + 3 ))
  if [ -n "$tottext" ]; then
    szlen=$(( 5 + ${#havetxt} + 3 + ${#tottext} ))
    if [ $(( core + szlen )) -le "$budget" ]; then
      line="${line}  ·  ${havetxt} / ${tottext}"
      core=$(( core + szlen ))
    fi
  fi
  if [ $(( core + 9 + ${#ratetxt} )) -le "$budget" ]; then
    line="${line}  ·  ${ratetxt} avg"
    core=$(( core + 9 + ${#ratetxt} ))
    shown=1
  fi
  if [ "$shown" = "1" ] && [ $(( core + 6 + ${#secs} )) -le "$budget" ]; then
    line="${line}  ·  ${secs}s"
    core=$(( core + 6 + ${#secs} ))
  fi
  if [ "$core" -gt "$budget" ]; then
    cut=$(( core - budget + 1 ))
    nlen=${#SHIM_FETCH_PREFIX}
    [ "$cut" -gt $(( nlen - 1 )) ] && cut=$(( nlen - 1 ))
    [ "$cut" -lt 0 ] && cut=0
    line="…${line:$cut}"
  fi
  printf '\r%s[K%s\n' "$SHIM_ESC" "$line"
}

shim_fetch_wait() {  # optional $1 = hard ceiling in seconds
  local rc=0 waited=0 limit="${1:-}"
  [ -n "$SHIM_FETCH_PID" ] || return 1
  # Never wait on a transfer FOREVER. curl now aborts stalled transfers
  # itself; this shell-side ceiling is the second belt (and the only one
  # if the pid somehow outlives the transfer). On overflow the transfer
  # is killed and the wait fails with 124 — `wearsic update` can then
  # report a stalled download instead of hanging.
  case "$limit" in
    ''|*[!0-9]*) limit=$(( ${SHIM_FETCH_MAX_WAIT:-1800} + 60 )) ;;
  esac
  while kill -0 "$SHIM_FETCH_PID" 2>/dev/null; do
    if [ "$waited" -ge "$limit" ]; then
      kill "$SHIM_FETCH_PID" 2>/dev/null || true
      sleep 1
      kill -9 "$SHIM_FETCH_PID" 2>/dev/null || true
      wait "$SHIM_FETCH_PID" 2>/dev/null || true
      SHIM_FETCH_PID=""
      # Wipe the mid-flight bar so the caller's error line starts clean.
      [ "${TTY_OUT:-0}" = "1" ] && { printf '\r%s[K\n' "$SHIM_ESC"; SHIM_LIVE_ACTIVE=0; }
      return 124
    fi
    sleep 1
    waited=$(( waited + 1 ))
  done
  wait "$SHIM_FETCH_PID"; rc=$?
  SHIM_FETCH_PID=""
  if [ "$rc" = "0" ]; then
    shim_fetch_done
  else
    # Wipe the mid-flight bar so the stage's own error line starts clean.
    [ "${TTY_OUT:-0}" = "1" ] && { printf '\r%s[K\n' "$SHIM_ESC"; SHIM_LIVE_ACTIVE=0; }
  fi
  return "$rc"
}

# ── stage machine ─────────────────────────────────────────────────────────
# One quiet line per stage. The ACTIVE stage shimmers in place; a finished
# stage collapses into a single line that is printed exactly once.
shim_stage_begin() { # $1 = stage name, $2 = detail (optional)
  SHIM_STAGE_NAME="$1"
  SHIM_STAGE_DETAIL="${2:-}"
  SHIM_LIVE_TEXT="$1"
  [ -n "$SHIM_STAGE_DETAIL" ] && SHIM_LIVE_TEXT="$1  $SHIM_STAGE_DETAIL"
  [ "$SHIM_DEPTH" = "0" ] && return 0
  shim_cursor_hide
  shim_draw_live 0
}

shim_stage_status() { # $1 = detail
  SHIM_STAGE_DETAIL="$1"
  SHIM_LIVE_TEXT="$SHIM_STAGE_NAME"
  [ -n "$SHIM_STAGE_DETAIL" ] && SHIM_LIVE_TEXT="$SHIM_STAGE_NAME  $SHIM_STAGE_DETAIL"
}

# The stage line without the wave — drawn between two animation segments so
# the line never blinks out of existence.
shim_stage_plain() {
  [ "$SHIM_DEPTH" = "0" ] && return 0
  [ "$TTY_OUT" = "1" ] || return 0
  printf '\r%s[K  %s%s%s' "$SHIM_ESC" "$SHIM_DIM" "$SHIM_LIVE_TEXT" "$SHIM_RESET"
  SHIM_LIVE_ACTIVE=1
}

shim_stage_end() { # $1 = ok|fail, $2 = detail (optional)
  local kind="$1" detail="${2:-$SHIM_STAGE_DETAIL}"
  if [ "$SHIM_DEPTH" != "0" ]; then
    shim_clear_live
    shim_cursor_show
  fi
  if [ "$kind" = "ok" ]; then
    printf '  %s✓%s %s%s%s' "$SHIM_OK" "$SHIM_RESET" "$SHIM_DIM" "$SHIM_STAGE_NAME" "$SHIM_RESET"
  else
    printf '  %s✗%s %s%s%s' "$SHIM_ERR" "$SHIM_RESET" "$SHIM_DIM" "$SHIM_STAGE_NAME" "$SHIM_RESET"
  fi
  if [ -n "$detail" ]; then
    printf '  %s%s%s' "$SHIM_DIM" "$detail" "$SHIM_RESET"
  fi
  printf '\n'
  SHIM_STAGE_NAME=""; SHIM_STAGE_DETAIL=""
}

# A whole stage in one call: shimmer the name while the command works, then
# print the single result line. The command's output is captured.
shim_stage_run() { # $1 = stage name, $2 = detail, rest = command
  local name="$1" detail="$2"; shift 2
  local log rc=0
  log="$(mktemp "${TMPDIR:-/tmp}/wearsic-shim.XXXXXX" 2>/dev/null)" || log="/dev/null"
  shim_stage_begin "$name" "$detail"
  if [ "$SHIM_DEPTH" != "0" ] && [ "$TTY_OUT" = "1" ]; then
    "$@" >"$log" 2>&1 &
    local pid=$!
    shim_animate "$pid"
    wait "$pid"; rc=$?
    shim_clear_live
  else
    "$@" >"$log" 2>&1
    rc=$?
  fi
  if [ "$rc" = "0" ]; then
    shim_stage_end ok "$detail"
  else
    shim_stage_end fail "$detail"
    shim_log_tail "$log" 12
  fi
  [ "$log" = "/dev/null" ] || rm -f "$log" 2>/dev/null
  return "$rc"
}

# The animation is opt-in per terminal: a real TTY with colour support gets
# the shimmer, everything else (pipes, CI logs, NO_COLOR) stays plain.
shim_init
# ── WSHIM-CORE-END ─────────────────────────────────────────────────────────
# Install a file ATOMICALLY: copy to a sibling temp, then rename into place.
# (Same helper as the wearsic CLI — keep the two in sync.) A plain `cp` over
# a script bash is currently executing truncates that inode, and the running
# shell dies parsing garbage; a rename swaps the inode instead, leaving the
# running process its old content, intact.
replace_file() { # $1 = source file, $2 = destination file
  local src="$1" dst="$2" tmp
  tmp="$(mktemp "$(dirname "$dst")/.wearsic-new.XXXXXX")" || return 1
  if cp "$src" "$tmp" && mv -f "$tmp" "$dst"; then
    return 0
  fi
  rm -f "$tmp" 2>/dev/null
  return 1
}



# Rounded box for the pairing card / big announcements. Buffered: content
# decides the width, so long URLs can never stick out past the border.
BOX_TITLE=""; BOX_BODY=""; BOX_N=0
box_begin() { BOX_TITLE="$1"; BOX_BODY=""; BOX_N=0; }
box_line() {
  if [ "$BOX_N" = "0" ]; then BOX_BODY="$1"; else BOX_BODY="$BOX_BODY
$1"; fi
  BOX_N=$((BOX_N + 1))
}
# Word-wrap plain text to a column budget: rows break at spaces so words
# stay whole; a token longer than the box itself is still hard-split.
# (The old body chunker cut mid-word — "doctor" became "d" + "octor".)
box_words() { # $1 = width, $2 = plain text -> one row per line
  local budget="$1" cur="" piece
  for piece in $2; do
    if [ -z "$cur" ]; then
      while [ "${#piece}" -gt "$budget" ]; do
        printf '%s\n' "$(printf '%s' "$piece" | cut -c1-"$budget")"
        piece="$(printf '%s' "$piece" | cut -c$((budget + 1))-)"
      done
      cur="$piece"
    elif [ $(( ${#cur} + 1 + ${#piece} )) -le "$budget" ]; then
      cur="$cur $piece"
    else
      printf '%s\n' "$cur"
      while [ "${#piece}" -gt "$budget" ]; do
        printf '%s\n' "$(printf '%s' "$piece" | cut -c1-"$budget")"
        piece="$(printf '%s' "$piece" | cut -c$((budget + 1))-)"
      done
      cur="$piece"
    fi
  done
  [ -n "$cur" ] && printf '%s\n' "$cur"
}
box_end() {
  local w line plain spaces i
  local cols="${COLS:-${COLUMNS:-80}}"
  case "$cols" in ''|*[!0-9]*) cols=80 ;; esac
  [ "$cols" -lt 30 ] && cols=30
  local maxw=$(( cols - 6 ))
  w=${#BOX_TITLE}
  local wrapped=""
  while IFS= read -r line; do
    plain="$(printf '%s' "$line" | sed "s/${SHIM_ESC}\[[0-9;]*m//g")"
    if [ "${#plain}" -le "$maxw" ]; then
      wrapped="$wrapped
$line"
    else
      # Wrap at word boundaries (line content prints plain — the handful
      # of wrapped rows prefers whole words over kept colouring), indented
      # to line up with the body's spacing.
      while IFS= read -r chunk; do
        [ -n "$chunk" ] || continue
        wrapped="$wrapped
   $chunk"
      done < <(box_words "$maxw" "$plain")
    fi
  done <<EOF
$BOX_BODY
EOF
  BOX_BODY="$(printf '%s' "$wrapped" | tail -n +2)"
  w=${#BOX_TITLE}
  while IFS= read -r line; do
    plain="$(printf '%s' "$line" | sed "s/${SHIM_ESC}\[[0-9;]*m//g")"
    [ "${#plain}" -gt "$w" ] && w=${#plain}
  done <<EOF
$BOX_BODY
EOF
  [ "$w" -lt 20 ] && w=20
  # Top border: `╭─ title ` + dashes + `╮` = w + 4 columns, exactly as wide
  # as the body rows so the whole box shares ONE right edge.
  printf "${C_ACC}╭─ %s " "$BOX_TITLE"
  i=$(( ${#BOX_TITLE} + 4 )); while [ "$i" -lt "$((w + 3))" ]; do printf '─'; i=$((i + 1)); done
  printf "${C_ACC}╮${C_RESET}\n"
  while IFS= read -r line; do
    plain="$(printf '%s' "$line" | sed "s/${SHIM_ESC}\[[0-9;]*m//g")"
    spaces=$(( w - ${#plain} ))
    printf "${C_ACC}│${C_RESET} %s" "$line"
    i=0; while [ "$i" -lt "$spaces" ]; do printf ' '; i=$((i + 1)); done
    printf " ${C_ACC}│${C_RESET}\n"
  done <<EOF
$BOX_BODY
EOF
  printf "${C_ACC}╰"
  # Bottom border: `╰` + (w + 2) dashes + `╯` = w + 4 columns, the same as
  # every row above it. (It used to print w + 3 dashes and no corner — the
  # bottom-right edge visibly stopped short.)
  i=0; while [ "$i" -lt "$((w + 2))" ]; do printf '─'; i=$((i + 1)); done
  printf "${C_ACC}╯${C_RESET}\n"
  BOX_TITLE=""; BOX_BODY=""; BOX_N=0
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
  # Best-effort LAN IPv4 — keep in sync with `lan_ip` in the wearsic CLI.
  # Termux ships neither iproute2 nor net-tools, and recent Android removed
  # the old dhcp.*.ipaddress properties, so every probe we know is tried.
  local ip="" iface sys gp
  iface="$(awk '$2 == "00000000" && $1 != "lo" { print $1; exit }' "${WEARSIC_PROC_NET:-/proc/net}/route" 2>/dev/null)"
  sys="${WEARSIC_SYS_BIN:-/system/bin}"

  if command -v termux-wifi-connectioninfo >/dev/null 2>&1; then
    ip="$(termux-wifi-connectioninfo 2>/dev/null \
      | sed -n 's/.*"ip"[[:space:]]*:[[:space:]]*"\([0-9.]\{7,\}\)".*/\1/p' | head -1)"
  fi
  if [ -z "$ip" ] && command -v ip >/dev/null 2>&1; then
    ip="$(ip -4 -o addr show wlan0 2>/dev/null | sed -n 's/.*inet \([0-9.]\+\).*/\1/p' | head -1)"
    if [ -z "$ip" ] && [ -n "$iface" ]; then
      ip="$(ip -4 -o addr show "$iface" 2>/dev/null | sed -n 's/.*inet \([0-9.]\+\).*/\1/p' | head -1)"
    fi
  fi
  if [ -z "$ip" ] && command -v ifconfig >/dev/null 2>&1; then
    ip="$(ifconfig wlan0 2>/dev/null | sed -n 's/.*inet addr:\([0-9.]\+\).*/\1/p; s/.*inet \([0-9.]\+\).*/\1/p' | head -1)"
    if [ -z "$ip" ] && [ -n "$iface" ]; then
      ip="$(ifconfig "$iface" 2>/dev/null | sed -n 's/.*inet addr:\([0-9.]\+\).*/\1/p; s/.*inet \([0-9.]\+\).*/\1/p' | head -1)"
    fi
  fi
  if [ -z "$ip" ] && [ -x "$sys/ip" ]; then
    ip="$("$sys/ip" -4 -o addr show wlan0 2>/dev/null | sed -n 's/.*inet \([0-9.]\+\).*/\1/p' | head -1)"
    if [ -z "$ip" ] && [ -n "$iface" ]; then
      ip="$("$sys/ip" -4 -o addr show "$iface" 2>/dev/null | sed -n 's/.*inet \([0-9.]\+\).*/\1/p' | head -1)"
    fi
  fi
  if [ -z "$ip" ] && [ -x "$sys/ifconfig" ]; then
    ip="$("$sys/ifconfig" wlan0 2>/dev/null | sed -n 's/.*inet addr:\([0-9.]\+\).*/\1/p; s/.*inet \([0-9.]\+\).*/\1/p' | head -1)"
    if [ -z "$ip" ] && [ -n "$iface" ]; then
      ip="$("$sys/ifconfig" "$iface" 2>/dev/null | sed -n 's/.*inet addr:\([0-9.]\+\).*/\1/p; s/.*inet \([0-9.]\+\).*/\1/p' | head -1)"
    fi
  fi
  gp=""
  if command -v getprop >/dev/null 2>&1; then gp="getprop"
  elif [ -x "$sys/getprop" ]; then gp="$sys/getprop"; fi
  if [ -z "$ip" ] && [ -n "$gp" ]; then
    ip="$("$gp" dhcp.wlan0.ipaddress 2>/dev/null | tr -d '\r\n')"
    if [ -z "$ip" ]; then
      ip="$("$gp" 2>/dev/null \
        | sed -n 's/^\[[^]]*[Ww][Ll][Aa][Nn][^]]*\.ipaddress\]: \[\([0-9.]*\)\]$/\1/p' | head -1)"
    fi
  fi
  if [ -z "$ip" ] && [ -n "$iface" ]; then
    ip="$(awk '/32 host/ { print f } { f = $2 }' "${WEARSIC_PROC_NET:-/proc/net}/fib_trie" 2>/dev/null \
      | grep -E '^(10\.|172\.(1[6-9]|2[0-9]|3[01])\.|192\.168\.)' | head -1)"
  fi

  if printf '%s' "$ip" | grep -qE '^([0-9]{1,3}\.){3}[0-9]{1,3}$'; then
    printf '%s' "$ip"
  fi
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
# Sets EFFECTIVE_KEY and API_KEY_STATE (for the stage line).
resolve_effective_key() {
  local k
  k="$(env_value WEARSIC_API_KEY)"
  if [ -n "$k" ]; then
    API_KEY_STATE="existing API key kept"
  elif [ -n "${WEARSIC_API_KEY:-}" ]; then
    k="$WEARSIC_API_KEY"
    write_env_value WEARSIC_API_KEY "$k"
    API_KEY_STATE="API key taken from WEARSIC_API_KEY"
  else
    k="$(generate_api_key)"
    write_env_value WEARSIC_API_KEY "$k"
    API_KEY_STATE="API key created"
  fi
  EFFECTIVE_KEY="$k"
}

port() { local p; p="$(env_value PORT)"; [ -n "$p" ] || p="8080"; printf '%s' "$p"; }

health_ok() { curl -sf -m 5 "http://127.0.0.1:$(port)/health" >/dev/null 2>&1; }

# ── the seven stages ──────────────────────────────────────────────────────
#   Download → Install → Configure → Build → Start → Verify → Ready
#
# The ACTIVE stage shimmers in place: a soft gray wave travels across the
# line while the work happens. A finished stage collapses into ONE quiet
# line, printed exactly once. Sub-process output is captured, so the
# terminal is never flooded with duplicate logs — only a failure shows what
# actually went wrong.

SRC=""
ZIP_URL=""
SHA_URL=""
EFFECTIVE_KEY=""
API_KEY_STATE=""
PKG_PID=0
PKG_NEED=""
# WEARSIC_VERBOSE=1 keeps the captured output of each step (the quiet
# default is the point: the terminal shows stages, not logs).
STAGE_VERBOSE="${WEARSIC_VERBOSE:-0}"

# Run a command with the stage line shimmering meanwhile. Its output is
# captured (never sprayed over the terminal).
stage_cmd() { # $1 = command...
  "$@" >"$WORK/stage.log" 2>&1 &
  local pid=$! rc=0
  shim_animate "$pid"
  wait "$pid"; rc=$?
  shim_stage_plain
  if [ "$STAGE_VERBOSE" = "1" ]; then
    shim_log_tail "$WORK/stage.log" 8
  fi
  return "$rc"
}

# A failed stage: the stage line turns into a failure, the captured output
# is shown, and the install stops WITHOUT touching anything else.
stage_fail() { # $1 = what failed
  shim_stage_end fail "$1"
  shim_log_tail "$WORK/stage.log" 12
  exit 1
}

# ── device ────────────────────────────────────────────────────────────────
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
  printf '  %sTermux on Android (%s) — native, no root needed%s\n' \
    "$SHIM_DIM" "$ARCH_LABEL" "$SHIM_RESET"
else
  printf '  %sNot Termux (%s) — continuing (plain JVM install)%s\n' \
    "$SHIM_DIM" "$ARCH_LABEL" "$SHIM_RESET"
fi

command -v sha256sum >/dev/null 2>&1 || command -v shasum >/dev/null 2>&1 \
  || die "sha256sum is missing (install coreutils)"

# ── dependencies (installed in the BACKGROUND while the release downloads) ─
deps_start() {
  [ "$IS_TERMUX" = "1" ] || return 0
  command -v pkg >/dev/null 2>&1 || return 0
  PKG_NEED=""
  local p
  for p in openjdk-17 unzip curl; do
    pkg list-installed 2>/dev/null | grep -q "^${p}/" || PKG_NEED="$PKG_NEED $p"
  done
  [ -n "$PKG_NEED" ] || return 0
  ( pkg update -y >/dev/null 2>&1
    # shellcheck disable=SC2086
    pkg install -y $PKG_NEED >/dev/null 2>&1 ) >"$WORK/pkg.log" 2>&1 &
  PKG_PID=$!
}

deps_wait() {
  [ "$PKG_PID" != "0" ] || return 0
  shim_stage_status "waiting for the dependencies (Java is ~100MB on first install)"
  if ! wait "$PKG_PID"; then
    shim_log_tail "$WORK/pkg.log" 6
    die "Failed to install dependencies. Run: pkg update -y && pkg install -y openjdk-17 unzip curl"
  fi
  PKG_PID=0
  local p
  for p in openjdk-17 unzip curl; do
    pkg list-installed 2>/dev/null | grep -q "^${p}/" \
      || die "Dependency '$p' is still missing after install. Run: pkg install -y $p"
  done
}

# The payload sitting next to this installer (a release package): no
# download needed. Piped from GitHub or run from a checkout: download the
# latest release instead.
LOCAL_PAYLOAD=""
find_local_payload() {
  local _here _cand
  SCRIPT_PATH="${BASH_SOURCE[0]:-}"
  [ -n "$SCRIPT_PATH" ] && [ -f "$SCRIPT_PATH" ] || return 0
  _here="$(CDPATH= cd -- "$(dirname -- "$SCRIPT_PATH")" 2>/dev/null && pwd)"
  for _cand in "$_here/bin/wearsic-server" "$_here/wearsic-server/bin/wearsic-server"; do
    if [ -f "$_cand" ]; then
      LOCAL_PAYLOAD="$(dirname -- "$(dirname -- "$_cand")")"
      break
    fi
  done
}

# ── 1. Download ───────────────────────────────────────────────────────────
shim_stage_begin "Download" "release"
deps_start
find_local_payload

if [ -n "$LOCAL_PAYLOAD" ]; then
  SRC="$LOCAL_PAYLOAD"
  # The dependencies install in the background; wait for them INSIDE this
  # stage, so a first run shows the wait instead of a silent minute.
  deps_wait
  shim_stage_end ok "local package — no download needed"
else
  ZIP_URL="${WEARSIC_ZIP_URL:-}"
  SHA_URL="${WEARSIC_SHA_URL:-}"
  if [ -z "$ZIP_URL" ]; then
    shim_stage_status "finding the latest release"
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
    [ -n "$ZIP_URL" ] || stage_fail "no release found for ${REPO} — check your internet connection"
  fi
  [ -n "$SHA_URL" ] || SHA_URL="${ZIP_URL}.sha256"
  PAYLOAD_NAME="$(basename "$ZIP_URL")"

  shim_stage_status "fetching $PAYLOAD_NAME"
  ZIP="$WORK/wearsic-server.zip"
  shim_fetch_start "$ZIP_URL" "$ZIP" "Download"
  SHIM_PROGRESS_HOOK=shim_fetch_progress
  shim_animate "$SHIM_FETCH_PID"
  if ! shim_fetch_wait; then
    SHIM_PROGRESS_HOOK=""
    stage_fail "the download failed — nothing was installed"
  fi
  SHIM_PROGRESS_HOOK=""
  [ -s "$ZIP" ] || stage_fail "the downloaded file is empty — nothing was installed"

  # Authenticity: never install bytes without a published checksum.
  shim_stage_status "verifying the checksum"
  SHA="$WORK/wearsic-server.zip.sha256"
  stage_cmd curl -fsSL --retry 2 -o "$SHA" "$SHA_URL" \
    || stage_fail "no SHA-256 checksum at $SHA_URL — refusing to install unverified code"
  [ -s "$SHA" ] || stage_fail "no SHA-256 checksum at $SHA_URL — refusing to install unverified code"
  EXPECTED="$(awk '{print $1; exit}' "$SHA" | tr 'A-F' 'a-f')"
  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL="$(sha256sum "$ZIP" | awk '{print $1}')"
  else
    ACTUAL="$(shasum -a 256 "$ZIP" | awk '{print $1}')"
  fi
  [ "$EXPECTED" = "$ACTUAL" ] \
    || stage_fail "SHA-256 MISMATCH — the download is corrupt or tampered. Nothing was installed."

  deps_wait
  shim_stage_status "extracting"
  STAGE="$WORK/stage"
  mkdir -p "$STAGE"
  stage_cmd unzip -t "$ZIP" || stage_fail "the downloaded file is not a valid ZIP (truncated download?)"
  stage_cmd unzip -oq "$ZIP" -d "$STAGE" || stage_fail "could not extract the server package"
  SRC="$STAGE/wearsic-server"
  [ -d "$SRC" ] || SRC="$STAGE"
  ver="${PAYLOAD_NAME#wearsic-server-termux-v}"
  case "$ver" in *.zip) ver="${ver%.zip}" ;; *) ver="" ;; esac
  if [ -n "$ver" ]; then
    PAYLOAD_DETAIL="v$ver · checksum verified"
  else
    PAYLOAD_DETAIL="$PAYLOAD_NAME · checksum verified"
  fi
  shim_stage_end ok "$PAYLOAD_DETAIL"
fi

# The dependencies must be there before anything is unpacked or started.
[ "$PKG_PID" != "0" ] && deps_wait

# ── 2. Install ────────────────────────────────────────────────────────────
disp="$DEST"
case "$DEST" in "${HOME:-}"/*) disp="~${DEST#"$HOME"}" ;; esac
shim_stage_begin "Install" "engine into $disp"

# The package must look like a complete engine before we touch the active
# installation (same check the supervisor's updater uses).
[ -f "$SRC/bin/wearsic-server" ] || stage_fail "the package is incomplete (bin/wearsic-server missing) — nothing was changed"
[ -n "$(ls -A "$SRC/lib" 2>/dev/null)" ] || stage_fail "the package is incomplete (lib/ empty) — nothing was changed"
[ -f "$SRC/run-termux.sh" ] || stage_fail "the package is incomplete (run-termux.sh missing) — nothing was changed"
# The Cloudflare front door uploads this file verbatim. A release without it
# can install but cannot deploy, so fail BEFORE touching the live install.
[ -f "$SRC/wearsic-cloudflare-worker.js" ] || stage_fail "the package is incomplete (wearsic-cloudflare-worker.js missing) — nothing was changed"

# Preserve existing data across (re)installs. These files are NEVER deleted:
# the swap only replaces bin/ and lib/ — the code, never your state.
KEPT=""
[ -f "$DEST/wearsic.db" ]     && KEPT="wearsic.db"
[ -f "$DEST/.env" ]           && KEPT="$KEPT .env"
[ -d "$DEST/downloads" ]      && KEPT="$KEPT downloads/"

mkdir -p "$DEST" "$DEST/wearsic-state" "$DEST/downloads"

shim_stage_status "staging the engine"
rm -rf "$DEST/bin.new" "$DEST/lib.new"
stage_cmd cp -r "$SRC/bin" "$DEST/bin.new" \
  || stage_fail "could not prepare the new engine (disk full?) — the current install is untouched"
stage_cmd cp -r "$SRC/lib" "$DEST/lib.new" || {
  rm -rf "$DEST/bin.new"
  stage_fail "could not prepare the new engine (disk full?) — the current install is untouched"
}

# Swap by rename: the previous engine is kept as bin.prev/lib.prev for
# rollback until the new one passes its health check (the Start stage).
shim_stage_status "swapping the engine in"
rm -rf "$DEST/bin.prev" "$DEST/lib.prev"
if [ -d "$DEST/bin" ]; then
  mv "$DEST/bin" "$DEST/bin.prev" && mv "$DEST/lib" "$DEST/lib.prev" \
    || { rm -rf "$DEST/bin.new" "$DEST/lib.new"; stage_fail "the swap failed — the current install is untouched"; }
fi
mv "$DEST/bin.new" "$DEST/bin" && mv "$DEST/lib.new" "$DEST/lib" \
  || stage_fail "the swap failed midway — run 'wearsic update' or reinstall to recover"
[ -f "$DEST/bin/wearsic-server" ] || stage_fail "the engine swap did not complete — run 'wearsic update' to recover"

# The tunnel binary is NOT part of the engine release (bin/ ships the
# engine only): a swap over an EXISTING install would delete cloudflared
# and the running supervisor's next launch would die on a missing binary
# while minting hostnames (live outage 2026-10-22). Keep the old one
# when the new bin/ lacks it.
if [ -x "$DEST/bin.prev/cloudflared" ] && [ ! -e "$DEST/bin/cloudflared" ]; then
  cp -f "$DEST/bin.prev/cloudflared" "$DEST/bin/cloudflared" 2>/dev/null \
    && chmod 0755 "$DEST/bin/cloudflared" 2>/dev/null
fi

# Launcher + supporting files (code only — never data). run-termux.sh goes
# in atomically: re-running the installer over a LIVE server must not
# truncate the supervisor's script while bash is reading it.
shim_stage_status "installing the launcher and the command"
for f in run-termux.sh .env.example README.md wearsic-cloudflare-worker.js; do
  [ -f "$SRC/$f" ] || continue
  if [ "$f" = "run-termux.sh" ]; then
    replace_file "$SRC/$f" "$DEST/$f"
  else
    cp "$SRC/$f" "$DEST/$f"
  fi
done
chmod +x "$DEST/run-termux.sh" "$DEST/bin/wearsic-server" 2>/dev/null || true

# Install the `wearsic` command (from the staged package, before $WORK is
# wiped). Termux: $PREFIX/bin is on PATH and writable without root.
CLI_BIN=""
if [ -f "$SRC/wearsic" ]; then
  if [ -n "${PREFIX:-}" ] && [ -d "${PREFIX}/bin" ] && [ -w "${PREFIX}/bin" ]; then
    CLI_BIN="$PREFIX/bin"
  else
    CLI_BIN="$HOME/.local/bin"
    mkdir -p "$CLI_BIN"
  fi
  # replace_file (not cp): the destination may be the very script an older
  # `wearsic` process is still executing (e.g. `wearsic update` in another
  # terminal) — a truncating copy corrupts the running shell's remaining
  # lines.
  replace_file "$SRC/wearsic" "$DEST/wearsic" 2>/dev/null || true
  if replace_file "$SRC/wearsic" "$CLI_BIN/wearsic"; then
    chmod +x "$CLI_BIN/wearsic" "$DEST/wearsic" 2>/dev/null || true
  fi
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
fi
  disp="$DEST"
  case "$DEST" in "${HOME:-}"/*) disp="~${DEST#"$HOME"}" ;; esac
  shim_stage_end ok "engine in $disp"
[ -n "$KEPT" ] && printf '  %skept%s %s\n' "$SHIM_DIM" "$SHIM_RESET" "$KEPT"
if [ -n "$CLI_BIN" ]; then
  case ":$PATH:" in
    *":$CLI_BIN:"*) ;;
    *) printf '  %s~/.local/bin/wearsic is not on PATH%s\n' "$SHIM_DIM" "$SHIM_RESET"
       printf '  %srun: export PATH="$HOME/.local/bin:\\$PATH"%s\n' "$SHIM_DIM" "$SHIM_RESET" ;;
  esac
fi

# ── 3. Configure ──────────────────────────────────────────────────────────
shim_stage_begin "Configure" "settings"
# .env is only ever created or key-updated — never reset, and an existing
# custom PORT/DB path is kept exactly as the user set it.
touch "$DEST/.env"
[ -n "$(env_value PORT)" ] || write_env_value PORT "${PORT:-8080}"
[ -n "$(env_value WEARSIC_DB_PATH)" ] || write_env_value WEARSIC_DB_PATH "wearsic.db"
chmod 600 "$DEST/.env" 2>/dev/null || true
resolve_effective_key
shim_stage_end ok "$API_KEY_STATE"

# Ownership hygiene: the engine, the tunnel and the CLI all run UNPRIVILEGED
# (the Termux app user). When the installer is run with root — e.g. from a
# proot shell — a root-owned 0700 tree locks the app user out of its own
# install: it cannot read .env, cannot write the state directory and cannot
# exec the tunnel binary, which shows up as a tunnel that dies in seconds.
# Hand the tree back to the owner of $HOME.
if [ "$(id -u)" = "0" ] && [ -n "${HOME:-}" ] && [ -d "$HOME" ]; then
  OWNER_UID="$(stat -c %u "$HOME" 2>/dev/null || echo 0)"
  OWNER_GID="$(stat -c %g "$HOME" 2>/dev/null || echo 0)"
  if [ "$OWNER_UID" != "0" ]; then
    chown -R "$OWNER_UID:$OWNER_GID" "$DEST" 2>/dev/null || true
    for f in "$CLI_BIN/wearsic" "$DEST/wearsic" "$HOME/.termux/boot/start-wearsic.sh"; do
      [ -e "$f" ] && chown "$OWNER_UID:$OWNER_GID" "$f" 2>/dev/null || true
    done
    chmod 755 "$DEST/bin/cloudflared" 2>/dev/null || true
  fi
fi

# ── 4. Build ──────────────────────────────────────────────────────────────
shim_stage_begin "Build" "engine"
# A release ships a prebuilt engine; a source checkout is compiled here.
if [ -x "$DEST/bin/wearsic-server" ]; then
  shim_stage_end ok "prebuilt engine — nothing to compile"
elif [ -f "$SRC/gradlew" ]; then
  shim_stage_status "compiling the engine (first build takes a while)"
  stage_cmd bash "$SRC/gradlew" -p "$SRC" :wearsic-server:installDist \
    || stage_fail "the engine build failed"
  shim_stage_end ok "engine built from source"
else
  shim_stage_end ok "prebuilt engine"
fi

# ── 5. Start ──────────────────────────────────────────────────────────────
if [ "${WEARSIC_NO_START:-0}" = "1" ]; then
  shim_stage_begin "Start" "server"
  shim_stage_end ok "skipped (WEARSIC_NO_START=1)"
else
  shim_stage_begin "Start" "server"
  # Restart cleanly on upgrades so the new engine actually runs.
  if health_ok; then
    shim_stage_status "restarting into the new engine"
    [ -x "$DEST/wearsic" ] && bash "$DEST/wearsic" stop >/dev/null 2>&1
    pkill -f "com.wearsic.server.ApplicationKt" 2>/dev/null
    sleep 1
  fi
  ( cd "$DEST" && nohup bash "$DEST/run-termux.sh" >/dev/null 2>&1 & )
  shim_stage_status "waiting for the first boot (a cold JVM takes a while on a phone)"
  shim_wait "Start  waiting for the first boot" 180 "health_ok" \
    || stage_fail "the server did not become healthy in time"
  shim_stage_end ok "listening on port $(port)"
fi

# ── 6. Verify ─────────────────────────────────────────────────────────────
shim_stage_begin "Verify" "the API"
shim_stage_status "checking health and the API key"
VERIFY_STATE="health ok"
CODE="$(curl -s -o /dev/null -w '%{http_code}' -m 10 \
  -H "X-Wearsic-Key: $EFFECTIVE_KEY" \
  "http://127.0.0.1:$(port)/api/frontdoor/probe" 2>/dev/null || echo 000)"
case "$CODE" in
  200|206) VERIFY_STATE="health ok · api key accepted" ;;
  401|403) stage_fail "the engine rejected the API key (an older server may still be running: wearsic stop)" ;;
  404)     VERIFY_STATE="health ok" ;;
  *)       VERIFY_STATE="health ok" ;;
esac
READY="$(curl -sf -m 5 "http://127.0.0.1:$(port)/ready" 2>/dev/null || true)"
if printf '%s' "$READY" | grep -q '"ready":true'; then
  VERIFY_STATE="$VERIFY_STATE · ready to serve music"
fi
shim_stage_end ok "$VERIFY_STATE"

# The new engine is healthy — the rollback copy is no longer needed.
rm -rf "$DEST/bin.prev" "$DEST/lib.prev"

# ── 7. Ready ──────────────────────────────────────────────────────────────
LAN_IP="$(detect_lan_ip)"
SERVER_URL=""
[ -n "$LAN_IP" ] && SERVER_URL="http://${LAN_IP}:$(port)"

shim_stage_begin "Ready" "connect the watch"
printf '\n'
box_begin "Wearsic server is ready"
box_line ""
if [ -n "$SERVER_URL" ]; then
  box_line "   ${C_BOLD}Server :${C_RESET} $SERVER_URL"
else
  # No placeholder: `http://<phone-IP>:8080` looks typeable — users typed it.
  box_line "   ${C_BOLD}Server :${C_RESET} ${C_YELLOW}couldn't detect this phone's IP${C_RESET}"
  box_line "   ${C_DIM}is Wi-Fi on? check Android Settings, then: wearsic url${C_RESET}"
fi
box_line "   ${C_BOLD}Key    :${C_RESET} $EFFECTIVE_KEY"
box_line ""
box_line "   On the watch, open Wearsic → Settings,"
box_line "   type the Server line, then the Key line."
box_line ""
box_line "   ${C_DIM}Again anytime:  wearsic url    ·    wearsic doctor${C_RESET}"
box_end
shim_stage_end ok "wearsic url · wearsic doctor"
printf '\n'
say "Manage it:  wearsic status | doctor | help"

# Optional: the permanent public URL in the same run.
if [ "${WEARSIC_CLOUDFLARE:-0}" = "1" ]; then
  if [ -x "$DEST/wearsic" ]; then
    say ""
    bash "$DEST/wearsic" cloudflare
  else
    warn "WEARSIC_CLOUDFLARE=1 was requested, but the wearsic command is missing — run: wearsic cloudflare"
  fi
fi
