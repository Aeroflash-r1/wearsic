#!/data/data/com.termux/files/usr/bin/bash
# Wearsic server launcher (Termux) — auto-heal supervisor.
#
# Responsibilities:
#   - start the server (source build via installDist, or legacy bin/ layout)
#   - restart it if it crashes, with exponential backoff
#   - detect hangs via /health and force-restart after repeated failures
#   - apply engine updates staged by the server's self-healing updater
#     with a CRASH-SAFE, transactional replacement (see below)
#   - roll back to the previous known-good engine when a replacement never
#     passes startup/health validation (bounded — never loops forever)
#   - keep a wake lock so Android does not freeze Termux in the background
#   - rotate the log at ~2 MB
#   - fail loudly (exit non-zero) if ffmpeg installation genuinely fails
#
# Engine replacement layout (next to this script):
#   bin/ + lib/            the ACTIVE engine (only ever complete builds)
#   bin.new/ + lib.new/    fully-prepared replacement (transient)
#   bin.prev/ + lib.prev/  previous known-good engine (rollback copy)
# State dir (wearsic-state/):
#   update.json            staged update written by the server process
#   update-in-progress     swap marker (phase=prepared|swapped)
#   rollback.json          why/when the last rollback happened (surfaced in
#                          /health as update.rollbackReason)
#   update-history.log     plain-text audit trail of applies + rollbacks
#
# The active engine is NEVER destroyed before its replacement is complete:
# the new build is copied in full first (bin.new/lib.new), only then are the
# directories swapped by rename. An interruption at ANY point is detected at
# the next start and either finished or rolled back — the supervisor never
# boots from a partially copied engine.
#
# JVM tuning: G1GC with a 512m heap (SerialGC froze the whole server on every
# collection). Drop -Xmx512m to -Xmx384m on phones with <3-4GB free RAM.
set -u

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
LOG="$SCRIPT_DIR/wearsic-server.log"

# Self-healing state dir (staged engine updates + update.json handoff).
# Must match the server's resolution order: WEARSIC_STATE_DIR, else
# <db-dir>/wearsic-state, else ./wearsic-state.
resolve_state_dir() {
  if [ -n "${WEARSIC_STATE_DIR:-}" ]; then
    echo "$WEARSIC_STATE_DIR"
  elif [ -n "${WEARSIC_DB_PATH:-}" ]; then
    echo "$(dirname -- "$WEARSIC_DB_PATH")/wearsic-state"
  else
    echo "$SCRIPT_DIR/wearsic-state"
  fi
}
STATE_DIR="$(resolve_state_dir)"

# Support both layouts:
#  - packaged install: wearsic-server/bin/wearsic-server (next to this script)
#  - from the Git repo: wearsic-server/build/install/wearsic-server/bin/wearsic-server
if [ -x "$SCRIPT_DIR/bin/wearsic-server" ]; then
  APP="$SCRIPT_DIR/bin/wearsic-server"
elif [ -x "$SCRIPT_DIR/build/install/wearsic-server/bin/wearsic-server" ]; then
  APP="$SCRIPT_DIR/build/install/wearsic-server/bin/wearsic-server"
else
  echo "Missing wearsic-server binary. Unzip the full package (bin/ and lib/ must sit next to this script) or run ./gradlew :wearsic-server:installDist first." >&2
  exit 1
fi

if [ -f "$SCRIPT_DIR/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$SCRIPT_DIR/.env"
  set +a
fi

# --- Perf-tuned JVM flags ---
export JAVA_OPTS="${JAVA_OPTS:--Xms64m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=150 -Dhttp.keepAlive=true -Dhttp.maxConnections=10}"
export PORT="${PORT:-8080}"
export WEARSIC_DB_PATH="${WEARSIC_DB_PATH:-$SCRIPT_DIR/wearsic.db}"

log() { echo "[wearsic $(date '+%H:%M:%S')] $*" | tee -a "$LOG"; }

rotate_log() {
  if [ -f "$LOG" ] && [ "$(wc -c < "$LOG")" -gt 2000000 ]; then
    mv -f "$LOG" "$LOG.old"
  fi
}

health_url() {
  echo "http://127.0.0.1:${PORT}/health"
}

health_ok() {
  if command -v curl >/dev/null 2>&1; then
    curl -sf -m 10 "$(health_url)" >/dev/null 2>&1
  elif command -v wget >/dev/null 2>&1; then
    wget -q -T 10 -O /dev/null "$(health_url)"
  else
    return 0   # no probe tool available: treat as OK (crash-restart still active)
  fi
}

cleanup() {
  [ -n "${CHILD:-}" ] && kill "$CHILD" 2>/dev/null
  exit 0
}
trap cleanup INT TERM

# --- Supervisor singleton ---------------------------------------------------
# Two supervisors would double-restart the server and fight over the engine
# directories during updates. Refuse to start when another instance of THIS
# launcher is already running (stale PID files are avoided entirely by
# checking live processes; the pattern is the absolute script path so it
# cannot match an unrelated process).
other_supervisor_pids() {
  # $$ is the script's PID even inside $() — the command substitution has its
  # OWN pid (BASHPID) and its command line also matches the pattern. Filter
  # both, or the guard would always "find itself" and refuse to start.
  local self="${BASHPID:-$$}"
  if command -v pgrep >/dev/null 2>&1; then
    pgrep -f "$SCRIPT_DIR/run-termux.sh" 2>/dev/null | grep -v "^$$\$" | grep -v "^$self\$"
  else
    ps -ef 2>/dev/null | awk -v p="$SCRIPT_DIR/run-termux.sh" -v s1="$$" -v s2="$self" \
      '$0 ~ p && $2 != s1 && $2 != s2 {print $2}'
  fi
}

# --- Crash-safe engine replacement -----------------------------------------
PKG_DIR="$(dirname -- "$(dirname -- "$APP")")"
MARKER="$STATE_DIR/update-in-progress"
UPDATE_JSON="$STATE_DIR/update.json"
HISTORY="$STATE_DIR/update-history.log"
ROLLBACK_JSON="$STATE_DIR/rollback.json"

history() {
  mkdir -p "$STATE_DIR"
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$HISTORY"
}

write_rollback_record() { # $1 = version, $2 = reason (no quotes/spaces)
  mkdir -p "$STATE_DIR"
  printf '{"version":"%s","reason":"%s","atMillis":%s}\n' \
    "${1:-unknown}" "$2" "$(date +%s)000" > "$ROLLBACK_JSON"
}

rollback_engine() { # $1 = machine-readable reason
  local reason="${1:-unknown}"
  if [ ! -d "$PKG_DIR/bin.prev" ] || [ ! -d "$PKG_DIR/lib.prev" ]; then
    log "ERROR: rollback requested ($reason) but no previous engine exists"
    rm -f "$MARKER"
    return 1
  fi
  log "ROLLING BACK to previous known-good engine ($reason)…"
  rm -rf "$PKG_DIR/bin" "$PKG_DIR/lib" "$PKG_DIR/bin.new" "$PKG_DIR/lib.new"
  mv "$PKG_DIR/bin.prev" "$PKG_DIR/bin"
  mv "$PKG_DIR/lib.prev" "$PKG_DIR/lib"
  APP="$PKG_DIR/bin/wearsic-server"
  chmod +x "$PKG_DIR/bin/wearsic-server" 2>/dev/null
  rm -f "$MARKER" "$UPDATE_JSON"
  rm -rf "$STATE_DIR/staging"
  write_rollback_record "${NEW_VER:-unknown}" "$reason"
  history "rollback: $reason (to previous engine)"
  log "rollback complete — running the previous engine again"
  return 0
}

finish_applied_update() { # $1 = new version
  # Cleanup happens ONLY here — i.e. only after the swap fully completed.
  rm -rf "$STATE_DIR/staging"
  rm -f "$UPDATE_JSON" "$MARKER"
  history "applied v${1:-?} (previous v${OLD_VER:-?})"
  log "engine updated to v${1:-?} (rollback copy kept until health is proven)"
}

# Detects and repairs an update interrupted by Android killing the process.
# Recovery rules:
#   - marker present, active bin+lib complete, bin.new still exists
#       -> swap never started: discard the prepared copy, leave update.json
#          so the update is applied again on the next cycle
#   - marker present, active bin+lib complete, bin.new consumed
#       -> swap completed but cleanup was interrupted: finish the cleanup
#   - marker present, active bin/lib incomplete
#       -> half-swapped: restore the previous known-good engine
#   - no marker but stray bin.new
#       -> interrupted preparation: discard it
recover_interrupted_update() {
  [ -f "$MARKER" ] || { rm -rf "$PKG_DIR/bin.new" "$PKG_DIR/lib.new"; return 0; }

  NEW_VER="$(sed -n 's/^version=//p' "$MARKER" | head -1)"
  local phase
  phase="$(sed -n 's/^phase=//p' "$MARKER" | head -1)"

  if [ -x "$PKG_DIR/bin/wearsic-server" ] && [ -n "$(ls -A "$PKG_DIR/lib" 2>/dev/null)" ]; then
    if [ -d "$PKG_DIR/bin.new" ] || [ -d "$PKG_DIR/lib.new" ]; then
      # Prepared but not swapped: active engine is untouched (old, complete).
      rm -rf "$PKG_DIR/bin.new" "$PKG_DIR/lib.new"
      rm -f "$MARKER"
      history "interrupted update v${NEW_VER:-?} before swap — will retry"
      log "recovered: interrupted update v${NEW_VER:-?} will be retried"
    else
      finish_applied_update "${NEW_VER:-?}"
      log "recovered: interrupted update cleanup for v${NEW_VER:-?} completed"
    fi
  else
    log "recovered: engine directories incomplete after interrupted update (phase=${phase:-?})"
    rollback_engine "interrupted-engine-swap"
  fi
}

# Staged package must look like a real engine release before we touch the
# active directories (mirrors the server-side validation).
staged_package_valid() { # $1 = package root
  [ -x "$1/bin/wearsic-server" ] || [ -f "$1/bin/wearsic-server" ] || return 1
  [ -n "$(ls -A "$1/lib" 2>/dev/null)" ] || return 1
  [ -f "$1/run-termux.sh" ] || return 1
  return 0
}

apply_staged_update() {
  [ -f "$UPDATE_JSON" ] || return 0

  # Accept only a 'staged' state — anything else is stale.
  if ! grep -q '"status"[[:space:]]*:[[:space:]]*"staged"' "$UPDATE_JSON"; then
    return 0
  fi
  NEW_VER=$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$UPDATE_JSON" | head -n1)
  OLD_VER=$(sed -n 's/.*"previousVersion"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$UPDATE_JSON" | head -n1)

  STAGE="$STATE_DIR/staging"
  # The release zip extracts as wearsic-server/... — find the package root.
  SRC="$STAGE"
  [ -f "$STAGE/wearsic-server/bin/wearsic-server" ] && SRC="$STAGE/wearsic-server"
  if ! staged_package_valid "$SRC"; then
    log "ERROR: staged update is incomplete (bin/lib/run-termux.sh) — discarding"
    rm -rf "$STAGE" "$UPDATE_JSON"
    return 0
  fi

  log "applying staged engine update: v${OLD_VER:-?} -> v${NEW_VER:-?}"

  # 1) Prepare COMPLETE copies next to the active engine. Nothing active is
  #    touched until both copies exist (crash here = nothing happened).
  rm -rf "$PKG_DIR/bin.new" "$PKG_DIR/lib.new"
  if ! cp -r "$SRC/bin" "$PKG_DIR/bin.new" || ! cp -r "$SRC/lib" "$PKG_DIR/lib.new"; then
    log "ERROR: could not prepare the new engine — keeping the current one"
    rm -rf "$PKG_DIR/bin.new" "$PKG_DIR/lib.new"
    rm -rf "$STAGE" "$UPDATE_JSON"
    return 0
  fi

  # 2) Mark the swap as in progress (recovery reads this after a crash),
  #    then swap by rename: bin -> bin.prev -> bin.new becomes bin.
  mkdir -p "$STATE_DIR"
  printf 'phase=prepared\nversion=%s\n' "${NEW_VER:-unknown}" > "$MARKER"
  rm -rf "$PKG_DIR/bin.prev" "$PKG_DIR/lib.prev"
  mv "$PKG_DIR/bin" "$PKG_DIR/bin.prev" && mv "$PKG_DIR/lib" "$PKG_DIR/lib.prev" \
    && mv "$PKG_DIR/bin.new" "$PKG_DIR/bin" && mv "$PKG_DIR/lib.new" "$PKG_DIR/lib"
  if [ ! -x "$PKG_DIR/bin/wearsic-server" ]; then
    log "ERROR: engine swap did not complete — restoring previous engine"
    rollback_engine "interrupted-engine-swap"
    return 0
  fi
  chmod +x "$PKG_DIR/bin/wearsic-server" "$PKG_DIR/run-termux.sh" 2>/dev/null

  # 3) Swap complete — record it, then clean up.
  printf 'phase=swapped\nversion=%s\n' "${NEW_VER:-unknown}" > "$MARKER"
  APP="$PKG_DIR/bin/wearsic-server"
  finish_applied_update "${NEW_VER:-?}"
}

# Keep Android from freezing/killing Termux in the background.
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock && log "wake lock acquired"

# Refuse a second supervisor BEFORE touching any state.
OTHER_SUPERVISORS="$(other_supervisor_pids || true)"
if [ -n "$OTHER_SUPERVISORS" ]; then
  log "another supervisor is already running (pid $OTHER_SUPERVISORS) — exiting"
  exit 0
fi

mkdir -p "$STATE_DIR"
recover_interrupted_update

log "auto-heal supervisor starting (health checks every 30s)"

# --- FFmpeg: required only for the rare Opus/WebM-only song (503 otherwise). ---
# Installation failures must NOT be reported as success: verify the binary
# actually exists after pkg install, and exit non-zero if it does not — a
# silent partial install previously made the supervisor print "ffmpeg
# installed" while transcoding stayed broken.
if ! command -v ffmpeg >/dev/null 2>&1; then
  log "ffmpeg not found — attempting installation..."
  if command -v pkg >/dev/null 2>&1; then
    if pkg install -y ffmpeg >> "$LOG" 2>&1; then
      if command -v ffmpeg >/dev/null 2>&1; then
        log "ffmpeg installed and verified"
      else
        log "ERROR: pkg reported success but ffmpeg is still not on PATH"
        log "Install it manually with: pkg install ffmpeg   (then re-run this script)"
        exit 1
      fi
    else
      log "ERROR: ffmpeg installation FAILED (unmet dependencies or network problem)"
      log "The server will still run, but songs YouTube only offers in Opus/WebM will answer 503."
      log "Fix with: pkg update -y && pkg install -y ffmpeg   (then re-run this script)"
      exit 1
    fi
  else
    log "ERROR: pkg not found — cannot install ffmpeg automatically"
    log "Install it manually with: pkg install ffmpeg   (then re-run this script)"
    exit 1
  fi
fi

# --- Restart loop with exponential backoff ---------------------------------
# crash -> restart after 3s -> next crash after 6s -> 12s ... capped at 60s.
# A permanently broken server stays visible (repeated ERROR logs every ~60s)
# instead of burning battery on a tight restart loop.
# A staged engine update is applied at the top of every cycle: the server
# exits cleanly after staging one, so the supervisor swaps bin/+lib/ and
# boots the new engine automatically.
RESTART_DELAY=3
MAX_RESTART_DELAY=60

while true; do
  rotate_log
  apply_staged_update
  "$APP" >> "$LOG" 2>&1 &
  CHILD=$!
  log "server started (pid $CHILD, port $PORT)"

  FAILS=0
  EVER_HEALTHY=0
  while kill -0 "$CHILD" 2>/dev/null; do
    sleep 30
    if ! kill -0 "$CHILD" 2>/dev/null; then
      break   # process died on its own; outer loop handles restart
    fi
    if health_ok; then
      FAILS=0
      EVER_HEALTHY=1
    else
      FAILS=$((FAILS+1))
      log "health check FAILED ($FAILS/3)"
      if [ "$FAILS" -ge 3 ]; then
        log "server unhealthy for 90s — killing for auto-heal restart"
        kill "$CHILD" 2>/dev/null
        sleep 5
        kill -9 "$CHILD" 2>/dev/null
        break
      fi
    fi
  done

  wait "$CHILD" 2>/dev/null
  EXIT_CODE=$?

  if [ "$EVER_HEALTHY" -eq 1 ]; then
    # The server worked this cycle — treat the exit as a one-off crash and
    # restart quickly again.
    RESTART_DELAY=3
    log "server exited (code $EXIT_CODE) — restarting in ${RESTART_DELAY}s (Ctrl+C to stop supervisor)"
    # Health validation passed: the rollback copy of the previous engine is
    # no longer needed (kept until this point precisely for this moment).
    if [ -d "$PKG_DIR/bin.prev" ]; then
      rm -rf "$PKG_DIR/bin.prev" "$PKG_DIR/lib.prev"
      history "health validated — rollback copy removed"
    fi
  else
    log "ERROR: server never became healthy this cycle (exit code $EXIT_CODE)"
    # A freshly replaced engine that NEVER becomes healthy is rolled back to
    # the previous known-good build — but at most once per hour, so a
    # genuinely broken phone environment can never cause a rollback/restart
    # storm. The server-side attempt budget (update-attempts.json) prevents
    # the same broken update from being re-staged afterwards.
    LAST_ROLLBACK_MS=$(sed -n 's/.*"atMillis"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p' "$ROLLBACK_JSON" 2>/dev/null | head -1)
    NOW_MS=$(date +%s)000
    if [ -d "$PKG_DIR/bin.prev" ] && \
       { [ -z "$LAST_ROLLBACK_MS" ] || [ $(( NOW_MS - LAST_ROLLBACK_MS )) -gt 3600000 ]; }; then
      NEW_VER=$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$UPDATE_JSON" 2>/dev/null | head -1)
      rollback_engine "new-engine-failed-health-validation"
    fi
    log "restart in ${RESTART_DELAY}s — check '$LOG' if this repeats"
  fi

  sleep "$RESTART_DELAY"
  # Exponential backoff capped at MAX_RESTART_DELAY; resets after any healthy cycle.
  RESTART_DELAY=$(( RESTART_DELAY * 2 ))
  if [ "$RESTART_DELAY" -gt "$MAX_RESTART_DELAY" ]; then
    RESTART_DELAY=$MAX_RESTART_DELAY
  fi
done
