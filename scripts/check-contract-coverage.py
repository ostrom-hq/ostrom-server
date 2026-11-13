#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fails when an operation the ostrom-core contract serves was never seen answering by the tests, so that no answer
escapes the strict check of ContractObserver (testing module).

    python3 scripts/check-contract-coverage.py <openapi.json> <unobserved-operations.txt> <KnownDrifts.kt> <reports>...

Every operation marked `x-ostrom-status: served` whose success (2xx) answers a JSON body must appear as `seen <id>` in
a report the test processes wrote (`build/reports/contract/observed-*.txt`, found under the given directories), unless
contract/unobserved-operations.txt lists it with its reason. Fails too for a line of that list that is no longer
needed (seen, or not a served JSON operation), so that the list only gets shorter. Prints the drifts the reports
recorded, known or not, and warns for a line of KnownDrifts no test met any more: its fix has landed, remove it.
"""
import json
import re
import pathlib
import sys

METHODS = ("get", "put", "post", "delete", "patch")


def required(contract: dict) -> set:
    """The ids of the served operations whose success answers JSON."""
    found = set()
    for path, item in contract.get("paths", {}).items():
        for method in METHODS:
            operation = item.get(method)
            if not operation or operation.get("x-ostrom-status") != "served":
                continue
            for status, response in operation.get("responses", {}).items():
                if "$ref" in response:
                    name = response["$ref"].split("/")[-1]
                    response = contract["components"]["responses"][name]
                if status.startswith("2") and "application/json" in response.get("content", {}):
                    found.add(operation.get("operationId", f"{method.upper()} {path}"))
    return found


def exceptions(text: str) -> dict:
    """`id  # reason` per line; a line without its reason is refused."""
    found = {}
    for number, line in enumerate(text.splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        name, _, reason = line.partition("#")
        if not reason.strip():
            raise SystemExit(f"unobserved-operations.txt:{number}: `{name.strip()}` has no reason (`id  # why`)")
        found[name.strip()] = reason.strip()
    return found


def reports(lines: list) -> tuple:
    """The operations seen, and the drift lines."""
    seen = {line[5:].strip() for line in lines if line.startswith("seen ")}
    drifts = sorted({line[6:].strip() for line in lines if line.startswith("drift ")})
    return seen, drifts


def known(text: str) -> set:
    """The keys KnownDrifts.kt lists: `"<operation> <status> <path> <kind>"`."""
    return set(re.findall(r'"(\S+ \d{3} \$\S* [A-Z]+)"', text))


def unmet(listed: set, drifts: list) -> list:
    """The listed drifts no report met."""
    met = {drift.split(" | ")[0].strip() for drift in drifts}
    return sorted(listed - met)


def compare(needed: set, seen: set, allowed: dict) -> list:
    errors = [f"{name} is served and answers JSON, but no test saw it answer: add a case, or list it with its "
              f"reason in contract/unobserved-operations.txt" for name in sorted(needed - seen - allowed.keys())]
    errors += [f"{name} is listed in contract/unobserved-operations.txt but a test saw it: remove the line"
               for name in sorted(allowed.keys() & seen)]
    errors += [f"{name} is listed in contract/unobserved-operations.txt but is not a served JSON operation: remove "
               f"the line" for name in sorted(allowed.keys() - needed)]
    return errors


def self_test() -> None:
    contract = {
        "components": {"responses": {"Ok": {"content": {"application/json": {}}}}},
        "paths": {
            "/a": {"get": {"operationId": "getA", "x-ostrom-status": "served",
                           "responses": {"200": {"content": {"application/json": {}}}}},
                   "post": {"operationId": "postA", "x-ostrom-status": "served", "responses": {"204": {}}}},
            "/b": {"get": {"operationId": "getB", "x-ostrom-status": "served",
                           "responses": {"200": {"$ref": "#/components/responses/Ok"}}},
                   "put": {"operationId": "putB", "x-ostrom-status": "planned",
                           "responses": {"200": {"content": {"application/json": {}}}}}},
        },
    }
    assert required(contract) == {"getA", "getB"}, required(contract)
    assert exceptions("# header\n\ngetB  # streamed\n") == {"getB": "streamed"}
    try:
        exceptions("getB\n")
        raise AssertionError("a line without its reason must be refused")
    except SystemExit:
        pass
    seen, drifts = reports(["seen getA", "drift x 200 $ MISSING | x", "drift x 200 $ MISSING | x", "other"])
    assert seen == {"getA"} and drifts == ["x 200 $ MISSING | x"], (seen, drifts)
    assert compare({"getA", "getB"}, {"getA"}, {"getB": "why"}) == []
    assert len(compare({"getA", "getB"}, set(), {})) == 2
    assert "remove the line" in compare({"getA"}, {"getA"}, {"getA": "why"})[0]
    assert "not a served JSON operation" in compare({"getA"}, {"getA"}, {"putB": "why"})[0]
    listed = known('waiting(\n    STEP_UP,\n    "a 403 $ TYPE",\n    "b 200 $.items[*].x UNDECLARED",\n)')
    assert listed == {"a 403 $ TYPE", "b 200 $.items[*].x UNDECLARED"}, listed
    assert unmet(listed, ["a 403 $ TYPE | a 403 $: both"]) == ["b 200 $.items[*].x UNDECLARED"]
    print("self-test ok")


def main() -> int:
    if sys.argv[1:] == ["--self-test"]:
        self_test()
        return 0
    contract = json.loads(pathlib.Path(sys.argv[1]).read_text())
    allowed = exceptions(pathlib.Path(sys.argv[2]).read_text())
    listed = known(pathlib.Path(sys.argv[3]).read_text())
    roots = sys.argv[4:]
    files = sorted(file for root in roots for file in pathlib.Path(root).glob("**/build/reports/contract/*.txt"))
    seen, drifts = reports([line for file in files for line in file.read_text().splitlines()])
    needed = required(contract)
    for drift in drifts:
        print(f"drift: {drift}")
    for name in unmet(listed, drifts):
        print(f"::warning::KnownDrifts lists `{name}` but no test met it: its fix has landed, remove the line")
    errors = compare(needed, seen, allowed)
    for error in errors:
        print(f"::error::{error}")
    print(f"{len(files)} report files, {len(needed)} served JSON operations, {len(needed & seen)} seen, "
          f"{len(allowed)} listed as unobserved, {len(drifts)} distinct drifts, {len(listed)} known, "
          f"{len(errors)} errors")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
