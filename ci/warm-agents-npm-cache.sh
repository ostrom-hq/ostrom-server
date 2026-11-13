#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Fills the npm cache $1 with the tarballs of runner/agents/package-lock.json, on the CI host, for the session image
# build to mount (ci/session-image-context.sh). Install scripts are not run here: only the download is wanted. A warm
# cache (restored by actions/cache, keyed on the lockfile) makes this a check that reads nothing from the registry.
# npm retries a network error or a 5xx/429 only, with backoff; on failure the full npm log is printed.
set -euo pipefail

cache="$1"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$cache"
cp runner/agents/package.json runner/agents/package-lock.json "$work/"
export NPM_CONFIG_FETCH_RETRIES=5 NPM_CONFIG_FETCH_RETRY_MINTIMEOUT=10000 NPM_CONFIG_FETCH_RETRY_MAXTIMEOUT=60000
if ! (cd "$work" && npm ci --ignore-scripts --no-audit --no-fund --prefer-offline --cache "$cache" \
  --logs-dir "$work/logs"); then
  cat "$work"/logs/*.log >&2 || true
  exit 1
fi
du -sh "$cache"
