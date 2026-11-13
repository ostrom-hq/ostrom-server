#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Moves every agent CLI of runner/agents/package.json to its latest version on the npm registry (an exact version,
# never a range), then rewrites package-lock.json without running any install script. Prints the changes, one
# "<package> <old> -> <new>" per line; prints nothing when every pin is already the latest. Reads the registry only.
set -euo pipefail

dir=runner/agents
export NPM_CONFIG_FETCH_RETRIES=5 NPM_CONFIG_FETCH_RETRY_MINTIMEOUT=10000 NPM_CONFIG_FETCH_RETRY_MAXTIMEOUT=60000
latest_versions=$(node -e '
  const deps = require(process.argv[1]).dependencies;
  console.log(Object.keys(deps).join("\n"));
' "$PWD/$dir/package.json" | while read -r name; do
  printf '%s %s\n' "$name" "$(npm view "$name" version)"
done)
# shellcheck disable=SC2016 # JavaScript, not shell: the template literal is meant for node.
node -e '
  const fs = require("fs");
  const file = process.argv[1];
  const pkg = JSON.parse(fs.readFileSync(file, "utf8"));
  for (const line of process.argv[2].split("\n").filter(Boolean)) {
    const [name, version] = line.split(" ");
    if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error(`not an exact version for ${name}: ${version}`);
    if (pkg.dependencies[name] !== version) console.log(`${name} ${pkg.dependencies[name]} -> ${version}`);
    pkg.dependencies[name] = version;
  }
  fs.writeFileSync(file, JSON.stringify(pkg, null, 2) + "\n");
' "$PWD/$dir/package.json" "$latest_versions"
(cd "$dir" && npm install --package-lock-only --ignore-scripts --no-audit --no-fund > /dev/null)
