#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Wearsic server installer (compatibility shim).
#
# The canonical one-command installer now lives at the repository root:
#
#   curl -fsSL https://raw.githubusercontent.com/Aeroflash-r1/wearsic/main/install.sh | bash
#
# This shim keeps the OLD documented URL working by forwarding to it. If the
# repository root is available next to this file (git checkout: ../install.sh),
# that copy runs directly; otherwise the root installer is fetched from
# GitHub and executed. All behaviour lives in the root install.sh.
# ─────────────────────────────────────────────────────────────────────────────
set -u

HERE="$(CDPATH= cd -- "$(dirname -- "$0")" 2>/dev/null && pwd)"
ROOT_INSTALLER="${WEARSIC_ROOT_INSTALLER:-}"

if [ -z "$ROOT_INSTALLER" ] && [ -n "$HERE" ] && [ -f "$HERE/../install.sh" ]; then
  ROOT_INSTALLER="$HERE/../install.sh"
fi

if [ -n "$ROOT_INSTALLER" ] && [ -f "$ROOT_INSTALLER" ]; then
  exec bash "$ROOT_INSTALLER" "$@"
fi

URL="https://raw.githubusercontent.com/${WEARSIC_REPO:-Aeroflash-r1/wearsic}/main/install.sh"
TMP="$(mktemp)" || { echo "[wearsic] ERROR: could not create a temp file" >&2; exit 1; }
trap 'rm -f "$TMP"' EXIT
curl -fsSL --retry 3 "$URL" -o "$TMP" || {
  echo "[wearsic] ERROR: could not download the installer from $URL" >&2
  exit 1
}
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
SHIM_LIVE_TEXT=""
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
  if [ "$n" -gt "$maxw" ]; then
    text="${text:0:$(( maxw - 1 ))}…"
    n=${#text}
  fi
  half=$(( n * SHIM_BAND_PCT / 100 / 2 ))
  [ "$half" -lt 3 ] && half=3
  centre=$(( -half + (n + 2 * half) * frame / SHIM_LOOP_FRAMES ))
  printf '\r%s[K' "$SHIM_ESC"
  shim_render "$text" "$centre" "$half"
}

shim_clear_live() {
  [ "$SHIM_CURSOR_HIDDEN" = "1" ] || [ "$SHIM_DEPTH" = "0" ] && return 0
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
shim_status() { # $1 = text
  [ -n "$SHIM_STATUS_FILE" ] || return 0
  printf '%s\n' "$1" > "$SHIM_STATUS_FILE" 2>/dev/null || true
}

# Animate until the job ($1) exits.
shim_animate() { # $1 = pid
  local pid="$1" frame=0
  shim_cursor_hide
  while kill -0 "$pid" 2>/dev/null; do
    if [ -n "$SHIM_STATUS_FILE" ] && [ -f "$SHIM_STATUS_FILE" ]; then
      SHIM_LIVE_TEXT="$(cat "$SHIM_STATUS_FILE" 2>/dev/null || true)"
    fi
    shim_draw_live "$frame"
    sleep "$SHIM_FRAME_S"
    frame=$(( (frame + 1) % SHIM_LOOP_FRAMES ))
  done
  shim_cursor_show
}

# Wait for a condition while the live line shimmers.
# $1 = label, $2 = timeout in seconds, $3 = shell test command
shim_wait() { # label limit test-command
  local label="$1" limit="$2" test="$3" i=0
  SHIM_LIVE_TEXT="$label"
  shim_cursor_hide
  while [ "$i" -lt "$limit" ]; do
    shim_draw_live $(( i % SHIM_LOOP_FRAMES ))
    if eval "$test" >/dev/null 2>&1; then
      shim_clear_live
      shim_cursor_show
      return 0
    fi
    sleep "$SHIM_FRAME_S"
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
# ── WSHIM-CORE-END ─────────────────────────────────────────────────────────
