#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Every commit of a pull request carries a GitHub noreply e-mail address, as author and as committer.

The repository is public and so are the commits of its pull requests (refs/pull/*), which a squash does not remove: a
personal address written in a branch commit stays readable for good. Set it once per clone:

  git config user.email "<id>+<login>@users.noreply.github.com"

Usage:
  check_commit_emails.py --base <ref>     check the commits since the merge base with <ref>
  check_commit_emails.py --self-test      run the rule against known good and bad addresses
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys

NOREPLY = re.compile(r"^(?:\d+\+)?[A-Za-z0-9-]+(?:\[bot\])?@users\.noreply\.github\.com$|^noreply@github\.com$")


def check(base: str) -> int:
    log = subprocess.run(
        ["git", "log", "--no-merges", "--format=%H%x1f%ae%x1f%ce", f"{base}..HEAD"],
        check=True, capture_output=True, text=True,
    ).stdout
    bad = 0
    for line in filter(None, log.splitlines()):
        sha, author, committer = line.split("\x1f")
        for who, email in (("author", author), ("committer", committer)):
            if not NOREPLY.match(email):
                print(f"::error::commit {sha[:12]} has a non-noreply {who} e-mail; run "
                      "git config user.email '<id>+<login>@users.noreply.github.com' and amend the commit")
                bad += 1
    print(f"commit e-mails: {bad} hit(s)")
    return bad


def self_test() -> int:
    failed = [e for e in ["1+jane@users.noreply.github.com", "jane@users.noreply.github.com",
                          "1+renovate[bot]@users.noreply.github.com", "noreply@github.com"] if not NOREPLY.match(e)]
    failed += [e for e in ["jane@gmail.com", "jane@ostrom.run", "x@users.noreply.github.com.evil.com"] if NOREPLY.match(e)]
    for item in failed:
        print(f"self-test failed on: {item!r}")
    return len(failed)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--base")
    parser.add_argument("--self-test", action="store_true")
    options = parser.parse_args()
    if options.self_test:
        sys.exit(1 if self_test() else 0)
    if not options.base:
        parser.error("--base is required")
    sys.exit(1 if check(options.base) else 0)
