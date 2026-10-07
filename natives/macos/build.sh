#!/usr/bin/env bash
# Build the self-contained macOS native bundle into build/natives/<platform>.
# Run on a Mac (the CI runner does): Homebrew supplies Mapnik's dependencies, Mapnik and the shim are
# compiled here, and bundle.py gathers everything. Usage: natives/macos/build.sh macos-aarch64|macos-x86_64
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT="$PWD"
PLATFORM="${1:?usage: build.sh macos-aarch64|macos-x86_64}"
MAPNIK_VERSION="$(grep '^mapnik.version=' gradle.properties | cut -d= -f2)"
PREFIX="$ROOT/build/mapnik-prefix"

# Homebrew bottles are built for the macOS the runner has, so that is the oldest the bundle supports.
export MACOSX_DEPLOYMENT_TARGET="$(sw_vers -productVersion | cut -d. -f1).0"
echo "macOS deployment target $MACOSX_DEPLOYMENT_TARGET"

FORMULAE=(cmake ninja pkgconf boost icu4c freetype harfbuzz libxml2 jpeg-turbo libtiff webp cairo libavif proj libpng sqlite zlib)
brew install "${FORMULAE[@]}" || brew upgrade "${FORMULAE[@]}" || true

# Keg-only formulae are not on the default search paths.
PREFIXES=""
PKGCONFIG=""
for f in "${FORMULAE[@]}"; do
  [[ "$f" == icu4c ]] && continue   # handled below, it is versioned
  p="$(brew --prefix "$f" 2>/dev/null || true)"
  [[ -d "$p" ]] || continue
  PREFIXES="$PREFIXES;$p"
  [[ -d "$p/lib/pkgconfig" ]] && PKGCONFIG="$PKGCONFIG:$p/lib/pkgconfig"
done
# icu4c is versioned (icu4c@77, icu4c@78 ...): use the newest one installed, and say which.
ICU_FORMULA="$(brew list --formula | grep '^icu4c' | sort -V | tail -1)"
ICU_ROOT="$(brew --prefix "$ICU_FORMULA")"
echo "ICU: $ICU_FORMULA at $ICU_ROOT"; ls "$ICU_ROOT/lib" | head -20
PREFIXES="$PREFIXES;$ICU_ROOT"
PKGCONFIG="$PKGCONFIG:$ICU_ROOT/lib/pkgconfig"
export CMAKE_PREFIX_PATH="${PREFIXES#;}"
export PKG_CONFIG_PATH="${PKGCONFIG#:}:$(brew --prefix)/lib/pkgconfig"

if [[ ! -e "$PREFIX/COPYING" ]]; then
  rm -rf build/mapnik-src
  git clone --branch "v${MAPNIK_VERSION}" --depth 1 --recurse-submodules --shallow-submodules \
    https://github.com/mapnik/mapnik.git build/mapnik-src
  # Same options as the Linux bundle: no GDAL, OGR or PostGIS plugins, logging on.
  cmake -S build/mapnik-src -B build/mapnik-build -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$PREFIX" \
    -DBUILD_TESTING=OFF -DBUILD_DEMO_VIEWER=OFF -DBUILD_DEMO_CPP=OFF -DBUILD_BENCHMARK=OFF \
    -DBUILD_UTILITY_GEOMETRY_TO_WKB=OFF -DBUILD_UTILITY_MAPNIK_INDEX=OFF -DBUILD_UTILITY_MAPNIK_RENDER=OFF \
    -DBUILD_UTILITY_PGSQL2SQLITE=OFF -DBUILD_UTILITY_SHAPEINDEX=OFF -DBUILD_UTILITY_SVG2PNG=OFF \
    -DUSE_MEMORY_MAPPED_FILE=ON -DUSE_LOG=ON -DUSE_LOG_SEVERITY=1 \
    -DUSE_PLUGIN_INPUT_GDAL=OFF -DUSE_PLUGIN_INPUT_OGR=OFF -DUSE_PLUGIN_INPUT_GDAL_OGR=OFF \
    -DUSE_PLUGIN_INPUT_POSTGIS=OFF -DUSE_PLUGIN_INPUT_PGRASTER=OFF -DUSE_PLUGIN_INPUT_POSTGIS_PGRASTER=OFF \
    -DUSE_PLUGIN_INPUT_TILES_SSL=OFF -DICU_ROOT="$ICU_ROOT"
  cmake --build build/mapnik-build
  cmake --install build/mapnik-build
  cp build/mapnik-src/COPYING "$PREFIX/COPYING"
fi

# CI saves the Mapnik build here, so a failure further on does not throw the compile away.
[[ "${STOP_AFTER_MAPNIK:-}" == "1" ]] && exit 0

# The shim, found through Mapnik's CMake package like for any user of a CMake-installed Mapnik.
cmake -S native -B build/shim-cmake -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_PREFIX_PATH="$PREFIX;$CMAKE_PREFIX_PATH"
cmake --build build/shim-cmake
ls -l build/native

# DejaVu fonts, pinned by checksum.
FONTS_TGZ="$ROOT/build/dejavu.tar.bz2"
if [[ ! -f "$FONTS_TGZ" ]]; then
  curl -fsSL -o "$FONTS_TGZ" https://github.com/dejavu-fonts/dejavu-fonts/releases/download/version_2_37/dejavu-fonts-ttf-2.37.tar.bz2
fi
echo "fa9ca4d13871dd122f61258a80d01751d603b4d3ee14095d65453b4e846e17d7  $FONTS_TGZ" | shasum -a 256 -c -
rm -rf build/dejavu && mkdir -p build/dejavu && tar xjf "$FONTS_TGZ" -C build/dejavu --strip-components=1

python3 natives/macos/bundle.py "build/natives/$PLATFORM" "$PLATFORM" "$PREFIX" "$ROOT/build/native/libmapnik_c.dylib" "$ROOT/build/dejavu" "$(brew --prefix proj)/share/proj"
echo "bundle in build/natives/$PLATFORM (Mapnik $MAPNIK_VERSION)"
