#!/usr/bin/env bash
# Offline regression test for `wearsic pair`.
#
# Extracts the SHIPPED functions (cmd_pair, its funnel_url dependency and
# the first-occurrence JSON readers funnel_url uses), stubs the presentation
# helpers, and fakes adb + curl + tailscale, so every decision that does not
# need a real watch or phone is pinned:
#   * the pairing file lands at the documented path with the exact two-line
#     payload (URL + key) and is read back over adb before reporting success
#   * the address is probed FIRST; failures abort with guidance unless
#     --skip-check is given
#   * LAN default / --funnel / explicit URL resolution, including the
#     "never public without a key" rule and the app's 8-char key floor
#   * adb selection: one device auto, several demand --device, none prints
#     the wireless-debugging guide, missing adb names the Termux package
#   * --restart force-stops the watch app so the next open imports
#   * funnel_url reads the node's own DNSName (first occurrence — Self
#     precedes Peer — with the trailing dot stripped)
set -uo pipefail

CLI="${CLI:-$(cd "$(dirname "$0")/.." && pwd)/wearsic}"
[ -f "$CLI" ] || { echo "FAIL cannot find CLI at $CLI"; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin" "$WORK/minimal"

# The shipped functions, verbatim.
sed -n '/^json_tail() {/,/^}/p' "$CLI" > "$WORK/json.sh"
sed -n '/^jstr() {/,/^}/p' "$CLI" >> "$WORK/json.sh"
sed -n '/^funnel_url() {/,/^}/p' "$CLI" > "$WORK/funnel_url.sh"
sed -n '/^cmd_pair() {/,/^}/p' "$CLI" > "$WORK/fn.sh"
[ -s "$WORK/fn.sh" ] || { echo "FAIL could not extract cmd_pair from the CLI"; exit 1; }
[ -s "$WORK/funnel_url.sh" ] || { echo "FAIL could not extract funnel_url from the CLI"; exit 1; }
grep -q json_tail "$WORK/json.sh" || { echo "FAIL could not extract json_tail/jstr"; exit 1; }

# Presentation helpers and the data dependencies are stubbed: this test
# checks decisions, not pixels — and not lan_ip, which lan-ip-test.sh owns.
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
box_begin() { BOX_TITLE="$1"; }
box_line() { echo "BOXLINE $*"; }
box_end() { echo "BOXEND $BOX_TITLE"; }
C_BOLD=""; C_RESET=""; C_DIM=""; C_ACC=""; C_WARN=""; C_OK=""; C_ERR=""; C_BRAND=""
installed() { :; }
port() { echo "8080"; }
api_key() { printf '%s' "$FAKE_API_KEY"; }
server_url() { printf '%s' "$FAKE_SERVER_URL"; }
STUBS

# --- fake adb: device list + push/readback against a local store ----------
cat > "$WORK/bin/adb" <<'FAKEADB'
#!/usr/bin/env bash
echo "ADB $*" >> "$CALLS"
if [ "$1" = "devices" ]; then
  printf 'List of devices attached\n'
  for d in ${FAKE_ADB_DEVICES:-}; do printf '%s\tdevice\n' "$d"; done
  exit 0
fi
if [ "$1" = "-s" ]; then shift; fi
serial="$1"; shift || true
cmd="${1:-}"; shift || true
case "$cmd" in
  push)
    if [ "${FAKE_ADB_PUSH_FAIL:-0}" = "1" ]; then
      echo "adb: error: failed to copy"
      exit 1
    fi
    cp "$1" "$FAKE_ADB_STORE"
    exit 0 ;;
  shell)
    case "${1:-}" in
      getprop) echo "Galaxy Watch7" ;;
      mkdir) exit 0 ;;
      cat)
        if [ "${FAKE_ADB_CORRUPT:-0}" = "1" ]; then echo "garbage"; else cat "$FAKE_ADB_STORE"; fi
        exit 0 ;;
      am) exit 0 ;;
    esac
    exit 0 ;;
esac
exit 0
FAKEADB
chmod +x "$WORK/bin/adb"

# --- fake curl: the reachability probe, exit code injected ----------------
cat > "$WORK/bin/curl" <<'FAKECURL'
#!/usr/bin/env bash
echo "CURL $*" >> "$CALLS"
exit "${FAKE_CURL_RC:-0}"
FAKECURL
chmod +x "$WORK/bin/curl"

# --- fake tailscale: `status --json` serves the fixture -------------------
cat > "$WORK/bin/tailscale" <<'FAKETS'
#!/usr/bin/env bash
case "${1:-}" in
  status) [ -n "${FAKE_TS_JSON:-}" ] && printf '%s\n' "$FAKE_TS_JSON" ;;
esac
exit 0
FAKETS
chmod +x "$WORK/bin/tailscale"

# A minimal tool farm WITHOUT adb/curl/tailscale, so the no-adb case cannot
# be flipped by a system-installed binary leaking through PATH.
for t in awk sed grep tr head cat cp mkdir rm mktemp bash sh; do
  p="$(command -v "$t" 2>/dev/null)" && ln -sf "$p" "$WORK/minimal/$t"
done

# --- harness ---------------------------------------------------------------
FAKE_API_KEY=""; FAKE_SERVER_URL=""; FAKE_ADB_DEVICES=""; FAKE_ADB_PUSH_FAIL=0
FAKE_ADB_CORRUPT=0; FAKE_CURL_RC=0; FAKE_TS_JSON=""; NO_ADB=0; NO_TAILSCALE=0
defaults() {
  FAKE_API_KEY="sekret-key-1"
  FAKE_SERVER_URL="http://192.168.1.50:8080"
  FAKE_ADB_DEVICES="SERIAL"
  FAKE_ADB_PUSH_FAIL=0
  FAKE_ADB_CORRUPT=0
  FAKE_CURL_RC=0
  FAKE_TS_JSON='{"BackendState":"Running","Self":{"ID":"node1","DNSName":"phone.tailnet.ts.net."},"Peer":{"p":{"DNSName":"peer.tailnet.ts.net."}}}'
  NO_ADB=0
  NO_TAILSCALE=0
}

run() { # first arg is the literal 'pair' (as typed on the CLI)
  shift
  local path="$WORK/bin:/usr/bin:/bin"
  [ "$NO_ADB" = "1" ] && path="$WORK/minimal"
  : > "$WORK/calls"
  : > "$WORK/push-store"
  CALLS="$WORK/calls" FAKE_ADB_STORE="$WORK/push-store" PATH="$path" \
  FAKE_API_KEY="$FAKE_API_KEY" FAKE_SERVER_URL="$FAKE_SERVER_URL" \
  FAKE_ADB_DEVICES="$FAKE_ADB_DEVICES" FAKE_ADB_PUSH_FAIL="$FAKE_ADB_PUSH_FAIL" \
  FAKE_ADB_CORRUPT="$FAKE_ADB_CORRUPT" FAKE_CURL_RC="$FAKE_CURL_RC" \
  FAKE_TS_JSON="$FAKE_TS_JSON" \
  bash -c 'source "$1"; source "$2"; source "$3"; source "$4"; cmd_pair "${@:5}"' bash \
    "$WORK/stubs.sh" "$WORK/json.sh" "$WORK/funnel_url.sh" "$WORK/fn.sh" "$@" 2>&1
}

run_furl() { # funnel_url alone, against the fake tailscale
  : > "$WORK/calls"
  local path="$WORK/bin:/usr/bin:/bin"
  [ "$NO_TAILSCALE" = "1" ] && path="$WORK/minimal"
  PATH="$path" FAKE_TS_JSON="$FAKE_TS_JSON" \
  bash -c 'source "$1"; source "$2"; funnel_url' bash \
    "$WORK/json.sh" "$WORK/funnel_url.sh" 2>&1
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
check_no_has() { # name needle haystack
  case "$3" in
    *"$2"*) echo "  FAIL $1"; echo "       unexpected: [$2]"; fail=$((fail + 1)) ;;
    *) echo "  ok   $1"; pass=$((pass + 1)) ;;
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
store() { cat "$WORK/push-store"; }
two_line() { printf 'WEARSIC_SERVER_URL=%s\nWEARSIC_API_KEY=%s' "$1" "$2"; }

echo "pair: deliver address + key to the watch over adb:"

# 1. Explicit URL, one connected watch: the full happy path.
defaults
out="$(run pair https://phone.tailnet.ts.net)"; rc=$?
check "explicit URL exits 0" "0" "$rc"
check_calls "probes /health before delivering" "CURL -fsS -m 15 https://phone.tailnet.ts.net/health"
check_calls "pushes to the app's external files dir" \
  "ADB -s SERIAL push"
check_calls "uses the documented pairing path" \
  "/sdcard/Android/data/com.wearsic.app/files/wearsic-pairing.env"
check_calls "reads the file back over adb" \
  "ADB -s SERIAL shell cat /sdcard/Android/data/com.wearsic.app/files/wearsic-pairing.env"
check "payload is the two-line contract" \
  "$(two_line https://phone.tailnet.ts.net sekret-key-1)" "$(store)"
check_has "confirms delivery" "OK address + key delivered to the watch" "$out"
check_has "points at the last step" "Open Wearsic on the watch" "$out"
check_no_has "never prints the key" "sekret-key-1" "$out"

# 2. No URL: the detected LAN address is the default.
defaults
out="$(run pair)"; rc=$?
check "default LAN pairing exits 0" "0" "$rc"
check_calls "probes over http with the short timeout" \
  "CURL -fsS -m 8 http://192.168.1.50:8080/health"
check "pushed the LAN URL" \
  "$(two_line http://192.168.1.50:8080 sekret-key-1)" "$(store)"

# 3. Wi-Fi probe came up empty: guidance, not a broken push.
defaults; FAKE_SERVER_URL=""
out="$(run pair)"; rc=$?
check "no LAN IP exits non-zero" "1" "$rc"
check_has "explains the detection failure" "couldn't detect this phone's Wi-Fi IP" "$out"
check_has "offers the funnel alternative" "--funnel" "$out"
check_no_calls "nothing is pushed" "ADB "

# 4. --funnel without a key: refused — public exposure needs auth.
defaults; FAKE_API_KEY=""
out="$(run pair --funnel)"; rc=$?
check "public pairing without a key is refused" "1" "$rc"
check_has "names the rule" "public" "$out"
check_no_calls "nothing is pushed" "ADB "

# 5. --funnel happy path: the URL comes from tailscale's node name.
defaults
out="$(run pair --funnel)"; rc=$?
check "funnel pairing exits 0" "0" "$rc"
check_calls "the funnel URL is probed end-to-end" "CURL -fsS -m 15 https://phone.tailnet.ts.net/health"
check "pushed the funnel URL" \
  "$(two_line https://phone.tailnet.ts.net sekret-key-1)" "$(store)"

# 6. Unreachable funnel: do NOT hand the watch a dead address.
defaults; FAKE_CURL_RC=22
out="$(run pair --funnel)"; rc=$?
check "unreachable funnel exits non-zero" "1" "$rc"
check_has "reports exactly what failed" "no answer from https://phone.tailnet.ts.net/health" "$out"
check_has "suggests enabling the funnel" "wearsic funnel" "$out"
check_has "hints at the override" "--skip-check" "$out"
check_no_calls "nothing is pushed" "ADB "

# 7. Server down on LAN: same gate, LAN guidance.
defaults; FAKE_CURL_RC=7
out="$(run pair)"; rc=$?
check "dead server exits non-zero" "1" "$rc"
check_has "suggests starting it" "wearsic start" "$out"
check_no_calls "nothing is pushed" "ADB "

# 8. --skip-check honors the explicit override and skips the probe entirely.
defaults; FAKE_CURL_RC=7
out="$(run pair --skip-check)"; rc=$?
check "--skip-check forces delivery" "0" "$rc"
check_no_calls "no probe is made" "CURL "
check_calls "the file still lands" "ADB -s SERIAL push"

# 9. adb missing entirely: name the Termux package (before any probing).
defaults; NO_ADB=1
out="$(run pair)"; rc=$?
check "missing adb exits non-zero" "1" "$rc"
check_has "names the Termux package" "pkg install android-tools" "$out"
check_no_calls "no probe before the adb gate" "CURL "

# 10. No watch attached: the wireless-debugging guide.
defaults; FAKE_ADB_DEVICES=""
out="$(run pair)"; rc=$?
check "no device exits non-zero" "1" "$rc"
check_has "prints the steps" "no watch is connected over adb" "$out"
check_has "shows the pairing command" "adb pair" "$out"
check_no_calls "no push attempt" "push"

# 11. Several devices: must demand --device instead of guessing.
defaults; FAKE_ADB_DEVICES="AAA BBB"
out="$(run pair)"; rc=$?
check "several devices exits non-zero" "1" "$rc"
check_has "asks for --device" "several adb devices" "$out"
check_no_calls "no push attempt" "push"

# 12. --device picks the exact watch.
defaults; FAKE_ADB_DEVICES="AAA PICKME"
out="$(run pair --device PICKME)"; rc=$?
check "explicit --device pairs" "0" "$rc"
check_calls "push targets the chosen serial" "ADB -s PICKME push"

# 13. A failed push is a named error, never a silent success.
defaults; FAKE_ADB_PUSH_FAIL=1
out="$(run pair)"; rc=$?
check "failed push exits non-zero" "1" "$rc"
check_has "says the push failed" "adb push failed" "$out"

# 14. Truncated/blocked read-back: refuse to report success.
defaults; FAKE_ADB_CORRUPT=1
out="$(run pair)"; rc=$?
check "corrupt read-back exits non-zero" "1" "$rc"
check_has "refuses an unverifiable file" "did not read back correctly" "$out"

# 15. Key shorter than the app's floor: refuse BEFORE it can silently fail.
defaults; FAKE_API_KEY="short"
out="$(run pair)"; rc=$?
check "short key exits non-zero" "1" "$rc"
check_has "names the app's floor" "8 characters" "$out"
check_no_calls "nothing is pushed" "ADB "

# 16. LAN without a key: allowed, and the key LINE is still written empty
#     (the app clears its stored key — no stale secret survives a server
#     that has none).
defaults; FAKE_API_KEY=""
out="$(run pair)"; rc=$?
check "open-LAN pairing exits 0" "0" "$rc"
check "key line present but empty" \
  "$(two_line http://192.168.1.50:8080 "")" "$(store)"
check_has "warns the LAN is open" "none set" "$out"

# 17. --restart closes the app so the next open is a cold start (imports).
defaults
out="$(run pair --restart)"; rc=$?
check "--restart exits 0" "0" "$rc"
check_calls "force-stops the watch app" "ADB -s SERIAL shell am force-stop com.wearsic.app"
check_has "explains the restart" "next open applies" "$out"

# 18. A trailing slash is stripped so the app joins paths cleanly.
defaults
out="$(run pair http://192.168.1.9:8080/)"; rc=$?
check "trailing slash is stripped" \
  "$(two_line http://192.168.1.9:8080 sekret-key-1)" "$(store)"

# 19. A URL that is nothing but a scheme is rejected, not pushed.
defaults
out="$(run pair "http://")"; rc=$?
check "schemeless host exits non-zero" "1" "$rc"
check_has "explains the expected shape" "must look like http://host:port" "$out"
check_no_calls "nothing is pushed" "ADB "

echo
echo "funnel_url derivation:"

# 20. The node's own name wins — a peer's DNSName later in the JSON must
#     never be picked up (first occurrence, Self precedes Peer).
FAKE_TS_JSON='{"BackendState":"Running","Self":{"ID":"n","DNSName":"phone.tailnet.ts.net."},"Peer":{"p":{"DNSName":"peer.tailnet.ts.net."}}}'
NO_TAILSCALE=0
out="$(run_furl)"
check "reads the node's own DNSName" "https://phone.tailnet.ts.net" "$out"

# 21. Logged out (no name yet): nothing to pair to — print nothing.
FAKE_TS_JSON='{"BackendState":"NeedsLogin","Self":{"DNSName":""},"Peer":{}}'
out="$(run_furl)"
check "no node name prints nothing" "" "$out"

# 22. tailscale absent entirely: print nothing (cmd_pair turns that into
#     "set up the funnel first" guidance).
FAKE_TS_JSON='{"Self":{"DNSName":"phone.tailnet.ts.net."}}'
NO_TAILSCALE=1
out="$(run_furl)"
check "no tailscale prints nothing" "" "$out"
NO_TAILSCALE=0

echo
if [ "$fail" -eq 0 ]; then
  echo "pair: all $((pass + fail)) checks passed"
else
  echo "pair: $fail FAILED, $pass passed"
  exit 1
fi
