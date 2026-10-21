#!/usr/bin/env bash
# bootstrap-aapt2-arm64.sh — the committed half of the on-device ARM64 fix.
#
# WHY THIS EXISTS (see gradle.properties): on an ARM64 host (Termux native or
# a Termux proot-distro), the build-tools `aapt2` binary AGP auto-uses from
# Maven is an x86_64 ELF — every resource task dies with
#   "Specified AAPT2 executable does not exist"
# The fix is an `android.aapt2FromMavenOverride` property pointing AGP at an
# ARM64-capable aapt2. Per the gradle.properties note this override MUST live
# OUTSIDE the repository (a stale committed path would break every other
# machine), so this script writes it into the USER Gradle properties
# (~/.gradle/gradle.properties) — never into the repo.
#
# Usage:
#   bash scripts/bootstrap-aapt2-arm64.sh            # detect + write override
#   bash scripts/bootstrap-aapt2-arm64.sh /path/to/aapt2   # explicit binary
#   bash scripts/bootstrap-aapt2-arm64.sh --verify   # only validate current override
set -euo pipefail

GRADLE_USER_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}"
USER_GRADLE_PROPS="$GRADLE_USER_HOME_DIR/gradle.properties"
PROP_KEY="android.aapt2FromMavenOverride"

say() { printf '%s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Candidate search. Order matters: prefer the first working binary.
# ---------------------------------------------------------------------------
find_aapt2_candidates() {
    # 1) already installed in Termux native (or on PATH inside proot)
    if command -v aapt2 >/dev/null 2>&1; then command -v aapt2; fi
    # 2) Termux native prefix ($PREFIX set in native Termux; proot default)
    if [ -n "${PREFIX:-}" ] && [ -x "$PREFIX/bin/aapt2" ]; then echo "$PREFIX/bin/aapt2"; fi
    if [ -x "/data/data/com.termux/files/usr/bin/aapt2" ]; then
        echo "/data/data/com.termux/files/usr/bin/aapt2"
    fi
    # 3) standard Android SDK locations (host-side installs, e.g. via Debian
    #    sdkmanager or a Termux shared SDK)
    for sdk_root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" /opt/android-sdk /usr/lib/android-sdk; do
        if [ -n "$sdk_root" ] && [ -d "$sdk_root/build-tools" ]; then
            # newest first
            for bt in $(ls -1 "$sdk_root/build-tools" 2>/dev/null | sort -Vr); do
                [ -x "$sdk_root/build-tools/$bt/aapt2" ] && echo "$sdk_root/build-tools/$bt/aapt2"
            done
        fi
    done
}

# A candidate is usable only if it runs on THIS machine's architecture.
# On ARM64 hosts an x86_64 aapt2 "exists" but fails at first use — reject it
# here, before the property is written.
aapt2_runs_here() {
    local bin="$1"
    [ -n "$bin" ] || return 1
    [ -x "$bin" ] || return 1
    if command -v file >/dev/null 2>&1; then
        local thead arch
        thead="$(file -L "$bin" 2>/dev/null || true)"
        case "$thead" in
            *aarch64*) return 0 ;;
            *x86_64*|*"x86-64"*) return 1 ;;
            *) ;;
        esac
    fi
    # No `file` / unknown arch: last resort — actually try to run it.
    "$bin" version >/dev/null 2>&1 || "$bin" --version >/dev/null 2>&1
}

# ---------------------------------------------------------------------------
# --verify mode: validate the property currently in the user gradle.properties
# ---------------------------------------------------------------------------
if [ "${1:-}" = "--verify" ]; then
    [ -f "$USER_GRADLE_PROPS" ] || die "no $USER_GRADLE_PROPS — run without --verify first"
    current="$(grep -E "^${PROP_KEY}=" "$USER_GRADLE_PROPS" 2>/dev/null | tail -1 | cut -d= -f2- || true)"
    [ -n "$current" ] || die "$PROP_KEY not set in $USER_GRADLE_PROPS"
    if aapt2_runs_here "$current"; then
        say "OK: override usable -> $current"
        exit 0
    fi
    die "override exists but is not usable on this machine: $current (re-run without --verify)"
fi

# ---------------------------------------------------------------------------
# Main: explicit path > detection
# ---------------------------------------------------------------------------
chosen=""
if [ $# -ge 1 ]; then
    chosen="$1"
    [ -x "$chosen" ] || die "not an executable: $chosen"
else
    say "Searching for an ARM64-capable aapt2…"
    for candidate in $(find_aapt2_candidates); do
        say "  candidate: $candidate"
        if aapt2_runs_here "$candidate"; then
            chosen="$candidate"
            break
        fi
        say "  -> not executable on this machine (wrong arch?), skipping"
        # shellcheck disable=SC2312
    done
fi

if [ -z "$chosen" ]; then
    say "No working aapt2 found. Install one first, then re-run this script:"
    say "  Termux native:            pkg install aapt"
    say "  Termux proot-distro:      install 'aapt' in the TERMUX session so"
    say "                            /data/data/com.termux/files/usr/bin/aapt2 exists"
    say "  Debian / generic Linux:   apt-get install aapt (or use a real SDK's"
    say "                            linux build-tools with ANDROID_HOME set)"
    exit 2
fi

chosen="$(cd "$(dirname "$chosen")" && pwd)/$(basename "$chosen")"
say "Using: $chosen"

mkdir -p "$GRADLE_USER_HOME_DIR"
if [ -f "$USER_GRADLE_PROPS" ] && grep -qE "^${PROP_KEY}=" "$USER_GRADLE_PROPS"; then
    # replace the last defined occurrence, keep everything else
    tmp="$(mktemp)"
    awk -v key="$PROP_KEY" -v val="$chosen" \
        'BEGIN{replaced=0}
         !replaced && $0 ~ "^"key"=" { print key"="val; replaced=1; next }
         $0 ~ "^"key"=" { next }
         { print }' "$USER_GRADLE_PROPS" > "$tmp"
    mv "$tmp" "$USER_GRADLE_PROPS"
else
    {
        echo ""
        echo "# Written by scripts/bootstrap-aapt2-arm64.sh — on-device ARM64 build"
        echo "# support (the override lives OUTSIDE the repo on purpose; see"
        echo "# gradle.properties)."
        echo "$PROP_KEY=$chosen"
    } >> "$USER_GRADLE_PROPS"
fi

say "Wrote $PROP_KEY=$chosen to $USER_GRADLE_PROPS (outside the repo, as intended)."
say "Now try an on-device build:  ./gradlew :app:assembleMinified"
