#!/usr/bin/env python3
"""Scream failing unit-test details into check-run ANNOTATIONS + summary.

Reads every JUnit XML under the given directory and, for suites whose
failures/errors count is non-zero, emits `::error title=TEST::<name>::`
annotations with the cause and a trimmed stack trace. Annotations are
readable ANONYMOUSLY through the public check-run API, so a failing
runner's diagnosis needs no API credentials and no interactive log.

When NO failing testcase XML exists at all (a build/system crash), the
tail of the captured Gradle log is printed instead.

Usage: summarize-failures.py [test-results-dir] [gradle-log-file]
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

MAX_ANNOTATIONS = 4
TRACE_LIMIT = 2600
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
    gradle_log = Path(sys.argv[2] if len(sys.argv) > 2 else "gradle.out")

    annotations = 0
    found = False
    if directory.is_dir():
        for path in sorted(directory.glob("TEST-*.xml")):
            if annotations >= MAX_ANNOTATIONS:
                break
            xml = unescape(path.read_text(encoding="utf-8", errors="replace"))
            header = re.search(r"<testsuite\b[^>]*>", xml)
            if not header:
                continue
            counts = {k: first_attr(header.group(0), k) for k in ("failures", "errors")}
            if all((counts.get(k) or "0") == "0" for k in ("failures", "errors")):
                continue
            for start_m in re.finditer(r"<testcase\b([^>]*)>", xml):
                if annotations >= MAX_ANNOTATIONS:
                    break
                tag = start_m.group(1)
                if tag.rstrip().endswith("/"):
                    continue  # self-closing testcase (passed)
                body_start = start_m.end()
                body_end = xml.find("</testcase>", body_start)
                if body_end == -1:
                    break
                body = xml[body_start:body_end]
                bad = (re.search(r"<failure\b[^>]*>(.*?)</failure>", body, re.S)
                       or re.search(r"<error\b[^>]*>(.*?)</error>", body, re.S))
                if not bad:
                    continue
                name = first_attr(tag, "name") or "?"
                msg = re.search(r'message="([^"]*)"', bad.group(0))
                cause = unescape(msg.group(1) if msg else "(no message)")
                cause = cause.replace("\n", " | ")[:600]
                trace = unescape(bad.group(1)).strip()
                short_class = path.name.replace("TEST-", "").removesuffix(".xml")
                full_name = f"{short_class}#{name}"
                print(f"::error title=TEST::{full_name}::"
                      + f"{cause} — trace in the log/summary below")
                print("")
                print(f"### {full_name}")
                print(f"cause: {cause}")
                print("")
                print("```")
                print("\n".join(
                    ln for ln in trace.splitlines() if ln.strip()
                )[:TRACE_LIMIT])
                print("```")
                print("")
                annotations += 1
                found = True

    if not found:
        print("::error title=Build crash::No failing testcase XMLs — Gradle output tail")
        if gradle_log.is_file():
            print(gradle_log.read_text(encoding="utf-8", errors="replace")[-6000:])
        else:
            print("(no gradle log captured)")


if __name__ == "__main__":
    main()
