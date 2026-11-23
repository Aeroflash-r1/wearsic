#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Regression test for the AOT-cache gate in the launcher.
#
# WHY THIS TEST EXISTS: the AOT cache is the only thing that touches JVM flags
# from a FILE on disk. Get the gate wrong and the server either never uses the
# cache (silently loses the optimisation) or keeps loading a cache built for a
# DIFFERENT engine — which, on a phone, looks like "the update broke my server".
# The three rules pinned here:
#   1. no cache, or no stamp            -> not usable
#   2. stamp for another engine / JVM   -> not usable (stale)
#   3. java below 25                    -> not usable (the VM would reject it)
#
# It also pins the quarantine path: the cache that killed a start must never be
# loaded again, but must be kept on disk for inspection.
#
# Usage:  bash wearsic-server/tests/aot-cache-test.sh
# ─────────────────────────────────────────────────────────────────────────────
set -u

HERE="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
RUN="$HERE/run-termux.sh"
[ -f "$RUN" ] || { echo "cannot find run-termux.sh next to this test"; exit 2; }

fails=0
pass() { echo "  ok   $1"; }
fail() { echo "  FAIL $1"; fails=$((fails+1)); }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Extract the functions under test from the SHIPPED launcher, so the test can
# never drift from what runs on the phone.
{
  sed -n '/^jvm_flags_for_java() {/,/^}/p' "$RUN"
  sed -n '/^aot_fingerprint() {/,/^}/p' "$RUN"
  sed -n '/^aot_stamp_matches() {/,/^}/p' "$RUN"
  sed -n '/^aot_usable() {/,/^}/p' "$RUN"
  sed -n '/^aot_quarantine() {/,/^}/p' "$RUN"
} > "$WORK/fn.sh"
for fn in jvm_flags_for_java aot_fingerprint aot_stamp_matches aot_usable aot_quarantine; do
  grep -q "^$fn() {" "$WORK/fn.sh" || { echo "FAIL could not extract $fn from run-termux.sh"; exit 1; }
done

# A stand-in environment: kernel dir with fake jars, plus the logging helpers
# the extracted functions call.
mkdir -p "$WORK/pkg/lib"
printf 'aaa' > "$WORK/pkg/lib/wearsic-server-1.0.0.jar"
printf 'bbbbbb' > "$WORK/pkg/lib/ktor-core.jar"

# shellcheck disable=SC2016
HARNESS='
SCRIPT_DIR="$1"
PKG_DIR="$SCRIPT_DIR/pkg"
AOT_CACHE="$SCRIPT_DIR/wearsic.aot"
AOT_STAMP="$SCRIPT_DIR/wearsic.aot.stamp"
AOT_MIN_JAVA=25
JAVA_MAJOR="${2:-25}"
log() { :; }
history() { :; }
. "$SCRIPT_DIR/fn.sh"

case "$3" in
  usable)   aot_usable && echo yes || echo no ;;
  stamp)    aot_stamp_matches && echo yes || echo no ;;
  write)    aot_fingerprint ;;
  stampw)   printf "fingerprint=%s\nbuiltAt=x\n" "$(aot_fingerprint)" > "$AOT_STAMP" ;;
  quarantine) aot_quarantine "$4" && echo quarantined || echo noop ;;
esac
'

run() { # $1 = action, $2 = java major, $3 = extra
  bash -c "$HARNESS" harness "$WORK" "${2:-25}" "$1" "${3:-}" 2>/dev/null
}

echo "AOT cache gate:"

# Fresh install: no cache at all.
[ "$(run usable 25)" = "no" ] && pass "no cache -> not usable" || fail "no cache must not be usable"

# A cache with no stamp (e.g. an old build, or a half-written pair).
printf 'cache' > "$WORK/wearsic.aot"
[ "$(run usable 25)" = "no" ] && pass "cache without stamp -> not usable" || fail "cache without a stamp must not be usable"

# The normal, correct case.
run stampw 25 >/dev/null
[ "$(run usable 25)" = "yes" ] && pass "cache + matching stamp (java 25) -> usable" || fail "a matching cache must be usable"

# Wrong JDK: the archive is tied to the VM that built it.
[ "$(run usable 21)" = "no" ] && pass "java 21 -> not usable" || fail "java below 25 must not load an AOT cache"

# Engine changed under a valid stamp: the jar set no longer matches, so the
# cache belongs to a different engine.
printf 'bbbbbbbbbbbb' > "$WORK/pkg/lib/ktor-core.jar"
[ "$(run usable 25)" = "no" ] && pass "engine changed -> stamp is stale" || fail "a rebuilt engine must invalidate the stamp"

# A new jar with the SAME byte size must still invalidate it (different file).
printf 'cccccc' > "$WORK/pkg/lib/ktor-core.jar"
printf 'newjar' > "$WORK/pkg/lib/new.jar"
run stampw 25 >/dev/null
[ "$(run usable 25)" = "yes" ] && pass "stamp rebuilt for the new engine -> usable" || fail "a rebuilt stamp must be usable again"

# Different JVM building the stamp must invalidate it, exactly like a jar change.
before="$(run write 25)"
after="$(run write 21)"
[ "$before" != "$after" ] && pass "fingerprint changes with the JVM ($before != $after)" \
  || fail "fingerprint must change with the JVM"

# Fingerprint is stable for unchanged inputs (a restart must not rebuild).
[ "$(run write 25)" = "$before" ] && pass "fingerprint is stable across runs" || fail "fingerprint must be deterministic"

# Quarantine: the bad cache is parked, never used again.
[ "$(run quarantine 25 'died 3s after start')" = "quarantined" ] \
  && [ -f "$WORK/wearsic.aot.bad" ] && [ ! -f "$WORK/wearsic.aot" ] && [ ! -f "$WORK/wearsic.aot.stamp" ] \
  && pass "quarantine parks the cache as .bad and drops the stamp" \
  || fail "quarantine must move the cache to .bad and remove the stamp"
[ "$(run usable 25)" = "no" ] && pass "quarantined cache is never used again" || fail "a quarantined cache must not be usable"
[ "$(run quarantine 25 again)" = "noop" ] && pass "second quarantine is a no-op" || fail "quarantine must be idempotent"

# The launcher must still parse after all of this.
if bash -n "$RUN" 2>/dev/null; then pass "run-termux.sh parses"; else fail "run-termux.sh has a syntax error"; fi

echo
if [ "$fails" -eq 0 ]; then echo "aot-cache-test: PASSED"; exit 0; fi
echo "aot-cache-test: $fails FAILED"; exit 1
