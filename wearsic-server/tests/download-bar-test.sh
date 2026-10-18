#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Regression test for the `wearsic update` progress-bar race.
#
# THE BUG: with a terminal attached, download_bar() ran curl in the background
# and polled the output file with `wc -c < "$out"`. curl creates $out only
# when its first bytes arrive; before that, the failed INPUT REDIRECTION made
# the shell print
#
#     bash: line 89: /data/.../update.zip: No such file or directory
#
# even though the command carried 2>/dev/null — redirections are applied in
# order, and the redirect that would have silenced it comes second. Users saw
# that line (twice) while the update actually worked underneath.
#
# download_bar() must poll a not-yet-created file with ZERO stderr noise,
# still draw the bar from 0% to 100%, and finish cleanly. curl is faked:
# HEAD answers Content-Length; GET sleeps before creating the file, so the
# poll loop really does run against a missing $out.
#
# Usage:  bash wearsic-server/tests/download-bar-test.sh
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

HERE="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CLI="${CLI:-$HERE/wearsic}"
[ -f "$CLI" ] || { echo "FAIL cannot find CLI at $CLI"; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin"

# Extract the download helpers straight from the shipped CLI, so this can
# never drift from the code users actually run.
{
  sed -n '/^human_bytes() {/,/^}/p' "$CLI"
  sed -n '/^draw_bar() {/,/^}/p' "$CLI"
  sed -n '/^download_bar() {/,/^}/p' "$CLI"
} > "$WORK/fn.sh"
grep -q '^download_bar() {' "$WORK/fn.sh" || { echo "FAIL could not extract download_bar from the CLI"; exit 1; }

# Fake curl:
#   * HEAD (-fsSIL) -> a Content-Length so download_bar picks the bar path;
#   * GET (-o FILE) -> leave FILE missing for 600ms (the race window), then
#     write exactly 40000 bytes, so the poll loop sees a real size later.
# The payload is built with pure printf — no head/tr/dd dependency games.
cat > "$WORK/bin/curl" <<'FAKECURL'
#!/usr/bin/env bash
case "$*" in
  *-fsSIL*)
    printf 'content-length: 40000\n'
    exit 0 ;;
esac
out=""; prev=""
for a in "$@"; do
  if [ "$prev" = "-o" ]; then out="$a"; fi
  prev="$a"
done
[ -n "$out" ] || { echo "fake curl: no -o target" >&2; exit 2; }
sleep 0.6
i=0
while [ "$i" -lt 400 ]; do
  printf 'xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx'
  i=$((i + 1))
done > "$out"
exit 0
FAKECURL
chmod +x "$WORK/bin/curl"

passes=0; fails=0
pass() { echo "  ok   $1"; passes=$((passes+1)); }
fail() { echo "  FAIL $1"; fails=$((fails+1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then pass "$1";
  else fail "$1"; echo "       expected: [$2]"; echo "       actual:   [$3]"; fi
}

echo "download bar (TTY) race:"

OUTF="$WORK/update.zip"
rm -f "$OUTF"
TTY_OUT=1 PATH="$WORK/bin:$PATH" \
  bash -c 'source "$1"; download_bar "https://example.test/x.zip" "$2" "downloading x.zip"' \
    bash "$WORK/fn.sh" "$OUTF" > "$WORK/stdout.txt" 2> "$WORK/stderr.txt"
rc=$?

check "download_bar exits 0" "0" "$rc"

# THE REGRESSION: the polling loop ran while the file did not exist yet —
# that must be completely silent.
if [ -s "$WORK/stderr.txt" ]; then
  fail "no stderr noise while polling a missing file"
  head -5 "$WORK/stderr.txt" | sed 's/^/       /'
else
  pass "no stderr noise while polling a missing file"
fi

# Non-vacuity: the loop must have polled the empty window (a 0% draw proves
# the guard was exercised) and still finished at 100%.
grep -q '  0%' "$WORK/stdout.txt" && pass "bar polled before the file existed (0%)" \
  || fail "bar never polled the empty window — test would be vacuous"
grep -q '100%' "$WORK/stdout.txt" && pass "bar reaches 100%" \
  || fail "bar reaches 100%"

check "downloaded file complete (40000 bytes)" "40000" "$(wc -c < "$OUTF" | tr -d '[:space:]')"

# Non-vacuity of the mechanism itself: the OLD unguarded pattern really does
# leak the redirection error to stderr (that is what users saw). If a future
# bash ever silences it, this pin tells us the guard is no longer the only
# thing standing between users and the noise.
old_err="$( { wc -c < "$WORK/never-created" 2>/dev/null || echo 0; } 2>&1 )"
case "$old_err" in
  *"No such file"*|*"cannot open"*) pass "old unguarded pattern leaks the error (mechanism pinned)" ;;
  *) fail "old unguarded pattern did not leak an error — check bash behaviour" ;;
esac

# Syntax check of the shipped CLI.
if bash -n "$CLI" 2>/dev/null; then pass "wearsic CLI parses"; else fail "wearsic CLI has a syntax error"; fi

echo
if [ "$fails" -gt 0 ]; then echo "download-bar-test: $fails FAILED, $passes passed"; exit 1; fi
echo "download-bar-test: PASSED ($passes checks)"
