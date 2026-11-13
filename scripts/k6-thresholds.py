#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Which k6 thresholds a run crossed, and whether that fails the Build.

    python3 scripts/k6-thresholds.py <summary.json>...   # files of `k6 run --summary-export`
    python3 scripts/k6-thresholds.py --self-test

ci-e2e plays ostrom-e2e's short k6 of the gates on every pull request. A threshold on a measured time (a Trend
metric: `http_req_duration{name:gate}` and its p95 or p99) is reported, never blocking: a shared runner under load
crosses it with no pull request at fault (orchestrator, JOURNAL 02:5x), and ostrom-e2e's nightly `chart-load.yml`
holds those thresholds. Every other threshold blocks: the failed requests, the checks, the race's counts (one yes, 99
conflicts, nothing else), which no runner's load changes. Prints a table for the job's summary; exits 1 if a blocking
threshold was crossed or a summary is missing.

In `--summary-export`, a threshold's value is true when it was crossed.
"""
import json
import pathlib
import sys


def is_time(metric: dict) -> bool:
    """A Trend metric (a measured time): it has a median, which counters and rates do not."""
    return "med" in metric


def verdict(paths: list) -> tuple:
    """(report, blocked) for the summaries at paths."""
    lines = ["| Summary | Metric | Threshold | Value | |", "|---|---|---|---|---|"]
    blocked = False
    for path in map(pathlib.Path, paths):
        if not path.is_file():
            lines.append(f"| {path.name} | | | | **missing: k6 did not finish** |")
            blocked = True
            continue
        for name, metric in sorted(json.loads(path.read_text()).get("metrics", {}).items()):
            for threshold, crossed in sorted(metric.get("thresholds", {}).items()):
                aggregate = threshold.split("<")[0].split(">")[0].split("=")[0].split("!")[0]
                value = metric.get(aggregate, metric.get("value", metric.get("count", "")))
                if not crossed:
                    state = "ok"
                elif is_time(metric):
                    state = "**crossed, reported** (perf: chart-load)"
                else:
                    state = "**crossed, blocking**"
                    blocked = True
                lines.append(f"| {path.name} | `{name}` | `{threshold}` | {value} | {state} |")
    return "### k6 thresholds\n\n" + "\n".join(lines) + "\n", blocked


def self_test() -> None:
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        base = pathlib.Path(tmp)
        slow = {"metrics": {
            "http_req_duration{name:gate}": {"med": 9, "p(95)": 180.5, "thresholds": {"p(95)<150": True}},
            "http_req_failed": {"value": 0, "passes": 0, "fails": 10, "thresholds": {"rate<0.01": False}},
        }}
        (base / "smoke-summary.json").write_text(json.dumps(slow))
        report, blocked = verdict([str(base / "smoke-summary.json")])
        assert not blocked, report
        assert "| smoke-summary.json | `http_req_duration{name:gate}` | `p(95)<150` | 180.5 | **crossed, reported**" in report
        assert "| `http_req_failed` | `rate<0.01` | 0 | ok |" in report, report
        race = {"metrics": {"race_other": {"count": 2, "rate": 1, "thresholds": {"count==0": True}}}}
        (base / "race-summary.json").write_text(json.dumps(race))
        report, blocked = verdict([str(base / "smoke-summary.json"), str(base / "race-summary.json")])
        assert blocked and "| `race_other` | `count==0` | 2 | **crossed, blocking** |" in report, report
        report, blocked = verdict([str(base / "absent.json")])
        assert blocked and "missing" in report, report
        empty = {"metrics": {"checks": {"value": 1}}}
        (base / "empty.json").write_text(json.dumps(empty))
        assert verdict([str(base / "empty.json")])[1] is False
    print("self-test ok")


def main(argv: list) -> int:
    if argv == ["--self-test"]:
        self_test()
        return 0
    if not argv:
        print(__doc__, file=sys.stderr)
        return 2
    report, blocked = verdict(argv)
    print(report)
    return 1 if blocked else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
