#!/usr/bin/env bash
# Build the self-contained Windows native bundle into build/natives/<platform>.
# Run from Git Bash on a Windows machine, inside a Visual Studio developer prompt (cl and ninja on the PATH);
# the CI runner does that. Dependencies come from vcpkg, as in Mapnik's own Windows CI: the repository
# mapnik/vcpkg at the commit Mapnik 4.3.2 pins. Usage: natives/windows/build.sh windows-x86_64
#
# STOP_AFTER=deps    stop once vcpkg has built the dependencies (CI saves its binary cache here)
# STOP_AFTER=mapnik  stop once Mapnik is installed (CI saves the install here)
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT="$PWD"
PLATFORM="${1:?usage: build.sh windows-x86_64}"
case "$PLATFORM" in
  windows-x86_64) TRIPLET=x64-windows-release ;;
  *) echo "unknown platform: $PLATFORM" >&2; exit 2 ;;
esac
MAPNIK_VERSION="$(grep '^mapnik.version=' gradle.properties | cut -d= -f2)"
# The vcpkg commit Mapnik's CI uses for this release (VCPKG_RELEASE in its .github/workflows/build_and_test.yml).
VCPKG_RELEASE=7e9d43fdadc456cb9ef892bd12d27e32503f41fa
SRC="$ROOT/build/mapnik-src"
PREFIX="$ROOT/build/mapnik-prefix"
win() { cygpath -m "$1"; }     # C:/path form, which CMake and vcpkg accept

git config --global core.longpaths true
export VCPKG_BINARY_SOURCES="clear;files,$(win "$ROOT/build/vcpkg-binary-cache"),readwrite"
export VCPKG_DISABLE_METRICS=1
mkdir -p "$ROOT/build/vcpkg-binary-cache"

if [[ ! -d "$SRC/.git" ]]; then
  rm -rf "$SRC"
  git clone --branch "v${MAPNIK_VERSION}" --depth 1 --recurse-submodules --shallow-submodules \
    https://github.com/mapnik/mapnik.git "$SRC"
fi
# The preset looks for the toolchain file in vcpkg/ inside the Mapnik source.
if [[ ! -d "$SRC/vcpkg/.git" ]]; then
  git clone --filter=blob:none https://github.com/mapnik/vcpkg "$SRC/vcpkg"
  git -C "$SRC/vcpkg" checkout "$VCPKG_RELEASE"
fi
[[ -f "$SRC/vcpkg/vcpkg.exe" ]] || cmd.exe //c "$(cygpath -w "$SRC/vcpkg/bootstrap-vcpkg.bat") -disableMetrics"

# The one change made to Mapnik: its tiles plugin must check the certificate of an HTTPS tile server.
if ! git -C "$SRC" apply --reverse --check "$ROOT"/natives/patches/*.patch 2>/dev/null; then
  git -C "$SRC" apply "$ROOT"/natives/patches/*.patch
fi

# Leave out what the bundle does not ship: GDAL and PostgreSQL, and cairomm, which only Mapnik's demos use. Each is a very large build.
python - "$SRC/vcpkg.json" <<'PY'
import json, sys
p = sys.argv[1]
d = json.load(open(p))
drop = {"gdal", "libpq", "cairomm"}
d["dependencies"] = [x for x in d["dependencies"] if (x if isinstance(x, str) else x["name"]) not in drop]
# Mapnik's CMake looks for pkg-config, which the runner does not have: have vcpkg install one.
d["dependencies"].append("pkgconf")
json.dump(d, open(p, "w"), indent=2)
PY

# Configuring makes vcpkg build every dependency (from its binary cache when it can), and later steps need
# its installed tree, so this always runs.
cmake -S "$SRC" --preset windows-ci \
    -DVCPKG_TARGET_TRIPLET="$TRIPLET" -DVCPKG_HOST_TRIPLET="$TRIPLET" \
    -DPKG_CONFIG_EXECUTABLE="$(win "$SRC/build/vcpkg_installed/$TRIPLET/tools/pkgconf/pkgconf.exe")" \
    -DADDITIONAL_LIBARIES_PATHS="$(win "$SRC/build/vcpkg_installed/$TRIPLET/bin")" \
    -DBUILD_SHARED_LIBS=ON -DCMAKE_CXX_STANDARD=20 -DINSTALL_DEPENDENCIES=OFF \
    -DBUILD_TESTING=OFF -DBUILD_DEMO_VIEWER=OFF -DBUILD_DEMO_CPP=OFF -DBUILD_BENCHMARK=OFF \
    -DBUILD_UTILITY_GEOMETRY_TO_WKB=OFF -DBUILD_UTILITY_MAPNIK_INDEX=OFF -DBUILD_UTILITY_MAPNIK_RENDER=OFF \
    -DBUILD_UTILITY_PGSQL2SQLITE=OFF -DBUILD_UTILITY_SHAPEINDEX=OFF -DBUILD_UTILITY_SVG2PNG=OFF \
    -DUSE_MEMORY_MAPPED_FILE=ON -DUSE_LOG=ON -DUSE_LOG_SEVERITY=1 \
    -DUSE_PLUGIN_INPUT_GDAL=OFF -DUSE_PLUGIN_INPUT_OGR=OFF -DUSE_PLUGIN_INPUT_GDAL_OGR=OFF \
    -DUSE_PLUGIN_INPUT_POSTGIS=OFF -DUSE_PLUGIN_INPUT_PGRASTER=OFF -DUSE_PLUGIN_INPUT_POSTGIS_PGRASTER=OFF \
    -DUSE_PLUGIN_INPUT_TILES_SSL=ON \
    -DCMAKE_INSTALL_PREFIX="$(win "$PREFIX")"
[[ "${STOP_AFTER:-}" == "deps" ]] && exit 0

if [[ ! -e "$PREFIX/COPYING" ]]; then
  cmake --build "$SRC/build"
  cmake --install "$SRC/build"
  cp "$SRC/COPYING" "$PREFIX/COPYING"
fi
[[ "${STOP_AFTER:-}" == "mapnik" ]] && exit 0

echo "--- what Mapnik installed"
find "$PREFIX" -maxdepth 3 \( -name '*.dll' -o -name '*.input' -o -name '*.cmake' \) | head -40

# The shim, found through Mapnik's CMake package like for any user of a CMake-installed Mapnik, with the
# dependencies from the same vcpkg tree.
INSTALLED="$SRC/build/vcpkg_installed"
cmake -S native -B build/shim-cmake -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$(win "$SRC/vcpkg/scripts/buildsystems/vcpkg.cmake")" \
  -DVCPKG_MANIFEST_MODE=OFF -DVCPKG_INSTALLED_DIR="$(win "$INSTALLED")" -DVCPKG_TARGET_TRIPLET="$TRIPLET" \
  -DCMAKE_PREFIX_PATH="$(win "$PREFIX");$(win "$INSTALLED/$TRIPLET")"
cmake --build build/shim-cmake
ls -l build/native

export MAPNIK_VERSION VCPKG_RELEASE
python natives/windows/bundle.py "build/natives/$PLATFORM" "$PLATFORM" "$PREFIX" "$ROOT/build/native/mapnik_c.dll" \
  "$INSTALLED/$TRIPLET" "$ROOT/build/dejavu"
echo "bundle in build/natives/$PLATFORM (Mapnik $MAPNIK_VERSION)"
