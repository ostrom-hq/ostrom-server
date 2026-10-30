#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Builds the release server binary for linuxX64 and leaves it in dist/ostrom-server, where the Docker image and the
# end-to-end and load tests of ostrom-e2e take it. Linux x64 hosts only (CI); on the shared Mac, never run it directly.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :app:linkReleaseExecutableLinuxX64
mkdir -p dist
cp app/build/bin/linuxX64/releaseExecutable/ostrom-server.kexe dist/ostrom-server
chmod 755 dist/ostrom-server
ls -lh dist/ostrom-server
