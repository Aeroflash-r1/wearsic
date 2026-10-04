#!/usr/bin/env bash
# Offline regression test for `wearsic update --pre`.
#
# Bug this pins: GitHub's releases/latest EXCLUDES pre-releases, so a plain
# `wearsic update` silently kept serving v1.6.0 while the published beta was
# v1.6.1. `resolve_pre_zip_url` walks the release LIST instead and must pick
# the newest release that actually has a server package — including pre-
# releases, but skipping drafts (which publish no assets).
#
# Extracts the function from the SHIPPED CLI (not a copy) and fakes curl, so
# the test proves the real script's behaviour with no network.
set -uo pipefail

CLI="${CLI:-$(cd "$(dirname "$0")/.." && pwd)/wearsic}"
[ -f "$CLI" ] || { echo "FAIL cannot find CLI at $CLI"; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin"

# Pull just resolve_pre_zip_url out of the shipped CLI.
sed -n '/^resolve_pre_zip_url() {/,/^}/p' "$CLI" > "$WORK/fn.sh"
[ -s "$WORK/fn.sh" ] || { echo "FAIL could not extract resolve_pre_zip_url from the CLI"; exit 1; }

# --- fake curl -------------------------------------------------------------
# FAKE_TAGS: whitespace-separated tags the "API" reports, newest first.
# FAKE_EXIST: whitespace-separated tags whose asset URL really exists.
cat > "$WORK/bin/curl" <<'FAKECURL'
#!/usr/bin/env bash
url="${*: -1}"
# JSON fetch of the release LIST (has a "?" query string) -> emit fake
# tag_name fields, newest first. A HEAD probe of a download URL never has
# one, so the two call shapes are unambiguous.
case "$url" in
  *\?*)
    for t in $FAKE_TAGS; do printf '{"tag_name":"%s","draft":false}\n' "$t"; done
    exit 0 ;;
esac
# HEAD probe of a download URL -> 200 only for tags in FAKE_EXIST
case "$url" in
  */releases/download/*)
    for t in $FAKE_EXIST; do
      case "$url" in
        */download/$t/*) echo 200; exit 0 ;;
      esac
    done
    echo 404; exit 0 ;;
esac
echo 000
FAKECURL
chmod +x "$WORK/bin/curl"

run() { PATH="$WORK/bin:$PATH" FAKE_TAGS="$1" FAKE_EXIST="$2" \
  bash -c 'source "$1"; resolve_pre_zip_url "$2"' bash "$WORK/fn.sh" "testrepo"; }

pass=0; fail=0
check() { # name expected actual
  if [ "$2" = "$3" ]; then echo "  ok   $1"; pass=$((pass+1));
  else echo "  FAIL $1"; echo "       expected: [$2]"; echo "       actual:   [$3]"; fail=$((fail+1)); fi
}

echo "update --pre release resolution:"

# 1. The regression itself: newest tag is a PRE-RELEASE and it has a package.
#    releases/latest would never return v1.6.1 — this must.
check "prefers newest pre-release over stable" \
  "https://github.com/testrepo/releases/download/v1.6.1/wearsic-server-termux-v1.6.1.zip" \
  "$(run 'v1.6.1 v1.6.0' 'v1.6.1 v1.6.0')"

# 2. A DRAFT has no assets -> must be skipped, falling through to the next tag.
check "skips a draft with no published assets" \
  "https://github.com/testrepo/releases/download/v1.6.0/wearsic-server-termux-v1.6.0.zip" \
  "$(run 'v1.7.0-draft v1.6.0' 'v1.6.0')"

# 3. Several drafts in a row, then a real pre-release.
check "skips multiple drafts, lands on pre-release" \
  "https://github.com/testrepo/releases/download/v1.6.2/wearsic-server-termux-v1.6.2.zip" \
  "$(run 'v1.7.0-draft v1.6.9-draft v1.6.2 v1.6.0' 'v1.6.2 v1.6.0')"

# 4. Release that ships no server package at all -> skipped.
check "skips a release with no server package" \
  "https://github.com/testrepo/releases/download/v1.6.0/wearsic-server-termux-v1.6.0.zip" \
  "$(run 'v1.6.1 v1.6.0' 'v1.6.0')"

# 5. Nothing installable -> empty output (caller turns this into a clear error).
check "returns empty when no release has a package" "" "$(run 'v1.6.1 v1.6.0' '')"

# 6. Empty / failed API response must not explode or hang.
check "returns empty on empty API response" "" "$(run '' 'v1.6.0')"

# 7. Non-vacuity: the fake must actually be exercised — if resolve_pre_zip_url
#    were stubbed to echo nothing, case 1 would fail; if it ignored FAKE_TAGS,
#    cases 2-4 would fail. Prove the probe URL contains the winning tag.
got="$(run 'v1.6.1 v1.6.0' 'v1.6.1 v1.6.0')"
case "$got" in
  */v1.6.1/wearsic-server-termux-v1.6.1.zip) echo "  ok   resolution is tag-specific (not hardcoded)"; pass=$((pass+1)) ;;
  *) echo "  FAIL resolution is tag-specific (not hardcoded): [$got]"; fail=$((fail+1)) ;;
esac

# 8. Syntax check of the shipped CLI.
if bash -n "$CLI" 2>/dev/null; then echo "  ok   wearsic CLI parses"; pass=$((pass+1));
else echo "  FAIL wearsic CLI parses"; fail=$((fail+1)); fi

echo
if [ "$fail" -gt 0 ]; then echo "update-pre-test: $fail FAILED, $pass passed"; exit 1; fi
echo "update-pre-test: PASSED ($pass checks)"