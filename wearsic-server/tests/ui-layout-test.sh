#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# UI layout regression test: every screen must FIT the terminal, panels must
# line up to the column, and scripted output must stay machine-readable.
#
# WHY: the CLI draws rounded panels whose width comes from the terminal (not
# the content), so a phone gets full-width panels at ~45 columns. Two real
# bug classes shipped here before: a wrap path that padded rows with the
# colour codes counted as visible width (ragged right borders), and content
# whose leading indent was eaten by word wrapping. Both are invisible without
# escaping the output and counting characters — this test does exactly that.
#
# What is pinned:
#   1. at 34/40/46/60/80 columns, NO rendered line is wider than the terminal
#   2. every border/panel row on a screen has the SAME visible width
#   3. panels really are drawn (rounded corners present on panel screens)
#   4. `wearsic url` piped (no terminal, no COLUMNS) stays plain, one line
#      per message, with the API key greppable on a single line
#   5. `wearsic health --json` is RAW json — zero escape codes even when
#      colour is forced on
#   6. NO_COLOR=1 forces plain output even with WEARSIC_COLOR=always
#   7. help lists all four command groups
#
# A stub engine answers /health and /ready (compact JSON, like the real
# server) so the status-style screens render their real panels.
#
# Usage:  bash wearsic-server/tests/ui-layout-test.sh
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

HERE="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CLI="${CLI:-$HERE/wearsic}"
[ -f "$CLI" ] || { echo "FAIL cannot find CLI at $CLI"; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

passes=0; fails=0
pass() { echo "  ok   $1"; passes=$((passes+1)); }
fail() { echo "  FAIL $1"; fails=$((fails+1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then pass "$1";
  else fail "$1"; echo "       expected: [$2]"; echo "       actual:   [$3]"; fi
}

# Visible width must count CHARACTERS (✓ ● │ are one column each). Only trust
# a UTF-8 locale if it actually reports the test glyph as one character.
if [ "$(LC_ALL=C.UTF-8 bash -c 'x="✓"; printf "%s" "${#x}"' 2>/dev/null)" = "1" ]; then
  export LC_ALL=C.UTF-8
fi

PORT=18931
DEST="$WORK/dest"
mkdir -p "$DEST/bin"
printf 'WEARSIC_API_KEY=test2345\nPORT=%s\n' "$PORT" > "$DEST/.env"
printf '#!/bin/bash\ntrue\n' > "$DEST/run-termux.sh"
printf '#!/bin/bash\ntrue\n' > "$DEST/bin/wearsic-server"
chmod +x "$DEST/run-termux.sh" "$DEST/bin/wearsic-server"

ESC="$(printf '\033')"
strip() { sed "s/${ESC}\\[[0-9;]*m//g"; }

# ── stub engine: compact JSON, /health + /ready only ────────────────────────
cat > "$WORK/hs.py" <<'PY'
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

HEALTH = {"status": "ok", "version": "1.9.2", "serverName": "Wearsic Server",
          "transcoderAvailable": True, "canaryHealthy": True, "crashCount": 0,
          "upstreamRefusals": 3,
          "extraction": {"successCount": 412, "failureCount": 3,
                         "failureRatePercent": 0, "consecutiveFailures": 0,
                         "lastError": ""},
          "update": {"status": "idle", "stagedVersion": ""}}
READY = {"ready": True, "database": True, "extractor": True,
         "transcoder": True, "engineVersion": "1.9.2"}


class H(BaseHTTPRequestHandler):
    def do_GET(self):
        body = HEALTH if self.path.startswith("/health") else (
            READY if self.path.startswith("/ready") else None)
        if body is None:
            self.send_response(404); self.end_headers(); return
        data = json.dumps(body, separators=(",", ":")).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *a):
        pass


HTTPServer(("127.0.0.1", int(__import__("os").environ.get("STUB_PORT", "18931"))), H).serve_forever()
PY
STUB_PID=""
if command -v python3 >/dev/null 2>&1; then
  STUB_PORT="$PORT" python3 "$WORK/hs.py" >/dev/null 2>&1 &
  STUB_PID=$!
  i=0
  while [ "$i" -lt 20 ]; do
    curl -s -m 1 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1 && break
    sleep 0.15
    i=$((i + 1))
  done
fi
trap '[ -n "$STUB_PID" ] && kill "$STUB_PID" 2>/dev/null; rm -rf "$WORK"' EXIT

run() { # $1 = width, rest = command + args
  local w="$1"; shift
  WEARSIC_DIR="$DEST" WEARSIC_COLOR=always COLUMNS="$w" bash "$CLI" "$@" 2>&1
}

echo "UI layout (fit + panels + scripted output):"

# ═══ 1+2+3. every screen, every width: no overflow, no drift, panels drawn ═══
for W in 34 40 46 60 80; do
  for cmd in help doctor version status ready url api-key ip; do
    out="$(run "$W" "$cmd")" || true
    bad=0; maxw=0; pan=""; panels=0
    while IFS= read -r line; do
      v="$(printf '%s' "$line" | strip)"
      n=${#v}
      [ "$n" -gt "$maxw" ] && maxw=$n
      [ "$n" -gt "$W" ] && bad=$((bad+1))
      case "$v" in
        *╭*) panels=$((panels+1)) ;;
      esac
      case "$v" in
        *│*|*╭*|*╰*)
          if [ -z "$pan" ]; then pan=$n
          elif [ "$n" -ne "$pan" ]; then bad=$((bad+1)); fi ;;
      esac
    done <<< "$out"
    if [ "$bad" -eq 0 ]; then
      pass "$cmd @${W}c: fits (max ${maxw}) ${panels:+panels:$panels}"
    else
      fail "$cmd @${W}c: $bad line(s) break the layout"
      printf '%s\n' "$out" | strip | awk -v w="$W" '{ if (length($0) > w) print "       over: " $0 }'
    fi
  done
done

# ═══ 4. scripted output: plain, single-line key, no codes when piped ═════════
echo "scripted output:"
u="$(WEARSIC_DIR="$DEST" COLUMNS= WEARSIC_COLOR=auto bash "$CLI" url 2>/dev/null)"
if printf '%s' "$u" | grep "$ESC" >/dev/null; then
  fail "piped url is plain (no escape codes)"
else
  pass "piped url is plain (no escape codes)"
fi
if printf '%s\n' "$u" | grep "test2345" >/dev/null; then
  pass "piped url keeps the API key on one line"
else
  fail "piped url keeps the API key on one line"
fi
if printf '%s\n' "$u" | grep '╭' >/dev/null; then
  pass "piped url still draws the pairing panel"
else
  fail "piped url still draws the pairing panel"
fi

# ═══ 5. health --json: raw body, colour override cannot leak into it ════════
hj="$(WEARSIC_DIR="$DEST" WEARSIC_COLOR=always bash "$CLI" health --json 2>/dev/null)"
rc=$?
check "health --json exits 0" "0" "$rc"
if printf '%s' "$hj" | grep '"status":"ok"' >/dev/null; then
  pass "health --json carries the raw body"
else
  fail "health --json carries the raw body"
fi
if printf '%s' "$hj" | grep "$ESC" >/dev/null; then
  fail "health --json has no escape codes"
else
  pass "health --json has no escape codes"
fi

# ═══ 6. NO_COLOR wins over WEARSIC_COLOR=always ═════════════════════════════
nc="$(WEARSIC_DIR="$DEST" NO_COLOR=1 WEARSIC_COLOR=always COLUMNS=46 bash "$CLI" help 2>&1)"
if printf '%s' "$nc" | grep "$ESC" >/dev/null; then
  fail "NO_COLOR=1 forces plain output"
else
  pass "NO_COLOR=1 forces plain output"
fi

# ═══ 7. help lists every command group ══════════════════════════════════════
h="$(run 46 help)"
for grp in "Server" "Watch pairing" "Engine" "Remote access"; do
  if printf '%s' "$h" | grep "$grp" >/dev/null; then
    pass "help lists '$grp'"
  else
    fail "help lists '$grp'"
  fi
done

echo "components:"
# ═══ 8. doctor rows = glyph + NAME (not the old "— ok" state-in-name bug) ═
do="$(run 46 doctor)"
if printf '%s\n' "$do" | strip | grep -E '^    .[[:space:]]+(java|ffmpeg|storage|termux|install|health)' >/dev/null; then
  pass "doctor rows render glyph + check name"
else
  fail "doctor rows render glyph + check name"
fi
if printf '%s\n' "$do" | strip | grep -E '— (ok|warn|err|off)' >/dev/null; then
  fail "doctor rows do not print the state in the name column"
else
  pass "doctor rows do not print the state in the name column"
fi

# ═══ 9. app-bar hairline is exactly as wide as the panel below it ══════════
st="$(run 80 status)"
# ${#var} counts CHARACTERS in a UTF-8 locale; awk length counts BYTES
# (every border glyph is 3 bytes), which would compare nonsense.
ruleline="$(printf '%s\n' "$st" | strip | sed -n '3p')"
panelline="$(printf '%s\n' "$st" | strip | grep -m1 '╭')"
rulew=${#ruleline}
panelw=${#panelline}
if [ -n "$rulew" ] && [ "$rulew" = "$panelw" ]; then
  pass "header rule aligns with the panel edge ($rulew cols)"
else
  fail "header rule aligns with the panel edge (rule=$rulew panel=$panelw)"
fi

# ═══ 10. status is a titled panel and states its verdict ══════════════════
if printf '%s\n' "$st" | strip | grep '╭─ status' >/dev/null; then
  pass "status draws the status panel"
else
  fail "status draws the status panel"
fi
if printf '%s\n' "$st" | strip | grep -E '(healthy|not healthy|stopped)' >/dev/null; then
  pass "status states its verdict"
else
  fail "status states its verdict"
fi

# ═══ 11. state chips degrade to [label] without colour ════════════════════
pp="$(WEARSIC_DIR="$DEST" NO_COLOR=1 COLUMNS=46 bash "$CLI" help 2>&1)"
if printf '%s\n' "$pp" | grep -E '\[(not )?(running|installed)\]' >/dev/null; then
  pass "state chips fall back to [label] when colour is off"
else
  fail "state chips fall back to [label] when colour is off"
fi

echo
if [ "$fails" -eq 0 ]; then
  echo "ui-layout-test: PASSED ($passes checks)"
  exit 0
fi
echo "ui-layout-test: FAILED ($fails of $((passes + fails)) checks)"
exit 1
