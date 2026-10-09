#!/usr/bin/env bash
# Build the portable Linux native bundle (glibc 2.28 or later) into build/natives/<platform>, in the manylinux_2_28
# container. Usage: natives/linux/build-manylinux.sh [linux-x86_64|linux-aarch64]   (default: this machine's)
# Needs Docker. Building the other architecture than this machine's needs emulation and is very slow: use CI.
# The first run builds every dependency from source and takes an hour or two; later runs reuse build/.
# STOP_AFTER=deps|mapnik stops early (see in-container.sh).
set -euo pipefail
cd "$(dirname "$0")/../.."
case "${1:-$(uname -m)}" in
  linux-x86_64|x86_64) PLATFORM=linux-x86_64; IMAGE=quay.io/pypa/manylinux_2_28_x86_64 ;;
  linux-aarch64|aarch64|arm64) PLATFORM=linux-aarch64; IMAGE=quay.io/pypa/manylinux_2_28_aarch64 ;;
  *) echo "unknown platform: ${1:-}" >&2; exit 2 ;;
esac
mkdir -p build
docker run --rm -v "$PWD":/work -w /work -e PYTHONDONTWRITEBYTECODE=1 -e STOP_AFTER="${STOP_AFTER:-}" -e MAPNIK_PREFIX_CACHED="${MAPNIK_PREFIX_CACHED:-}" "$IMAGE" \
  bash -c "bash natives/linux/in-container.sh $PLATFORM; rc=\$?; chown -R $(id -u):$(id -g) /work/build 2>/dev/null; exit \$rc"
