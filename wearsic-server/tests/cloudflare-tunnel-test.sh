#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Tests for the Cloudflare front door (`wearsic cloudflare`).
#
# WHAT IS BEING TESTED
# --------------------
# The permanent-URL feature has two halves that can be verified WITHOUT a
# Cloudflare account:
#
#   1. Reading the random tunnel hostname that cloudflared hands out.
#      cloudflared announces it on stderr AND exposes it in its Prometheus
#      metrics. Both paths are exercised, because a build that logs it
#      differently must still work.
#
#   2. Turning that hostname into a permanent workers.dev address and
#      re-pointing the Worker when the hostname changes — the whole point
#      of the feature. If the tunnel restarts and the Worker is not
#      re-pointed, the watch breaks; that is the bug this guards against.
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

# Extract functions straight from the shipped CLI, so the test can never
# drift from the code users actually run.
extract() { # $1 = function name
  sed -n "/^$1() {/,/^}/p" "$CLI" > "$WORK/fn_$1.sh"
  [ -s "$WORK/fn_$1.sh" ] || { echo "FAIL could not extract $1 from the CLI"; exit 1; }
}

for fn in cf_hostname_from_text cf_hostname_from_log cf_hostname_from_metrics \
           cf_normalize_origin cf_worker_name cf_permanent_url; do
  extract "$fn"
done

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

# A 63-char label is the DNS maximum; make sure a long one is not truncated.
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

# ── 2. Origin normalisation ────────────────────────────────────────────────
echo "origin normalisation"

source "$WORK/fn_cf_normalize_origin.sh"

got="$(cf_normalize_origin 'https://abc.trycloudflare.com')"
[ "$got" = "https://abc.trycloudflare.com" ] && pass "keeps a full URL" || fail "url (got '$got')"

got="$(cf_normalize_origin 'abc.trycloudflare.com')"
[ "$got" = "https://abc.trycloudflare.com" ] && pass "upgrades a bare hostname to https" || fail "hostname (got '$got')"

got="$(cf_normalize_origin 'https://abc.trycloudflare.com/')"
[ "$got" = "https://abc.trycloudflare.com" ] && pass "strips a trailing slash" || fail "trailing slash (got '$got')"

got="$(cf_normalize_origin '')"
[ -z "$got" ] && pass "empty input stays empty" || fail "empty (got '$got')"

# ── 3. Permanent URL construction ──────────────────────────────────────────
echo "permanent URL"

source "$WORK/fn_cf_worker_name.sh"
source "$WORK/fn_cf_permanent_url.sh"

# cf_worker_name reads the credential through cf_credential, which is not
# extracted here — stub it, along with die (the CLI's real die exits).
cat > "$WORK/stub_cred.sh" <<'EOF'
cf_credential() { printf '%s' "${STUB_CRED:-}"; }
die() { echo "die: $*" >&2; exit 9; }
EOF

( . "$WORK/stub_cred.sh"; . "$WORK/fn_cf_worker_name.sh" ) >/dev/null 2>&1
STUB_CRED="wearsic" bash -c '
  . "'"$WORK"'/stub_cred.sh"; . "'"$WORK"'/fn_cf_worker_name.sh"
  w="$(cf_worker_name)"; [ "$w" = "wearsic" ] && echo PASS || echo "FAIL got=$w"
' > "$WORK/wn.default" 2>&1
grep -q PASS "$WORK/wn.default" \
  && pass "defaults the worker name to 'wearsic'" \
  || fail "default worker name ($(tail -1 "$WORK/wn.default"))"

STUB_CRED="my-watch-gw" bash -c '
  . "'"$WORK"'/stub_cred.sh"; . "'"$WORK"'/fn_cf_worker_name.sh"
  w="$(cf_worker_name)"; [ "$w" = "my-watch-gw" ] && echo PASS || echo "FAIL got=$w"
' > "$WORK/wn.custom" 2>&1
grep -q PASS "$WORK/wn.custom" \
  && pass "honours a custom worker name" \
  || fail "custom worker name ($(tail -1 "$WORK/wn.custom"))"

STUB_CRED="bad name!" bash -c '
  . "'"$WORK"'/stub_cred.sh"; . "'"$WORK"'/fn_cf_worker_name.sh"
  cf_worker_name >/dev/null 2>&1
' >"$WORK/wn.bad" 2>&1
[ $? -ne 0 ] \
  && pass "rejects a worker name that is not a DNS label" \
  || fail "invalid worker name accepted"

STUB_CRED="-dash-" bash -c '
  . "'"$WORK"'/stub_cred.sh"; . "'"$WORK"'/fn_cf_worker_name.sh"
  cf_worker_name >/dev/null 2>&1
' >"$WORK/wn.dash" 2>&1
[ $? -ne 0 ] \
  && pass "rejects a leading/trailing dash" \
  || fail "dash-padded worker name accepted"

STUB_CRED="wearsic" bash -c '
  . "'"$WORK"'/stub_cred.sh"; . "'"$WORK"'/fn_cf_worker_name.sh"; . "'"$WORK"'/fn_cf_permanent_url.sh"
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

# The audio path depends on these two: without Range the engine re-sends the
# whole file on every seek; without streaming the Worker buffers a whole
# song before replying.
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

# The Range header must NOT be in the hop-by-hop list.
if sed -n '/^const HOP_BY_HOP/,/]);/p' "$W" | grep -qi '"range"'; then
  fail "Range is stripped — byte-range audio would break"
else
  pass "keeps the Range header (byte-range audio survives)"
fi

# ── 5. The API call the CLI makes ──────────────────────────────────────────
echo "cloudflare API"

# A fake curl that records the request and returns canned JSON. The
# functions under test are driven against it.
cat > "$WORK/bin/curl" <<EOF
#!/bin/sh
# Record the request, then answer like the Cloudflare API.
{
  echo "ARGS: \$*"
  for a in "\$@"; do
    case "\$a" in
      https://*) echo "URL: \$a" ;;
      Authorization*) echo "AUTH: \$a" ;;
    esac
  done
} >> "$WORK/requests.log"
case "\$*" in
  *"/workers/subdomain"*) echo '{"success":true,"result":{"subdomain":"mysub"}}' ;;
  *"/workers/scripts/"*)  echo '{"success":true,"result":{"id":"abc"}}' ;;
  *) echo '{"success":true,"result":{}}' ;;
esac
exit 0
EOF
chmod +x "$WORK/bin/curl"

# Extract the deploy function and drive it.
sed -n '/^cf_deploy_worker() {/,/^}/p' "$CLI" > "$WORK/fn_deploy.sh"
[ -s "$WORK/fn_deploy.sh" ] || { echo "FAIL could not extract cf_deploy_worker"; exit 1; }

: > "$WORK/requests.log"
# The stub needs a real Worker source to upload.
cp "$HERE/wearsic-cloudflare-worker.js" "$WORK/worker.js"
PATH="$WORK/bin:$PATH" \
WORKDIR="$WORK" \
bash -c '
  . "'"$WORK"'/fn_deploy.sh"
  # Stub the collaborators the function reaches for, so only the real
  # request-building code under test is exercised.
  cf_token()   { printf "faketoken"; }
  cf_account() { printf "acct123"; }
  cf_worker_name() { printf "wearsic"; }
  cf_worker_src()  { printf "%s" "$WORKDIR/worker.js"; }
  die() { echo "DIE: $*" >&2; exit 1; }
  cf_deploy_worker "https://abc.trycloudflare.com" >/dev/null
' > "$WORK/deploy.out" 2>&1

if grep -q 'workers/scripts/wearsic' "$WORK/requests.log" 2>/dev/null; then
  pass "uploads to /accounts/<id>/workers/scripts/<worker>"
else
  fail "wrong upload endpoint ($(grep URL "$WORK/requests.log" 2>/dev/null | head -1))"
fi

if grep -q 'Authorization: Bearer faketoken' "$WORK/requests.log" 2>/dev/null; then
  pass "sends the API token as a bearer header"
else
  fail "no bearer token sent"
fi

if grep -q 'abc.trycloudflare.com' "$WORK/requests.log" 2>/dev/null; then
  pass "binds the Worker to the current tunnel origin"
else
  fail "ORIGIN binding missing from the upload"
fi

if grep -q 'main_module' "$WORK/requests.log" 2>/dev/null; then
  pass "sends multipart metadata with main_module"
else
  fail "no multipart metadata in the upload"
fi

if grep -q 'compatibility_date' "$WORK/requests.log" 2>/dev/null; then
  pass "sends a compatibility_date"
else
  fail "no compatibility_date in the upload"
fi

# ── 6. The re-point path ───────────────────────────────────────────────────
echo "re-pointing on tunnel change"

# The supervisor must call cf_deploy_worker again when the hostname
# changes. Assert the loop exists and re-deploys.
if grep -q 'cf_deploy_worker "\$origin"' "$CLI"; then
  pass "the supervisor re-deploys the Worker"
else
  fail "no re-deploy of the Worker in the supervisor"
fi

if grep -q '\[ "\$new" != "\$origin" \]' "$CLI"; then
  pass "the supervisor detects a changed tunnel hostname"
else
  fail "no changed-hostname detection in the supervisor"
fi

if grep -q 'cf_state_write "\$sub" "\$origin" "\$perm"' "$CLI"; then
  pass "the state file is rewritten after a re-point"
else
  fail "state not rewritten after a re-point"
fi

# ── 7. Guardrails ──────────────────────────────────────────────────────────
echo "guardrails"

# The permanent URL is public: an API key must be required, exactly like
# every other public-exposure path.
if sed -n '/^cmd_cloudflare() {/,/^}/p' "$CLI" | grep -q 'api_key'; then
  pass "requires an API key before exposing a public URL"
else
  fail "no API key requirement for the public URL"
fi

if sed -n '/^cmd_cloudflare() {/,/^}/p' "$CLI" | grep -q 'CLOUDFLARE_API_TOKEN'; then
  pass "requires a Cloudflare API token"
else
  fail "no Cloudflare API token requirement"
fi

if sed -n '/^cmd_cloudflare() {/,/^}/p' "$CLI" | grep -q 'health_ok'; then
  pass "refuses to start when the engine is not healthy"
else
  fail "no engine health check"
fi

# The token must never be printed.
if grep -qE 'say[^"]*\$CF_TOKEN|printf[^"]*\$CF_TOKEN' "$CLI"; then
  fail "the API token can be printed"
else
  pass "the API token is never printed"
fi

# ── summary ────────────────────────────────────────────────────────────────
echo
if [ "$fails" -eq 0 ]; then
  echo "Result: all checks passed"
  exit 0
fi
echo "Result: $fails check(s) FAILED"
exit 1
