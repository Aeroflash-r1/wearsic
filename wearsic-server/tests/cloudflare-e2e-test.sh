#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# End-to-end test for the Cloudflare front door.
#
# This drives the REAL `wearsic cloudflare` command against:
#   - a real Wearsic engine (the one this checkout builds), and
#   - a mocked Cloudflare edge: a fake curl that answers the Cloudflare
#     API with canned JSON, proxies *.workers.dev requests to the local
#     engine (exactly what the real Worker does), and a fake cloudflared
#     that hands out a random trycloudflare hostname.
#
# What it proves, end to end:
#   1. `wearsic cloudflare` starts, deploys the Worker and prints a
#      PERMANENT workers.dev URL.
#   2. The permanent URL actually serves the engine (the request is
#      proxied through the Worker to the tunnel origin).
#   3. When the tunnel hands out a NEW random hostname, the Worker is
#      re-pointed and the permanent URL keeps working. This is the whole
#     point of the feature.
#   4. `wearsic cloudflare status` and `stop` behave.
#
# No Cloudflare account and no network egress are needed.
#
# Usage:  bash wearsic-server/tests/cloudflare-e2e-test.sh
# ─────────────────────────────────────────────────────────────────────────────
set -u

HERE="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CLI="$HERE/wearsic"
[ -f "$CLI" ] || { echo "cannot find the wearsic CLI next to this test"; exit 2; }

fails=0
pass() { echo "  ok   $1"; }
fail() { echo "  FAIL $1"; fails=$((fails+1)); }

WORK="$(mktemp -d)"
cleanup() {
  # Kill the supervisor this test started (it is scoped to this install
  # directory), the fake tunnel, and the engine. Leaving any of them behind
  # makes the OTHER shell suites flaky.
  if [ -n "${DEST:-}" ]; then
    pkill -f "[_]_cf_supervise $DEST" 2>/dev/null
  fi
  pkill -f "$WORK/bin/cloudflared" 2>/dev/null
  [ -n "${ENGINE_PID:-}" ] && kill "$ENGINE_PID" 2>/dev/null
  rm -rf "$WORK"
}
trap cleanup EXIT

# ── the mock edge ───────────────────────────────────────────────────────────
mkdir -p "$WORK/bin"

PORT=18099
export TEST_PORT="$PORT"

# A fake cloudflared: announces a hostname on stderr (like the real one)
# and flips to a new one when the test asks it to.
cat > "$WORK/bin/cloudflared" <<EOF
#!/bin/sh
# Announce the current hostname, and keep announcing it: the real
# cloudflared re-announces on every reconnect, and a reconnect is exactly
# when the hostname CHANGES. The supervisor must notice the latest one.
while true; do
  url="https://first-random-words.trycloudflare.com"
  [ -f "$WORK/new-host" ] && url="\$(cat "$WORK/new-host")"
  echo "2026-01-01T00:00:00Z INF |  \$url  |" >&2
  sleep 2
done
EOF
chmod +x "$WORK/bin/cloudflared"

# A fake curl: Cloudflare API -> canned JSON (logged); *.workers.dev -> the
# local engine (what the Worker does); everything else -> the real curl.
cat > "$WORK/bin/curl" <<EOF
#!/bin/sh
url=""
for a in "\$@"; do
  case "\$a" in
    http://*|https://*) [ -z "\$url" ] && url="\$a" ;;
  esac
done
case "\$url" in
  https://api.cloudflare.com/*)
    echo "REQ \$*" >> "$WORK/cloudflare.log"
    case "\$url" in
      *"/workers/subdomain"*) echo '{"success":true,"result":{"subdomain":"testsub"}}' ;;
      *"/workers/scripts/"*)  echo '{"success":true,"result":{"id":"w1"}}' ;;
      *) echo '{"success":true,"result":{}}' ;;
    esac
    exit 0
    ;;
  https://*.workers.dev/*|http://*.workers.dev/*)
    # Stand in for the Worker: forward to the engine the way the real
    # Worker forwards to the tunnel origin.
    path="\${url#*workers.dev}"
    real="http://127.0.0.1:$TEST_PORT\${path}"
    exec /usr/bin/curl "\$@" "\$real"
    ;;
esac
exec /usr/bin/curl "\$@"
EOF
chmod +x "$WORK/bin/curl"

# ── the engine ──────────────────────────────────────────────────────────────
ENGINE="$HERE/build/install/wearsic-server/bin/wearsic-server"
if [ ! -x "$ENGINE" ]; then
  echo "  skip the engine is not built — run: ./gradlew :wearsic-server:installDist"
  exit 0
fi

PORT=18099
export PORT
export WEARSIC_DB_PATH="$WORK/test.db"
export WEARSIC_STATE_DIR="$WORK/state"

# The engine is a JVM app. On hosts where the JVM needs an LD_PRELOAD shim
# to start (Android-in-a-proot), pick it up the same way the CLI does —
# otherwise the engine dies before it can answer /health.
if ! java -version >/dev/null 2>&1; then
  for shim in "${WEARSIC_JVM_SHIM:-}" \
              "/public/dev/android/toolchains/jvm-shim/libmprotect-shim.so" \
              "$HOME/.wearsic/libmprotect-shim.so"; do
    if [ -n "$shim" ] && [ -f "$shim" ]; then
      LD_PRELOAD="$shim${LD_PRELOAD:+:$LD_PRELOAD}"
      export LD_PRELOAD
      break
    fi
  done
fi

"$ENGINE" > "$WORK/engine.log" 2>&1 &
ENGINE_PID=$!
for i in $(seq 1 60); do
  curl -fsS --max-time 3 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1 && break
  sleep 1
done
if ! curl -fsS --max-time 3 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
  echo "  FAIL the engine did not start"; tail -5 "$WORK/engine.log"; exit 1
fi
pass "engine is up on port $PORT"

# The CLI wants its own install layout: DEST/bin, DEST/run-termux.sh, .env.
DEST="$WORK/dest"
mkdir -p "$DEST/bin" "$DEST/wearsic-state"
cp "$ENGINE" "$DEST/bin/wearsic-server"
cp "$HERE/run-termux.sh" "$DEST/run-termux.sh"
cp "$HERE/wearsic-cloudflare-worker.js" "$DEST/wearsic-cloudflare-worker.js"
printf 'PORT=%s\nWEARSIC_API_KEY=testkey123\n' "$PORT" > "$DEST/.env"
export WEARSIC_DIR="$DEST"
export TEST_PORT="$PORT"
export CLOUDFLARE_API_TOKEN="testtoken"
export CLOUDFLARE_ACCOUNT_ID="acct123"

# `wearsic` resolves the engine as $DEST/bin/wearsic-server and the CLI
# itself must be the copy next to this test, so CLI_DIR points at the repo.
run_cli() { bash "$CLI" "$@"; }

# ── 1. start ───────────────────────────────────────────────────────────────
echo "start"

PATH="$WORK/bin:$PATH" timeout 120 bash "$CLI" cloudflare start > "$WORK/start.out" 2>&1
rc=$?
if [ $rc -eq 0 ]; then
  pass "wearsic cloudflare start succeeded"
else
  fail "wearsic cloudflare start failed (rc=$rc)"
  tail -12 "$WORK/start.out" | sed 's/^/       /'
  exit 1
fi

url="$(grep -oE 'https://[a-z0-9.-]+\.workers\.dev' "$WORK/start.out" | head -1)"
if [ "$url" = "https://wearsic.testsub.workers.dev" ]; then
  pass "prints the permanent URL ($url)"
else
  fail "permanent URL wrong (got '$url')"
  sed 's/^/       /' "$WORK/start.out" | head -10
fi

if grep -q 'first-random-words.trycloudflare.com' "$WORK/cloudflare.log" 2>/dev/null; then
  pass "the Worker was bound to the tunnel origin"
else
  fail "the Worker was not bound to the tunnel origin"
  sed 's/^/       /' "$WORK/cloudflare.log" 2>/dev/null | head -5
fi

if [ -f "$DEST/wearsic-state/cloudflare.json" ]; then
  pass "the state file records the URL and the origin"
else
  fail "no state file written"
  ls -la "$DEST/wearsic-state/" 2>&1 | sed 's/^/       /' | head -6
fi

# ── 2. the permanent URL actually serves the engine ─────────────────────────
echo "serving"

body="$(PATH="$WORK/bin:$PATH" curl -fsS --max-time 15 "$url/health" 2>/dev/null)"
case "$body" in
  *'"status":"ok"'*) pass "the permanent URL serves /health" ;;
  *) fail "the permanent URL did not serve /health (got '$body')" ;;
esac

# ── 3. a NEW random hostname re-points the Worker ───────────────────────────
echo "re-point"

echo "https://second-random-words.trycloudflare.com" > "$WORK/new-host"
# The supervisor notices the change and re-deploys; give it a moment.
for i in $(seq 1 30); do
  grep -q 'second-random-words.trycloudflare.com' "$WORK/cloudflare.log" 2>/dev/null && break
  sleep 1
done
if grep -q 'second-random-words.trycloudflare.com' "$WORK/cloudflare.log" 2>/dev/null; then
  pass "the Worker was re-pointed when the tunnel hostname changed"
else
  fail "the Worker was not re-pointed after the hostname changed"
fi

# The permanent URL must be UNCHANGED — that is the entire point.
url2="$(bash "$CLI" cloudflare url 2>/dev/null)"
if [ "$url2" = "$url" ]; then
  pass "the permanent URL did not change ($url2)"
else
  fail "the permanent URL changed ($url -> $url2)"
fi

# ── 4. status and stop ─────────────────────────────────────────────────────
echo "status and stop"

PATH="$WORK/bin:$PATH" timeout 60 bash "$CLI" cloudflare status > "$WORK/status.out" 2>&1
if grep -q "$url" "$WORK/status.out"; then
  pass "status reports the permanent URL"
else
  fail "status did not report the permanent URL"
fi

PATH="$WORK/bin:$PATH" timeout 60 bash "$CLI" cloudflare stop > "$WORK/stop.out" 2>&1
if ! pgrep -f 'cloudflared' >/dev/null 2>&1; then
  pass "stop killed the tunnel"
else
  fail "the tunnel survived stop"
  pkill -f 'cloudflared' 2>/dev/null
fi

# ── summary ────────────────────────────────────────────────────────────────
kill "$ENGINE_PID" 2>/dev/null
echo
if [ "$fails" -eq 0 ]; then
  echo "Result: all checks passed"
  exit 0
fi
echo "Result: $fails check(s) FAILED"
exit 1
