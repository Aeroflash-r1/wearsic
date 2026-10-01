#!/data/data/com.termux/files/usr/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# Wearsic server installer (compatibility shim).
#
# The canonical one-command installer now lives at the repository root:
#
#   curl -fsSL https://raw.githubusercontent.com/Aeroflash-r1/wearsic/main/install.sh | bash
#
# This shim keeps the OLD documented URL working by forwarding to it. If the
# repository root is available next to this file (git checkout: ../install.sh),
# that copy runs directly; otherwise the root installer is fetched from
# GitHub and executed. All behaviour lives in the root install.sh.
# ─────────────────────────────────────────────────────────────────────────────
set -u

HERE="$(CDPATH= cd -- "$(dirname -- "$0")" 2>/dev/null && pwd)"
ROOT_INSTALLER="${WEARSIC_ROOT_INSTALLER:-}"

if [ -z "$ROOT_INSTALLER" ] && [ -n "$HERE" ] && [ -f "$HERE/../install.sh" ]; then
  ROOT_INSTALLER="$HERE/../install.sh"
fi

if [ -n "$ROOT_INSTALLER" ] && [ -f "$ROOT_INSTALLER" ]; then
  exec bash "$ROOT_INSTALLER" "$@"
fi

URL="https://raw.githubusercontent.com/${WEARSIC_REPO:-Aeroflash-r1/wearsic}/main/install.sh"
TMP="$(mktemp)" || { echo "[wearsic] ERROR: could not create a temp file" >&2; exit 1; }
trap 'rm -f "$TMP"' EXIT
curl -fsSL --retry 3 "$URL" -o "$TMP" || {
  echo "[wearsic] ERROR: could not download the installer from $URL" >&2
  exit 1
}
exec bash "$TMP" "$@"
