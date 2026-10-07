#!/usr/bin/env bash
# Run the integration tests against the native bundle in a container that has no Mapnik installed.
# Usage: scripts/test-natives-linux.sh [linux-x86_64|linux-aarch64]   (default: this machine's)
# Build the bundle first with scripts/build-natives-linux.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
case "${1:-$(uname -m)}" in
  linux-x86_64|x86_64) PLATFORM=linux-x86_64; DOCKER_PLATFORM=linux/amd64 ;;
  linux-aarch64|aarch64|arm64) PLATFORM=linux-aarch64; DOCKER_PLATFORM=linux/arm64 ;;
  *) echo "unknown platform: ${1:-}" >&2; exit 2 ;;
esac
test -f "build/natives/${PLATFORM}/MANIFEST" || { echo "build the bundle first: scripts/build-natives-linux.sh ${PLATFORM}" >&2; exit 1; }
docker build --platform "$DOCKER_PLATFORM" -f natives/linux/Dockerfile.test --build-arg "PLATFORM=${PLATFORM}" --progress=plain .
