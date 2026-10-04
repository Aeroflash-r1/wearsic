#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Regression test for the `wearsic funnel` setup failure.
#
# THE BUG: funnel probed `tailscale status` to answer two different questions
#   1. "is tailscaled running?"
#   2. "is this node authenticated?"
# `tailscale status` exits NON-ZERO in both cases, so the two answers were
# identical and a logged-OUT phone was reported as a dead daemon. The
# `tailscale up` branch was unreachable code that could never run, so the
# user was told "Tailscale daemon is not running. Try: tailscaled-start" —
# the wrong remedy for the actual problem.
#
# `ts_state` fixes this by reading Tailscale's BackendState. These cases pin
# that behaviour, including older tailscale builds that have no --json flag.
#
# Usage:  bash wearsic-server/tests/funnel-state-test.sh
# ─────────────────────────────────────────────────────────────────────────────
set -u

HERE="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CLI="$HERE/wearsic"
[ -f "$CLI" ] || { echo "cannot find wearsic CLI next to this test"; exit 2; }

fails=0
pass() { echo "  ok   $1"; }
fail() { echo "  FAIL $1"; fails=$((fails+1)); }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin"

# Extract the function under test straight from the shipped CLI, so this can
# never drift from the code users actually run.
sed -n '/^ts_state() {/,/^}/p' "$CLI" > "$WORK/fn.sh"
[ -s "$WORK/fn.sh" ] || { echo "FAIL could not extract ts_state from the CLI"; exit 1; }

# A fake `tailscale` that answers however the case under test wants.
fake_tailscale() {
  cat > "$WORK/bin/tailscale" <<EOF
#!/bin/sh
$1
EOF
  chmod +x "$WORK/bin/tailscale"
}

expect_state() {
  local want="$1" label="$2"
  local got
  got="$(PATH="$WORK/bin:$PATH" bash -c 'source "$1"; ts_state' bash "$WORK/fn.sh" 2>/dev/null)"
  if [ "$got" = "$want" ]; then pass "$label -> $want"
  else fail "$label: expected '$want', got '${got:-<empty>}'"; fi
}

echo "funnel state detection:"

# Modern CLI: the authoritative BackendState path. NOTE the pattern is
# "*--json*", not "--json": ts_state runs `tailscale status --json`, so $1 is
# "status". Matching on exactly "--json" would silently fall through to the
# legacy branch and never test the code path we care about.
fake_tailscale 'case "$*" in *--json*) echo "{\"BackendState\":\"Running\"}" ;; *) exit 0 ;; esac'
expect_state Running "connected node (BackendState)"

fake_tailscale 'case "$*" in *--json*) echo "{\"BackendState\":\"NeedsLogin\"}" ;; *) echo "Logged out."; exit 1 ;; esac'
expect_state NeedsLogin "daemon up, NOT logged in"

fake_tailscale 'case "$*" in *--json*) echo "{\"BackendState\":\"Stopped\"}" ;; *) exit 1 ;; esac'
expect_state Stopped "authenticated but stopped"

# A modern build whose daemon is unreachable must NOT be reported as
# NeedsLogin just because the JSON body is missing the field.
fake_tailscale 'case "$*" in *--json*) echo "{}" ;; *) echo "failed to connect" >&2; exit 1 ;; esac'
expect_state NoState "modern build, daemon unreachable"

# THE REGRESSION CASE. Before the fix this was indistinguishable from the
# daemon-down case, which produced the misleading "daemon is not running".
fake_tailscale 'echo "failed to connect to local tailscaled" >&2; exit 1'
expect_state NoState "daemon unreachable"

# Older tailscale builds without --json must still be classified correctly.
fake_tailscale 'echo "Logged out." >&2; exit 1'
expect_state NeedsLogin "old build, logged out"

fake_tailscale 'echo "100.64.0.1  phone  linux"; exit 0'
expect_state Running "old build, connected"

# The CLI must stay syntactically valid after all of this.
if bash -n "$CLI" 2>/dev/null; then pass "wearsic CLI parses"
else fail "wearsic CLI has a syntax error"; fi

echo
if [ "$fails" -eq 0 ]; then echo "funnel-state-test: PASSED"; exit 0; fi
echo "funnel-state-test: $fails FAILED"; exit 1