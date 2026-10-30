#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Lays out the build context of the cloud session image (runner/Dockerfile) in $1:
#   Dockerfile, bin/ostrom-runner ($2), bin/ostrom ($3, when given), agents/ (the pinned agents' CLIs) and npm-cache/.
# npm-cache/ is a copy of $SESSION_IMAGE_NPM_CACHE when it names a directory (the CI keeps one per lockfile, see
# warm_npm_cache), else empty: the Dockerfile mounts it, so a warm cache makes the install read nothing from the
# registry. Used by ci/runner-isolation.sh and the Runner image workflow.
set -euo pipefail

context="$1"
runner="$2"
cli="${3:-}"

mkdir -p "$context/bin" "$context/agents" "$context/npm-cache"
cp runner/Dockerfile "$context/Dockerfile"
cp "$runner" "$context/bin/ostrom-runner"
if [ -n "$cli" ]; then cp "$cli" "$context/bin/ostrom"; fi
cp runner/agents/package.json runner/agents/package-lock.json "$context/agents/"
cache="${SESSION_IMAGE_NPM_CACHE:-}"
if [ -n "$cache" ] && [ -d "$cache" ]; then
  # Hard links when the cache is on the same file system: no copy of the tarballs.
  cp -al "$cache/." "$context/npm-cache/" 2> /dev/null || cp -a "$cache/." "$context/npm-cache/"
fi
