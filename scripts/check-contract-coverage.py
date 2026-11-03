#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Fails when an operation the ostrom-core contract serves was never seen answering by the tests, so that no answer
escapes the strict check of ContractObserver (testing module).

    python3 scripts/check-contract-coverage.py <openapi.json> <unobserved-operations.txt> <reports directory>...

Every operation marked `x-ostrom-status: served` whose success (2xx) answers a JSON body must appear as `seen <id>` in
a report the test processes wrote (`build/reports/contract/observed-*.txt`, found under the given directories), unless
contract/unobserved-operations.txt lists it with its reason. Fails too for a line of that list that is no longer
needed (seen, or not a served JSON operation), so that the list only gets shorter. Prints the drifts the reports
recorded, known or not, for the record.
"""
import json
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
    print("self-test ok")


def main() -> int:
    if sys.argv[1:] == ["--self-test"]:
        self_test()
        return 0
    contract = json.loads(pathlib.Path(sys.argv[1]).read_text())
    allowed = exceptions(pathlib.Path(sys.argv[2]).read_text())
    files = sorted(file for root in sys.argv[3:] for file in pathlib.Path(root).glob("**/build/reports/contract/*.txt"))
    seen, drifts = reports([line for file in files for line in file.read_text().splitlines()])
    needed = required(contract)
    for drift in drifts:
        print(f"drift: {drift}")
    errors = compare(needed, seen, allowed)
    for error in errors:
        print(f"::error::{error}")
    print(f"{len(files)} report files, {len(needed)} served JSON operations, {len(needed & seen)} seen, "
          f"{len(allowed)} listed as unobserved, {len(drifts)} distinct drifts, {len(errors)} errors")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
