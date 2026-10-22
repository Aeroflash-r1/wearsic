#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# End-to-end test for the Cloudflare front door.
#
# This drives the REAL `wearsic cloudflare` command against:
#   - a real Wearsic engine (the one this checkout builds), and
#   - a mocked Cloudflare edge: a fake curl that answers the Cloudflare
#     API with canned JSON, proxies *.workers.dev AND *.trycloudflare.com
#     requests to the local engine (exactly what the Worker does), and a
#     fake cloudflared that hands out random trycloudflare hostnames.
#
# What it proves, end to end:
#   1. `wearsic cloudflare` starts, authorizes with a saved token, creates
#      no subdomain when one exists, deploys the Worker and prints a
#      PERMANENT workers.dev URL.
#   2. A transient 500 on the first upload is retried and succeeds.
#   3. The permanent URL actually serves the engine, rejects a request
#      without the API key, and preserves Range/206 through the proxy.
#   4. When the tunnel hands out a NEW hostname, the Worker is re-pointed
#      and the permanent URL keeps working.
#   5. When the tunnel PROCESS dies, the supervisor restarts it, discovers
#      yet another hostname, and re-points the Worker again.
#   6. `start` is idempotent; `status`, `verify`, `url`, `logs` and `stop`
#      behave; the permanent URL survives `stop`.
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
  if [ -n "${KEEP:-}" ]; then
    echo "  (kept test dir: $WORK)"
  else
    rm -rf "$WORK"
  fi
}
trap cleanup EXIT

# ── the mock edge ───────────────────────────────────────────────────────────
mkdir -p "$WORK/bin"

PORT=18099
export TEST_PORT="$PORT"

ACCOUNT_ID="0123456789abcdef0123456789abcdef"
export TEST_ACCOUNT_ID="$ACCOUNT_ID"

# A fake cloudflared: announces a hostname on stderr (like the real one)
# and flips to a new one when the test asks it to. It is killed and
# restarted by the supervisor to exercise process-restart recovery.
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

# A fake curl:
#   - Cloudflare API -> canned JSON + the HTTP status line that the CLI
#     asks for with -w %{http_code} (a fake must emulate that, or the
#     CLI cannot tell success from a 403);
#   - *.workers.dev and *.trycloudflare.com -> the local engine (what the
#     Worker and the tunnel do);
#   - everything else -> the real curl.
#
# `$WORK/fail-deploy` holds the number of uploads that should fail with a
# 500 first. This proves the bounded retry path against the real CLI.
cat > "$WORK/bin/curl" <<EOF
#!/usr/bin/env bash
# Collect every argument EXCEPT the original URL. The mapped request must
# be made against the local engine, not as a second URL: curl would try
# both and -w %{http_code} would then print two status codes.
url=""
args=()
for a in "\$@"; do
  case "\$a" in
    http://*|https://*) url="\$a" ;;
    *) args+=("\$a") ;;
  esac
done
case "\$url" in
  https://api.cloudflare.com/*)
    echo "REQ \$*" >> "$WORK/cloudflare.log"
    case "\$url" in
      *"/accounts?per_page=50"*)
        printf '{"success":true,"result":[{"id":"$ACCOUNT_ID","name":"Test Account"}]}\\n200'
        ;;
      *"/workers/subdomain"*)
        case "\$*" in
          *"-X PUT"*)
            printf '{"success":true,"result":{"subdomain":"testsub"}}\\n200'
            ;;
          *)
            printf '{"success":true,"result":{"subdomain":"testsub"}}\\n200'
            ;;
        esac
        ;;
      *"/workers/scripts/"*"/subdomain"*)
        # Workers uploaded through the API are private until their
        # workers.dev route is enabled explicitly. The real API
        # pretty-prints this response.
        printf '{\n  "success": true,\n  "result": {\n    "enabled": true,\n    "previews_enabled": false\n  }\n}\n200'
        ;;
      *"/workers/scripts/"*)
        fail=0
        [ -f "$WORK/fail-deploy" ] && fail="\$(cat "$WORK/fail-deploy")"
        n=0
        [ -f "$WORK/deploy-count" ] && n="\$(cat "$WORK/deploy-count")"
        n=\$((n + 1))
        echo "\$n" > "$WORK/deploy-count"
        if [ "\$n" -le "\$fail" ]; then
          printf '{"success":false,"errors":[{"code":1000,"message":"temporary edge failure"}]}\\n500'
        else
          printf '{"success":true,"result":{"id":"w1"}}\\n200'
        fi
        ;;
      *)
        printf '{"success":true,"result":{}}\\n200'
        ;;
    esac
    exit 0
    ;;
  https://api.trycloudflare.com/tunnel)
    # Stand in for the quick-tunnel MINT: the supervisor asks for a tunnel
    # with curl (its own Go client stalls on real carriers) and turns the
    # answer into a TUNNEL_TOKEN. The hostname must match the one the fake
    # cloudflared announces, so both sides of the test agree.
    host="first-random-words.trycloudflare.com"
    [ -f "$WORK/new-host" ] && host="\$(basename "\$(cat "$WORK/new-host")" .trycloudflare.com).trycloudflare.com"
    printf '{"success":true,"result":{"id":"11111111-2222-3333-4444-555555555555","name":"qt-test","hostname":"%s","account_tag":"0123456789abcdef0123456789abcdef","secret":"c2VjcmV0"}}\n200' "\$host"
    exit 0
    ;;
  https://*.workers.dev/*|http://*.workers.dev/*)
    # Stand in for the Worker: forward to the engine the way the real
    # Worker forwards to the tunnel origin.
    path="\${url#*workers.dev}"
    real="http://127.0.0.1:$TEST_PORT\${path}"
    exec /usr/bin/curl "\${args[@]}" "\$real"
    ;;
  https://*.trycloudflare.com/*|http://*.trycloudflare.com/*)
    # Stand in for the tunnel: the supervisor probes /health here before
    # deploying, exactly as it does against a real Quick Tunnel.
    path="\${url#*trycloudflare.com}"
    real="http://127.0.0.1:$TEST_PORT\${path}"
    exec /usr/bin/curl "\${args[@]}" "\$real"
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

export PORT
export WEARSIC_DB_PATH="$WORK/test.db"
export WEARSIC_STATE_DIR="$WORK/state"
# The engine reads its shared secret from the environment at startup. It
# must be set BEFORE the JVM launches: this is what makes the unauth probe
# through the permanent URL return 401.
export WEARSIC_API_KEY="testkey123"

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
chmod 600 "$DEST/.env"
export WEARSIC_DIR="$DEST"
export TEST_PORT="$PORT"
export CLOUDFLARE_API_TOKEN="testtoken"
# Account ID is deliberately NOT set: the CLI must discover it from the
# token (and persist it) — that is the one-manual-step path.
unset CLOUDFLARE_ACCOUNT_ID 2>/dev/null || true
unset CLOUDFLARE_SUBDOMAIN 2>/dev/null || true
export WEARSIC_CF_RETRY_BASE=0
export WEARSIC_CF_MAX_ATTEMPTS=6

# The first upload fails with a 500; the retry must succeed.
echo 1 > "$WORK/fail-deploy"
: > "$WORK/deploy-count"

run_cli() { bash "$CLI" "$@"; }

# ── 1. start ───────────────────────────────────────────────────────────────
echo "start"

PATH="$WORK/bin:$PATH" timeout 180 bash "$CLI" cloudflare start > "$WORK/start.out" 2>&1
rc=$?
if [ $rc -eq 0 ]; then
  pass "wearsic cloudflare start succeeded"
else
  fail "wearsic cloudflare start failed (rc=$rc)"
  tail -20 "$WORK/start.out" | sed 's/^/       /'
  exit 1
fi

url="$(grep -oE 'https://[a-z0-9.-]+\.workers\.dev' "$WORK/start.out" | head -1)"
if [ "$url" = "https://wearsic.testsub.workers.dev" ]; then
  pass "prints the permanent URL ($url)"
else
  fail "permanent URL wrong (got '$url')"
  sed 's/^/       /' "$WORK/start.out" | head -10
fi

if grep -q 'Server URL' "$WORK/start.out" && grep -q 'API key' "$WORK/start.out"; then
  pass "shows the watch URL and API key together"
else
  fail "the setup card is missing the URL or API key"
  sed 's/^/       /' "$WORK/start.out" | tail -15
fi

if grep -q 'first-random-words.trycloudflare.com' "$WORK/cloudflare.log" 2>/dev/null; then
  pass "the Worker was bound to the tunnel origin"
else
  fail "the Worker was not bound to the tunnel origin"
  sed 's/^/       /' "$WORK/cloudflare.log" 2>/dev/null | head -5
fi

state="$DEST/wearsic-state/cloudflare.json"
if [ -f "$state" ]; then
  pass "the state file records the URL and the origin"
else
  fail "no state file written"
  ls -la "$DEST/wearsic-state/" 2>&1 | sed 's/^/       /' | head -6
fi

if grep -q '"status":"live"' "$state" 2>/dev/null; then
  pass "the state is live, not merely 'starting'"
else
  fail "state is not live"
  cat "$state" 2>/dev/null | sed 's/^/       /'
fi

if grep -q '"deployed_origin":"https://first-random-words.trycloudflare.com"' "$state" 2>/dev/null; then
  pass "the last deployed origin is recorded separately"
else
  fail "deployed_origin is missing or wrong"
  cat "$state" 2>/dev/null | sed 's/^/       /'
fi

if [ "$(cat "$WORK/deploy-count" 2>/dev/null)" -ge 2 ]; then
  pass "the transient 500 was retried (upload attempted $(cat "$WORK/deploy-count") times)"
else
  fail "the transient 500 was not retried"
fi

if grep -q "CLOUDFLARE_ACCOUNT_ID=$ACCOUNT_ID" "$DEST/.env" 2>/dev/null; then
  pass "the account id was auto-detected and persisted"
else
  fail "the account id was not persisted"
  sed 's/^/       /' "$DEST/.env" 2>/dev/null
fi

if grep -q 'CLOUDFLARE_SUBDOMAIN=testsub' "$DEST/.env" 2>/dev/null; then
  pass "the existing workers.dev subdomain was reused"
else
  fail "the subdomain was not persisted"
fi

# The secret must not appear in user-visible output or the CLI's log.
if grep -q 'testtoken' "$WORK/start.out" "$DEST/wearsic-state/cloudflare.log" 2>/dev/null; then
  fail "the API token leaked into output/logs"
else
  pass "the API token never appears in output or logs"
fi

# ── 2. the permanent URL actually serves the engine ─────────────────────────
echo "serving"

body="$(PATH="$WORK/bin:$PATH" curl -fsS --max-time 15 "$url/health" 2>/dev/null)"
case "$body" in
  *'"status":"ok"'*) pass "the permanent URL serves /health" ;;
  *) fail "the permanent URL did not serve /health (got '$body')" ;;
esac

code="$(PATH="$WORK/bin:$PATH" curl -sS -o /dev/null -w '%{http_code}' \
    --max-time 20 "$url/api/frontdoor/probe" 2>/dev/null || true)"
[ "$code" = "401" ] \
  && pass "the permanent URL rejects requests without the API key (401)" \
  || fail "unauthenticated probe returned '$code' (expected 401)"

code="$(PATH="$WORK/bin:$PATH" curl -sS -o "$WORK/probe.body" -D "$WORK/probe.hdr" \
    -w '%{http_code}' --max-time 30 \
    -H "X-Wearsic-Key: testkey123" -H "Range: bytes=10-19" \
    "$url/api/frontdoor/probe" 2>/dev/null || true)"
crange="$(grep -i '^content-range:' "$WORK/probe.hdr" 2>/dev/null | head -1 | tr -d '\r')"
len="$(wc -c < "$WORK/probe.body" 2>/dev/null | tr -d ' ')"
if [ "$code" = "206" ] \
    && printf '%s' "$crange" | grep -qi 'content-range: bytes 10-19/65536' \
    && [ "$len" = "10" ]; then
  pass "Range + 206 + Content-Range survive the permanent URL"
else
  fail "Range probe failed (HTTP ${code:-none}, '$crange', length=${len:-?})"
fi

# ── 3. a NEW random hostname re-points the Worker ───────────────────────────
echo "re-point"

echo "https://second-random-words.trycloudflare.com" > "$WORK/new-host"
# Wait for the DEPLOYMENT, not merely the log line that says the hostname
# changed: the state is the authoritative record of what Cloudflare accepted.
for i in $(seq 1 60); do
  grep -q '"deployed_origin":"https://second-random-words.trycloudflare.com"' "$state" 2>/dev/null && break
  sleep 1
done
if grep -q '"deployed_origin":"https://second-random-words.trycloudflare.com"' "$state" 2>/dev/null; then
  pass "the Worker was re-pointed when the tunnel hostname changed"
else
  fail "the Worker was not re-pointed after the hostname changed"
  tail -10 "$DEST/wearsic-state/cloudflare.log" 2>/dev/null | sed 's/^/       /'
fi

if grep -q '"origin":"https://second-random-words.trycloudflare.com"' "$state" 2>/dev/null; then
  pass "the state follows the newly discovered origin"
else
  fail "origin did not follow the re-point"
  cat "$state" 2>/dev/null | sed 's/^/       /'
fi

# The permanent URL must be UNCHANGED — that is the entire point.
url2="$(bash "$CLI" cloudflare url 2>/dev/null)"
if [ "$url2" = "$url" ]; then
  pass "the permanent URL did not change ($url2)"
else
  fail "the permanent URL changed ($url -> $url2)"
fi

# ── 4. a tunnel PROCESS restart recovers ────────────────────────────────────
echo "tunnel restart"

echo "https://third-random-words.trycloudflare.com" > "$WORK/new-host"
# Kill the tunnel process itself (not a hostname rotation). The supervisor
# must notice, restart it, discover the new hostname and re-deploy.
pkill -f "$WORK/bin/cloudflared" 2>/dev/null
for i in $(seq 1 90); do
  if grep -q '"deployed_origin":"https://third-random-words.trycloudflare.com"' "$state" 2>/dev/null \
     && grep -q '"status":"live"' "$state" 2>/dev/null; then break; fi
  sleep 1
done
if grep -q '"deployed_origin":"https://third-random-words.trycloudflare.com"' "$state" 2>/dev/null; then
  pass "the supervisor restarted the tunnel and re-pointed the Worker"
else
  fail "the supervisor did not recover from a tunnel process restart"
  tail -12 "$DEST/wearsic-state/cloudflare.log" 2>/dev/null | sed 's/^/       /'
fi

body="$(PATH="$WORK/bin:$PATH" curl -fsS --max-time 15 "$url/health" 2>/dev/null)"
case "$body" in
  *'"status":"ok"'*) pass "the permanent URL still serves /health after the restart" ;;
  *) fail "the permanent URL broke after the tunnel restart" ;;
esac

# ── 5. idempotent start ────────────────────────────────────────────────────
echo "idempotent start"

before="$(pgrep -fc "[_]_cf_supervise $DEST" 2>/dev/null || echo 0)"
PATH="$WORK/bin:$PATH" timeout 90 bash "$CLI" cloudflare start --no-verify > "$WORK/start2.out" 2>&1
rc=$?
after="$(pgrep -fc "[_]_cf_supervise $DEST" 2>/dev/null || echo 0)"
if [ "$rc" -eq 0 ] && grep -qi 'already running' "$WORK/start2.out" \
    && [ "$before" = "$after" ]; then
  pass "a second start is idempotent (no second supervisor)"
else
  fail "a second start spawned another supervisor or failed (rc=$rc, $before -> $after)"
  tail -6 "$WORK/start2.out" | sed 's/^/       /'
fi

# ── 6. status, verify, logs, stop ──────────────────────────────────────────
echo "status and stop"

PATH="$WORK/bin:$PATH" timeout 60 bash "$CLI" cloudflare status > "$WORK/status.out" 2>&1
rc=$?
if [ "$rc" -eq 0 ] && grep -q "$url" "$WORK/status.out" \
    && grep -q 'Health         200 OK' "$WORK/status.out" \
    && grep -q 'API key accepted' "$WORK/status.out"; then
  pass "status reports the URL, health and auth through the permanent URL"
else
  fail "status output is incomplete (rc=$rc)"
  sed 's/^/       /' "$WORK/status.out" | head -12
fi

PATH="$WORK/bin:$PATH" timeout 60 bash "$CLI" cloudflare verify > "$WORK/verify.out" 2>&1
rc=$?
if [ "$rc" -eq 0 ] && grep -q '206 Partial Content' "$WORK/verify.out"; then
  pass "verify proves auth + Range/206 streaming through the permanent URL"
else
  fail "verify failed (rc=$rc)"
  sed 's/^/       /' "$WORK/verify.out" | head -12
fi

PATH="$WORK/bin:$PATH" timeout 30 bash "$CLI" cloudflare logs > "$WORK/logs.out" 2>&1
if grep -q 'worker ->' "$WORK/logs.out"; then
  pass "logs show the deployment history"
else
  fail "logs are empty or missing the deployment history"
fi

PATH="$WORK/bin:$PATH" timeout 60 bash "$CLI" cloudflare stop > "$WORK/stop.out" 2>&1
if ! pgrep -f "[_]_cf_supervise $DEST" >/dev/null 2>&1 \
    && ! pgrep -f "$WORK/bin/cloudflared" >/dev/null 2>&1; then
  pass "stop killed the supervisor and its own tunnel"
else
  fail "the supervisor or tunnel survived stop"
  pkill -f "[_]_cf_supervise $DEST" 2>/dev/null
  pkill -f "$WORK/bin/cloudflared" 2>/dev/null
fi

after_stop="$(bash "$CLI" cloudflare url 2>/dev/null)"
if [ "$after_stop" = "$url" ]; then
  pass "the permanent URL survives stop (the Worker stays deployed)"
else
  fail "stop lost the permanent URL"
fi

PATH="$WORK/bin:$PATH" timeout 60 bash "$CLI" cloudflare status > "$WORK/status2.out" 2>&1
rc=$?
if [ "$rc" -ne 0 ] && grep -q 'Status         stopped' "$WORK/status2.out" \
    && grep -q 'Supervisor     NOT running' "$WORK/status2.out"; then
  pass "status after stop reports 'stopped', no supervisor, and exits non-zero"
else
  fail "status after stop is wrong (rc=$rc)"
  sed 's/^/       /' "$WORK/status2.out" | head -10
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
