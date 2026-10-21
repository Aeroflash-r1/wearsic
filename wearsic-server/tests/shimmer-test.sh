#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Tests for the terminal shimmer (the soft gray wave) and the stage machine.
#
# WHAT IS BEING TESTED
# --------------------
#   1. The exact colours: base #8A8A85 (138,138,133) and highlight #C9C9C3
#      (201,201,195), truecolor AND the 256-colour gray ramp fallback.
#   2. The blend: the band centre is the highlight, the far edge is the base,
#      and everything in between is a smooth mix of the two.
#   3. The timing: ~90ms per frame, ~1.7s for one left→right pass, and the
#      band really moves left→right.
#   4. NO_COLOR, a non-TTY stdout and TERM=dumb turn the animation off.
#   5. The cursor is hidden while animating and ALWAYS restored: normal
#      exit, an error, and Ctrl+C.
#   6. Narrow terminals: the live line is truncated instead of wrapping.
#   7. The stage machine: one line per stage, the active stage redrawn in
#      place, finished stages printed exactly once.
#   8. The CLI and the standalone installer carry byte-identical copies of
#      the core (they cannot source each other).
#
# Usage:  bash wearsic-server/tests/shimmer-test.sh
# ─────────────────────────────────────────────────────────────────────────────
set -u

HERE="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT="$(CDPATH= cd -- "$HERE/.." && pwd)"
CLI="$HERE/wearsic"
INSTALLER="$ROOT/install.sh"
CORE_SRC="$HERE/lib/wearsic-shimmer.sh"
E="$(printf '\033')"

[ -f "$CLI" ] || { echo "cannot find the wearsic CLI next to this test"; exit 2; }
[ -f "$INSTALLER" ] || { echo "cannot find install.sh"; exit 2; }

fails=0
pass() { echo "  ok   $1"; }
fail() { echo "  FAIL $1"; fails=$((fails + 1)); }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

extract_core() { # $1 = file → the core block on stdout
  sed -n '/^# ── WSHIM-CORE-BEGIN/,/^# ── WSHIM-CORE-END/p' "$1"
}

extract_core "$CLI"       > "$WORK/core-cli.sh"
extract_core "$INSTALLER" > "$WORK/core-installer.sh"

# ── 1. the two copies are identical ────────────────────────────────────────
echo "core sync"
if diff -q "$WORK/core-cli.sh" "$WORK/core-installer.sh" >/dev/null 2>&1; then
  pass "the CLI and the installer carry the same shimmer core"
else
  fail "the shimmer core drifted between wearsic and install.sh"
  diff "$WORK/core-cli.sh" "$WORK/core-installer.sh" | head -10
fi

if [ -f "$CORE_SRC" ] && diff -q "$CORE_SRC" "$WORK/core-cli.sh" >/dev/null 2>&1; then
  pass "lib/wearsic-shimmer.sh matches the shipped copy"
else
  fail "lib/wearsic-shimmer.sh differs from the shipped copy (run lib/sync-shimmer.sh)"
fi

# The helper runs the core in a controlled environment.
cat > "$WORK/helper.sh" <<'EOF'
TTY_OUT="${TTY_OUT:-0}"
TERM="${TERM:-xterm-256color}"
export TTY_OUT TERM
. "$1"
shift
shim_init
"$@"
EOF

# The colour depth is forced so the test is deterministic on any host
# (a Termux box would otherwise always report truecolor).
run_core() { # run_core VAR=VAL... -- command args
  env -u COLORTERM -u NO_COLOR -u WEARSIC_COLOR_DEPTH -u PREFIX -u TERMUX_VERSION \
    "$@" bash "$WORK/helper.sh" "$WORK/core-cli.sh" "${CORE_CMD[@]}"
}

# ── 2. the exact colours ───────────────────────────────────────────────────
echo "colours"

CORE_CMD=(shim_render abcdefghij 5 3)
out="$(run_core TTY_OUT=1 WEARSIC_COLOR_DEPTH=24 TERM=xterm-256color | cat -v)"
if printf '%s' "$out" | grep -q '38;2;201;201;195'; then
  pass "the band centre is the highlight gray #C9C9C3"
else
  fail "the highlight #C9C9C3 (201,201,195) is missing (got: $out)"
fi
if printf '%s' "$out" | grep -q '38;2;138;138;133'; then
  pass "the far edge is the base gray #8A8A85"
else
  fail "the base #8A8A85 (138,138,133) is missing (got: $out)"
fi

CORE_CMD=(shim_render abcdefghij 5 3)
out="$(run_core TTY_OUT=1 WEARSIC_COLOR_DEPTH=256 TERM=xterm-256color | cat -v)"
if printf '%s' "$out" | grep -q '38;5;253'; then
  pass "the 256-colour fallback uses the gray ramp highlight (253)"
else
  fail "no 256-colour highlight (got: $out)"
fi
if printf '%s' "$out" | grep -q '38;5;245'; then
  pass "the 256-colour fallback uses color 245 as the base"
else
  fail "no 256-colour base 245 (got: $out)"
fi

# ── 3. the blend is smooth ────────────────────────────────────────────────
echo "blend"
CORE_CMD=(shim_render abcdefghij 5 3)
out="$(run_core TTY_OUT=1 WEARSIC_COLOR_DEPTH=24 TERM=xterm-256color)"
# Split before every escape sequence so the colour numbers stay readable.
reds="$(printf '%s' "$out" | sed "s/${E}\[/\n/g" | grep -o '38;2;[0-9]*' | cut -d';' -f3 | tr '\n' ' ')"
prev=""
smooth=1
for r in $reds; do
  if [ -n "$prev" ]; then
    d=$(( r - prev )); [ "$d" -lt 0 ] && d=$(( -d ))
    [ "$d" -gt 40 ] && smooth=0
  fi
  prev="$r"
done
if [ "$smooth" = "1" ] && [ -n "$reds" ]; then
  pass "the blend steps smoothly between the two grays"
else
  fail "the blend is not smooth (reds: $reds)"
fi
maxr="$(printf '%s' "$reds" | tr ' ' '\n' | sort -n | tail -1)"
minr="$(printf '%s' "$reds" | tr ' ' '\n' | sort -n | head -1)"
if [ "$maxr" = "201" ] && [ "$minr" = "138" ]; then
  pass "the frame spans exactly #8A8A85 → #C9C9C3"
else
  fail "the frame spans $minr → $maxr (expected 138 → 201)"
fi

# ── 4. timing ─────────────────────────────────────────────────────────────
echo "timing"
cat > "$WORK/seconds.sh" <<'EOF'
. "$1"
printf '%s %s %s\n' "$SHIM_FRAME_S" "$SHIM_LOOP_FRAMES" "$SHIM_BAND_PCT"
EOF
read -r frame_s frames band < <(bash "$WORK/seconds.sh" "$WORK/core-cli.sh")
loop_s="$(awk "BEGIN{printf \"%.2f\", $frame_s * $frames}")"
if awk "BEGIN{exit !($loop_s >= 1.5 && $loop_s <= 2.0)}"; then
  pass "one left→right pass takes ${loop_s}s (1.5–2.0s)"
else
  fail "one pass takes ${loop_s}s (expected 1.5–2.0s)"
fi
if awk "BEGIN{exit !($frame_s >= 0.08 && $frame_s <= 0.10)}"; then
  pass "frames are redrawn every ${frame_s}s (~90ms)"
else
  fail "the frame interval is ${frame_s}s (expected ~0.09s)"
fi

# The band really moves: the first lit column grows with the frame index.
cat > "$WORK/first.sh" <<'EOF'
. "$1"
frame="$2"
text="abcdefghijklmnop"
n=${#text}
half=$(( n * SHIM_BAND_PCT / 100 / 2 ))
[ "$half" -lt 3 ] && half=3
centre=$(( -half + (n + 2 * half) * frame / SHIM_LOOP_FRAMES ))
i=0
while [ "$i" -lt "$n" ]; do
  d=$(( i - centre )); [ "$d" -lt 0 ] && d=$(( -d ))
  t="$(shim_intensity "$d" "$half")"
  if [ "$t" -gt 0 ]; then printf '%s' "$i"; exit 0; fi
  i=$(( i + 1 ))
done
printf '%s' "-1"
EOF
first4="$(bash "$WORK/first.sh" "$WORK/core-cli.sh" 4)"
first9="$(bash "$WORK/first.sh" "$WORK/core-cli.sh" 9)"
first14="$(bash "$WORK/first.sh" "$WORK/core-cli.sh" 14)"
if [ "$first4" != "-1" ] && [ "$first9" -gt "$first4" ] && [ "$first14" -gt "$first9" ]; then
  pass "the band travels left → right (column $first4 → $first9 → $first14)"
else
  fail "the band does not travel right (got '$first4' → '$first9' → '$first14')"
fi

# ── 5. when the animation must be off ─────────────────────────────────────
echo "plain modes"
CORE_CMD=(shim_render abcdefghij 5 3)
out="$(run_core TTY_OUT=1 NO_COLOR=1 WEARSIC_COLOR_DEPTH=24 TERM=xterm-256color)"
if [ "$out" = "abcdefghij" ]; then
  pass "NO_COLOR prints the plain text"
else
  fail "NO_COLOR still emitted escape codes ($out)"
fi
out="$(run_core TTY_OUT=0 WEARSIC_COLOR_DEPTH=24 TERM=xterm-256color)"
if [ "$out" = "abcdefghij" ]; then
  pass "a non-TTY stdout prints the plain text"
else
  fail "a non-TTY stdout still emitted escape codes ($out)"
fi
out="$(run_core TTY_OUT=1 TERM=dumb WEARSIC_COLOR_DEPTH=24)"
if [ "$out" = "abcdefghij" ]; then
  pass "TERM=dumb prints the plain text"
else
  fail "TERM=dumb still emitted escape codes ($out)"
fi

# ── 6. the cursor ──────────────────────────────────────────────────────────
echo "cursor"
CORE_CMD=(shim_cursor_hide)
out="$(run_core TTY_OUT=1 TERM=xterm-256color | cat -v)"
if printf '%s' "$out" | grep -q '\[?25l'; then
  pass "the cursor is hidden while animating"
else
  fail "the cursor is not hidden ($out)"
fi
cat > "$WORK/hideshow.sh" <<'EOF'
TTY_OUT=1
export TTY_OUT
. "$1"
shim_cursor_hide
shim_cursor_show
EOF
out="$(bash "$WORK/hideshow.sh" "$WORK/core-cli.sh" | cat -v)"
if printf '%s' "$out" | grep -q '\[?25h'; then
  pass "the cursor is restored"
else
  fail "the cursor is not restored ($out)"
fi

# The shipped CLI restores the cursor on every exit path.
grep -q 'die()  { shim_clear_live; shim_cursor_show;' "$CLI" \
  && pass "the CLI's die() restores the cursor" \
  || fail "the CLI's die() does not restore the cursor"
grep -q "trap 'shim_clear_live; shim_cursor_show; shim_cleanup" "$CLI" \
  && pass "the CLI's exit trap restores the cursor" \
  || fail "the CLI has no cursor-restoring exit trap"

# Ctrl+C: the real CLI traps SIGINT and puts the cursor back.
grep -q "shim_cursor_show; shim_cleanup; exit 130' INT" "$CLI" \
  && pass "the CLI traps SIGINT and restores the cursor" \
  || fail "the CLI does not restore the cursor on SIGINT"

cat > "$WORK/int.sh" <<'EOF'
TTY_OUT=1
export TTY_OUT
. "$1"
shim_cursor_hide
trap 'shim_clear_live; shim_cursor_show; shim_cleanup; exit 130' INT
while :; do sleep 0.2; done
EOF
# `timeout -s INT` delivers the signal the way a terminal does (to the
# foreground process), so the trap really runs.
timeout -s INT 1 bash "$WORK/int.sh" "$WORK/core-cli.sh" > "$WORK/int.out" 2>&1
if grep -q '\[?25h' "$WORK/int.out"; then
  pass "Ctrl+C restores the cursor"
else
  fail "Ctrl+C left the cursor hidden"
fi

# ── 7. narrow terminals ───────────────────────────────────────────────────
echo "narrow terminal"
cat > "$WORK/narrow.sh" <<'EOF'
TTY_OUT=1
export TTY_OUT
. "$1"
SHIM_LIVE_TEXT="Download  fetching a very long release name for the narrow screen"
SHIM_LIVE_ACTIVE=0
shim_draw_live 4 | sed "s/${SHIM_ESC}\[[0-9;]*[a-zA-Z]//g" | tr -d '\r' | wc -m
EOF
width="$(COLUMNS=34 bash "$WORK/narrow.sh" "$WORK/core-cli.sh" | tail -1)"
if [ "${width:-99}" -le 34 ]; then
  pass "the live line is truncated to the terminal width (${width} cols)"
else
  fail "the live line is ${width} columns wide on a 34-column terminal"
fi

# ── 8. the stage machine ──────────────────────────────────────────────────
echo "stages"
cat > "$WORK/stages.sh" <<'EOF'
. "$1"
shim_stage_begin "Download" "release"
shim_stage_status "verifying"
shim_stage_end ok "release v1.9.5"
shim_stage_begin "Install" "engine"
shim_stage_end ok "engine in ~/wearsic-server"
EOF
out="$(TTY_OUT=1 TERM=xterm-256color bash "$WORK/stages.sh" "$WORK/core-cli.sh" | cat -v)"
if printf '%s' "$out" | grep -q 'Download'; then
  pass "a stage prints its name"
else
  fail "the stage name is missing ($out)"
fi
if printf '%s' "$out" | grep -q 'release v1.9.5'; then
  pass "a finished stage keeps its result"
else
  fail "the stage result is missing ($out)"
fi
if printf '%s' "$out" | grep -q 'engine in ~/wearsic-server'; then
  pass "every stage keeps its own line"
else
  fail "the second stage is missing ($out)"
fi
if printf '%s' "$out" | grep -q '\[K'; then
  pass "the active stage is redrawn in place (line erase + carriage return)"
else
  fail "the stage line is not redrawn in place ($out)"
fi

# The seven installer stages, in order.
echo "installer stages"
order="$(grep -o 'shim_stage_begin "[A-Za-z]*"' "$INSTALLER" | sed 's/.*"\(.*\)"/\1/' | awk '!seen[$0]++' | tr '\n' ' ')"
want="Download Install Configure Build Start Verify Ready "
if [ "$order" = "$want" ]; then
  pass "the installer walks Download → Install → Configure → Build → Start → Verify → Ready"
else
  fail "the installer stages are: $order"
fi

# ── 9. the installer's pairing card is aligned ───────────────────────────
echo "installer card"
# The card is drawn by the installer (a standalone script), so its box
# functions are extracted and rendered here: every border (top, rows, bottom)
# must land on the SAME column, or the bottom-right corner sticks out.
sed -n '/^box_begin()/p' "$INSTALLER"        >  "$WORK/box-fns.sh"
sed -n '/^box_line() {/,/^}/p' "$INSTALLER"  >> "$WORK/box-fns.sh"
sed -n '/^box_end() {/,/^}/p' "$INSTALLER"   >> "$WORK/box-fns.sh"
cat > "$WORK/box.sh" <<'EOF'
C_ACC=$'\033[38;5;114m'
C_RESET=$'\033[0m'
C_BOLD=$'\033[1m'
C_DIM=$'\033[2m'
SHIM_ESC="$(printf '\033')"
export C_ACC C_RESET C_BOLD C_DIM
. "$1"
box_begin "Wearsic server is ready"
box_line ""
box_line "   ${C_BOLD}Server :${C_RESET} http://192.168.1.42:8080"
box_line "   ${C_BOLD}Key    :${C_RESET} abcd2345"
box_line ""
box_line "   On the watch, open Wearsic, Settings,"
box_line "   type the Server line, then the Key line."
box_line ""
box_line "   ${C_DIM}Again anytime:  wearsic url    -    wearsic doctor${C_RESET}"
box_end
EOF
widths="$(bash "$WORK/box.sh" "$WORK/box-fns.sh" \
  | sed "s/${E}\[[0-9;]*[a-zA-Z]//g" \
  | awk '{ print length($0) }' | sort -u | tr '\n' ' ')"
uniq="$(printf '%s\n' "$widths" | wc -l | tr -d ' ')"
if [ "$uniq" = "1" ]; then
  pass "the installer card draws every border on the same column (${widths% } cols)"
else
  fail "the installer card is ragged: line widths ${widths}"
fi

# ── summary ────────────────────────────────────────────────────────────────
echo
if [ "$fails" -eq 0 ]; then
  echo "Result: all checks passed"
  exit 0
fi
echo "Result: $fails check(s) FAILED"
exit 1
