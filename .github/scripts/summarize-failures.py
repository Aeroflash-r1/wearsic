#!/usr/bin/env python3
"""Append failing unit-test details to the GitHub job summary.

Reads every JUnit XML under the given directory and, for files whose
failures/errors count is non-zero, prints the failing test names, their
fail/error cause line and a trimmed stack trace. The summary is readable
anonymously, so a failing runner needs no API credentials to diagnose.

Usage: summarize-failures.py [dir] -- writes markdown to stdout.
"""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

UNESCAPE = [
    ("&gt;", ">"),
    ("&lt;", "<"),
    ("&quot;", '"'),
    ("&#39;", "'"),
    ("&amp;", "&"),
]


def unescape(text: str) -> str:
    for ent, ch in UNESCAPE:
        text = text.replace(ent, ch)
    return text


def first_attr(tag: str, attr: str) -> str | None:
    m = re.search(attr + r'="([^"]*)"', tag)
    return m.group(1) if m else None


def main() -> None:
    directory = Path(sys.argv[1] if len(sys.argv) > 1 else
                     "app/build/test-results/testDebugUnitTest")
    if not directory.is_dir():
        print(f"(no test-results dir at {directory})")
        return

    bad = 0
    for path in sorted(directory.glob("TEST-*.xml")):
        xml = unescape(path.read_text(encoding="utf-8", errors="replace"))
        header = re.search(r"<testsuite\b[^>]*>", xml)
        if not header:
            continue
        fails = header.group(0)
        counts = {k: first_attr(fails, k) for k in ("failures", "errors")}
        if not (counts["failures"] or "").isdigit() and not (counts["errors"] or "").isdigit():
            continue
        if counts["failures"] in (None, "0") and counts["errors"] in (None, "0"):
            continue
        bad += 1
        print(f"### {path.name} (failures={counts['failures']}, errors={counts['errors']})")
        for case in re.finditer(r"<testcase\b([^>]*)>(.*?)</testcase>", xml, re.S):
            tag, body = case.group(1), case.group(2)
            if tag.rstrip().endswith("/"):
                continue
            name = first_attr(tag, "name")
            failure = re.search(r"<failure\b([^>]*)>(.*?)</failure>", body, re.S)
            error = re.search(r"<error\b([^>]*)>(.*?)</error>", body, re.S)
            bad_tag = failure or error
            if not bad_tag or name is None:
                continue
            attrs, trace = bad_tag.group(1), bad_tag.group(2).strip()
            message = first_attr(attrs, "message") or "?"
            print()
            print(f"* `{name}`")
            print(f"  - cause: {message[:500]}")
            lines = [ln for ln in trace.splitlines() if ln.strip()][:30]
            print("  - trace:")
            print("    ```")
            for ln in lines:
                print("    " + ln)
            print("    ```")
    if bad == 0:
        print("(no failing testcase XMLs — the failure was a build/system crash)")


if __name__ == "__main__":
    main()
