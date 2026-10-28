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
# One pass takes ~2.0s; every frame is redrawn in place every ~90ms. More
# frames per pass (22) and a wider band (40%) make the wave read as a smooth,
# continuous glide instead of a stepping/blinking highlight. On a 256-colour
# terminal the blend is approximated with the gray ramp (245 → 253).
# NO_COLOR, a non-TTY stdout and TERM=dumb turn it off, and the cursor is
# always restored (normal exit, error, or Ctrl+C).
#
# This block is byte-identical in `wearsic` and `install.sh`: the installer is
# a standalone bootstrap script that cannot source the CLI.
# tests/shimmer-test.sh fails when the two copies drift apart.
SHIM_BASE_R=138; SHIM_BASE_G=138; SHIM_BASE_B=133
SHIM_HI_R=201;   SHIM_HI_G=201;   SHIM_HI_B=195
SHIM_256_BASE=245; SHIM_256_HI=253
SHIM_FRAME_S=0.09
SHIM_LOOP_FRAMES=22
SHIM_BAND_PCT=40
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

# Draw the live line, replacing whatever is on the current line. The 22
# gradient frames are computed ONCE per label (cached) — per-tick work is a
# single frame print, so the text shimmer stays smooth on a phone even with
# a long label (previously every frame re-rendered each character).
SHIM_FRAMES=()
SHIM_CACHE_KEY=""

shim_collect_frames() { # $1 = text, $2 = half
  local text="$1" half="$2" f centre
  SHIM_FRAMES=()
  f=0
  while [ "$f" -lt "$SHIM_LOOP_FRAMES" ]; do
    centre=$(( -half + (${#text} + 2 * half) * f / SHIM_LOOP_FRAMES ))
    SHIM_FRAMES[$f]="$(shim_render "$text" "$centre" "$half")"
    f=$(( f + 1 ))
  done
}

# Draw the live line, replacing whatever is on the current line.
shim_draw_live() { # $1 = frame index
  local frame="$1" text="$SHIM_LIVE_TEXT" n half maxw key
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
  key="${text}|${SHIM_LIVE_SHORT}|${SHIM_DEPTH}|${maxw}"
  if [ "$key" != "$SHIM_CACHE_KEY" ]; then
    shim_collect_frames "$text" "$half"
    SHIM_CACHE_KEY="$key"
  fi
  printf '\r%s[K%s' "$SHIM_ESC" "${SHIM_FRAMES[$frame]}"
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
