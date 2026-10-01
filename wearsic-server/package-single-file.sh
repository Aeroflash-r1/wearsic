#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Builds THE single-file Wearsic server bundle: one downloadable .sh that
# contains the entire server (engine + launcher + CLI) inside itself.
#
#   ./wearsic-server.sh              install (default)
#   ./wearsic-server.sh start        start the server (auto-heal supervisor)
#   ./wearsic-server.sh stop|restart|status|logs|health|url|ip
#   ./wearsic-server.sh uninstall    remove the installation
#
# Usage (build machine / CI):
#   ./gradlew :wearsic-server:installDist
#   wearsic-server/package-single-file.sh [installDistDir] [outputFile]
#
# Output default: dist/wearsic-server-<version>.sh
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="${1:-$HERE/build/install/wearsic-server}"
VERSION="$(grep -oP 'VERSION: String = "\K[^"]+' "$HERE/src/main/kotlin/com/wearsic/server/ServerVersion.kt")"
OUT="${2:-$HERE/../dist/wearsic-server-$VERSION.sh}"

[ -x "$SRC/bin/wearsic-server" ] || {
  echo "No installDist at $SRC — run: ./gradlew :wearsic-server:installDist" >&2
  exit 1
}

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

# Everything the server needs to run, in the exact layout run-termux.sh and
# the `wearsic` CLI expect: bin/ + lib/ + launcher + CLI + env template.
cp -r "$SRC/bin" "$SRC/lib" "$STAGE/"
cp "$HERE/run-termux.sh" "$HERE/wearsic" "$HERE/.env.example" "$STAGE/"

mkdir -p "$(dirname "$OUT")"
{
  sed "s/@VERSION@/$VERSION/g" <<'STUB'
#!/data/data/com.termux/files/usr/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# wearsic-server @VERSION@ — the Wearsic music server, ALL-IN-ONE.
#
# This one file contains the whole server. Download it, run it once:
#
#   chmod +x wearsic-server.sh
#   ./wearsic-server.sh install
#
# then manage everything with the single `wearsic` command it installs
# (or keep using ./wearsic-server.sh — it forwards to the same place):
#
#   ./wearsic-server.sh start|stop|restart|status|logs|health|url|ip
#   ./wearsic-server.sh uninstall
#
# Requires Java (Termux: pkg install -y openjdk-17). The install generates a
# secure API key for you and preserves your database / key on reinstall.
# ─────────────────────────────────────────────────────────────────────────────
set -u

DEST="${WEARSIC_DIR:-$HOME/wearsic-server}"
CLI="$DEST/wearsic"

say()  { printf '\033[1;35m[wearsic]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[wearsic]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[wearsic] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# Random 8-char API key from an unambiguous alphabet (no 0/O, 1/l/I) so it
# stays typeable on a watch keyboard (same scheme as install.sh).
generate_api_key() {
  local alphabet="23456789abcdefghjkmnpqrstuvwxyz" key="" r n
  n="${#alphabet}"
  while [ "${#key}" -lt 8 ]; do
    r="$(od -An -N1 -tu1 /dev/urandom 2>/dev/null | tr -d ' \n')"
    [ -n "$r" ] || r="$RANDOM"
    key="$key${alphabet:$((r % n)):1}"
  done
  printf '%s' "$key"
}

install_bundle() {
  command -v java >/dev/null 2>&1 \
    || die "Java is missing. In Termux run: pkg install -y openjdk-17"
  say "Installing Wearsic server to $DEST …"
  mkdir -p "$DEST"

  local line
  line="$(awk '/^__ARCHIVE_BELOW__$/ {print NR + 1; exit}' "$0")"
  [ -n "$line" ] || die "corrupt bundle (payload marker missing)"
  tail -n +"$line" "$0" | base64 -d | tar xz -C "$DEST" || die "payload extraction failed"

  chmod +x "$DEST/run-termux.sh" "$DEST/wearsic" "$DEST"/bin/* 2>/dev/null || true

  # Fresh installs are never left open: generate a shared secret once.
  if ! grep -q '^WEARSIC_API_KEY=..*' "$DEST/.env" 2>/dev/null; then
    local key
    key="$(generate_api_key)"
    printf '\n# Shared secret — enter the same value in the watch app (Settings → API Key)\nWEARSIC_API_KEY=%s\n' "$key" >> "$DEST/.env"
    say "Generated a secure API key (saved in $DEST/.env)"
  fi

  # Put the `wearsic` command on PATH (Termux bin, else ~/.local/bin).
  local bindir=""
  [ -n "${PREFIX:-}" ] && [ -d "$PREFIX/bin" ] && bindir="$PREFIX/bin"
  [ -z "$bindir" ] && [ -d "$HOME/.local/bin" ] && bindir="$HOME/.local/bin"
  if [ -n "$bindir" ]; then
    cp "$DEST/wearsic" "$bindir/wearsic"
    chmod +x "$bindir/wearsic"
    say "Installed the 'wearsic' command to $bindir/wearsic"
  else
    warn "No bin directory on PATH — use: $CLI"
  fi

  say "Done. Start it with:  wearsic server start"
  say "Everything lives inside this one file — no downloads, no extra steps."
}

uninstall_bundle() {
  say "Removing $DEST (your database and .env are deleted too)…"
  [ -x "$CLI" ] && "$CLI" server stop >/dev/null 2>&1
  rm -rf "$DEST"
  for bindir in "${PREFIX:-/nonexistent}/bin" "$HOME/.local/bin"; do
    [ -f "$bindir/wearsic" ] && rm -f "$bindir/wearsic"
  done
  say "Uninstalled."
}

cmd="${1:-install}"
case "$cmd" in
  install)
    install_bundle
    ;;
  uninstall)
    uninstall_bundle
    ;;
  start|stop|restart|status|logs|health|url|ip|api|cookies|funnel|public)
    [ -x "$CLI" ] || install_bundle
    # bash explicitly: the CLI's shebang points at the Termux bash path,
    # which only exists on Termux. Dispatch works everywhere.
    exec bash "$CLI" server "$cmd" "${@:2}"
    ;;
  version|--version|-V)
    echo "wearsic-server @VERSION@"
    ;;
  help|--help|-h)
    sed -n '2,18p' "$0"
    ;;
  *)
    echo "Unknown command: $cmd" >&2
    echo "Try: ./wearsic-server.sh help" >&2
    exit 1
    ;;
esac
exit 0
__ARCHIVE_BELOW__
STUB
  tar -czf - -C "$STAGE" . | base64
} > "$OUT"

chmod +x "$OUT"
echo "Wrote $OUT ($(du -h "$OUT" | cut -f1), server version $VERSION)"
