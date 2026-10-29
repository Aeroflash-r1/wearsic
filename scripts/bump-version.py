#!/usr/bin/env python3
"""Rewrites the version pins for a WearSic release — the mechanical half of
the Extractor Hotfix workflow, runnable by hand too:

    scripts/bump-version.py [--repo-root DIR] [--version X.Y.Z] [--newpipe-ref REF] [--dry-run]

Edits (all-or-nothing — ANY pattern mismatch aborts before a single write):

  · wearsic-server/.../ServerVersion.kt   VERSION "x.y.z" -> new (patch bump by default)
  · app/build.gradle.kts                  versionCode N -> N+1, versionName -> new
  · README.md, API_CONTRACT.md, TERMUX_SERVER_GUIDE.md
        the LIVING version samples only — the /health example, the zip-URL,
        sideload and health-panel version-row examples, the release-tag
        command.
        Historical notes ("since 1.9.1", "added in v1.9.1") are deliberately
        left alone; every pattern must match its expected occurrence count.
  · wearsic-server/build.gradle.kts       NewPipeExtractor pin (--newpipe-ref)
  · wearsic-server/SETUP.md               its NewPipeExtractor pin token (--newpipe-ref)

Exits non-zero on: an unparseable current version, a --version that is not
x.y.z, or any pattern not matching its expected count — in which case
NOTHING is written (a half-bumped tree would cut a malformed release).
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO_LAYOUT = {
    "server_version": "wearsic-server/src/main/kotlin/com/wearsic/server/ServerVersion.kt",
    "server_gradle": "wearsic-server/build.gradle.kts",
    "setup": "wearsic-server/SETUP.md",
    "app_gradle": "app/build.gradle.kts",
    "readme": "README.md",
    "contract": "API_CONTRACT.md",
    "guide": "TERMUX_SERVER_GUIDE.md",
}


def die(msg: str) -> "None":
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


class Planner:
    """Collects every edit in memory; nothing is written until ALL matched."""

    def __init__(self, root: Path) -> None:
        self.root = root
        self.texts: dict[str, str] = {}
        self.changes: list[str] = []

    def load(self, key: str) -> None:
        path = self.root / REPO_LAYOUT[key]
        if not path.is_file():
            die(f"missing file: {REPO_LAYOUT[key]} (wrong --repo-root?)")
        self.texts[key] = path.read_text(encoding="utf-8")

    def edit(self, key: str, pattern: str, replacement: str, expected: int = 1) -> None:
        new_text, count = re.subn(pattern, replacement, self.texts[key])
        if count != expected:
            die(
                f"{REPO_LAYOUT[key]}: pattern {pattern!r} matched {count}x, "
                f"expected {expected}x — the file changed shape; update "
                f"scripts/bump-version.py (nothing was written)"
            )
        self.texts[key] = new_text
        self.changes.append(f"{REPO_LAYOUT[key]}: {count} edit(s)")


def main() -> None:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("--repo-root", default=".", help="repository root (default: cwd)")
    ap.add_argument(
        "--version", default=None,
        help="explicit new x.y.z (default: patch bump of the current ServerVersion)",
    )
    ap.add_argument(
        "--newpipe-ref", default=None,
        help="also rewrite the NewPipeExtractor pin to this ref (tag / commit / branch-SNAPSHOT)",
    )
    ap.add_argument("--dry-run", action="store_true", help="show what would change, write nothing")
    args = ap.parse_args()

    p = Planner(Path(args.repo_root).resolve())
    for key in REPO_LAYOUT:
        p.load(key)

    m = re.search(r'VERSION: String = "(\d+\.\d+\.\d+)"', p.texts["server_version"])
    if not m:
        die("ServerVersion.kt: VERSION marker line not found")
    old = m.group(1)

    if args.version:
        if not re.fullmatch(r"\d+\.\d+\.\d+", args.version):
            die("--version must be x.y.z")
        new = args.version
    else:
        major, minor, patch = (int(x) for x in old.split("."))
        new = f"{major}.{minor}.{patch + 1}"
    if new == old:
        die(f"new version {new} equals current {old}")

    # ---- code pins ----
    p.edit("server_version", rf'VERSION: String = "{re.escape(old)}"', f'VERSION: String = "{new}"')

    vc = re.search(r"versionCode = (\d+)", p.texts["app_gradle"])
    if not vc:
        die("app/build.gradle.kts: versionCode line not found")
    p.edit("app_gradle", r"versionCode = \d+", f"versionCode = {int(vc.group(1)) + 1}")
    p.edit("app_gradle", rf'versionName = "{re.escape(old)}"', f'versionName = "{new}"')

    if args.newpipe_ref:
        pin = re.search(r'NewPipeExtractor:([^")]+)', p.texts["server_gradle"])
        if not pin:
            die("wearsic-server/build.gradle.kts: NewPipeExtractor pin not found")
        p.edit(
            "server_gradle",
            rf"(NewPipeExtractor:){re.escape(pin.group(1))}(\")",
            rf"\g<1>{args.newpipe_ref}\g<2>",
        )
        p.edit(
            "setup",
            rf"NewPipeExtractor \(v{re.escape(pin.group(1).lstrip('v'))}\)",
            f"NewPipeExtractor ({args.newpipe_ref})",
        )

    # ---- living doc samples (NOT historical notes) ----
    p.edit("readme", rf'"version": "{re.escape(old)}",', f'"version": "{new}",')
    p.edit(
        "readme",
        rf"WEARSIC_ZIP_URL=https://github\.com/Aeroflash-r1/wearsic/releases/download/v{re.escape(old)}/"
        rf"wearsic-server-termux-v{re.escape(old)}\.zip",
        f"WEARSIC_ZIP_URL=https://github.com/Aeroflash-r1/wearsic/releases/download/v{new}/"
        f"wearsic-server-termux-v{new}.zip",
    )
    # Quick-start download links carry the version in the filename — these
    # drifted twice because the patterns were missing. Exactly one each.
    p.edit(
        "readme",
        rf"wearsic-server-v{re.escape(old)}\.sh",
        f"wearsic-server-v{new}.sh",
    )
    p.edit(
        "readme",
        rf"Wearsic-v{re.escape(old)}\.apk",
        f"Wearsic-v{new}.apk",
    )
    p.edit(
        "readme",
        rf"git tag v{re.escape(old)} && git push origin v{re.escape(old)}",
        f"git tag v{new} && git push origin v{new}",
    )

    p.edit("contract", rf'"version": "{re.escape(old)}",', f'"version": "{new}",')
    p.edit("contract", rf'"engineVersion": "{re.escape(old)}"', f'"engineVersion": "{new}"')

    p.edit("guide", rf"wearsic sideload v{re.escape(old)}\b", f"wearsic sideload v{new}")
    # The guide's `wearsic status` panel prints `version     vX.Y.Z` and
    # nothing else (the CLI never emits a "Wearsic Engine" banner — see
    # box_kv2 "version" in wearsic-server/wearsic), so pin that row.
    p.edit("guide", rf"(version\s+)v{re.escape(old)}", rf"\g<1>v{new}")

    print(f"version: {old} -> {new}")
    for line in p.changes:
        print(f"  · {line}")

    if args.dry_run:
        print("DRY RUN — nothing written")
        return

    for key, rel in REPO_LAYOUT.items():
        (p.root / rel).write_text(p.texts[key], encoding="utf-8")


if __name__ == "__main__":
    main()
