# syntax=docker/dockerfile:1
#
# Builds Mapnik and the mapnik-java shim on Ubuntu 24.04 and assembles a self-contained bundle:
# the shim, Mapnik, every library they need except the C and C++ runtimes, the input plugins, fonts
# and PROJ data. Build from the repository root:
#
#   docker build -f natives/linux-x86_64/Dockerfile --target export --output type=local,dest=build/natives .
#
# Ubuntu 24.04 is the oldest release whose libraries meet Mapnik 4.1's minimums (Boost 1.83,
# HarfBuzz 8.3, PROJ 9.4), so the bundle needs a host with at least glibc 2.39 and libstdc++ from GCC 13.

FROM ubuntu:24.04 AS mapnik
ARG MAPNIK_VERSION=4.1.0
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates git cmake ninja-build g++ python3 pkg-config patchelf file \
        libboost-dev libboost-filesystem-dev libboost-program-options-dev libboost-regex-dev \
        libicu-dev libfreetype-dev libharfbuzz-dev libxml2-dev libjpeg-dev libtiff-dev libwebp-dev \
        libcairo2-dev libproj-dev libpng-dev libsqlite3-dev zlib1g-dev \
        fonts-dejavu-core proj-data \
    && rm -rf /var/lib/apt/lists/*
RUN git clone --branch "v${MAPNIK_VERSION}" --depth 1 --recurse-submodules --shallow-submodules \
        https://github.com/mapnik/mapnik.git /src/mapnik
WORKDIR /src/mapnik
# No GDAL, OGR or PostGIS plugins: each pulls in a very large dependency tree. Logging is switched on
# because Mapnik drops every log message otherwise.
RUN cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/opt/mapnik \
        -DBUILD_TESTING=OFF -DBUILD_DEMO_VIEWER=OFF -DBUILD_DEMO_CPP=OFF -DBUILD_BENCHMARK=OFF \
        -DBUILD_UTILITY_GEOMETRY_TO_WKB=OFF -DBUILD_UTILITY_MAPNIK_INDEX=OFF -DBUILD_UTILITY_MAPNIK_RENDER=OFF \
        -DBUILD_UTILITY_PGSQL2SQLITE=OFF -DBUILD_UTILITY_SHAPEINDEX=OFF -DBUILD_UTILITY_SVG2PNG=OFF \
        -DUSE_MEMORY_MAPPED_FILE=ON -DUSE_LOG=ON -DUSE_LOG_SEVERITY=1 \
        -DUSE_PLUGIN_INPUT_GDAL=OFF -DUSE_PLUGIN_INPUT_OGR=OFF \
        -DUSE_PLUGIN_INPUT_POSTGIS=OFF -DUSE_PLUGIN_INPUT_PGRASTER=OFF \
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
COPY natives/linux-x86_64/bundle.sh /usr/local/bin/bundle.sh
RUN bash /usr/local/bin/bundle.sh /out/linux-x86_64

FROM scratch AS export
COPY --from=bundle /out/ /
