#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""The times the tests measured, against their budgets (testing module, TimeBudget.kt).

    python3 scripts/perf-budgets.py summary <root>...    # the Build: a table for the job's summary, never fails
    python3 scripts/perf-budgets.py check <run>...       # perf.yml: the median of the runs, fails over a budget
    python3 scripts/perf-budgets.py --self-test

Each test process appends one line per measure to `build/reports/perf/budgets.tsv` of its module: the measure, the
time and the budget, in milliseconds, tab-separated. The Build never fails on these times (a shared runner under load
slows what no pull request touched, orchestrator, JOURNAL 02:5x): `summary` shows them, marking the ones over their
budget. The Perf workflow runs the benches three times, each run's files under a directory of its own, and `check`
holds the median of each measure to its budget; a measure missing from a run (its test failed before measuring) fails
it too.
"""
import pathlib
import statistics
import sys

FILE = "budgets.tsv"


def read(root: pathlib.Path) -> dict:
    """The measures written under root: name -> (time, budget), the last line of a name winning."""
    found = {}
    for path in sorted(root.rglob(FILE)):
        for line in path.read_text().splitlines():
            if not line.strip():
                continue
            name, measured, budget = line.split("\t")
            found[name] = (float(measured), float(budget))
    return found


def table(rows: list) -> str:
    """Markdown rows (measure, time, budget, verdict), the worst ratio first."""
    lines = ["| Measure | Time (ms) | Budget (ms) | |", "|---|---:|---:|---|"]
    for name, measured, budget in sorted(rows, key=lambda row: -row[1] / row[2]):
        verdict = "within" if measured <= budget else f"**over, x{measured / budget:.1f}**"
        lines.append(f"| {name} | {measured:.2f} | {budget:.2f} | {verdict} |")
    return "\n".join(lines)


def summary(roots: list) -> str:
    """The job summary of a Build: every measure, none blocking."""
    measures = {}
    for root in roots:
        measures.update(read(pathlib.Path(root)))
    if not measures:
        return "### Measured times\n\nNo time was measured.\n"
    rows = [(name, measured, budget) for name, (measured, budget) in measures.items()]
    over = sum(1 for _, measured, budget in rows if measured > budget)
    return (
        f"### Measured times ({len(rows)}, {over} over their budget)\n\n"
        "Reported, not blocking: the Perf workflow (`perf.yml`) holds them to their budget, on the median of three "
        "runs.\n\n" + table(rows) + "\n"
    )


def check(runs: list) -> tuple:
    """The medians of the runs against their budgets: (report, failed)."""
    measured = [read(pathlib.Path(run)) for run in runs]
    names = sorted(set().union(*measured)) if measured else []
    rows, missing = [], []
    for name in names:
        times = [run[name][0] for run in measured if name in run]
        if len(times) < len(runs):
            missing.append(f"{name}: measured in {len(times)} of {len(runs)} runs")
        budget = next(run[name][1] for run in measured if name in run)
        rows.append((name, statistics.median(times), budget))
    over = [row for row in rows if row[1] > row[2]]
    report = f"### Times, median of {len(runs)} runs ({len(over)} over their budget)\n\n"
    report += table(rows) if rows else "No time was measured."
    if missing:
        report += "\n\n#### Missing from a run\n\n" + "\n".join(f"- {line}" for line in missing)
    return report + "\n", bool(over or missing or not rows)


def self_test() -> None:
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        base = pathlib.Path(tmp)
        for run, times in (("1", (10, 70)), ("2", (12, 40)), ("3", (90, 45))):
            report = base / run / "modules/a/build/reports/perf"
            report.mkdir(parents=True)
            (report / FILE).write_text(f"page p95\t{times[0]}\t50\n\nreport alone\t{times[1]}\t50\n")
        (base / "4/modules/b/build/reports/perf").mkdir(parents=True)
        (base / "4/modules/b/build/reports/perf" / FILE).write_text("page p95\t30\t50\npage p95\t60\t50\n")
        text, failed = check([str(base / run) for run in ("1", "2", "3")])
        assert not failed, text
        assert "| page p95 | 12.00 | 50.00 | within |" in text, text
        assert "| report alone | 45.00 | 50.00 | within |" in text, text
        text, failed = check([str(base / run) for run in ("1", "3")])
        assert failed and "| report alone | 57.50 | 50.00 | **over, x1.1** |" in text, text
        text, failed = check([str(base / run) for run in ("1", "4")])
        assert failed and "report alone: measured in 1 of 2 runs" in text, text
        assert check([str(base / "empty")]) == ("### Times, median of 1 runs (0 over their budget)\n\n"
                                                "No time was measured.\n", True)
        text = summary([str(base / "4")])
        assert "(1, 1 over their budget)" in text and "| page p95 | 60.00 | 50.00 | **over, x1.2** |" in text, text
        assert "No time was measured." in summary([str(base / "empty")])
    print("self-test ok")


def main(argv: list) -> int:
    if argv == ["--self-test"]:
        self_test()
        return 0
    if len(argv) >= 2 and argv[0] == "summary":
        print(summary(argv[1:]))
        return 0
    if len(argv) >= 2 and argv[0] == "check":
        report, failed = check(argv[1:])
        print(report)
        return 1 if failed else 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
