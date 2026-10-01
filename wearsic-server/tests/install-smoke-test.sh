#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# wearsic installer/CLI smoke test — exercises the one-command installer and
# the `wearsic` CLI end-to-end on the build machine (no phone required):
#
#   1. fresh install        (dirs, .env, API key, CLI on PATH, server boots)
#   2. reinstall            (idempotent: same API key, database preserved)
#   3. CLI commands         (start/stop/restart/status/logs/health/url/api-key/doctor)
#   4. update               (verified download + staged swap + restart)
#   5. bad checksum         (update/install refuse; nothing changes)
#   6. failed download      (update fails cleanly; nothing changes)
#   7. rollback             (broken engine -> previous engine restored + healthy)
#   8. data preservation    (wearsic.db, .env, API key, downloads/, state survive all)
#   9. secret hygiene       (API key / cookie never appear in logs)
#
# Usage:  bash wearsic-server/tests/install-smoke-test.sh
# Needs:  java on PATH (or JAVA_HOME), a built server dist
#         (./gradlew :wearsic-server:installDist).
# ─────────────────────────────────────────────────────────────────────────────
set -u

HERE="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"   # wearsic-server/
ROOT="$(CDPATH= cd -- "$HERE/.." && pwd)"                              # repo root
BUILD_DIST="$HERE/build/install/wearsic-server"

[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
command -v java >/dev/null 2>&1 || { echo "java not on PATH — set JAVA_HOME"; exit 2; }
command -v unzip >/dev/null 2>&1 || { echo "unzip missing"; exit 2; }
command -v curl >/dev/null 2>&1 || { echo "curl missing"; exit 2; }
[ -x "$BUILD_DIST/bin/wearsic-server" ] || {
  echo "No build at $BUILD_DIST — run: ./gradlew :wearsic-server:installDist"; exit 2
}

PASS=0; FAIL=0
pass() { printf '  \033[1;32mPASS\033[0m  %s\n' "$*"; PASS=$((PASS+1)); }
fail() { printf '  \033[1;31mFAIL\033[0m  %s\n' "$*"; FAIL=$((FAIL+1)); }
note() { printf '  ....  %s\n' "$*"; }
section() { printf '\n\033[1;35m== %s ==\033[0m\n' "$*"; }

T="$(mktemp -d)"
teardown() {
  [ -x "$DEST/wearsic" ] && bash "$DEST/wearsic" stop >/dev/null 2>&1
  pkill -f "com.wearsic.server.ApplicationKt" 2>/dev/null
  pkill -f "$DEST/run-termux.sh" 2>/dev/null
  rm -rf "$T"
}
trap teardown EXIT

# ── isolated environment ─────────────────────────────────────────────────────
export HOME="$T/home"
mkdir -p "$HOME"
export WEARSIC_DIR="$HOME/wearsic-server"
DEST="$WEARSIC_DIR"
export WEARSIC_NO_BOOT=1
export WEARSIC_UPDATE_TIMEOUT=25
export PORT=18477
export PATH="$HOME/.local/bin:$PATH"

# The supervisor insists on ffmpeg being present (Termux installs it via pkg).
# On the build machine a no-op stub satisfies the check — transcoding is not
# exercised by this smoke test.
mkdir -p "$T/stub"
printf '#!/bin/sh\nexit 0\n' > "$T/stub/ffmpeg"
chmod +x "$T/stub/ffmpeg"
export PATH="$T/stub:$PATH"

CLI() { bash "$HOME/.local/bin/wearsic" "$@"; }
HEALTH() { curl -sf -m 5 "http://127.0.0.1:$PORT/health" 2>/dev/null; }

# ── release fixtures (built from the local installDist, like CI packages) ────
make_release() { # $1 = version tag, $2 = marker content
  local d="$T/dist-$1"
  mkdir -p "$d/wearsic-server"
  cp -r "$BUILD_DIST/bin" "$BUILD_DIST/lib" "$d/wearsic-server/"
  cp "$HERE/run-termux.sh" "$HERE/wearsic" "$HERE/.env.example" "$d/wearsic-server/"
  printf '%s\n' "$2" > "$d/wearsic-server/lib/test-engine-marker"
  (cd "$d" && zip -qr "wearsic-server-termux-v$1.zip" wearsic-server)
  (cd "$d" && sha256sum "wearsic-server-termux-v$1.zip" > "wearsic-server-termux-v$1.zip.sha256")
  echo "$d"
}

make_broken_release() { # $1 = version tag — engine binary can never start
  local d
  d="$(make_release "$1" "broken-$1")"
  printf '#!/bin/sh\necho "engine is broken" >&2\nexit 1\n' > "$d/wearsic-server/bin/wearsic-server"
  chmod +x "$d/wearsic-server/bin/wearsic-server"
  rm -f "$d/wearsic-server-termux-v$1.zip" "$d/wearsic-server-termux-v$1.zip.sha256"
  (cd "$d" && zip -qr "wearsic-server-termux-v$1.zip" wearsic-server)
  (cd "$d" && sha256sum "wearsic-server-termux-v$1.zip" > "wearsic-server-termux-v$1.zip.sha256")
  echo "$d"
}

url() { echo "file://$1/wearsic-server-termux-v$2.zip"; }
sha_url() { echo "file://$1/wearsic-server-termux-v$2.zip.sha256"; }

run_install() { # $1 = dist dir, $2 = version
  ( export WEARSIC_ZIP_URL="$(url "$1" "$2")" WEARSIC_SHA_URL="$(sha_url "$1" "$2")"
    bash "$ROOT/install.sh" </dev/null )
}
run_update() { # $1 = dist dir, $2 = version
  ( export WEARSIC_ZIP_URL="$(url "$1" "$2")" WEARSIC_SHA_URL="$(sha_url "$1" "$2")"
    bash "$HOME/.local/bin/wearsic" update )
}

D1="$(make_release "1.0.0" "engine-v1")"
D2="$(make_release "2.0.0" "engine-v2")"
DBAD="$(make_release "3.0.0" "engine-v3-unreachable")"
DBROKEN="$(make_broken_release "4.0.0")"

# ═══ 1. FRESH INSTALL ════════════════════════════════════════════════════════
section "1. fresh install"
if run_install "$D1" "1.0.0" >"$T/install1.log" 2>&1; then
  pass "install.sh exits 0"
else
  fail "install.sh exits 0 (see $T/install1.log)"
  tail -20 "$T/install1.log"
fi
[ -x "$DEST/bin/wearsic-server" ] && pass "engine installed" || fail "engine installed"
[ -f "$DEST/run-termux.sh" ] && pass "supervisor installed" || fail "supervisor installed"
[ -x "$HOME/.local/bin/wearsic" ] && pass "wearsic CLI on PATH" || fail "wearsic CLI on PATH"
[ -d "$DEST/wearsic-state" ] && pass "wearsic-state/ created" || fail "wearsic-state/ created"
[ -d "$DEST/downloads" ] && pass "downloads/ created" || fail "downloads/ created"
if grep -q '^WEARSIC_API_KEY=..*' "$DEST/.env"; then
  pass "API key generated in .env"
else
  fail "API key generated in .env"
fi
API_KEY="$(sed -n 's/^WEARSIC_API_KEY=//p' "$DEST/.env" | tail -1)"
if HEALTH | grep -q '"status":"ok"'; then
  pass "server boots and answers /health"
else
  fail "server boots and answers /health"
fi

# ═══ 2. REINSTALL (idempotent) ═══════════════════════════════════════════════
section "2. reinstall (idempotent)"
printf 'SQLITE-DATA-MARKER\n' > "$DEST/wearsic.db"
printf 'user-download\n' > "$DEST/downloads/keep.me"
if run_install "$D1" "1.0.0" >"$T/install2.log" 2>&1; then
  pass "reinstall exits 0"
else
  fail "reinstall exits 0 (see $T/install2.log)"
fi
KEY2="$(sed -n 's/^WEARSIC_API_KEY=//p' "$DEST/.env" | tail -1)"
[ "$KEY2" = "$API_KEY" ] && pass "API key preserved on reinstall" || fail "API key preserved on reinstall (was: $API_KEY now: $KEY2)"
grep -q 'SQLITE-DATA-MARKER' "$DEST/wearsic.db" && pass "wearsic.db preserved" || fail "wearsic.db preserved"
[ -f "$DEST/downloads/keep.me" ] && pass "downloads/ preserved" || fail "downloads/ preserved"

# ═══ 3. CLI COMMANDS ═════════════════════════════════════════════════════════
section "3. CLI commands"
CLI status >/dev/null 2>&1 && pass "wearsic status (exit 0 = healthy)" || fail "wearsic status (exit 0 = healthy)"
CLI health 2>/dev/null | grep -q '"status":"ok"' && pass "wearsic health" || fail "wearsic health"
CLI url 2>/dev/null | grep -q "$API_KEY" && pass "wearsic url (shows pairing info)" || fail "wearsic url (shows pairing info)"
CLI api-key 2>/dev/null | grep -q "$API_KEY" && pass "wearsic api-key (shows key)" || fail "wearsic api-key (shows key)"
CLI version 2>/dev/null | grep -qi 'engine' && pass "wearsic version" || fail "wearsic version"
CLI ready >/dev/null 2>&1 && pass "wearsic ready (exit 0)" || fail "wearsic ready (exit 0)"
if CLI doctor >"$T/doctor.log" 2>&1; then
  pass "wearsic doctor (exit 0 = READY)"
else
  fail "wearsic doctor (exit 0 = READY)"
  tail -25 "$T/doctor.log"
fi
CLI restart >/dev/null 2>&1 && HEALTH | grep -q '"status":"ok"' \
  && pass "wearsic restart (back to healthy)" || fail "wearsic restart (back to healthy)"
CLI stop >/dev/null 2>&1
CLI status >/dev/null 2>&1
rc=$?
if [ "$rc" = "3" ]; then
  pass "wearsic status exits 3 when stopped"
else
  fail "wearsic status exits 3 when stopped (got $rc)"
fi
CLI start >/dev/null 2>&1 && HEALTH | grep -q '"status":"ok"' \
  && pass "wearsic start" || fail "wearsic start"

# ═══ 4. UPDATE (verified, staged, restarted) ═════════════════════════════════
section "4. update"
if run_update "$D2" "2.0.0" >"$T/update.log" 2>&1; then
  pass "wearsic update exits 0"
else
  fail "wearsic update exits 0 (see $T/update.log)"
  tail -20 "$T/update.log"
fi
grep -q 'engine-v2' "$DEST/lib/test-engine-marker" && pass "new engine active" || fail "new engine active"
KEY4="$(sed -n 's/^WEARSIC_API_KEY=//p' "$DEST/.env" | tail -1)"
[ "$KEY4" = "$API_KEY" ] && pass "API key preserved across update" || fail "API key preserved across update"
grep -q 'SQLITE-DATA-MARKER' "$DEST/wearsic.db" && pass "wearsic.db preserved across update" || fail "wearsic.db preserved across update"
[ -f "$DEST/downloads/keep.me" ] && pass "downloads/ preserved across update" || fail "downloads/ preserved across update"
HEALTH | grep -q '"status":"ok"' && pass "server healthy after update" || fail "server healthy after update"

# ═══ 5. BAD CHECKSUM ═════════════════════════════════════════════════════════
section "5. bad checksum (must refuse)"
cp -r "$DBAD" "$T/dist-badsum"
printf '0000000000000000000000000000000000000000000000000000000000000000  wearsic-server-termux-v3.0.0.zip\n' \
  > "$T/dist-badsum/wearsic-server-termux-v3.0.0.zip.sha256"
if ( export WEARSIC_ZIP_URL="$(url "$T/dist-badsum" "3.0.0")" WEARSIC_SHA_URL="$(sha_url "$T/dist-badsum" "3.0.0")"
      bash "$HOME/.local/bin/wearsic" update ) >"$T/badsum-update.log" 2>&1; then
  fail "update refuses bad checksum (exited 0!)"
else
  grep -qi 'mismatch' "$T/badsum-update.log" && pass "update refuses bad checksum (SHA-256 mismatch)" \
    || pass "update refuses bad checksum (non-zero exit)"
fi
grep -q 'engine-v2' "$DEST/lib/test-engine-marker" && pass "engine unchanged after refused update" || fail "engine unchanged after refused update"
# and the installer path too
if ( export WEARSIC_ZIP_URL="$(url "$T/dist-badsum" "3.0.0")" WEARSIC_SHA_URL="$(sha_url "$T/dist-badsum" "3.0.0")"
      bash "$ROOT/install.sh" </dev/null ) >"$T/badsum-install.log" 2>&1; then
  fail "installer refuses bad checksum (exited 0!)"
else
  pass "installer refuses bad checksum"
fi

# ═══ 6. FAILED DOWNLOAD ══════════════════════════════════════════════════════
section "6. failed download (must fail cleanly)"
if ( export WEARSIC_ZIP_URL="file://$T/does-not-exist.zip" WEARSIC_SHA_URL="file://$T/does-not-exist.zip.sha256"
      bash "$HOME/.local/bin/wearsic" update ) >"$T/baddl-update.log" 2>&1; then
  fail "update fails on missing download (exited 0!)"
else
  pass "update fails cleanly on missing download"
fi
grep -q 'engine-v2' "$DEST/lib/test-engine-marker" && pass "engine unchanged after failed download" || fail "engine unchanged after failed download"

# ═══ 7. ROLLBACK (broken engine) ═════════════════════════════════════════════
section "7. rollback (broken engine -> previous restored)"
if run_update "$DBROKEN" "4.0.0" >"$T/rollback.log" 2>&1; then
  fail "update of a broken engine exits non-zero (exited 0!)"
else
  pass "update of a broken engine exits non-zero"
fi
grep -q 'engine-v2' "$DEST/lib/test-engine-marker" && pass "previous engine restored (rollback)" \
  || { fail "previous engine restored (rollback)"; grep -q 'broken' "$DEST/lib/test-engine-marker" && note "broken engine is still active!"; }
HEALTH | grep -q '"status":"ok"' && pass "server healthy after rollback" || fail "server healthy after rollback"
grep -q 'SQLITE-DATA-MARKER' "$DEST/wearsic.db" && pass "wearsic.db preserved through rollback" || fail "wearsic.db preserved through rollback"

# ═══ 8. SECRET HYGIENE ═══════════════════════════════════════════════════════
section "8. secrets never appear in logs"
if grep -qF "$API_KEY" "$DEST/wearsic-server.log" "$DEST/wearsic-server.log.old" 2>/dev/null; then
  fail "API key absent from server logs"
else
  pass "API key absent from server logs"
fi
CLI cookies "SECRET-COOKIE-XYZW; HSID=abc" >/dev/null 2>&1
CLI restart >/dev/null 2>&1
sleep 1
if grep -q "SECRET-COOKIE-XYZW" "$DEST/wearsic-server.log" 2>/dev/null; then
  fail "YouTube cookie absent from server logs"
else
  pass "YouTube cookie absent from server logs"
fi
if grep -q "SECRET-COOKIE-XYZW" "$T"/*.log 2>/dev/null; then
  fail "YouTube cookie absent from CLI output captures"
else
  pass "YouTube cookie absent from CLI output captures"
fi

# ═══ summary ═════════════════════════════════════════════════════════════════
printf '\n\033[1mResult: %d passed, %d failed\033[0m\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
