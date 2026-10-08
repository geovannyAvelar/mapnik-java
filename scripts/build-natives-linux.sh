#!/usr/bin/env bash
# Build the portable Linux native bundle (and the PostGIS add-on) into build/natives/<platform>, in the
# manylinux_2_28 container. Usage: scripts/build-natives-linux.sh [linux-x86_64|linux-aarch64]
# Needs Docker. The first run builds every dependency from source and takes an hour or two.
exec "$(dirname "$0")/../natives/linux/build-manylinux.sh" "$@"
