#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Lists the lines and branches Kover did not cover, from every module's XML report (CI log, on failure)."""
import pathlib
import xml.etree.ElementTree as ElementTree

for report in sorted(pathlib.Path(".").glob("**/build/reports/kover/report.xml")):
    module = report.parents[3].as_posix()
    root = ElementTree.parse(report).getroot()
    for package in root.iter("package"):
        for source in package.iter("sourcefile"):
            for line in source.iter("line"):
                missed_instructions = int(line.get("mi", "0"))
                missed_branches = int(line.get("mb", "0"))
                if missed_instructions or missed_branches:
                    what = "line" if missed_instructions else f"{missed_branches} branch(es)"
                    print(f"{module}: {package.get('name')}/{source.get('name')}:{line.get('nr')}: {what} not covered")
