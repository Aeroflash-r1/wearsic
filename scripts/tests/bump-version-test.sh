#!/usr/bin/env bash
# Offline test for scripts/bump-version.py — the release bump must be EXACT
# or it must refuse: a "mostly right" version rewrite ships a malformed
# release, and the Extractor Hotfix workflow trusts this script.
#
# Strategy: run the real script against a COPY of the real repo files, so
# the suite fails the moment any pattern the script depends on drifts.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT="$ROOT/scripts/bump-version.py"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

PASS=0
FAIL=0
t_eq()    { if [ "$2" = "$3" ]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); echo "  FAIL: $1: expected [$2] got [$3]"; fi; }
t_has()   { if grep -qF -- "$2" "$3"; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); echo "  FAIL: $1: [$2] missing from $3"; fi; }
t_lacks() { if grep -qF -- "$2" "$3"; then FAIL=$((FAIL+1)); echo "  FAIL: $1: [$2] unexpectedly present in $3"; else PASS=$((PASS+1)); fi; }

fresh() {
  rm -rf "$TMP/repo"
  mkdir -p "$TMP/repo/app" "$TMP/repo/wearsic-server/src/main/kotlin/com/wearsic/server"
  cp "$ROOT/wearsic-server/src/main/kotlin/com/wearsic/server/ServerVersion.kt" \
     "$TMP/repo/wearsic-server/src/main/kotlin/com/wearsic/server/ServerVersion.kt"
  cp "$ROOT/wearsic-server/build.gradle.kts" "$TMP/repo/wearsic-server/build.gradle.kts"
  cp "$ROOT/wearsic-server/SETUP.md" "$TMP/repo/wearsic-server/SETUP.md"
  cp "$ROOT/app/build.gradle.kts" "$TMP/repo/app/build.gradle.kts"
  cp "$ROOT/README.md" "$ROOT/API_CONTRACT.md" "$ROOT/TERMUX_SERVER_GUIDE.md" "$TMP/repo/"
}

tree_state() { (cd "$1" && find . -type f -exec cksum {} \; | sort); }
cur_ver()  { sed -n 's/.*VERSION: String = "\([^"]*\)".*/\1/p' \
               "$1/wearsic-server/src/main/kotlin/com/wearsic/server/ServerVersion.kt"; }
cur_code() { sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$1/app/build.gradle.kts"; }
bump_patch() { echo "$1" | awk -F. '{print $1"."$2"."$3+1}'; }

echo "=== bump-version.py test ==="

# --- 1. happy path: pin + patch bump against the REAL repo files ---
fresh
OLD="$(cur_ver "$TMP/repo")"
OLD_CODE="$(cur_code "$TMP/repo")"
CUR_PIN="$(sed -n 's/.*NewPipeExtractor:\([^")]*\).*/\1/p' "$ROOT/wearsic-server/build.gradle.kts" | head -1)"
NEW="$(bump_patch "$OLD")"
NEW_CODE=$((OLD_CODE + 1))

if python3 "$SCRIPT" --repo-root "$TMP/repo" --newpipe-ref "v9.9.9" > "$TMP/run1.log" 2>&1; then
  PASS=$((PASS+1))
else
  FAIL=$((FAIL+1)); echo "  FAIL: happy path exited non-zero:"; sed 's/^/    /' "$TMP/run1.log"
fi

t_eq  "ServerVersion bumped"          "$NEW" "$(cur_ver "$TMP/repo")"
t_eq  "versionCode incremented"       "$NEW_CODE" "$(cur_code "$TMP/repo")"
t_has "app versionName bumped"        "versionName = \"$NEW\"" "$TMP/repo/app/build.gradle.kts"
t_has "NewPipe pin bumped"            "NewPipeExtractor:v9.9.9" "$TMP/repo/wearsic-server/build.gradle.kts"
t_has "SETUP pin token bumped"        "NewPipeExtractor (v9.9.9)" "$TMP/repo/wearsic-server/SETUP.md"
t_lacks "SETUP old pin token gone"    "NewPipeExtractor ($CUR_PIN)" "$TMP/repo/wearsic-server/SETUP.md"
t_has "health sample (README)"        "\"version\": \"$NEW\"," "$TMP/repo/README.md"
t_has "zip URL sample (README)"       "download/v$NEW/wearsic-server-termux-v$NEW.zip" "$TMP/repo/README.md"
t_has "release tag example (README)"  "git tag v$NEW && git push origin v$NEW" "$TMP/repo/README.md"
t_has "engineVersion (contract)"      "\"engineVersion\": \"$NEW\"" "$TMP/repo/API_CONTRACT.md"
t_has "sideload example (guide)"      "wearsic sideload v$NEW" "$TMP/repo/TERMUX_SERVER_GUIDE.md"
t_has "engine banner (guide)"         "v$NEW · Wearsic Engine" "$TMP/repo/TERMUX_SERVER_GUIDE.md"
t_lacks "no stale sideload example"   "wearsic sideload v$OLD" "$TMP/repo/TERMUX_SERVER_GUIDE.md"

# --- 2. repeatability: a second bump walks the patterns forward again ---
NEW2="$(bump_patch "$NEW")"
if python3 "$SCRIPT" --repo-root "$TMP/repo" > "$TMP/run2.log" 2>&1; then
  PASS=$((PASS+1))
else
  FAIL=$((FAIL+1)); echo "  FAIL: second run exited non-zero:"; sed 's/^/    /' "$TMP/run2.log"
fi
t_has  "second bump lands"          "VERSION: String = \"$NEW2\"" "$TMP/repo/wearsic-server/src/main/kotlin/com/wearsic/server/ServerVersion.kt"
t_lacks "first bump fully replaced" "VERSION: String = \"$NEW\"" "$TMP/repo/wearsic-server/src/main/kotlin/com/wearsic/server/ServerVersion.kt"
t_has  "historical notes preserved" "added in v1.9.1" "$TMP/repo/README.md"

# --- 3. drift guard: a missing pattern refuses AND writes nothing ---
fresh
grep -v '· Wearsic Engine' "$TMP/repo/TERMUX_SERVER_GUIDE.md" > "$TMP/guide.tmp" \
  && mv "$TMP/guide.tmp" "$TMP/repo/TERMUX_SERVER_GUIDE.md"
BEFORE="$(tree_state "$TMP/repo")"
python3 "$SCRIPT" --repo-root "$TMP/repo" > "$TMP/run3.log" 2>&1
RC=$?
t_eq "drift guard exits non-zero" "1" "$RC"
t_eq "drift guard writes nothing" "$BEFORE" "$(tree_state "$TMP/repo")"

# --- 4. dry run writes nothing ---
fresh
BEFORE="$(tree_state "$TMP/repo")"
if python3 "$SCRIPT" --repo-root "$TMP/repo" --dry-run > "$TMP/run4.log" 2>&1; then
  PASS=$((PASS+1))
else
  FAIL=$((FAIL+1)); echo "  FAIL: dry run exited non-zero:"; sed 's/^/    /' "$TMP/run4.log"
fi
t_eq  "dry run writes nothing" "$BEFORE" "$(tree_state "$TMP/repo")"
t_has "dry run says so"        "DRY RUN" "$TMP/run4.log"

echo
echo "======================================"
echo "BUMP TEST: $PASS passed, $FAIL failed"
echo "======================================"
[ "$FAIL" -eq 0 ]
