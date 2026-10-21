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

# Animate until the job ($1) exits. A progress hook ($SHIM_PROGRESS_HOOK) is
# called before every frame, so a download can report its real percentage.
shim_animate() { # $1 = pid
  local pid="$1" frame=0 t=""
  shim_cursor_hide
  while kill -0 "$pid" 2>/dev/null; do
    if [ -n "$SHIM_PROGRESS_HOOK" ]; then
      "$SHIM_PROGRESS_HOOK" >/dev/null 2>&1 || true
    fi
    if [ -n "$SHIM_STATUS_FILE" ] && [ -s "$SHIM_STATUS_FILE" ]; then
      t="$(cat "$SHIM_STATUS_FILE" 2>/dev/null || true)"
      [ -n "$t" ] && SHIM_LIVE_TEXT="$t"
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
  local label="$1" limit="$2" test="$3" i=0 max step
  case "$limit" in ''|*[!0-9]*) limit=30 ;; esac
  if [ "$SHIM_DEPTH" = "0" ] || [ "$TTY_OUT" != "1" ]; then
    # No animation: poll once a second, exactly like the old spinner.
    max="$limit"; step=1
  else
    max=$(( limit * 10 )); step=0
  fi
  SHIM_LIVE_TEXT="$label"
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
  curl -fL --retry 3 --retry-delay 2 -o "$2" "$1" >/dev/null 2>&1 &
  SHIM_FETCH_PID=$!
}

# The animation hook: report the real percentage of the running download.
shim_fetch_progress() {
  local have=0 pct=0
  [ -n "$SHIM_FETCH_PID" ] || return 0
  if [ -e "$SHIM_FETCH_OUT" ]; then have="$(wc -c < "$SHIM_FETCH_OUT" 2>/dev/null || echo 0)"; fi
  case "$have" in ''|*[!0-9]*) have=0 ;; esac
  if [ -n "$SHIM_FETCH_TOTAL" ] && [ "$SHIM_FETCH_TOTAL" -gt 0 ]; then
    pct=$(( have * 100 / SHIM_FETCH_TOTAL )); [ "$pct" -gt 100 ] && pct=100
    shim_status "$SHIM_FETCH_PREFIX  ${pct}% · $(shim_bytes "$have") / $(shim_bytes "$SHIM_FETCH_TOTAL")"
  else
    shim_status "$SHIM_FETCH_PREFIX  downloading…"
  fi
}

shim_fetch_wait() {
  local rc=0
  [ -n "$SHIM_FETCH_PID" ] || return 1
  wait "$SHIM_FETCH_PID"; rc=$?
  SHIM_FETCH_PID=""
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
