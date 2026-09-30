#!/usr/bin/env bash
# Run the integration tests against the native bundle in a container that has no Mapnik installed.
# Build the bundle first with scripts/build-natives-linux.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
test -f build/natives/linux-x86_64/MANIFEST || { echo "build the bundle first: scripts/build-natives-linux.sh" >&2; exit 1; }
docker build -f natives/linux-x86_64/Dockerfile.test --progress=plain .
