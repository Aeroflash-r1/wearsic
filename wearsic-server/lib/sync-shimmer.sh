#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Sync the shimmer core into the two scripts that ship it.
#
# `wearsic` (the CLI) and `install.sh` (the standalone bootstrap) each carry
# their own copy of the shimmer core, because the installer is piped straight
# from GitHub and cannot source the CLI. The copies MUST stay byte-identical,
# so this script re-inserts the canonical block from
# `wearsic-server/lib/wearsic-shimmer.sh` into both files.
#
#   bash wearsic-server/lib/sync-shimmer.sh
#
# tests/shimmer-test.sh verifies the two copies are identical.
# ─────────────────────────────────────────────────────────────────────────────
set -eu

HERE="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(CDPATH= cd -- "$HERE/../.." && pwd)"
CORE="$HERE/wearsic-shimmer.sh"
CLI="$ROOT/wearsic-server/wearsic"
INSTALLER="$ROOT/install.sh"

[ -f "$CORE" ] || { echo "missing $CORE"; exit 1; }
[ -f "$CLI" ] || { echo "missing $CLI"; exit 1; }
[ -f "$INSTALLER" ] || { echo "missing $INSTALLER"; exit 1; }

sync_into() { # $1 = target file, $2 = awk pattern (line the core goes before)
  local target="$1" pattern="$2" tmp
  tmp="$(mktemp "${TMPDIR:-/tmp}/wearsic-sync.XXXXXX")"
  awk -v core="$CORE" -v pat="$pattern" '
    $0 ~ pat && !done { while ((getline line < core) > 0) print line; close(core); done=1 }
    { print }
  ' "$target" > "$tmp"
  grep -q 'WSHIM-CORE-BEGIN' "$tmp" || { rm -f "$tmp"; echo "sync failed for $target"; exit 1; }
  mv -f "$tmp" "$target"
}

# 1. strip the current copy out of both files
for f in "$CLI" "$INSTALLER"; do
  tmp="$(mktemp "${TMPDIR:-/tmp}/wearsic-sync.XXXXXX")"
  awk '/^# ── WSHIM-CORE-BEGIN/ { skip=1 }
       /^# ── WSHIM-CORE-END/   { skip=0; next }
       !skip { print }' "$f" > "$tmp"
  mv -f "$tmp" "$f"
done

# 2. put the canonical copy back in
sync_into "$CLI" '^human_bytes\(\) \{'
sync_into "$INSTALLER" '^# Install a file ATOMICALLY'

# 3. prove they are the same bytes
a="$(mktemp "${TMPDIR:-/tmp}/wearsic-sync.XXXXXX")"
b="$(mktemp "${TMPDIR:-/tmp}/wearsic-sync.XXXXXX")"
sed -n '/^# ── WSHIM-CORE-BEGIN/,/^# ── WSHIM-CORE-END/p' "$CLI" > "$a"
sed -n '/^# ── WSHIM-CORE-BEGIN/,/^# ── WSHIM-CORE-END/p' "$INSTALLER" > "$b"
if diff -q "$a" "$b" >/dev/null; then
  echo "shimmer core synced into: $CLI"
  echo "shimmer core synced into: $INSTALLER"
  rm -f "$a" "$b"
  exit 0
fi
echo "ERROR: the two shimmer copies differ"
diff "$a" "$b" | head -20
rm -f "$a" "$b"
exit 1
