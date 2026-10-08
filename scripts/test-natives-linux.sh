#!/usr/bin/env bash
# Run the integration tests against the Linux native bundle on old systems that have nothing but a JDK.
# Usage: scripts/test-natives-linux.sh [linux-x86_64|linux-aarch64] [base image ...]
# Default images: almalinux:8 (glibc 2.28, the floor), ubuntu:20.04 and ubuntu:24.04.
# Build the bundle first with scripts/build-natives-linux.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
case "${1:-$(uname -m)}" in
  linux-x86_64|x86_64) PLATFORM=linux-x86_64 ;;
  linux-aarch64|aarch64|arm64) PLATFORM=linux-aarch64 ;;
  *) echo "unknown platform: ${1:-}" >&2; exit 2 ;;
esac
shift || true
BASES=("$@")
[[ ${#BASES[@]} -eq 0 ]] && BASES=(almalinux:8 ubuntu:20.04 ubuntu:24.04)
test -f "build/natives/${PLATFORM}/MANIFEST" || { echo "build the bundle first: scripts/build-natives-linux.sh ${PLATFORM}" >&2; exit 1; }
for base in "${BASES[@]}"; do
  echo "=== $base"
  docker build -f natives/linux/Dockerfile.test-old --build-arg "BASE=${base}" --build-arg "PLATFORM=${PLATFORM}" --progress=plain .
done
