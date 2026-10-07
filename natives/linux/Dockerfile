# syntax=docker/dockerfile:1
#
# Builds Mapnik and the mapnik-java shim on Ubuntu 24.04 and assembles a self-contained bundle:
# the shim, Mapnik, every library they need except the C and C++ runtimes, the input plugins, fonts
# and PROJ data. Build from the repository root:
#
#   docker build -f natives/linux/Dockerfile --target export [--build-arg PLATFORM=linux-aarch64] --output type=local,dest=build/natives .
#
# Ubuntu 24.04 is the oldest release whose libraries meet Mapnik 4.3 minimums (Boost 1.83,
# HarfBuzz 8.3, PROJ 9.4), so the bundle needs a host with at least glibc 2.39 and libstdc++ from GCC 13.

FROM ubuntu:24.04 AS mapnik
ARG MAPNIK_VERSION=4.3.2
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates git ninja-build g++ python3 python3-pip pkg-config patchelf file \
        libboost-dev libboost-filesystem-dev libboost-program-options-dev libboost-regex-dev libboost-url-dev libboost-context-dev libboost-json-dev \
        libicu-dev libfreetype-dev libharfbuzz-dev libxml2-dev libjpeg-dev libtiff-dev libwebp-dev \
        libcairo2-dev libavif-dev libpq-dev libproj-dev libpng-dev libsqlite3-dev zlib1g-dev \
        fonts-dejavu-core proj-data \
    && rm -rf /var/lib/apt/lists/*
# Mapnik 4.3 needs CMake 3.30; Ubuntu 24.04 has 3.28.
RUN pip install --break-system-packages --no-cache-dir cmake==3.31.6
RUN git clone --branch "v${MAPNIK_VERSION}" --depth 1 --recurse-submodules --shallow-submodules \
        https://github.com/mapnik/mapnik.git /src/mapnik
WORKDIR /src/mapnik
# No GDAL or OGR plugins: they pull in a very large dependency tree. PostGIS (with pgraster, they are built together) is shipped as an add-on bundle. Logging is switched on
# because Mapnik drops every log message otherwise.
RUN cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/opt/mapnik \
        -DBUILD_TESTING=OFF -DBUILD_DEMO_VIEWER=OFF -DBUILD_DEMO_CPP=OFF -DBUILD_BENCHMARK=OFF \
        -DBUILD_UTILITY_GEOMETRY_TO_WKB=OFF -DBUILD_UTILITY_MAPNIK_INDEX=OFF -DBUILD_UTILITY_MAPNIK_RENDER=OFF \
        -DBUILD_UTILITY_PGSQL2SQLITE=OFF -DBUILD_UTILITY_SHAPEINDEX=OFF -DBUILD_UTILITY_SVG2PNG=OFF \
        -DUSE_MEMORY_MAPPED_FILE=ON -DUSE_LOG=ON -DUSE_LOG_SEVERITY=1 \
        -DUSE_PLUGIN_INPUT_GDAL=OFF -DUSE_PLUGIN_INPUT_OGR=OFF -DUSE_PLUGIN_INPUT_GDAL_OGR=OFF \
        -DUSE_PLUGIN_INPUT_POSTGIS=OFF -DUSE_PLUGIN_INPUT_PGRASTER=OFF -DUSE_PLUGIN_INPUT_POSTGIS_PGRASTER=ON -DUSE_PLUGIN_INPUT_TILES_SSL=OFF \
 && cmake --build build \
 && cmake --install build

# The shim, found through Mapnik's CMake package exactly as a user with a CMake-installed Mapnik would.
FROM mapnik AS shim
COPY native /src/shim/native
COPY gradle.properties /src/shim/gradle.properties
RUN cmake -S /src/shim/native -B /src/shim/build/cmake -G Ninja -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_PREFIX_PATH=/opt/mapnik \
 && cmake --build /src/shim/build/cmake \
 && ls -l /src/shim/build/native

FROM shim AS bundle
ARG PLATFORM=linux-x86_64
COPY natives/linux/bundle.sh natives/linux/bundle-extra.sh /usr/local/bin/
RUN bash /usr/local/bin/bundle.sh "/out/${PLATFORM}" "${PLATFORM}" \
 && bash /usr/local/bin/bundle-extra.sh "/out/${PLATFORM}" "/out/${PLATFORM}-postgis" "${PLATFORM}" "postgis+pgraster.input" postgis

FROM scratch AS export
COPY --from=bundle /out/ /
