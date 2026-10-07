#!/usr/bin/env bash
# Build the self-contained Linux native bundle into build/natives/<platform>.
# Usage: scripts/build-natives-linux.sh [linux-x86_64|linux-aarch64]   (default: this machine's)
# Needs Docker. The first run compiles Mapnik and takes a while; Docker caches the result.
# Building the other architecture than this machine's needs QEMU emulation and is very slow: let CI do it.
set -euo pipefail
cd "$(dirname "$0")/.."
case "${1:-$(uname -m)}" in
  linux-x86_64|x86_64) PLATFORM=linux-x86_64; DOCKER_PLATFORM=linux/amd64 ;;
  linux-aarch64|aarch64|arm64) PLATFORM=linux-aarch64; DOCKER_PLATFORM=linux/arm64 ;;
  *) echo "unknown platform: ${1:-}" >&2; exit 2 ;;
esac
MAPNIK_VERSION="$(grep '^mapnik.version=' gradle.properties | cut -d= -f2)"
rm -rf "build/natives/${PLATFORM}"
mkdir -p build/natives
docker build --platform "$DOCKER_PLATFORM" -f natives/linux/Dockerfile --target export \
  --build-arg "MAPNIK_VERSION=${MAPNIK_VERSION}" --build-arg "PLATFORM=${PLATFORM}" \
  --output type=local,dest=build/natives .
echo "bundle in build/natives/${PLATFORM} (Mapnik ${MAPNIK_VERSION})"
