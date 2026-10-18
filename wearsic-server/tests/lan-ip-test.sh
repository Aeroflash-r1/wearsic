#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Regression test for the `wearsic url` Wi-Fi false negative.
#
# THE BUG: lan_ip() walked a short probe list and when every probe failed it
# printed a bare newline, while server_url/show_pairing fell back to the
# literal `http://<phone-IP>:8080`. Users read that as a real address, typed
# it into the watch app, and concluded the server was broken. Worse, the
# probe list itself was wrong for stock Termux: the one tool Termux never
# has (iproute2's `ip`) came first, while tools it DOES have (net-tools
# ifconfig, /system/bin/{ip,ifconfig}, getprop, /proc/net/fib_trie) were
# missing entirely — a permanent false negative.
#
# lan_ip() must now:
#   * try termux-api -> ip -> ifconfig -> /system/bin/{ip,ifconfig}
#     -> getprop -> /proc/net/fib_trie, in that order;
#   * never emit anything but a plain dotted quad (loopback fib_trie entries
#     must not leak through as the phone's address);
#   * print NOTHING when every probe fails, so callers show guidance instead
#     of a placeholder — server_url() is pinned to prove that here.
#
# Every probe is faked through a hermetic PATH + WEARSIC_SYS_BIN +
# WEARSIC_PROC_NET, so the test is offline and deterministic.
#
# Usage:  bash wearsic-server/tests/lan-ip-test.sh
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

HERE="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CLI="${CLI:-$HERE/wearsic}"
[ -f "$CLI" ] || { echo "FAIL cannot find CLI at $CLI"; exit 1; }

# Absolute path to bash: inside run_fn() below, a `PATH=...` assignment
# precedes the command, so the shell resolves `bash` itself against the NEW
# path — which deliberately contains only the staged fakes. A bare `bash`
# would never be found and every positive case would read as empty.
BASH_BIN="$(command -v bash)"
[ -n "$BASH_BIN" ] || { echo "FAIL bash not on PATH"; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin" "$WORK/sysbin" "$WORK/procnet" "$WORK/util"

# Extract the functions under test straight from the shipped CLI, so this can
# never drift from the code users actually run.
{
  sed -n '/^default_iface() {/,/^}/p' "$CLI"
  sed -n '/^lan_ip() {/,/^}/p' "$CLI"
  sed -n '/^server_url() {/,/^}/p' "$CLI"
} > "$WORK/fn.sh"
grep -q '^default_iface() {' "$WORK/fn.sh" || { echo "FAIL could not extract default_iface from the CLI"; exit 1; }
grep -q '^lan_ip() {' "$WORK/fn.sh" || { echo "FAIL could not extract lan_ip from the CLI"; exit 1; }
grep -q '^server_url() {' "$WORK/fn.sh" || { echo "FAIL could not extract server_url from the CLI"; exit 1; }
# server_url() also needs port(); pin 8080 (the default) so this test does
# not depend on .env plumbing.
cat >> "$WORK/fn.sh" <<'PORT'
port() { printf '%s' 8080; }
PORT

# A hermetic PATH: the fake bin dir first, then a util dir holding symlinks to
# the real text tools lan_ip needs (sed/head/awk/grep/tr) and NOTHING else —
# so `command -v ip` answers exactly what each case intends and never the
# host's real tools.
for t in sed head awk grep tr; do
  ln -s "$(command -v "$t")" "$WORK/util/$t"
done

fails=0
pass() { echo "  ok   $1"; }
fail() { echo "  FAIL $1"; fails=$((fails + 1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then pass "$1";
  else fail "$1"; echo "       expected: [$2]"; echo "       actual:   [$3]"; fi
}

# Reset the fake world between cases; util/ is shared and untouched.
reset() {
  rm -rf "$WORK/bin" "$WORK/sysbin" "$WORK/procnet" "$WORK/env"
  mkdir -p "$WORK/bin" "$WORK/sysbin" "$WORK/procnet"
}
stub() { # $1 = full path; body on stdin
  cat > "$1"
  chmod +x "$1"
}
run_fn() { # $1 = function name
  PATH="$WORK/bin:$WORK/util" \
  WEARSIC_SYS_BIN="$WORK/sysbin" WEARSIC_PROC_NET="$WORK/procnet" \
  ENV_FILE="$WORK/env" \
  "$BASH_BIN" -c 'source "$1"; shift; "$@"' bash "$WORK/fn.sh" "$1" 2>/dev/null
}
run_fn_lan_ip_env() { # $1 = WEARSIC_LAN_IP value
  PATH="$WORK/bin:$WORK/util" \
  WEARSIC_SYS_BIN="$WORK/sysbin" WEARSIC_PROC_NET="$WORK/procnet" \
  ENV_FILE="$WORK/env" WEARSIC_LAN_IP="$1" \
  "$BASH_BIN" -c 'source "$1"; shift; "$@"' bash "$WORK/fn.sh" lan_ip 2>/dev/null
}
expect() { # $1 = function, $2 = expected, $3 = label
  check "$3" "$2" "$(run_fn "$1")"
}

echo "lan_ip probe chain:"

# 1. termux-api wins — and beats `ip`, proving the order (not just presence).
reset
stub "$WORK/bin/termux-wifi-connectioninfo" <<'EOF'
#!/bin/sh
printf '{"ip": "10.1.2.3", "ssid": "TestNet"}\n'
EOF
stub "$WORK/bin/ip" <<'EOF'
#!/bin/sh
printf '2: wlan0    inet 192.168.99.99/24 scope global wlan0\n'
EOF
expect lan_ip "10.1.2.3" "termux-api result beats ip (probe order)"

# 2. iproute2 on PATH (wlan0).
reset
stub "$WORK/bin/ip" <<'EOF'
#!/bin/sh
printf '2: wlan0    inet 192.168.7.7/24 brd 192.168.7.255 scope global wlan0\n'
EOF
expect lan_ip "192.168.7.7" "ip -o addr on wlan0"

# 3. wlan0 absent -> the default-route interface taken from /proc/net/route.
reset
printf 'lo\t00000000\t00000000\t0003\t0\t0\t0\t00000000\t0\t0\t0\n' > "$WORK/procnet/route"
printf 'eth0\t00000000\t0101A8C0\t0003\t0\t0\t0\t00000000\t0\t0\t0\n' >> "$WORK/procnet/route"
stub "$WORK/bin/ip" <<'EOF'
#!/bin/sh
case "$*" in
  *wlan0*) exit 0 ;;
  *) printf '3: eth0    inet 10.9.9.9/24 scope global eth0\n' ;;
esac
EOF
expect lan_ip "10.9.9.9" "ip falls back to the default-route interface"

# 4. net-tools ifconfig, "inet addr:" flavour.
reset
stub "$WORK/bin/ifconfig" <<'EOF'
#!/bin/sh
printf 'wlan0: flags=4163<UP,BROADCAST,RUNNING,MULTICAST>  mtu 1500\n'
printf '        inet addr:192.168.3.3  Bcast:192.168.3.255  Mask:255.255.255.0\n'
EOF
expect lan_ip "192.168.3.3" "ifconfig (net-tools inet addr:)"

# 5. busybox/iproute style "inet 192.168.4.4/24".
reset
stub "$WORK/bin/ifconfig" <<'EOF'
#!/bin/sh
printf 'wlan0     Link encap:UNSPEC\n'
printf '          inet 192.168.4.4/24 scope global wlan0\n'
EOF
expect lan_ip "192.168.4.4" "ifconfig (busybox inet x/24)"

# 6. /system/bin/ip by absolute path (Termux's PATH never contains it).
reset
stub "$WORK/sysbin/ip" <<'EOF'
#!/bin/sh
printf '2: wlan0    inet 10.222.1.1/24 scope global wlan0\n'
EOF
expect lan_ip "10.222.1.1" "system/bin ip (absolute path)"

# 7. /system/bin/ifconfig by absolute path.
reset
stub "$WORK/sysbin/ifconfig" <<'EOF'
#!/bin/sh
printf '        inet addr:10.33.3.3  Bcast:10.33.3.255  Mask:255.255.255.0\n'
EOF
expect lan_ip "10.33.3.3" "system/bin ifconfig (absolute path)"

# 8. getprop on PATH (pre-Android-10 devices expose dhcp.*.ipaddress).
reset
stub "$WORK/bin/getprop" <<'EOF'
#!/bin/sh
printf '10.44.4.4\n'
EOF
expect lan_ip "10.44.4.4" "getprop dhcp.wlan0.ipaddress"

# 9. /system/bin/getprop full dump when the single property is gone.
reset
stub "$WORK/sysbin/getprop" <<'EOF'
#!/bin/sh
case "$*" in
  dhcp.wlan0.ipaddress) exit 0 ;;
  *) printf '[ro.product.model]: [TestPhone]\n[dhcp.wlan0.ipaddress]: [10.55.5.5]\n' ;;
esac
EOF
expect lan_ip "10.55.5.5" "system/bin getprop dump fallback"

# 10. /proc/net/fib_trie last resort: loopback must NOT leak through.
reset
printf 'wlan0\t00000000\t0101A8C0\t0003\t0\t0\t0\t00000000\t0\t0\t0\n' > "$WORK/procnet/route"
printf 'Main:\n  +-- 0.0.0.0/0 3 0 5\n     |-- 127.0.0.1\n        /32 host LOCAL\n     |-- 192.168.1.50\n        /32 host LOCAL\n' > "$WORK/procnet/fib_trie"
expect lan_ip "192.168.1.50" "fib_trie skips loopback"

# 11. A loopback-only fib_trie yields NOTHING.
reset
printf 'wlan0\t00000000\t0101A8C0\t0003\t0\t0\t0\t00000000\t0\t0\t0\n' > "$WORK/procnet/route"
printf 'Main:\n     |-- 127.0.0.1\n        /32 host LOCAL\n' > "$WORK/procnet/fib_trie"
expect lan_ip "" "fib_trie with only loopback yields nothing"

# 12. Garbage from a probe must not become an address.
reset
stub "$WORK/bin/getprop" <<'EOF'
#!/bin/sh
printf 'not-an-ip\n'
EOF
expect lan_ip "" "malformed probe output is rejected"

# 13. No probes at all: silent (the crash-report / CI case).
reset
expect lan_ip "" "no probes available -> empty (no placeholder)"

# 14/15. default_iface itself: skip loopback, pick the first default route.
reset
printf 'lo\t00000000\t00000000\t0003\t0\t0\t0\t00000000\t0\t0\t0\n' > "$WORK/procnet/route"
printf 'wlan0\t00000000\t0101A8C0\t0003\t0\t0\t0\t00000000\t0\t0\t0\n' >> "$WORK/procnet/route"
check "default_iface skips loopback" "wlan0" "$(run_fn default_iface)"
reset
printf 'lo\t00000000\t00000000\t0003\t0\t0\t0\t00000000\t0\t0\t0\n' > "$WORK/procnet/route"
check "default_iface on loopback-only -> empty" "" "$(run_fn default_iface)"

# 16. server_url: a detected IP becomes a real URL; NO IP must print an
#     EMPTY string — never the old literal placeholder users typed verbatim.
reset
stub "$WORK/bin/termux-wifi-connectioninfo" <<'EOF'
#!/bin/sh
printf '{"ip": "10.1.2.3"}\n'
EOF
check "server_url builds from the detected IP" "http://10.1.2.3:8080" "$(run_fn server_url)"
reset
check "server_url empty when no IP (no <phone-IP> placeholder)" "" "$(run_fn server_url)"

# 18. MODERN ANDROID (the reported bug): /proc/net/route is unreadable while
#     /proc/net/fib_trie still works. fib_trie used to be gated on the
#     default interface, so this exact combination found nothing and
#     `wearsic ip` blamed Wi-Fi.
reset
printf 'Main:\n  +-- 0.0.0.0/0 3 0 5\n     |-- 192.168.7.42\n        /32 host LOCAL\n' > "$WORK/procnet/fib_trie"
expect lan_ip "192.168.7.42" "fib_trie works with NO /proc/net/route (modern Android)"

# 19. WEARSIC_LAN_IP wins over every probe.
reset
stub "$WORK/bin/ip" <<'EOF'
#!/bin/sh
printf '2: wlan0    inet 10.0.0.9/24 scope global wlan0\n'
EOF
check "WEARSIC_LAN_IP overrides probes" "192.168.50.5" "$(run_fn_lan_ip_env 192.168.50.5)"

# 20. An invalid override must NOT block the real probes.
reset
stub "$WORK/bin/ip" <<'EOF'
#!/bin/sh
printf '2: wlan0    inet 10.0.0.9/24 scope global wlan0\n'
EOF
check "invalid WEARSIC_LAN_IP falls through to probes" "10.0.0.9" "$(run_fn_lan_ip_env not-an-ip)"

# 21. The .env value (what `wearsic ip <addr>` saves) is honoured.
reset
printf 'WEARSIC_LAN_IP=10.7.7.7\n' > "$WORK/env"
check ".env WEARSIC_LAN_IP is honoured" "10.7.7.7" "$(run_fn lan_ip)"

# 22. wlan0 absent AND no default route: the all-interfaces probe must still
#     find the real Wi-Fi address (Android also names it wlan1/swlan0).
reset
stub "$WORK/bin/ip" <<'EOF'
#!/bin/sh
case "$*" in
  *wlan0*) exit 0 ;;
  *"addr show"*) printf '1: lo    inet 127.0.0.1/8 scope host lo\n3: wlan1    inet 192.168.11.11/24 scope global wlan1\n' ;;
  *) exit 0 ;;
esac
EOF
expect lan_ip "192.168.11.11" "ip all-interfaces fallback (wlan1, no default route)"

# 17. Syntax check of the shipped CLI.
if bash -n "$CLI" 2>/dev/null; then pass "wearsic CLI parses"; else fail "wearsic CLI has a syntax error"; fi

echo
if [ "$fails" -eq 0 ]; then echo "lan-ip-test: PASSED"; exit 0; fi
echo "lan-ip-test: $fails FAILED"; exit 1
