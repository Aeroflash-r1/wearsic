#!/usr/bin/env bash
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
# G1GC with a 512m heap (SerialGC froze the whole server on every
# collection). TieredStopAtLevel=1: this server is I/O-bound (network +
# SQLite), so C2's peak-throughput win never materializes while its slow
# optimizing compiles stretch cold boots on little phone CPUs by ~30%.
# Drop -Xmx512m to -Xmx384m on phones with <3-4GB free RAM.
# -Djava.net.preferIPv4Stack=true: this network resolves AAAA records that
# are NOT routable (documented for the Gradle daemon in gradle.properties;
# the engine JVM was missing it). The dual-stack stall it causes showed up
# as update.lastError=UnresolvedAddressException in /health.
# -XX:+ExitOnOutOfMemoryError: a half-alive JVM wedged after OOM is worse
# than a dead one — the supervisor's restart loop IS the recovery path.
# GC log: bounded (2×2MB) for post-mortem, always on disk when it matters.
# The `::` after the log path is an EMPTY decorator list: without it the
# parser reads `filecount=2` as a DECORATOR, rejects the option and the
# JVM refuses to start at all (this shipped once — every engine start
# failed with "Invalid decorator 'filecount=2'").
export JAVA_OPTS="${JAVA_OPTS:--Xms64m -Xmx512m -XX:+UseG1GC -XX:TieredStopAtLevel=1 -XX:MaxGCPauseMillis=150 -Dhttp.keepAlive=true -Dhttp.maxConnections=10 -Djava.net.preferIPv4Stack=true -XX:+ExitOnOutOfMemoryError -Xlog:gc:file=$SCRIPT_DIR/wearsic-gc.log::filecount=2,filesize=2M}"
export PORT="${PORT:-8080}"
export WEARSIC_DB_PATH="${WEARSIC_DB_PATH:-$SCRIPT_DIR/wearsic.db}"

# --- JVM bootstrap ----------------------------------------------------------
# Some hosts cannot start a JVM without help: inside an Android proot
# (Acode's Alpine), HotSpot's startup probe tries to mprotect() a page to
# PROT_READ|PROT_WRITE|PROT_EXEC, which Android's SELinux policy refuses
# (EACCES), so the VM aborts with "Failed to mark memory page as executable".
# A tiny LD_PRELOAD shim works around it. If the JVM cannot start and a shim
# is available, use it — the engine then works without the caller having
# sourced any environment first.
if ! java -version >/dev/null 2>&1; then
  _shim="${WEARSIC_JVM_SHIM:-}"
  if [ -z "$_shim" ]; then
    for _c in "$HOME/.wearsic/libmprotect-shim.so"; do
      [ -f "$_c" ] && { _shim="$_c"; break; }
    done
  fi
  if [ -n "$_shim" ]; then
    LD_PRELOAD="$_shim${LD_PRELOAD:+:$LD_PRELOAD}"
    export LD_PRELOAD
    java -version >/dev/null 2>&1 \
      && log "JVM needed the LD_PRELOAD shim ($_shim) — engine starting" \
      || log "ERROR: the JVM still cannot start with the shim ($_shim)"
  else
    log "ERROR: the JVM cannot start and no shim was found — set WEARSIC_JVM_SHIM"
  fi
fi

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

# --- Front-door watchdog ----------------------------------------------------
# The Cloudflare supervisor (permanent URL) is a separate process from this
# engine supervisor, and NOTHING used to watch it: when it died (lmkd kill,
# fatal Worker error, phone reboot) the public URL stayed dead until a human
# noticed and ran `wearsic cloudflare` by hand — observed live: hours of a
# dead front door. Termux:Boot starts only THIS script, so boot persistence
# for the front door must live here too. Idempotent: it starts the
# supervisor only when one is configured but not running.
ensure_front_door() {
  local state="$SCRIPT_DIR/wearsic-state/cloudflare.json" cli status dir p cmdline
  [ -f "$state" ] || return 0            # cloudflare was never configured
  status="$(sed -n 's/.*"status"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$state" 2>/dev/null | head -1)"
  case "$status" in
    stopped|error) return 0 ;;           # explicitly stopped, or fatal config error: needs a human, not a loop
  esac
  cli="$SCRIPT_DIR/wearsic"
  [ -f "$cli" ] || return 0
  for dir in /proc/[0-9]*; do
    p="${dir#/proc/}"
    [ "$p" = "$$" ] && continue
    cmdline="$( { tr '\0' ' ' < "$dir/cmdline"; } 2>/dev/null )" || continue
    case "$cmdline" in
      *"__cf_supervise"*"$SCRIPT_DIR"*) return 0 ;;   # already supervised
    esac
  done
  log "front door supervisor is not running — starting it"
  if command -v setsid >/dev/null 2>&1; then
    ( cd "$SCRIPT_DIR" && setsid nohup bash "$cli" __cf_supervise "$PORT" "$SCRIPT_DIR" >/dev/null 2>&1 </dev/null & )
  else
    ( cd "$SCRIPT_DIR" && nohup bash "$cli" __cf_supervise "$PORT" "$SCRIPT_DIR" >/dev/null 2>&1 </dev/null & )
  fi
}

cleanup() {
  # Kill the engine child ONLY if the pid still looks like our engine: after
  # the JVM exits the number can be recycled, and killing the recycled pid
  # once took down the front-door tunnel's cloudflared (live 2026-10-23 —
  # the tunnel died the moment a phantom run-termux's stale CHILD fired).
  if [ -n "${CHILD:-}" ] && [ -r "/proc/$CHILD/cmdline" ]; then
    case "$( { tr '\0' ' ' < "/proc/$CHILD/cmdline"; } 2>/dev/null )" in
      *wearsic-server*) kill "$CHILD" 2>/dev/null ;;
    esac
  fi
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
  # Precise /proc scan: a process counts as the supervisor only when its
  # argv[1] IS the script (bash /path/run-termux.sh). Matching the path as
  # a SUBSTRING was over-broad — a recovery launcher like
  #   bash -c "cd … && setsid nohup bash …/run-termux.sh &"
  # mentions the path in its own argv, was counted as a live supervisor,
  # and made the real instance refuse to start ("another supervisor is
  # already running (pid <the launcher>)") while no engine existed at all.
  # argv[1] either IS the path verbatim, or resolves (relative argv[1]
  # against the process's OWN cwd) to the same canonical file — covers
  # ./run-termux.sh and the /data/user/0 vs /data/data spellings without
  # cross-counting a different install's run-termux.sh basename.
  # $$ is the script's PID even inside $() — the command substitution has
  # its OWN pid (BASHPID) and its argv[1] can also be the script. Filter
  # both, or the guard would always "find itself" and refuse to start.
  local self="${BASHPID:-$$}" d pid a1 cwd cand canon_pat canon
  canon_pat="$(readlink -f "$SCRIPT_DIR/run-termux.sh" 2>/dev/null)"
  [ -z "$canon_pat" ] && canon_pat="$SCRIPT_DIR/run-termux.sh"
  for d in /proc/[0-9]*; do
    pid="${d#/proc/}"
    [ "$pid" = "$$" ] && continue
    [ "$pid" = "$self" ] && continue
    a1="$( { tr '\0' '\n' < "$d/cmdline"; } 2>/dev/null | sed -n 2p )" || continue
    [ -z "$a1" ] && continue
    case "$a1" in
      "$SCRIPT_DIR/run-termux.sh") printf '%s\n' "$pid"; continue ;;
    esac
    [ "${a1##*/}" = "run-termux.sh" ] || continue
    cand="$a1"
    case "$a1" in
      /*) ;;
      *) cwd="$(readlink "/proc/$pid/cwd" 2>/dev/null)"
         [ -n "$cwd" ] && cand="$cwd/$a1" ;;
    esac
    canon="$(readlink -f "$cand" 2>/dev/null)"
    [ -n "$canon" ] && [ "$canon" = "$canon_pat" ] && printf '%s\n' "$pid"
  done
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

  # The tunnel binary is not part of the engine release: keep it across
  # the swap (bin.prev still holds the old bin/), or the Cloudflare
  # supervisor's next launch dies on a missing binary while minting
  # hostnames (live outage 2026-10-22). Rollback restores bin.prev and
  # thereby the binary too.
  if [ -x "$PKG_DIR/bin.prev/cloudflared" ] && [ ! -e "$PKG_DIR/bin/cloudflared" ]; then
    cp -f "$PKG_DIR/bin.prev/cloudflared" "$PKG_DIR/bin/cloudflared" 2>/dev/null \
      && chmod 0755 "$PKG_DIR/bin/cloudflared" 2>/dev/null \
      && log "kept the tunnel binary (cloudflared) across the engine swap"
  fi

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
# Installs in the BACKGROUND so first boot isn't held hostage by a ~50MB
# download: AAC songs (nearly everything) play immediately; the log says
# exactly when conversion comes online. Failures stay LOUD in the log —
# a silent partial install once made the supervisor claim success while
# transcoding stayed broken, so the background job verifies the binary.
if ! command -v ffmpeg >/dev/null 2>&1; then
  if command -v pkg >/dev/null 2>&1; then
    log "ffmpeg not found — installing in the background (music plays meanwhile)…"
    ( if pkg install -y ffmpeg >> "$LOG" 2>&1 && command -v ffmpeg >/dev/null 2>&1; then
        log "ffmpeg installed and verified — all songs now play"
      else
        log "ERROR: ffmpeg installation FAILED — Opus/WebM-only songs will answer 503."
        log "Fix with: pkg update -y && pkg install -y ffmpeg   (then: wearsic restart)"
      fi ) &
  elif command -v apk >/dev/null 2>&1; then
    # Alpine (Acode's proot userspace) uses apk, not Termux's pkg.
    log "ffmpeg not found — installing in the background (music plays meanwhile)…"
    ( if apk add --no-cache ffmpeg >> "$LOG" 2>&1 && command -v ffmpeg >/dev/null 2>&1; then
        log "ffmpeg installed and verified — all songs now play"
      else
        log "ERROR: ffmpeg installation FAILED — Opus/WebM-only songs will answer 503."
        log "Fix with: apk add ffmpeg   (then: wearsic restart)"
      fi ) &
  else
    log "ERROR: neither pkg nor apk found — cannot install ffmpeg automatically (run: pkg install ffmpeg manually)"
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
  ensure_front_door
  apply_staged_update
  CYCLE_START_MS="$(date +%s)000"
  "$APP" >> "$LOG" 2>&1 &
  CHILD=$!
  log "server started (pid $CHILD, port $PORT)"

  FAILS=0
  EVER_HEALTHY=0
  TICK=0
  while kill -0 "$CHILD" 2>/dev/null; do
    sleep 30
    TICK=$((TICK+1))
    if [ $((TICK % 10)) -eq 0 ]; then
      # A long-healthy engine must not grow the log without bound (the
      # /health probe alone is ~160 KB/day), and the front door must be
      # re-checked even while everything else is quiet.
      rotate_log
      ensure_front_door
    fi
    if ! kill -0 "$CHILD" 2>/dev/null; then
      break   # process died on its own; outer loop handles restart
    fi
    if health_ok; then
      FAILS=0
      EVER_HEALTHY=1
    else
      FAILS=$((FAILS+1))
      log "health check FAILED ($FAILS/3)"
      # Never kill a freshly started server: a cold JVM on a phone CPU can
      # take minutes before /health answers. Killing it at 90s guaranteed a
      # permanent restart loop on slow devices ("starts then dies forever").
      # Only auto-heal after 4 minutes of consecutive failures.
      NOW_MS="$(date +%s)000"
      UPTIME_MS=$(( NOW_MS - CYCLE_START_MS ))
      if [ "$FAILS" -ge 3 ] && [ "$UPTIME_MS" -gt 240000 ]; then
        log "server unhealthy for 90s after boot grace — killing for auto-heal restart"
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
