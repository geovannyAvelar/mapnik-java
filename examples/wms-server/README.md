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

curl -o map.png "http://localhost:8080/wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=world&STYLES=&CRS=EPSG:4326&BBOX=-90,-180,90,180&WIDTH=800&HEIGHT=400&FORMAT=image/png"
```

It also works with WMS clients. For example, GDAL:

```bash
gdal_translate -of PNG "WMS:http://localhost:8080/wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=world&CRS=EPSG:4326&BBOX=-90,-180,90,180&FORMAT=image/png" out.png -outsize 400 200
```

## What it supports

- Versions 1.1.1 (`SRS`) and 1.3.0 (`CRS`).
- Requests: `GetCapabilities` and `GetMap`. Anything else returns `OperationNotSupported`.
- One layer, `world` (two polygons and a line from GeoJSON).
- CRS `EPSG:4326` and `CRS:84`. Axis order follows the spec: with 1.3.0 and `EPSG:4326` the `BBOX` is `miny,minx,maxy,maxx`; with `CRS:84` or 1.1.1 it is `minx,miny,maxx,maxy`.
- Formats `image/png` and `image/jpeg`. Sizes up to 4096.
- Parameter names are case-insensitive.
- Errors are OGC `ServiceExceptionReport` XML with HTTP 400.

## Limits

- Other CRSs, such as `EPSG:3857`, are rejected. The wrapper does not yet expose setting the map projection.
- Mapnik widens the requested extent to match the `WIDTH:HEIGHT` ratio. Clients should send a `BBOX` with the same aspect ratio as the image.
- `LAYERS` accepts only `world`. Picking layers within a style is not wrapped yet.
- No `GetFeatureInfo`, no `SLD`, no caching.
- Each request renders with its own `MapnikMap`, because maps are not thread-safe. A real server would pool them.

## Test

```bash
./gradlew test
```

Starts the server on a free port and checks capabilities, both versions, axis order, zoom, both formats, every error case, and concurrent requests.
