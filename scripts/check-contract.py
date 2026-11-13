#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Compares the routes this server mounts with the ostrom-core contract (api/openapi.yaml), so that they cannot drift.

    python3 scripts/check-contract.py <ostrom-core checkout>

Routes are read from the Kotlin sources by ostrom-core's scripts/served-routes.py (no build, no network). Fails when
  - a route the server mounts is not in the contract: declare it in ostrom-core;
  - an operation marked `served` is not mounted by this server.
Warns (does not fail) for a mounted route whose operation is still `planned`: the server may merge before core.
"""
import pathlib
import re
import subprocess
import sys

METHODS = ("get", "post", "put", "patch", "delete")


def key(method: str, path: str) -> str:
    return f"{method.upper()} {re.sub(r'{[^}]*}', '{}', path)}"


def declared(text: str) -> dict:
    """`METHOD /path` -> `x-ostrom-status`, from the paths of the contract."""
    found, path, verb = {}, None, None
    for line in text.split("\n"):
        match = re.match(r"^  (/\S*):\s*$", line)
        if match:
            path, verb = match.group(1), None
            continue
        match = re.match(rf"^    ({'|'.join(METHODS)}):\s*$", line)
        if match and path:
            verb = match.group(1)
            continue
        match = re.match(r"^      x-ostrom-status: (\w+)", line)
        if match and path and verb:
            found[key(verb, path)] = match.group(1)
    return found


def mounted(core: pathlib.Path, root: pathlib.Path) -> set:
    out = subprocess.run([sys.executable, "-I", str(core / "scripts/served-routes.py"), str(root)],
                         capture_output=True, text=True, check=True).stdout
    return {line.strip() for line in out.split("\n") if re.match(r"^(GET|POST|PUT|PATCH|DELETE) /", line)}


def compare(routes: set, contract: dict) -> tuple:
    missing = sorted(r for r in routes if r not in contract)
    unmounted = sorted(k for k, status in contract.items() if status == "served" and k not in routes)
    planned = sorted(r for r in routes if contract.get(r) == "planned")
    return missing, unmounted, planned


def self_test() -> None:
    text = "paths:\n  /api/v1/a/{id}:\n    get:\n      operationId: a\n      x-ostrom-status: served\n" \
           "    post:\n      x-ostrom-status: planned\n  /api/v1/b:\n    get:\n      x-ostrom-status: served\n"
    contract = declared(text)
    assert contract == {"GET /api/v1/a/{}": "served", "POST /api/v1/a/{}": "planned", "GET /api/v1/b": "served"}, contract
    result = compare({"GET /api/v1/a/{}", "POST /api/v1/a/{}", "GET /api/v1/c"}, contract)
    assert result == (["GET /api/v1/c"], ["GET /api/v1/b"], ["POST /api/v1/a/{}"]), result
    print("self-test ok")


def main() -> int:
    if sys.argv[1:] == ["--self-test"]:
        self_test()
        return 0
    core = pathlib.Path(sys.argv[1])
    contract = declared((core / "api/openapi.yaml").read_text())
    missing, unmounted, planned = compare(mounted(core, pathlib.Path(".")), contract)
    for route in planned:
        print(f"::warning::{route} is mounted but still planned in ostrom-core: mark it served in the core pull request")
    for route in missing:
        print(f"::error::{route} is mounted but not in the ostrom-core contract: declare it in api/openapi.yaml")
    for route in unmounted:
        print(f"::error::{route} is served in the ostrom-core contract but this server does not mount it")
    print(f"{len(missing)} undeclared, {len(unmounted)} served but not mounted, {len(planned)} planned but mounted")
    return 1 if missing or unmounted else 0


sys.exit(main())
