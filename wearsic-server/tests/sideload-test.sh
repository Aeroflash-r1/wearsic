#!/usr/bin/env bash
# Offline regression test for `wearsic sideload`.
#
# Extracts cmd_sideload from the SHIPPED CLI (not a copy), stubs the
# presentation helpers, and fakes curl + adb, so every decision that does
# not need a real watch is pinned:
#   * latest release resolves to the predictable asset URL (Wearsic-<tag>.apk)
#   * published sha256: verified / corrupt (refused) / missing (warned)
#   * adb selection: one device auto, several demand --device, none prints
#     the wireless-debugging guide, missing adb names the Termux package
#   * --apk skips the network entirely, --download-only stops before adb
set -uo pipefail

CLI="${CLI:-$(cd "$(dirname "$0")/.." && pwd)/wearsic}"
[ -f "$CLI" ] || { echo "FAIL cannot find CLI at $CLI"; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin" "$WORK/minimal"

# The shipped function, verbatim.
sed -n '/^cmd_sideload() {/,/^}/p' "$CLI" > "$WORK/fn.sh"
[ -s "$WORK/fn.sh" ] || { echo "FAIL could not extract cmd_sideload from the CLI"; exit 1; }

# Presentation helpers are stubbed: this test checks decisions, not pixels.
cat > "$WORK/stubs.sh" <<'STUBS'
brand() { :; }
section() { echo "SECTION $*"; }
say() { echo "SAY $*"; }
note() { say "$*"; }
ok() { echo "OK $*"; }
warn() { echo "WARN $*"; }
err() { echo "ERR $*" >&2; }
die() { echo "DIE $*" >&2; exit 1; }
kv() { echo "KV $1=$2"; }
C_BOLD=""; C_RESET=""
download_bar() { echo "GET $1 -> $2" >> "$CALLS"; cp "$FIXTURE_APK" "$2"; }
STUBS

printf 'fake apk payload\n' > "$WORK/fixture.apk"
FIX_SHA="$(sha256sum "$WORK/fixture.apk" | awk '{ print $1 }')"

# --- fake curl: the GitHub API + the release .sha256 -----------------------
cat > "$WORK/bin/curl" <<'FAKECURL'
#!/usr/bin/env bash
url=""; out=""
while [ $# -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift 2 ;;
    http://*|https://*) url="$1"; shift ;;
    *) shift ;;
  esac
done
echo "CURL $url" >> "$CALLS"
case "$url" in
  */releases/latest)
    if [ "${FAKE_LATEST:-v9.9.9}" = "none" ]; then exit 22; fi
    printf '{"tag_name":"%s"}\n' "$FAKE_LATEST"
    exit 0 ;;
  *.apk.sha256)
    case "${FAKE_SHA:-good}" in
      good) printf '%s  Wearsic-x.apk\n' "$FIX_SHA" > "$out" ;;
      corrupt) printf 'deadbeef  Wearsic-x.apk\n' > "$out" ;;
      missing) exit 22 ;;
    esac
    exit 0 ;;
esac
exit 0
FAKECURL
chmod +x "$WORK/bin/curl"
# The no-adb case runs with a minimal tool farm: everything cmd_sideload
# needs EXCEPT adb and curl, plus the fake curl. A system-wide adb (CI
# images with android-tools, developer machines) must not leak in and flip
# the branch under test.
for t in sha256sum awk sed grep tr head basename mkdir rm cp mktemp cat bash; do
  p="$(command -v "$t" 2>/dev/null)" && ln -sf "$p" "$WORK/minimal/$t"
done
cp "$WORK/bin/curl" "$WORK/minimal/curl"

# --- fake adb: device list + install + dumpsys -----------------------------
cat > "$WORK/bin/adb" <<'FAKEADB'
#!/usr/bin/env bash
echo "ADB $*" >> "$CALLS"
if [ "$1" = "devices" ]; then
  printf 'List of devices attached\n'
  for d in ${FAKE_ADB_DEVICES:-}; do printf '%s\tdevice\n' "$d"; done
  exit 0
fi
if [ "$1" = "-s" ]; then shift; fi
serial="$1"; shift
cmd="${1:-}"; shift || true
case "$cmd" in
  install)
    if [ "${FAKE_ADB_INSTALL:-success}" = "success" ]; then
      echo "Performing Streamed Install"
      echo "Success"
      exit 0
    fi
    echo "Failure [INSTALL_FAILED_${FAKE_ADB_INSTALL}]"
    exit 1 ;;
  shell)
    case "${1:-}" in
      getprop) echo "Galaxy Watch7" ;;
      dumpsys) echo "    versionName=9.9.9" ;;
    esac
    exit 0 ;;
esac
exit 0
FAKEADB
chmod +x "$WORK/bin/adb"

# --- harness ---------------------------------------------------------------
FAKE_LATEST=""; FAKE_SHA=""; FAKE_ADB_DEVICES=""; FAKE_ADB_INSTALL=""; NO_ADB=""; CACHEDIR=""
defaults() { FAKE_LATEST=v9.9.9; FAKE_SHA=good; FAKE_ADB_DEVICES="SERIAL"; FAKE_ADB_INSTALL=success; NO_ADB=0; CACHEDIR=""; }

run() { # first arg is the literal 'sideload' (as typed on the CLI); the
  # function is invoked directly here, so drop it and pass the rest through.
  shift
  local path="$WORK/bin:/usr/bin:/bin"
  [ "$NO_ADB" = "1" ] && path="$WORK/minimal"
  # Fresh cache per invocation: `run` executes in a command-substitution
  # subshell, so a counter would NOT survive between cases — mktemp does.
  local cache="${CACHEDIR:-}"
  [ -n "$cache" ] || cache="$(mktemp -d "$WORK/cache-XXXXXX")"
  : > "$WORK/calls"
  CALLS="$WORK/calls" FIXTURE_APK="$WORK/fixture.apk" FIX_SHA="$FIX_SHA" \
  PATH="$path" WEARSIC_APK_CACHE="$cache" WEARSIC_REPO="testrepo" \
  FAKE_LATEST="$FAKE_LATEST" FAKE_SHA="$FAKE_SHA" \
  FAKE_ADB_DEVICES="$FAKE_ADB_DEVICES" FAKE_ADB_INSTALL="$FAKE_ADB_INSTALL" \
  bash -c 'source "$1"; source "$2"; cmd_sideload "${@:3}"' bash \
    "$WORK/stubs.sh" "$WORK/fn.sh" "$@" 2>&1
}

pass=0; fail=0
check() { # name expected actual
  if [ "$2" = "$3" ]; then echo "  ok   $1"; pass=$((pass + 1));
  else echo "  FAIL $1"; echo "       expected: [$2]"; echo "       actual:   [$3]"; fail=$((fail + 1)); fi
}
check_has() { # name needle haystack
  case "$3" in
    *"$2"*) echo "  ok   $1"; pass=$((pass + 1)) ;;
    *) echo "  FAIL $1"; echo "       missing: [$2]"; fail=$((fail + 1)) ;;
  esac
}
check_calls() { # name needle
  if grep -qF -- "$2" "$WORK/calls" 2>/dev/null; then echo "  ok   $1"; pass=$((pass + 1));
  else echo "  FAIL $1"; echo "       no call containing: [$2]"; fail=$((fail + 1)); fi
}
check_no_calls() { # name needle
  if grep -qF -- "$2" "$WORK/calls" 2>/dev/null; then echo "  FAIL $1"; echo "       unexpected call: [$2]"; fail=$((fail + 1));
  else echo "  ok   $1"; pass=$((pass + 1)); fi
}

echo "sideload release + adb behaviour:"

# 1. Latest release, checksum good, one connected watch: the full happy path.
defaults
out="$(run sideload)"; rc=$?
check "happy path exits 0" "0" "$rc"
check_calls "downloads the predictable APK path" \
  "GET https://github.com/testrepo/releases/download/v9.9.9/Wearsic-v9.9.9.apk -> "
check_has "confirms the checksum" "OK APK checksum verified" "$out"
check_calls "installs with adb -r on the only device" "ADB -s SERIAL install -r"
check_has "confirms the install" "OK installed Wearsic-v9.9.9.apk" "$out"
check_has "reports the installed version" "KV on watch=9.9.9" "$out"

# 2. Corrupt download: MUST be refused before adb is ever consulted.
defaults; FAKE_SHA=corrupt
out="$(run sideload)"; rc=$?
check "corrupt checksum exits non-zero" "1" "$rc"
check_has "names the mismatch" "DIE checksum mismatch" "$out"
check_no_calls "corrupt APK never reaches adb" "ADB "

# 3. Older release with no published checksum: warn, then install.
defaults; FAKE_SHA=missing
out="$(run sideload)"; rc=$?
check "missing checksum still installs" "0" "$rc"
check_has "warns instead of failing" "WARN this release ships no APK checksum" "$out"
check_calls "install proceeds without a checksum" "ADB -s SERIAL install -r"

# 4. GitHub unreachable: clear error, nothing downloaded.
defaults; FAKE_LATEST=none
out="$(run sideload)"; rc=$?
check "API unreachable exits non-zero" "1" "$rc"
check_has "says GitHub could not be reached" "DIE could not reach GitHub" "$out"
check_no_calls "nothing is downloaded" "GET "

# 5. No watch attached: the wireless-debugging guide.
defaults; FAKE_ADB_DEVICES=""
out="$(run sideload)"; rc=$?
check "no device exits non-zero" "1" "$rc"
check_has "prints the steps" "no watch is connected over adb" "$out"
check_has "shows the pairing command" "adb pair" "$out"
check_no_calls "no install attempt" "ADB -s "

# 6. Several devices: must demand --device instead of guessing.
defaults; FAKE_ADB_DEVICES="AAA BBB"
out="$(run sideload)"; rc=$?
check "several devices exits non-zero" "1" "$rc"
check_has "asks for --device" "several adb devices" "$out"
check_no_calls "no install attempt" "ADB -s "

# 7. --device with a matching serial installs on exactly that watch.
defaults; FAKE_ADB_DEVICES="AAA PICKME"
out="$(run sideload --device PICKME)"; rc=$?
check "explicit --device installs" "0" "$rc"
check_calls "used the chosen serial" "ADB -s PICKME install -r"

# 8. --device that is not connected is a named error, not a silent miss.
defaults; FAKE_ADB_DEVICES="AAA"
out="$(run sideload --device NOPE)"; rc=$?
check "--device not present exits non-zero" "1" "$rc"
check_has "says the device is not connected" "is not connected" "$out"

# 9. adb missing entirely: name the Termux package.
defaults; NO_ADB=1
out="$(run sideload)"; rc=$?
check "missing adb exits non-zero" "1" "$rc"
check_has "names the Termux package" "pkg install android-tools" "$out"

# 10. --apk installs a local build with zero network calls.
defaults
out="$(run sideload --apk "$WORK/fixture.apk")"; rc=$?
check "local APK installs" "0" "$rc"
check_no_calls "local APK never touches the network" "CURL "
check_no_calls "no download either" "GET "
check_calls "installs the local build" "ADB -s SERIAL install -r"

# 11. --download-only fetches and stops before any adb use.
defaults
out="$(run sideload --download-only)"; rc=$?
check "download-only exits 0" "0" "$rc"
check_has "says it saved the APK" "OK saved Wearsic-v9.9.9.apk" "$out"
check_no_calls "stops before adb" "ADB "

# 12. An explicit tag never consults releases/latest.
defaults
out="$(run sideload v1.2.3)"; rc=$?
check "explicit tag installs" "0" "$rc"
check_calls "asset URL follows the tag" \
  "GET https://github.com/testrepo/releases/download/v1.2.3/Wearsic-v1.2.3.apk -> "
check_no_calls "no releases/latest probe" "CURL https://api.github.com"

# 13. A cached, checksum-pinned APK reuses the cache instead of re-downloading.
defaults
CACHEDIR="$WORK/cache-pin"; mkdir -p "$CACHEDIR"
cp "$WORK/fixture.apk" "$CACHEDIR/Wearsic-v9.9.9.apk"
printf '%s  Wearsic-v9.9.9.apk\n' "$FIX_SHA" > "$CACHEDIR/Wearsic-v9.9.9.apk.sha256"
out="$(run sideload)"; rc=$?
check "cached APK installs" "0" "$rc"
check_has "says the cache is reused" "Using the cached download" "$out"
check_no_calls "no re-download" "GET "
CACHEDIR=""

# 14. Debug-signature collision: explain, don't leave the user guessing.
defaults; FAKE_ADB_INSTALL=UPDATE_INCOMPATIBLE
out="$(run sideload)"; rc=$?
check "incompatible signature exits non-zero" "1" "$rc"
check_has "explains the signature mismatch" "signature mismatch" "$out"
check_has "gives the uninstall command" "uninstall com.wearsic.app" "$out"

echo
if [ "$fail" -eq 0 ]; then
  echo "PASS ($pass checks)"
  exit 0
fi
echo "FAILED ($fail of $((pass + fail)) checks)"
exit 1
