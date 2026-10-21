#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Tests for the Cloudflare front door (`wearsic cloudflare`).
#
# WHAT IS BEING TESTED
# --------------------
# The permanent-URL feature has pieces that can be verified WITHOUT a
# Cloudflare account:
#
#   1. Reading the random tunnel hostname that cloudflared hands out, from
#      both its log and its Prometheus metrics, with the LAST announcement
#      winning (a reconnect can change the hostname).
#   2. Turning that hostname into a permanent workers.dev address and
#      re-pointing the Worker when it changes. If the tunnel restarts and
#      the Worker is not re-pointed, the watch breaks.
#   3. Deploy retries: bounded exponential backoff with jitter, retryable
#      vs permanent (4xx) failures, and the last SUCCESSFULLY deployed
#      origin tracked separately from the discovered one.
#   4. Credential handling: no token in logs/URLs, .env written atomically
#      with mode 600, login/logout paths.
#   5. Guardrails: the public URL requires (or creates) the engine API key,
#      the engine is started automatically, there is no Hello World fallback,
#      and stale state is never reported as a live URL.
#
# The Cloudflare API itself is exercised against a FAKE curl that records
# the request and returns canned JSON, so the exact endpoint, HTTP method,
# multipart metadata and ORIGIN binding can be asserted. A real account is
# never contacted.
#
# Usage:  bash wearsic-server/tests/cloudflare-tunnel-test.sh
# ─────────────────────────────────────────────────────────────────────────────
set -u

HERE="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CLI="$HERE/wearsic"

[ -f "$CLI" ] || { echo "cannot find the wearsic CLI next to this test"; exit 2; }
[ -f "$HERE/wearsic-cloudflare-worker.js" ] || {
  echo "cannot find wearsic-cloudflare-worker.js next to this test"; exit 2; }

fails=0
pass() { echo "  ok   $1"; }
fail() { echo "  FAIL $1"; fails=$((fails+1)); }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin"
export WORK

# Extract functions straight from the shipped CLI, so the test can never
# drift from the code users actually run.
extract() { # $1 = function name
  sed -n "/^$1()[[:space:]]*{/,/^}/p" "$CLI" > "$WORK/fn_$1.sh"
  [ -s "$WORK/fn_$1.sh" ] || { echo "FAIL could not extract $1 from the CLI"; exit 1; }
}

for fn in cf_hostname_from_text cf_hostname_from_log cf_hostname_from_metrics \
          cf_normalize_origin cf_origin_valid cf_worker_name cf_permanent_url \
          cf_retry_delay cf_enable_workers_dev cf_deploy_worker cf_deploy_worker_retry cf_api cf_api_ok cf_api_message \
          cf_generate_api_key ensure_api_key ensure_engine \
          env_value write_env_key \
          cf_api_code cf_api_explain cf_state_dir cf_state_file cf_state_get \
          cf_state_get_num cf_state_write cf_json_escape cf_env_set cf_env_unset; do
  extract "$fn"
done
# api_key is a deliberately tiny one-line function; the generic extractor
# expects a closing brace on its own line.
sed -n '/^api_key()[[:space:]]*{/p' "$CLI" > "$WORK/fn_api_key.sh"
[ -s "$WORK/fn_api_key.sh" ] || { echo "FAIL could not extract api_key"; exit 1; }
for fn in json_tail jstr jnum; do
  sed -n "/^$fn()[[:space:]]*{/,/^}/p" "$CLI" > "$WORK/fn_$fn.sh"
done

# Shared stubs: quiet output, no exits from command substitutions.
cat > "$WORK/stubs.sh" <<'EOF'
say() { :; }
warn() { :; }
err() { printf 'ERR: %s\n' "$*" >&2; }
dim() { :; }
ok() { :; }
die() { echo "die: $*" >&2; exit 9; }
port() { printf '8080'; }
cf_worker_name() { printf 'wearsic'; }
CF_API_BASE="https://api.cloudflare.com/client/v4"
CF_WORKER_SRC_NAME="wearsic-cloudflare-worker.js"
CF_COMPAT_DATE="2025-08-06"
CF_RETRY_BASE=2
CF_RETRY_CAP=60
CF_RETRY_MAX=6
EOF

# ── 1. Hostname capture ────────────────────────────────────────────────────
echo "hostname capture"

source "$WORK/fn_cf_hostname_from_text.sh"
source "$WORK/fn_cf_hostname_from_log.sh"
source "$WORK/fn_cf_hostname_from_metrics.sh"

got="$(cf_hostname_from_text '2026-01-01T00:00:00Z INF |  https://odd-words-here.trycloudflare.com  |')"
[ "$got" = "https://odd-words-here.trycloudflare.com" ] \
  && pass "reads the URL from cloudflared's log line" \
  || fail "log line parse (got '$got')"

got="$(cf_hostname_from_text 'INF |  https://a-b-c.trycloudflare.com  |  INF Starting tunnel')"
[ "$got" = "https://a-b-c.trycloudflare.com" ] \
  && pass "reads the URL out of a mixed log" \
  || fail "mixed log parse (got '$got')"

# A reconnect appends a NEW hostname. The LAST match must win; the first
# would pin the supervisor to a dead origin forever.
got="$(cf_hostname_from_text 'INF https://old-host.trycloudflare.com
INF https://new-host.trycloudflare.com')"
[ "$got" = "https://new-host.trycloudflare.com" ] \
  && pass "the newest tunnel hostname wins after a reconnect" \
  || fail "newest hostname (got '$got')"

long="abcdefghij-abcdefghij-abcdefghij-abcdefghij-abcdefghij-abc"
got="$(cf_hostname_from_text "INF | https://$long.trycloudflare.com |")"
[ "$got" = "https://$long.trycloudflare.com" ] \
  && pass "keeps a full 63-character label" \
  || fail "long label (got '$got')"

printf 'nothing to see here\n' > "$WORK/plain.log"
got="$(cf_hostname_from_log "$WORK/plain.log")"
[ -z "$got" ] && pass "no hostname in an unrelated log" || fail "false positive (got '$got')"

printf 'INF | https://from-the-log.trycloudflare.com |\n' > "$WORK/t.log"
got="$(cf_hostname_from_log "$WORK/t.log")"
[ "$got" = "https://from-the-log.trycloudflare.com" ] \
  && pass "reads the hostname from a log file" \
  || fail "log file parse (got '$got')"

# The metrics fallback: the function is driven through a stubbed curl that
# serves canned Prometheus metrics.
cat > "$WORK/bin/curl" <<EOF
#!/bin/sh
# Stub: serve canned Prometheus metrics for the metrics probe.
for a in "\$@"; do
  case "\$a" in
    *"/metrics"*) cat "$WORK/metrics.txt"; exit 0 ;;
  esac
done
exit 1
EOF
chmod +x "$WORK/bin/curl"
cat > "$WORK/metrics.txt" <<'EOF'
# HELP cloudflared_tunnel_ha_connections Number of active HA connections.
# TYPE cloudflared_tunnel_ha_connections gauge
cloudflared_tunnel_ha_connections{hostname="metric-words.trycloudflare.com",connection_id=1} 4
EOF
PATH="$WORK/bin:$PATH" bash -c '
  . "'"$WORK"'/fn_cf_hostname_from_text.sh"
  . "'"$WORK"'/fn_cf_hostname_from_metrics.sh"
  got="$(cf_hostname_from_metrics 20241)"
  [ "$got" = "https://metric-words.trycloudflare.com" ] && echo PASS || echo "FAIL got=$got"
' > "$WORK/metrics.result" 2>&1
grep -q PASS "$WORK/metrics.result" \
  && pass "falls back to the Prometheus metrics endpoint" \
  || fail "metrics fallback ($(tail -1 "$WORK/metrics.result"))"

# ── 2. Origin normalisation and validation ─────────────────────────────────
echo "origin normalisation"

source "$WORK/fn_cf_normalize_origin.sh"
source "$WORK/fn_cf_origin_valid.sh"

got="$(cf_normalize_origin 'https://abc.trycloudflare.com')"
[ "$got" = "https://abc.trycloudflare.com" ] && pass "keeps a full URL" || fail "url (got '$got')"

got="$(cf_normalize_origin 'abc.trycloudflare.com')"
[ "$got" = "https://abc.trycloudflare.com" ] && pass "upgrades a bare hostname to https" || fail "hostname (got '$got')"

got="$(cf_normalize_origin 'https://abc.trycloudflare.com/')"
[ "$got" = "https://abc.trycloudflare.com" ] && pass "strips a trailing slash" || fail "trailing slash (got '$got')"

got="$(cf_normalize_origin '')"
[ -z "$got" ] && pass "empty input stays empty" || fail "empty (got '$got')"

cf_origin_valid "https://abc-def.trycloudflare.com" \
  && pass "accepts a real Quick Tunnel hostname" \
  || fail "valid origin rejected"
cf_origin_valid "https://abc.trycloudflare.com/evil" \
  && fail "origin with a path was accepted" \
  || pass "rejects an origin with a path/query"
cf_origin_valid "http://abc.trycloudflare.com" \
  && fail "plain http origin was accepted" \
  || pass "rejects a plain-http origin"
cf_origin_valid "https://evil.example.com" \
  && fail "non-trycloudflare origin was accepted" \
  || pass "rejects a non-trycloudflare origin"

# ── 3. Permanent URL construction ──────────────────────────────────────────
echo "permanent URL"

source "$WORK/stubs.sh"
source "$WORK/fn_cf_worker_name.sh"
source "$WORK/fn_cf_permanent_url.sh"

( . "$WORK/stubs.sh"; . "$WORK/fn_cf_worker_name.sh" ) >/dev/null 2>&1
STUB_CRED="" bash -c '
  cf_credential() { printf "%s" "$STUB_CRED"; }
  . "'"$WORK"'/fn_cf_worker_name.sh"
  w="$(cf_worker_name)"; [ "$w" = "wearsic" ] && echo PASS || echo "FAIL got=$w"
' > "$WORK/wn.default" 2>&1
grep -q PASS "$WORK/wn.default" \
  && pass "defaults the worker name to 'wearsic'" \
  || fail "default worker name ($(tail -1 "$WORK/wn.default"))"

STUB_CRED="my-watch-gw" bash -c '
  cf_credential() { printf "%s" "$STUB_CRED"; }
  . "'"$WORK"'/fn_cf_worker_name.sh"
  w="$(cf_worker_name)"; [ "$w" = "my-watch-gw" ] && echo PASS || echo "FAIL got=$w"
' > "$WORK/wn.custom" 2>&1
grep -q PASS "$WORK/wn.custom" \
  && pass "honours a custom worker name" \
  || fail "custom worker name ($(tail -1 "$WORK/wn.custom"))"

STUB_CRED="bad name!" bash -c '
  cf_credential() { printf "%s" "$STUB_CRED"; }
  . "'"$WORK"'/fn_cf_worker_name.sh"
  cf_worker_name >/dev/null 2>&1
' >"$WORK/wn.bad" 2>&1
[ $? -ne 0 ] \
  && pass "rejects a worker name that is not a DNS label" \
  || fail "invalid worker name accepted"

STUB_CRED="-dash-" bash -c '
  cf_credential() { printf "%s" "$STUB_CRED"; }
  . "'"$WORK"'/fn_cf_worker_name.sh"
  cf_worker_name >/dev/null 2>&1
' >"$WORK/wn.dash" 2>&1
[ $? -ne 0 ] \
  && pass "rejects a leading/trailing dash" \
  || fail "dash-padded worker name accepted"

STUB_CRED="wearsic" bash -c '
  cf_credential() { printf "%s" "$STUB_CRED"; }
  . "'"$WORK"'/fn_cf_worker_name.sh"; . "'"$WORK"'/fn_cf_permanent_url.sh"
  u="$(cf_permanent_url "mysub")"
  [ "$u" = "https://wearsic.mysub.workers.dev" ] && echo PASS || echo "FAIL got=$u"
' > "$WORK/perm" 2>&1
grep -q PASS "$WORK/perm" \
  && pass "builds https://<worker>.<subdomain>.workers.dev" \
  || fail "permanent URL ($(tail -1 "$WORK/perm"))"

# ── 4. The Worker source itself ────────────────────────────────────────────
echo "worker source"

W="$HERE/wearsic-cloudflare-worker.js"

grep -q 'export default' "$W" \
  && pass "exports a Worker module" \
  || fail "no default export"

grep -q 'ORIGIN' "$W" \
  && pass "reads the origin from the ORIGIN binding" \
  || fail "no ORIGIN binding"

grep -q 'request.body' "$W" \
  && pass "forwards the request body" \
  || fail "request body not forwarded"

grep -q 'upstream.body' "$W" \
  && pass "streams the response body (no buffering)" \
  || fail "response body is not streamed"

grep -qi 'cacheTtl' "$W" \
  && pass "disables the edge cache" \
  || fail "edge cache not disabled"

grep -qi 'HOP_BY_HOP' "$W" \
  && pass "strips hop-by-hop headers" \
  || fail "hop-by-hop headers not stripped"

grep -q 'redirect: "manual"' "$W" \
  && pass "does not follow redirects" \
  || fail "redirects are followed"

grep -q 'rewriteLocation' "$W" \
  && pass "rewrites same-origin redirects to the permanent host" \
  || fail "same-origin redirects leak the tunnel hostname"

grep -q 'getSetCookie' "$W" \
  && pass "preserves multiple Set-Cookie headers" \
  || fail "Set-Cookie values are folded"

grep -q 'request_id' "$W" \
  && pass "returns an opaque request id on failure" \
  || fail "no request id in error responses"

if grep -q 'String(err)' "$W"; then
  fail "error responses can leak the tunnel origin"
else
  pass "error responses never echo the origin"
fi

# Content-Length is end-to-end, not hop-by-hop: the watch and media stacks
# use it, and stripping it breaks HEAD/206 clients.
if sed -n '/^const HOP_BY_HOP/,/]);/p' "$W" | grep -qi '"content-length"'; then
  fail "Content-Length is stripped — response headers are not preserved"
else
  pass "keeps Content-Length (response headers preserved)"
fi

if sed -n '/^const HOP_BY_HOP/,/]);/p' "$W" | grep -qi '"range"'; then
  fail "Range is stripped — byte-range audio would break"
else
  pass "keeps the Range header (byte-range audio survives)"
fi

# ── 5. Retry/backoff policy ────────────────────────────────────────────────
echo "retry and backoff"

CF_RETRY_BASE=4
CF_RETRY_CAP=60
source "$WORK/fn_cf_retry_delay.sh"

ok_delay=1
for attempt in 1 2 3 4 5 6; do
  case "$attempt" in
    1) lo=2; hi=4 ;;
    2) lo=4; hi=8 ;;
    3) lo=8; hi=16 ;;
    4) lo=16; hi=32 ;;
    5) lo=30; hi=60 ;;
    6) lo=30; hi=60 ;;
  esac
  for _ in 1 2 3 4 5; do
    d="$(cf_retry_delay "$attempt")"
    if [ "$d" -lt "$lo" ] || [ "$d" -gt "$hi" ]; then
      ok_delay=0
      echo "       attempt $attempt: $d outside [$lo,$hi]"
    fi
  done
done
[ "$ok_delay" -eq 1 ] \
  && pass "exponential backoff is capped at 60s with jitter in [d/2,d]" \
  || fail "backoff bounds are wrong"

CF_RETRY_BASE=0
d="$(cf_retry_delay 5)"
[ "$d" = "0" ] && pass "a zero base disables the wait (test hook)" || fail "zero base gave '$d'"

# The retry wrapper must stop on a permanent (4xx) failure, not loop.
cat > "$WORK/retry_permanent.sh" <<'EOF'
. "$WORK/fn_cf_retry_delay.sh"
. "$WORK/fn_cf_deploy_worker_retry.sh"
cf_log() { :; }
sleep() { :; }
calls=0
cf_deploy_worker() { calls=$((calls+1)); CF_DEPLOY_RETRYABLE=0; CF_DEPLOY_ERROR="bad token"; return 1; }
cf_deploy_worker_retry "https://a.trycloudflare.com" 6
rc=$?
[ "$rc" = "2" ] && [ "$calls" = "1" ] && echo PASS || echo "FAIL rc=$rc calls=$calls"
EOF
CF_RETRY_BASE=0 bash "$WORK/retry_permanent.sh" > "$WORK/retry_permanent.out" 2>&1
grep -q PASS "$WORK/retry_permanent.out" \
  && pass "a permanent 4xx stops retrying immediately" \
  || fail "permanent failure retried ($(tail -1 "$WORK/retry_permanent.out"))"

# A retryable failure succeeds on the third attempt.
cat > "$WORK/retry_recover.sh" <<'EOF'
. "$WORK/fn_cf_retry_delay.sh"
. "$WORK/fn_cf_deploy_worker_retry.sh"
cf_log() { :; }
sleep() { :; }
calls=0
cf_deploy_worker() { calls=$((calls+1)); [ "$calls" -ge 3 ]; }
cf_deploy_worker_retry "https://a.trycloudflare.com" 6
rc=$?
[ "$rc" = "0" ] && [ "$calls" = "3" ] && echo PASS || echo "FAIL rc=$rc calls=$calls"
EOF
CF_RETRY_BASE=0 bash "$WORK/retry_recover.sh" > "$WORK/retry_recover.out" 2>&1
grep -q PASS "$WORK/retry_recover.out" \
  && pass "a retryable failure recovers on a later attempt" \
  || fail "retry recovery ($(tail -1 "$WORK/retry_recover.out"))"

# ── 6. The API call the CLI makes ──────────────────────────────────────────
echo "cloudflare API"

source "$WORK/stubs.sh"
source "$WORK/fn_cf_api.sh"
source "$WORK/fn_cf_api_ok.sh"
source "$WORK/fn_cf_api_message.sh"
source "$WORK/fn_cf_api_code.sh"
source "$WORK/fn_cf_api_explain.sh"

# A fake curl that records the request and returns canned JSON + a status
# line (the CLI asks curl for `%{http_code}` itself).
cat > "$WORK/bin/curl" <<EOF
#!/bin/sh
{
  echo "ARGS: \$*"
  for a in "\$@"; do
    case "\$a" in
      https://*) echo "URL: \$a" ;;
      Authorization*) echo "AUTH: \$a" ;;
    esac
  done
} >> "$WORK/requests.log"
mode="\$(cat "$WORK/api-mode" 2>/dev/null || echo ok)"
case "\$mode" in
  deny)
    printf '{"success":false,"errors":[{"code":10000,"message":"Authentication error"}]}\n403'
    ;;
  pretty)
    printf '{\n  "success": true,\n  "result": {\n    "subdomain": "mysub"\n  }\n}\n200'
    ;;
  *)
    printf '{"success":true,"result":{"subdomain":"mysub"}}\n200'
    ;;
esac
exit 0
EOF
chmod +x "$WORK/bin/curl"

cf_token() { printf 'faketoken'; }
: > "$WORK/requests.log"
echo ok > "$WORK/api-mode"
PATH="$WORK/bin:$PATH" bash -c '
  . "'"$WORK"'/stubs.sh"
  . "'"$WORK"'/fn_cf_api.sh"
  . "'"$WORK"'/fn_cf_api_ok.sh"
  cf_token() { printf "faketoken"; }
  cf_api GET "/accounts/0123456789abcdef0123456789abcdef/workers/subdomain"
  cf_api_ok && echo PASS || echo FAIL
' > "$WORK/api.ok" 2>&1
grep -q PASS "$WORK/api.ok" \
  && pass "a 2xx success:true response is accepted" \
  || fail "API success not detected ($(tail -1 "$WORK/api.ok"))"

# Cloudflare's live API pretty-prints JSON: "success": true, with spaces.
# The old exact-string check made every real setup fail at the subdomain read.
echo pretty > "$WORK/api-mode"
PATH="$WORK/bin:$PATH" bash -c '
  . "'"$WORK"'/stubs.sh"
  . "'"$WORK"'/fn_cf_api.sh"
  . "'"$WORK"'/fn_cf_api_ok.sh"
  cf_token() { printf "faketoken"; }
  cf_api GET "/accounts/0123456789abcdef0123456789abcdef/workers/subdomain"
  cf_api_ok && echo PASS || echo FAIL
' > "$WORK/api.pretty" 2>&1
grep -q PASS "$WORK/api.pretty" \
  && pass "pretty-printed success: true is accepted" \
  || fail "pretty-printed API success not detected ($(tail -1 "$WORK/api.pretty"))"
. "$WORK/fn_json_tail.sh"
. "$WORK/fn_jstr.sh"
PRETTY_BODY='{
  "result": {
    "subdomain": "mysub"
  }
}'
[ "$(jstr "$PRETTY_BODY" subdomain)" = "mysub" ] \
  && pass "pretty-printed JSON fields are readable" \
  || fail "pretty-printed JSON field lookup failed"
echo ok > "$WORK/api-mode"

grep -q 'workers/subdomain' "$WORK/requests.log" \
  && pass "calls /accounts/<id>/workers/subdomain" \
  || fail "wrong API path"

grep -q 'Authorization: Bearer faketoken' "$WORK/requests.log" \
  && pass "sends the API token as a bearer header" \
  || fail "no bearer token sent"

# A non-2xx body must be KEPT and explained — the old `curl -f` discarded it.
echo deny > "$WORK/api-mode"
PATH="$WORK/bin:$PATH" bash -c '
  . "'"$WORK"'/stubs.sh"
  . "'"$WORK"'/fn_cf_api.sh"
  . "'"$WORK"'/fn_cf_api_ok.sh"
  . "'"$WORK"'/fn_cf_api_message.sh"
  . "'"$WORK"'/fn_cf_api_code.sh"
  . "'"$WORK"'/fn_cf_api_explain.sh"
  cf_token() { printf "faketoken"; }
  cf_api GET "/accounts/x/workers/subdomain"
  echo "status=$CF_HTTP_STATUS"
  cf_api_explain "check failed"
' > "$WORK/api.deny" 2>&1
grep -q 'status=403' "$WORK/api.deny" \
  && pass "a 403 keeps its HTTP status" \
  || fail "403 status lost ($(tail -2 "$WORK/api.deny"))"
grep -q 'Authentication error' "$WORK/api.deny" \
  && pass "a 403 keeps Cloudflare's own message" \
  || fail "Cloudflare message discarded"
grep -qi 're-authorize' "$WORK/api.deny" \
  && pass "a 401/403 explains how to re-authorize" \
  || fail "no actionable auth guidance"

# A saved subdomain must keep the permanent URL stable when the API read
# fails (transient network error, or a token that lost Account Settings read).
# It must NOT trigger a create request or a different hostname.
echo "subdomain reuse"
for fn in cf_ensure_subdomain cf_subdomain cf_credential env_value cf_rand4; do
  extract "$fn"
done
cat > "$WORK/sub-run.sh" <<EOF
set -u
. "$WORK/stubs.sh"
. "$WORK/fn_cf_api.sh"
. "$WORK/fn_cf_api_ok.sh"
. "$WORK/fn_cf_credential.sh"
. "$WORK/fn_env_value.sh"
. "$WORK/fn_cf_subdomain.sh"
. "$WORK/fn_cf_rand4.sh"
. "$WORK/fn_cf_ensure_subdomain.sh"
. "$WORK/fn_json_tail.sh"
. "$WORK/fn_jstr.sh"
ENV_FILE="$WORK/sub.env"
: > "\$ENV_FILE"
cf_token() { printf 'faketoken'; }
cf_log() { :; }
CLOUDFLARE_SUBDOMAIN=saved
export CLOUDFLARE_SUBDOMAIN
cf_ensure_subdomain 0123456789abcdef0123456789abcdef
EOF
echo deny > "$WORK/api-mode"
: > "$WORK/requests.log"
if out="$(PATH="$WORK/bin:$PATH" bash "$WORK/sub-run.sh")"; then
  [ "$out" = "saved" ] \
    && pass "a saved subdomain is reused when the API read fails" \
    || fail "saved subdomain not reused (got '$out')"
else
  fail "saved subdomain fallback exited non-zero"
fi
! grep -q 'PUT' "$WORK/requests.log" \
  && pass "the fallback does not try to create another subdomain" \
  || fail "the fallback sent a PUT"
grep -q 'workers/subdomain' "$WORK/requests.log" \
  && pass "the fallback still reads the authoritative value first" \
  || fail "the fallback skipped the API read"

# ── 7. Worker deployment ───────────────────────────────────────────────────
echo "worker deployment"

source "$WORK/fn_cf_api.sh"
source "$WORK/fn_cf_api_ok.sh"
source "$WORK/fn_cf_api_message.sh"
source "$WORK/fn_cf_api_code.sh"
source "$WORK/fn_cf_api_explain.sh"
source "$WORK/fn_cf_origin_valid.sh"
source "$WORK/fn_cf_worker_name.sh"
source "$WORK/fn_cf_enable_workers_dev.sh"
source "$WORK/fn_cf_deploy_worker.sh"

sed -n '/^cf_deploy_worker() {/,/^}/p' "$CLI" > "$WORK/fn_deploy.sh"

# The deploy stub needs a real Worker source to upload.
cp "$HERE/wearsic-cloudflare-worker.js" "$WORK/worker.js"

deploy_run() { # $1 = origin, $2 = response body, $3 = status, $4 = subdomain mode
  cat > "$WORK/bin/curl" <<EOF
#!/bin/sh
{
  echo "ARGS: \$*"
  for a in "\$@"; do
    case "\$a" in
      https://*) echo "URL: \$a" ;;
      Authorization*) echo "AUTH: \$a" ;;
    esac
  done
} >> "$WORK/deploy.log"
submode="${4:-}"
case "\$*" in
  *workers/scripts/wearsic/subdomain*)
    if [ "\$submode" = "deny" ]; then
      printf '%s\n%s' '{"success":false,"errors":[{"code":10000,"message":"Authentication error"}]}' '403'
    else
      # Cloudflare pretty-prints live API responses, and the route is off by
      # default after an upload. Both facts are exercised here.
      printf '{\n  "success": true,\n  "result": {\n    "enabled": true,\n    "previews_enabled": false\n  }\n}\n200'
    fi
    ;;
  *)
    printf '%s\n%s' '$2' '$3'
    ;;
esac
exit 0
EOF
  chmod +x "$WORK/bin/curl"
  : > "$WORK/deploy.log"
  PATH="$WORK/bin:$PATH" DEST="$WORK/dest" WORKDIR="$WORK" bash -c '
    . "'"$WORK"'/stubs.sh"
    . "'"$WORK"'/fn_cf_api.sh"
    . "'"$WORK"'/fn_cf_api_ok.sh"
    . "'"$WORK"'/fn_cf_api_message.sh"
    . "'"$WORK"'/fn_cf_api_code.sh"
    . "'"$WORK"'/fn_cf_api_explain.sh"
    . "'"$WORK"'/fn_cf_origin_valid.sh"
    . "'"$WORK"'/fn_cf_enable_workers_dev.sh"
    . "'"$WORK"'/fn_deploy.sh"
    cf_token()       { printf "faketoken"; }
    cf_account()     { printf "0123456789abcdef0123456789abcdef"; }
    cf_worker_name() { printf "wearsic"; }
    cf_worker_src()  { printf "%s" "$WORKDIR/worker.js"; }
    cf_deploy_worker "'"$1"'" >/dev/null
    echo "rc=$? retryable=$CF_DEPLOY_RETRYABLE"
  ' > "$WORK/deploy.out" 2>&1
}

deploy_run "https://abc.trycloudflare.com" '{"success":true,"result":{"id":"w1"}}' 200
if grep -q 'rc=0' "$WORK/deploy.out"; then
  pass "uploads a valid Worker to a valid origin"
else
  fail "deploy failed ($(tail -2 "$WORK/deploy.out"))"
fi

grep -q 'workers/scripts/wearsic' "$WORK/deploy.log" 2>/dev/null \
  && pass "uploads to /accounts/<id>/workers/scripts/<worker>" \
  || fail "wrong upload endpoint"

grep -q 'Authorization: Bearer faketoken' "$WORK/deploy.log" 2>/dev/null \
  && pass "sends the API token as a bearer header on upload" \
  || fail "no bearer token sent on upload"

grep -q 'abc.trycloudflare.com' "$WORK/deploy.log" 2>/dev/null \
  && pass "binds the Worker to the current tunnel origin" \
  || fail "ORIGIN binding missing from the upload"

grep -q 'main_module' "$WORK/deploy.log" 2>/dev/null \
  && pass "sends multipart metadata with main_module" \
  || fail "no multipart metadata in the upload"

grep -q 'compatibility_date' "$WORK/deploy.log" 2>/dev/null \
  && pass "sends a compatibility_date" \
  || fail "no compatibility_date in the upload"

grep -q '@'"$WORK"'/worker.js' "$WORK/deploy.log" 2>/dev/null \
  && pass "uploads the real Worker source (no Hello World fallback)" \
  || fail "the real Worker source was not uploaded"

grep -q 'workers/scripts/wearsic/subdomain' "$WORK/deploy.log" 2>/dev/null \
  && pass "turns on the Worker's workers.dev route after upload" \
  || fail "the workers.dev route was not enabled"

# Cloudflare's live API pretty-prints JSON. An exact '"success":true' check
# accepted the upload but reported a failure on every real deployment.
deploy_run "https://abc.trycloudflare.com" '{
  "success": true,
  "result": { "id": "w1" }
}' 200
grep -q 'rc=0' "$WORK/deploy.out" \
  && pass "accepts a pretty-printed upload response" \
  || fail "pretty upload response rejected ($(tail -2 "$WORK/deploy.out"))"

# An upload can succeed while enabling the public route fails. That must not
# be reported as a working permanent URL.
deploy_run "https://abc.trycloudflare.com" '{"success":true,"result":{"id":"w1"}}' 200 deny
grep -q 'rc=1 retryable=0' "$WORK/deploy.out" \
  && pass "a forbidden workers.dev enable is a permanent failure" \
  || fail "failed workers.dev enable was not surfaced ($(tail -2 "$WORK/deploy.out"))"

# A 4xx is permanent: retrying a bad token or a missing permission cannot
# help, so the supervisor must stop rather than spin forever.
deploy_run "https://abc.trycloudflare.com" '{"success":false,"errors":[{"code":10000,"message":"Authentication error"}]}' 403
grep -q 'rc=1 retryable=0' "$WORK/deploy.out" \
  && pass "a 403 is classified as permanent (no infinite retry)" \
  || fail "403 classification ($(tail -1 "$WORK/deploy.out"))"

deploy_run "https://abc.trycloudflare.com" '{"success":false,"errors":[{"code":1000,"message":"temporary"}]}' 500
grep -q 'rc=1 retryable=1' "$WORK/deploy.out" \
  && pass "a 500 is classified as retryable" \
  || fail "500 classification ($(tail -1 "$WORK/deploy.out"))"

deploy_run "https://evil.example.com" '{}' 200
grep -q 'rc=2' "$WORK/deploy.out" \
  && pass "refuses to deploy an unexpected origin" \
  || fail "unexpected origin accepted ($(tail -1 "$WORK/deploy.out"))"
[ -s "$WORK/deploy.log" ] \
  && fail "an invalid origin still reached the network" \
  || pass "an invalid origin never reaches the network"

# ── 8. State: discovered origin vs last successfully deployed origin ────────
echo "state tracking"

source "$WORK/stubs.sh"
source "$WORK/fn_json_tail.sh"
source "$WORK/fn_jstr.sh"
source "$WORK/fn_jnum.sh"
source "$WORK/fn_cf_json_escape.sh"
source "$WORK/fn_cf_state_dir.sh"
source "$WORK/fn_cf_state_file.sh"
source "$WORK/fn_cf_state_get.sh"
source "$WORK/fn_cf_state_get_num.sh"
source "$WORK/fn_cf_state_write.sh"

DEST="$WORK/state-dest"
mkdir -p "$DEST"
cf_state_write live testsub "https://wearsic.testsub.workers.dev" \
  "https://one.trycloudflare.com" "https://one.trycloudflare.com" "" "0123456789abcdef0123456789abcdef"
at1="$(cf_state_get_num deployed_at)"

[ "$(cf_state_get origin)" = "https://one.trycloudflare.com" ] \
  && pass "records the discovered origin" \
  || fail "discovered origin missing"
[ "$(cf_state_get deployed_origin)" = "https://one.trycloudflare.com" ] \
  && pass "records the successfully deployed origin" \
  || fail "deployed origin missing"

# The tunnel moved; the Worker has NOT caught up. The two fields must
# diverge so status can explain what is actually live.
cf_state_write degraded testsub "https://wearsic.testsub.workers.dev" \
  "https://two.trycloudflare.com" "https://one.trycloudflare.com" "deploy failed" "0123456789abcdef0123456789abcdef"
[ "$(cf_state_get origin)" = "https://two.trycloudflare.com" ] \
  && [ "$(cf_state_get deployed_origin)" = "https://one.trycloudflare.com" ] \
  && pass "keeps the last deployed origin separate when the tunnel moves" \
  || fail "origin/deployed_origin were not tracked separately"
at2="$(cf_state_get_num deployed_at)"
[ "$at1" = "$at2" ] \
  && pass "a failed deploy does not fake a new deployed_at timestamp" \
  || fail "deployed_at changed on a failed deploy ($at1 -> $at2)"
[ "$(cf_state_get last_error)" = "deploy failed" ] \
  && pass "records the failure reason" \
  || fail "failure reason missing"

cf_state_write live testsub "https://wearsic.testsub.workers.dev" \
  "https://two.trycloudflare.com" "https://two.trycloudflare.com" "" "0123456789abcdef0123456789abcdef"
[ "$(cf_state_get deployed_origin)" = "https://two.trycloudflare.com" ] \
  && pass "a successful re-point updates the deployed origin" \
  || fail "deployed origin not updated"
[ "$(cf_state_get_num deployed_at)" -ge "$at1" ] \
  && pass "a successful deploy advances deployed_at" \
  || fail "deployed_at did not advance"

# ── 9. Credential storage ──────────────────────────────────────────────────
echo "credential storage"

source "$WORK/stubs.sh"
source "$WORK/fn_cf_env_set.sh"
source "$WORK/fn_cf_env_unset.sh"

ENV_FILE="$WORK/env-dest/.env"
mkdir -p "$WORK/env-dest"
printf 'WEARSIC_API_KEY=keep-this\nCLOUDFLARE_API_TOKEN=old-token\n' > "$ENV_FILE"
chmod 644 "$ENV_FILE"

cf_env_set CLOUDFLARE_API_TOKEN "new-token"
grep -q '^CLOUDFLARE_API_TOKEN=new-token$' "$ENV_FILE" \
  && pass "replaces an existing token in .env" \
  || fail "token not replaced"
grep -q '^WEARSIC_API_KEY=keep-this$' "$ENV_FILE" \
  && pass "preserves the existing engine API key" \
  || fail "API key was lost"
[ "$(stat -c %a "$ENV_FILE" 2>/dev/null)" = "600" ] \
  && pass "forces .env to mode 600" \
  || fail ".env mode is $(stat -c %a "$ENV_FILE" 2>/dev/null)"
ls "$WORK"/env-dest/.env.tmp.* "$WORK"/env-dest/.env.value.* >/dev/null 2>&1 \
  && fail "temporary credential files were left behind" \
  || pass "removes temporary credential files"

cf_env_unset CLOUDFLARE_API_TOKEN
grep -q '^CLOUDFLARE_API_TOKEN=' "$ENV_FILE" \
  && fail "logout did not remove the token" \
  || pass "logout removes the saved token"
grep -q '^WEARSIC_API_KEY=keep-this$' "$ENV_FILE" \
  && pass "logout leaves the engine API key untouched" \
  || fail "logout removed the API key"

# ── 10. Supervisor structure and recovery ─────────────────────────────────
echo "supervisor recovery"

SUP="$(sed -n '/^cf_supervise() {/,/^}/p' "$CLI")"

printf '%s' "$SUP" | grep -q ': > "$tlog"' \
  && pass "truncates the per-tunnel log before each start (no stale hostname)" \
  || fail "stale tunnel log can be mistaken for the current one"

printf '%s' "$SUP" | grep -q 'cf_origin_ready "$origin"' \
  && pass "checks that the tunnel serves /health before deploying" \
  || fail "deploys a hostname that may not be reachable"

printf '%s' "$SUP" | grep -q 'cf_deploy_worker_retry "$origin"' \
  && pass "deploys with the retry wrapper" \
  || fail "no retry wrapper around deployment"

printf '%s' "$SUP" | grep -q 'cf_state_write live "\$sub" "\$perm" "\$origin" "\$deployed"' \
  && pass "writes live state only with the deployed origin" \
  || fail "live state does not record the deployed origin"

printf '%s' "$SUP" | grep -q 'deployment failed permanently' \
  && pass "stops on a permanent deployment failure" \
  || fail "permanent failures are not distinguished"

printf '%s' "$SUP" | grep -q 're-point failed permanently' \
  && pass "stops when a tunnel-change re-point fails permanently" \
  || fail "a permanent re-point failure is retried forever"

printf '%s' "$SUP" | grep -q 're-deploy after health check failed permanently' \
  && pass "stops when a health-check re-deploy fails permanently" \
  || fail "a permanent health-check re-deploy failure is retried forever"

printf '%s' "$SUP" | grep -q 'cf_permanent_health "$perm"' \
  && pass "probes the permanent URL while healthy" \
  || fail "no permanent-URL self-heal probe"

printf '%s' "$SUP" | grep -q 'retrying Worker deployment' \
  && pass "keeps retrying after a degraded deployment" \
  || fail "a degraded deployment is never retried"

printf '%s' "$SUP" | grep -q 'retry skipped: .* is not serving /health' \
  && pass "rechecks tunnel readiness before a degraded retry" \
  || fail "a degraded retry can deploy a hostname that is not serving"

printf '%s' "$SUP" | grep -q "trap 'cf_supervise_cleanup; exit 0' INT TERM HUP" \
  && pass "SIGTERM actually stops the supervisor" \
  || fail "SIGTERM only cleans up and then restarts the tunnel"

printf '%s' "$SUP" | grep -q 'cf_lock_acquire' \
  && pass "takes a single-instance lock" \
  || fail "two supervisors can race"

grep -q "pkill -f 'cloudflared tunnel'" "$CLI" \
  && fail "stop kills unrelated cloudflared tunnels" \
  || pass "stop never kills an unrelated cloudflared tunnel"

grep -q 'cf_state_clear' "$CLI" \
  && pass "start clears stale state before launching" \
  || fail "a previous run's URL can be reported as success"

grep -q 'st="$(cf_state_get status' "$CLI" \
  && pass "start waits for a real live/error state" \
  || fail "start does not wait for the live state"

# ── 11. Guardrails ─────────────────────────────────────────────────────────
echo "guardrails"

START="$(sed -n '/^cf_start() {/,/^}/p' "$CLI")"
VERIFY="$(sed -n '/^cf_verify() {/,/^}/p' "$CLI")"
KEY_HELPER="$(sed -n '/^cf_generate_api_key() {/,/^}/p' "$CLI")"
ENGINE_HELPER="$(sed -n '/^ensure_engine() {/,/^}/p' "$CLI")"

printf '%s' "$START" | grep -q 'ensure_api_key' \
  && pass "requires or creates an engine API key before exposing a public URL" \
  || fail "no API key requirement for the public URL"

printf '%s' "$KEY_HELPER" | grep -q '/dev/urandom' \
  && pass "generates a strong API key when none exists" \
  || fail "no API-key generator"

. "$WORK/fn_cf_generate_api_key.sh"
generated="$(cf_generate_api_key)"
case "$generated" in
  ''|*[!a-f0-9]*) generated_bad=1 ;;
  *) generated_bad=0 ;;
esac
[ "$generated_bad" -eq 0 ] && [ "${#generated}" -eq 48 ] \
  && pass "the generated key is 48 lowercase hex characters" \
  || fail "generated key is not 48 hex characters"

printf '%s' "$START" | grep -q 'ensure_engine' \
  && pass "starts the engine automatically when it is not running" \
  || fail "engine startup is still a manual prerequisite"

printf '%s' "$ENGINE_HELPER" | grep -q 'health_ok' \
  && pass "waits for the engine to become healthy before continuing" \
  || fail "no engine health check"

printf '%s' "$VERIFY" | grep -q 'API key is required to verify' \
  && pass "verify cannot report success without an API key" \
  || fail "verify can skip auth/streaming when no key is set"

printf '%s' "$START" | grep -q 'cf_ensure_auth' \
  && pass "requires Cloudflare authorization" \
  || fail "no Cloudflare authorization step"

LOGIN_HELPER="$(sed -n '/^cf_login() {/,/^}/p' "$CLI")"
# Native OAuth: the default login implements browser OAuth itself
# (device flow + PKCE browser flow) and needs no wrangler/npm at all.
printf '%s' "$LOGIN_HELPER" | grep -q 'cf_login_device_flow' \
  && printf '%s' "$LOGIN_HELPER" | grep -q 'cf_login_browser_flow' \
  && pass "native browser OAuth login (no wrangler/npm required)" \
  || fail "no native browser-login path"

grep -q -- '--token-stdin' "$CLI" \
  && pass "manual scoped-token fallback retained" \
  || fail "no manual token fallback"

grep -q 'cf_oauth_refresh' "$CLI" \
  && grep -q 'refresh_token' "$CLI" \
  && pass "the OAuth session auto-refreshes (does not go stale)" \
  || fail "no refresh-token handling"

printf '%s' "$START" | grep -q 'Worker source not found' \
  && pass "refuses to run without the real Worker source" \
  || fail "missing Worker source is not fatal"

# A generated/inline Worker would contain a fetch handler. The real Worker
# is a separate, reviewable file uploaded verbatim (asserted above).
grep -q 'addEventListener' "$CLI" \
  && fail "the CLI embeds a generated Hello-World-style Worker" \
  || pass "there is no generated/inline Worker fallback"

grep -q 'cf_env_unset CLOUDFLARE_API_TOKEN' "$CLI" \
  && pass "logout can remove the saved API token" \
  || fail "no logout path"

grep -q "s/(Bearer )" "$CLI" \
  && pass "logs redact bearer tokens" \
  || fail "logs do not redact bearer tokens"

# The token must never be printed. (It may appear in a Bearer header passed
# to curl, but never in say/printf output or a URL.)
if grep -nE '(say|printf|err|warn)[^#]*\$\{?CLOUDFLARE_API_TOKEN' "$CLI" >/dev/null 2>&1; then
  fail "the Cloudflare API token can be printed"
else
  pass "the Cloudflare API token is never printed"
fi

if grep -qE 'https://[^ ]*api_token=' "$CLI"; then
  fail "the token is placed in a URL"
else
  pass "the token is never placed in a URL"
fi

# ── 12. One-command preflight ──────────────────────────────────────────────
echo "one-command preflight"

PRE="$WORK/preflight"
mkdir -p "$PRE"
export PRE
cat > "$WORK/fake-engine.sh" <<'EOF'
#!/usr/bin/env bash
echo run >> "$PRE/run-count"
touch "$PRE/health"
EOF
chmod +x "$WORK/fake-engine.sh"

if (
  . "$WORK/stubs.sh"
  . "$WORK/fn_env_value.sh"
  . "$WORK/fn_write_env_key.sh"
  . "$WORK/fn_api_key.sh"
  . "$WORK/fn_cf_generate_api_key.sh"
  . "$WORK/fn_ensure_api_key.sh"
  . "$WORK/fn_ensure_engine.sh"
  DEST="$PRE"
  ENV_FILE="$PRE/.env"
  RUN="$WORK/fake-engine.sh"
  export DEST
  health_ok() { [ -f "$PRE/health" ]; }
  supervisor_running() { return 1; }
  cmd_stop() { rm -f "$PRE/health"; }

  CF_API_KEY_CREATED=0
  ensure_api_key
  key="$(api_key)"
  case "$key" in ''|*[!a-f0-9]*) exit 10 ;; esac
  [ "${#key}" -eq 48 ] || exit 11
  [ "$(grep -c '^WEARSIC_API_KEY=' "$ENV_FILE")" -eq 1 ] || exit 12

  ensure_engine
  [ -f "$PRE/health" ] || exit 13
  [ "$(wc -l < "$PRE/run-count")" -ge 1 ] || exit 14

  # An existing key must win; setup must not invent a replacement.
  printf 'WEARSIC_API_KEY=custom-key\n' > "$ENV_FILE"
  CF_API_KEY_CREATED=0
  ensure_api_key
  [ "$(api_key)" = "custom-key" ] || exit 15

  # A newly created key while the engine is up forces one restart so the
  # running process actually uses that key.
  CF_API_KEY_CREATED=1
  ensure_engine
  [ -f "$PRE/health" ] || exit 16
  [ "$(api_key)" = "custom-key" ] || exit 17
  [ "$(wc -l < "$PRE/run-count")" -ge 2 ] || exit 18
  exit 0
); then
  pass "creates a key only when needed, preserves an existing key, and auto-starts the engine"
else
  fail "one-command key/engine preflight failed (rc=$?)"
fi

# ── summary ────────────────────────────────────────────────────────────────
echo
if [ "$fails" -eq 0 ]; then
  echo "Result: all checks passed"
  exit 0
fi
echo "Result: $fails check(s) FAILED"
exit 1
