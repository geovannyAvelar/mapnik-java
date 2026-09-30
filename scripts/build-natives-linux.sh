#!/usr/bin/env bash
# Build the self-contained Linux x86_64 native bundle into build/natives/linux-x86_64.
# Needs Docker. The first run compiles Mapnik and takes a while; Docker caches the result.
set -euo pipefail
cd "$(dirname "$0")/.."
MAPNIK_VERSION="$(grep '^mapnik.version=' gradle.properties | cut -d= -f2)"
rm -rf build/natives
mkdir -p build/natives
docker build -f natives/linux-x86_64/Dockerfile --target export \
  --build-arg "MAPNIK_VERSION=${MAPNIK_VERSION}" \
  --output type=local,dest=build/natives .
echo "bundle in build/natives/linux-x86_64 (Mapnik ${MAPNIK_VERSION})"
