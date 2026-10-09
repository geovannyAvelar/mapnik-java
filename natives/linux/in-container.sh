#!/usr/bin/env bash
# Build the portable Linux native bundle inside the manylinux_2_28 container (AlmaLinux 8: glibc 2.28, GCC Toolset,
# system libstdc++ from GCC 8). The bundle then runs on any Linux with glibc 2.28 or later and a libstdc++ from GCC 8
# or later: RHEL 8 and 9, Ubuntu 20.04 and newer, Debian 10 and newer, Amazon Linux 2023.
#
# Run it through natives/linux/build-manylinux.sh. Dependencies are built from source with vcpkg, the same recipe
# (mapnik/vcpkg at the commit Mapnik pins) as the Windows bundle, because Mapnik 4.3 needs newer Boost, HarfBuzz
# and PROJ than those old systems have.
#
# STOP_AFTER=deps    stop once vcpkg has built the dependencies
# STOP_AFTER=mapnik  stop once Mapnik is installed
set -euo pipefail
cd /work
ROOT=/work
PLATFORM="${1:?usage: in-container.sh linux-x86_64|linux-aarch64}"
case "$PLATFORM" in
  linux-x86_64) TRIPLET=x64-linux-mapnik ;;
  linux-aarch64) TRIPLET=arm64-linux-mapnik ;;
  *) echo "unknown platform: $PLATFORM" >&2; exit 2 ;;
esac
MAPNIK_VERSION="$(grep '^mapnik.version=' gradle.properties | cut -d= -f2)"
VCPKG_RELEASE="$(cat natives/vcpkg-release.txt)"
SRC="$ROOT/build/mapnik-src"
PREFIX="$ROOT/build/mapnik-prefix"
INSTALLED="$SRC/build/vcpkg_installed"
export PATH="/opt/python/cp312-cp312/bin:$PATH"
export VCPKG_FORCE_SYSTEM_BINARIES=1       # there is no vcpkg binary for every architecture: use the tools here
export VCPKG_DISABLE_METRICS=1
export VCPKG_BINARY_SOURCES="clear;files,$ROOT/build/vcpkg-binary-cache,readwrite"
export MAKEFLAGS="-j$(nproc)"
mkdir -p "$ROOT/build/vcpkg-binary-cache"

echo "--- tools"
# autoconf-archive is in EPEL, not in the base repositories; vcpkg's autotools ports need it
dnf install -y epel-release >/dev/null
dnf install -y zip flex perl-IPC-Cmd perl-Time-Piece perl-Digest-SHA autoconf-archive >/dev/null
pip install --quiet --root-user-action=ignore ninja certifi
git config --global --add safe.directory '*'
git config --global advice.detachedHead false

if [[ ! -d "$SRC/.git" ]]; then
  rm -rf "$SRC"
  git clone --branch "v${MAPNIK_VERSION}" --depth 1 --recurse-submodules --shallow-submodules \
    https://github.com/mapnik/mapnik.git "$SRC"
fi
if [[ ! -d "$SRC/vcpkg/.git" ]]; then
  git clone --filter=blob:none https://github.com/mapnik/vcpkg "$SRC/vcpkg"
  git -C "$SRC/vcpkg" checkout "$VCPKG_RELEASE"
fi
[[ -x "$SRC/vcpkg/vcpkg" ]] || "$SRC/vcpkg/bootstrap-vcpkg.sh" -disableMetrics

# The one change made to Mapnik: its tiles plugin must check the certificate of an HTTPS tile server.
if ! git -C "$SRC" apply --reverse --check "$ROOT"/natives/patches/*.patch 2>/dev/null; then
  git -C "$SRC" apply "$ROOT"/natives/patches/*.patch
fi

# Leave out what the bundle does not ship (GDAL, and cairomm which only Mapnik's demos use; PostgreSQL is built, for the
# PostGIS add-on), and build cairo
# without the X11 backend, which needs a pile of system packages and is no use to a map renderer.
git -C "$SRC" checkout -- vcpkg.json          # start from the original, so the changes below can be redone
python - "$SRC/vcpkg.json" <<'PY'
import json, sys
p = sys.argv[1]
d = json.load(open(p))
drop = {"gdal", "cairomm"}
out = []
for x in d["dependencies"]:
    name = x if isinstance(x, str) else x["name"]
    if name in drop:
        continue
    if name == "cairo":
        x = {"name": "cairo", "default-features": False, "features": ["freetype", "fontconfig"]}
    out.append(x)
d["dependencies"] = out
json.dump(d, open(p, "w"), indent=2)
PY

# abseil and utf8-range are static libraries that protobuf, a shared library, links in, so they must be position
# independent. The triplet's flags do not reach them (the toolchain chain-loaded for GCC Toolset replaces the one
# that applies them), so give just these two ports the option, through overlay copies of their ports.
OVERLAY="$ROOT/build/overlay-ports"
rm -rf "$OVERLAY"; mkdir -p "$OVERLAY"
for port in abseil utf8-range; do
  cp -r "$SRC/vcpkg/ports/$port" "$OVERLAY/$port"
  python - "$OVERLAY/$port/portfile.cmake" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
open(p, "w").write('set(VCPKG_CMAKE_CONFIGURE_OPTIONS "${VCPKG_CMAKE_CONFIGURE_OPTIONS};-DCMAKE_POSITION_INDEPENDENT_CODE=ON")\n' + s)
PY
done

echo "--- configure (vcpkg builds the dependencies)"
export PKG_CONFIG_PATH="$INSTALLED/$TRIPLET/lib/pkgconfig:$INSTALLED/$TRIPLET/share/pkgconfig"
TOOLCHAIN="$SRC/vcpkg/scripts/buildsystems/vcpkg.cmake"
VCPKG_ARGS=(-DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" -DVCPKG_TARGET_TRIPLET="$TRIPLET" -DVCPKG_HOST_TRIPLET="$TRIPLET"
  -DVCPKG_OVERLAY_TRIPLETS="$ROOT/natives/linux/triplets" -DVCPKG_OVERLAY_PORTS="$OVERLAY"
  -DVCPKG_CHAINLOAD_TOOLCHAIN_FILE="$ROOT/natives/linux/triplets/toolchain-gcc-toolset.cmake"
  "-DVCPKG_INSTALL_OPTIONS=--clean-after-build")
cmake -S "$SRC" -B "$SRC/build" -G Ninja -DCMAKE_BUILD_TYPE=Release "${VCPKG_ARGS[@]}" \
  -DBUILD_SHARED_LIBS=ON -DCMAKE_CXX_STANDARD=20 \
  -DBUILD_TESTING=OFF -DBUILD_DEMO_VIEWER=OFF -DBUILD_DEMO_CPP=OFF -DBUILD_BENCHMARK=OFF \
  -DBUILD_UTILITY_GEOMETRY_TO_WKB=OFF -DBUILD_UTILITY_MAPNIK_INDEX=OFF -DBUILD_UTILITY_MAPNIK_RENDER=OFF \
  -DBUILD_UTILITY_PGSQL2SQLITE=OFF -DBUILD_UTILITY_SHAPEINDEX=OFF -DBUILD_UTILITY_SVG2PNG=OFF \
  -DUSE_MEMORY_MAPPED_FILE=ON -DUSE_LOG=ON -DUSE_LOG_SEVERITY=1 \
  -DUSE_PLUGIN_INPUT_GDAL=OFF -DUSE_PLUGIN_INPUT_OGR=OFF -DUSE_PLUGIN_INPUT_GDAL_OGR=OFF \
  -DUSE_PLUGIN_INPUT_POSTGIS=OFF -DUSE_PLUGIN_INPUT_PGRASTER=OFF -DUSE_PLUGIN_INPUT_POSTGIS_PGRASTER=ON \
  -DUSE_PLUGIN_INPUT_TILES_SSL=ON \
  -DCMAKE_INSTALL_PREFIX="$PREFIX"
[[ "${STOP_AFTER:-}" == "deps" ]] && exit 0

# Incremental: nothing is rebuilt when nothing changed. CI restores an earlier install and sets MAPNIK_PREFIX_CACHED.
if [[ "${MAPNIK_PREFIX_CACHED:-}" != "1" || ! -e "$PREFIX/COPYING" ]]; then
  echo "--- build Mapnik"
  cmake --build "$SRC/build"
  cmake --install "$SRC/build"
  cp "$SRC/COPYING" "$PREFIX/COPYING"
fi
[[ "${STOP_AFTER:-}" == "mapnik" ]] && exit 0

echo "--- the shim"
cmake -S native -B build/shim-cmake -G Ninja -DCMAKE_BUILD_TYPE=Release "${VCPKG_ARGS[@]}" \
  -DVCPKG_MANIFEST_MODE=OFF -DVCPKG_INSTALLED_DIR="$INSTALLED" \
  -DCMAKE_PREFIX_PATH="$PREFIX;$INSTALLED/$TRIPLET"
cmake --build build/shim-cmake
ls -l build/native

echo "--- the bundle"
export MAPNIK_VERSION VCPKG_RELEASE
python natives/linux/bundle_manylinux.py "build/natives/$PLATFORM" "$PLATFORM" "$PREFIX" \
  "$ROOT/build/native/libmapnik_c.so" "$INSTALLED/$TRIPLET" "$ROOT/build/dejavu"
python natives/linux/bundle_extra_manylinux.py "build/natives/$PLATFORM" "build/natives/$PLATFORM-postgis" "$PLATFORM" \
  "$PREFIX" "$INSTALLED/$TRIPLET" "postgis+pgraster.input" postgis
echo "bundle in build/natives/$PLATFORM (Mapnik $MAPNIK_VERSION)"
