#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Tests for the CLI convenience layer: leading-slash dispatch, the
# `shortcuts` installer command, and the live terminal-width re-measure.
#
# Usage:  bash wearsic-server/tests/cli-shortcuts-test.sh
# ─────────────────────────────────────────────────────────────────────────────
set -u

HERE="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CLI="$HERE/wearsic"
[ -f "$CLI" ] || { echo "cannot find the wearsic CLI next to this test"; exit 2; }

fails=0
pass() { echo "  ok   $1"; }
fail() { echo "  FAIL $1"; fails=$((fails+1)); }
check() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1"; echo "       expected: [$2]"; echo "       actual:   [$3]"; fi; }

# 1. The dispatch accepts a leading slash (`wearsic /start` ⇔ `wearsic start`).
grep -q 'if \[ $# -gt 0 \] && \[ "${1:0:1}" = "/" \]' "$CLI" \
  && pass "leading-slash commands are normalized before dispatch" \
  || fail "no leading-slash normalization in dispatch"

# 2. The `shortcuts` command exists, is dispatched, and installs ws-* links.
grep -q 'shortcuts)  cmd_shortcuts ;;' "$CLI" \
  && pass 'wearsic shortcuts is dispatched' \
  || fail 'wearsic shortcuts is not dispatched'

grep -q '^cmd_shortcuts()' "$CLI" \
  && pass "cmd_shortcuts is defined" \
  || fail "cmd_shortcuts missing"

grep -q 'ln -s "$cli" "$bin/$n"' "$CLI" \
  && pass "shortcuts create symlinks (never overwrite)" \
  || fail "shortcut symlink logic missing"

grep -q 'ws-cf' "$CLI" \
  && pass "shortcut list includes ws-cf (cloudflare)" \
  || fail "ws-cf missing from shortcut list"

# 3. Live width re-measure: cur_cols exists, is cached, and flips on WINCH.
grep -q '^cur_cols()' "$CLI" \
  && pass "cur_cols is defined (live terminal width)" \
  || fail "cur_cols missing"

grep -q "trap '_COLS_CACHE=\"\"' WINCH" "$CLI" \
  && pass "SIGWINCH invalidates the width cache" \
  || fail "SIGWINCH trap missing"

grep -q 'tw=$(( $(cur_cols) - 4 ))' "$CLI" \
  && pass "panels re-measure at render time" \
  || fail "box_end still uses the startup width"

# 4. The message helpers still exist and the CLI parses.
bash -n "$CLI" 2>/dev/null \
  && pass "the CLI parses" \
  || fail "the CLI has a syntax error"

echo
if [ "$fails" -eq 0 ]; then echo "cli-shortcuts-test: PASSED"; exit 0; fi
echo "cli-shortcuts-test: $fails FAILED"; exit 1
