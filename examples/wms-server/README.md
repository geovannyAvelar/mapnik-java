# wms-server

A minimal WMS server built on mapnik-java, using only the JDK's built-in HTTP server. It is a demo of the wrapper, not a production map server.

It resolves `dev.avelar:mapnik-java` from this repository through a Gradle composite build (`includeBuild("../..")` in `settings.gradle.kts`). To use a released version from Maven Central, delete that line.

## Run

Build the native shim once from the repository root:

```bash
cmake -S native -B build/cmake && cmake --build build/cmake
```

Then:

```bash
cd examples/wms-server
./gradlew run --args="8080"
```

```bash
curl "http://localhost:8080/wms?SERVICE=WMS&REQUEST=GetCapabilities"

curl -o map.png "http://localhost:8080/wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=land,route&STYLES=&CRS=EPSG:4326&BBOX=-90,-180,90,180&WIDTH=800&HEIGHT=400&FORMAT=image/png"
```

It also works with WMS clients. For example, GDAL:

```bash
gdal_translate -of PNG "WMS:http://localhost:8080/wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=land,route&CRS=EPSG:4326&BBOX=-90,-180,90,180&FORMAT=image/png" out.png -outsize 400 200
```

## What it supports

- Versions 1.1.1 (`SRS`) and 1.3.0 (`CRS`).
- Requests: `GetCapabilities` and `GetMap`. Anything else returns `OperationNotSupported`.
- Two layers, `land` and `route`, read from the bundled style with `MapnikMap.layerNames()`. `LAYERS` picks any subset (`MapnikMap.setActiveLayers`). Names are case-sensitive.
- CRS `EPSG:4326`, `CRS:84` and `EPSG:3857`, applied with `MapnikMap.setSrs`. The data is stored in EPSG:4326 and Mapnik reprojects it. Axis order follows the spec: with 1.3.0 and `EPSG:4326` the `BBOX` is `miny,minx,maxy,maxx`; with `CRS:84`, `EPSG:3857` or 1.1.1 it is `x,y`.
- Formats `image/png` and `image/jpeg`. Sizes up to 4096.
- Parameter names are case-insensitive.
- Errors are OGC `ServiceExceptionReport` XML with HTTP 400.

## Limits

- Other CRSs are rejected. Any PROJ-known CRS would work in the wrapper, but each one needs its own axis-order rule and capabilities entry here.
- The `BBOX` is used exactly (`AspectFixMode.RESPECT`). If its aspect ratio differs from `WIDTH:HEIGHT`, the image is stretched, as the WMS spec says.
- `LAYERS` turns layers on or off. Drawing order is always the style's order, not the order in the request, so a request cannot put `land` above `route`.
- No `GetFeatureInfo`, no `SLD`, no caching.
- Each request renders with its own `MapnikMap`, because maps are not thread-safe. A real server would pool them.

## Test

```bash
./gradlew test
```

Starts the server on a free port and checks capabilities, both versions, axis order, zoom, both formats, every error case, and concurrent requests.
