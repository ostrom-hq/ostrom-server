#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Check the license of every source file: an SPDX header, the right one for its path, and the license files.

Configuration: .github/spdx.json
  default   the SPDX identifier expected outside any rule
  rules     [{"prefix": "ee/", "license": "LicenseRef-Ostrom-Enterprise"}], the first matching prefix wins
  allowed   identifiers also accepted outside a rule (a header wins over its folder, see LICENSING.md)
  exclude   glob patterns of tracked files that are never checked (generated or vendored code)
  scope     "all" checks every tracked source file; "added" fails only on the files a pull request adds
            (on main, the files still without a header are reported as warnings: the backfill is pending)

Usage:
  check_spdx.py                       check (scope from the configuration)
  check_spdx.py --base <ref>          scope "added": files added since the merge base with <ref>
  check_spdx.py --all                 check every tracked source file, whatever the scope
  check_spdx.py --fix [paths...]      add the expected header where it is missing (default: the files this branch
                                      adds since origin/main, untracked files included)
"""
from __future__ import annotations

import argparse
import fnmatch
import json
import subprocess
import sys
from pathlib import Path

COMMENT = {
    ".kt": "//", ".kts": "//", ".java": "//", ".ts": "//", ".tsx": "//", ".js": "//", ".jsx": "//",
    ".mjs": "//", ".cjs": "//", ".go": "//", ".rs": "//", ".swift": "//", ".c": "//", ".h": "//",
    ".py": "#", ".sh": "#", ".bash": "#",
}
DEFAULT_EXCLUDE = [
    "gradlew", "gradlew.bat", "gradle/wrapper/*", "**/generated/*", "*/generated/*", "node_modules/*",
    "*-env.d.ts", "dist/*", "build/*", "**/build/*", "coverage/*",
]
LICENSE_MARKERS = {
    "Apache-2.0": "Apache License",
    "MIT": "MIT License",
}
HEAD_LINES = 5


def git(*args: str) -> str:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def excluded(path: str, patterns: list[str]) -> bool:
    return any(fnmatch.fnmatch(path, p) for p in patterns)


def expected(path: str, cfg: dict) -> str:
    for rule in cfg.get("rules", []):
        if path.startswith(rule["prefix"]):
            return rule["license"]
    return cfg["default"]


def accepted(path: str, cfg: dict) -> set[str]:
    want = expected(path, cfg)
    if any(path.startswith(r["prefix"]) for r in cfg.get("rules", [])):
        return {want}
    return {want, *cfg.get("allowed", [])}


def header_of(path: Path) -> str | None:
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()[:HEAD_LINES]
    except OSError:
        return None
    for line in lines:
        if "SPDX-License-Identifier:" in line:
            return line.split("SPDX-License-Identifier:", 1)[1].strip().rstrip("*/").strip()
    return None


def add_header(path: Path, license_id: str) -> None:
    mark = COMMENT[path.suffix]
    text = path.read_text(encoding="utf-8")
    line = f"{mark} SPDX-License-Identifier: {license_id}\n"
    if text.startswith("#!"):
        first, _, rest = text.partition("\n")
        path.write_text(f"{first}\n{line}{rest}", encoding="utf-8")
    else:
        path.write_text(line + text, encoding="utf-8")


def source_files(paths: list[str], cfg: dict) -> list[str]:
    patterns = DEFAULT_EXCLUDE + cfg.get("exclude", [])
    return sorted(p for p in paths if Path(p).suffix in COMMENT and not excluded(p, patterns) and Path(p).is_file())


def check_license_files(cfg: dict) -> list[str]:
    errors = []
    root = Path("LICENSE")
    marker = LICENSE_MARKERS.get(cfg["default"])
    if not root.is_file():
        errors.append("LICENSE: missing")
    elif marker and marker not in root.read_text(encoding="utf-8")[:400]:
        errors.append(f"LICENSE: does not hold the {cfg['default']} text")
    for rule in cfg.get("rules", []):
        lic = Path(rule["prefix"]) / "LICENSE"
        if Path(rule["prefix"]).is_dir() and not lic.is_file():
            errors.append(f"{lic}: missing (the license of {rule['prefix']})")
    return errors


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--fix", action="store_true")
    ap.add_argument("paths", nargs="*")
    args = ap.parse_args()
    cfg = json.loads(Path(".github/spdx.json").read_text())

    warn_only: list[str] = []
    if args.paths:
        files = source_files(args.paths, cfg)
    elif args.all or cfg.get("scope", "all") == "all":
        files = source_files(git("ls-files").splitlines(), cfg)
    elif args.base or args.fix:
        base = args.base or "origin/main"  # --fix alone: the files this branch adds
        added = git("diff", "--name-only", "--diff-filter=A", f"{base}...HEAD").splitlines()
        added += git("ls-files", "--others", "--exclude-standard").splitlines()
        files = source_files(added, cfg)
    else:
        files = []
        warn_only = source_files(git("ls-files").splitlines(), cfg)

    if args.fix:
        fixed = 0
        for f in files:
            if header_of(Path(f)) is None:
                add_header(Path(f), expected(f, cfg))
                fixed += 1
        print(f"spdx: header added to {fixed} file(s)")
        return 0

    errors = check_license_files(cfg)
    for f in files:
        found = header_of(Path(f))
        ok = accepted(f, cfg)
        if found is None:
            errors.append(f"{f}: no SPDX-License-Identifier in the first {HEAD_LINES} lines "
                          f"(expected {expected(f, cfg)})")
        elif found not in ok:
            errors.append(f"{f}: SPDX-License-Identifier is {found}, expected {' or '.join(sorted(ok))}")

    pending = [f for f in warn_only if header_of(Path(f)) is None]
    for f in pending:
        print(f"::warning file={f}::{f}: no SPDX-License-Identifier yet (backfill pending, scope 'added')")
    for e in errors:
        path = e.split(":", 1)[0]
        print(f"::error file={path}::{e}")
    summary = f"spdx: {len(files)} source file(s) checked, {len(errors)} problem(s)"
    if warn_only:
        summary += f"; {len(pending)} older file(s) without a header"
    print(summary)
    if errors:
        print("Fix: python3 .github/scripts/check_spdx.py --fix <files>  (see CONTRIBUTING.md, License headers)")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
