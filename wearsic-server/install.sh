#!/usr/bin/env bash
# Compatibility shim for the old installer URL. The canonical installer lives
# at the repository root; run a local checkout directly or fetch and execute it.
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
# Do not exec: the EXIT trap must get a chance to remove the temp copy.
bash "$TMP" "$@"
