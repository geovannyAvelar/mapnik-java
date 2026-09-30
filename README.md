# mapnik-java

Java bindings for [Mapnik](https://mapnik.org), the C++ map rendering library.

Mapnik has no C API, so Java cannot call it directly. This project puts a small `extern "C"` shim in front of the Mapnik C++ API and binds the shim with [JNA](https://github.com/java-native-access/jna). It works on Java 8 and later and needs no code generation.

## How it works

```
Java (MapnikMap)  ->  JNA (NativeApi)  ->  libmapnik_c.so (C shim)  ->  Mapnik (C++)
```

- `native/` holds the C shim (`mapnik_c.h` and `src/*.cpp`, one file per area) and its CMake build. The shim catches every C++ exception and exposes the message through `mapnik_last_error()`, so no exception crosses the FFI boundary.
- `src/main/java/dev/avelar/mapnik/` holds the Java side:
  - `Mapnik`: global setup (version, datasource plugins, fonts).
  - `MapnikMap`: a map: load a style, projection, extent and zoom, layers, render to a file or to bytes.
  - `Layer` and `Datasource`: layers and their data, from a style or built in code.
  - `Box2d` and `AspectFixMode`: small value types.
  - `NativeApi`: the raw JNA mapping of `mapnik_c.h`.
  - `MapnikException`: thrown when a native call fails.

## Requirements

- Mapnik 4.x with development files, found through either `mapnik-config` on your `PATH` (SCons builds, most distro packages) or Mapnik's CMake package (set `CMAKE_PREFIX_PATH` to the install prefix)
- Mapnik's own development dependencies (on Debian or Ubuntu, for example `libharfbuzz-dev` and `libcairo2-dev`)
- CMake 3.16 or later and a C++20 compiler
- JDK 8 or later (the Gradle wrapper is included)

## Build

```bash
cmake -S native -B build/cmake
cmake --build build/cmake
./gradlew build
```

The shared library is written to `build/native/libmapnik_c.so`. Gradle passes that directory to the tests through `jna.library.path`. Tests skip themselves when the library is missing.

## Installation

Releases of the Java jar are published to Maven Central. It does **not** contain the native shim, because the shim has to be compiled against the Mapnik installed on your machine. Build `libmapnik_c.so` as described above and make it available to JNA.

```kotlin
// Gradle
implementation("dev.avelar:mapnik-java:4.1.0.0")
```

```xml
<!-- Maven -->
<dependency>
  <groupId>dev.avelar</groupId>
  <artifactId>mapnik-java</artifactId>
  <version>4.1.0.0</version>
</dependency>
```

## Versioning

Releases follow Mapnik: `<mapnik version>.<wrapper revision>`.

- `4.1.0.0` is the first release for Mapnik 4.1.0. `4.1.0.1` is a wrapper-only fix for the same Mapnik.
- Use the release whose major.minor matches the Mapnik you have installed. The shim is compiled against your Mapnik, so a mismatch can fail to compile or behave differently.
- The wrapper logs a warning the first time you create a `MapnikMap` if the loaded Mapnik differs in major.minor from the one the release was built for. `Mapnik.expectedVersion()`, `Mapnik.version()` and `Mapnik.isCompatible()` expose the same check.
- The CMake build prints a warning for the same mismatch.
- A weekly workflow checks for new Mapnik releases and opens an issue with an upgrade checklist.

## Usage

```java
Mapnik.registerDatasources("/usr/local/lib/mapnik/input");
Mapnik.registerFonts("/usr/local/lib/mapnik/fonts");

try (MapnikMap map = new MapnikMap(1024, 768)) {
    map.load(Paths.get("style.xml")).zoomAll();

    map.renderToFile(Paths.get("out.png"), "png");
    byte[] png = map.renderToPng();
}
```

To use the library from your own application, put `libmapnik_c.so` on the JNA search path:

```bash
java -Djna.library.path=build/native -cp ... YourApp
```

### Projection, extent and appearance

```java
try (MapnikMap map = new MapnikMap(512, 512)) {
    map.load(Paths.get("style.xml"));

    map.setSrs("epsg:3857");                      // layers are reprojected to the map's projection
    map.setBackground("#cfe8f7");
    map.setAspectFixMode(AspectFixMode.RESPECT);  // use the box exactly, stretching pixels if needed
    map.zoomToBox(new Box2d(-20037508, -20037508, 20037508, 20037508)); // in the map's projection

    map.extent();            // what is shown now
    map.scaleDenominator();
    map.zoom(0.5);           // below 1 zooms in, above 1 zooms out
    map.pan(10, 0);

    String xml = map.toXml(); // the whole map as Mapnik XML
}
```

Set the projection before `zoomToBox`, because the extent is interpreted in the map's projection. An invalid projection string fails when you render, not when you set it.

### Layers

```java
map.layerNames();                                  // ["land", "route"], in drawing order
map.setActiveLayers(Arrays.asList("land"));        // draw only these
map.setLayerActive("route", true);                 // or toggle one
map.layer("land").setMinimumScaleDenominator(1000).setOpacity(0.8);
```

`map.layer(...)` returns a live view onto a layer in the map. It stops working, with an `IllegalStateException`, when the map's layer list changes (add, remove, load) or the map is closed. Get a fresh view after changing the list.

### Building a map in code

```java
Map<String, Object> params = new HashMap<>();
params.put("type", "geojson");
params.put("file", "/data/places.geojson");

try (MapnikMap map = new MapnikMap(800, 400);
     Layer layer = Layer.create("places", "epsg:4326");
     Datasource ds = Datasource.create(params)) {

    map.loadString("<Map><Style name=\"red\"><Rule><PolygonSymbolizer fill=\"red\"/></Rule></Style></Map>", null);
    layer.setDatasource(ds).addStyle("red");   // the layer keeps its own reference to ds
    map.addLayer(layer);                       // copies the layer, so you may close yours
    map.zoomToBox(layer.envelope());
    byte[] png = map.renderToPng();
}
```

### Features and queries

```java
try (Datasource ds = Datasource.create(params);
     Featureset fs = ds.features(FeatureQuery.within(new Box2d(-10, -10, 10, 10)).properties("name"))) {
    for (Feature f : fs) {
        f.id();
        f.attribute("name");     // String, Long, Double, Boolean or null
        f.geometryKind();        // POINT, POLYGON, ...
        f.geometryWkt();         // "POINT(1 2)"
        f.toGeoJson();           // a GeoJSON Feature
    }
}

try (Featureset hits = map.queryPoint("places", lon, lat)) { ... }   // in the map's projection
try (Featureset hits = map.queryMapPoint("places", px, py)) { ... }  // a pixel of the rendered image
```

A `Featureset` can be read once. Features are snapshots: they stay valid after the set is closed. A map must have an extent (for example from `zoomToBox`) before you query it.

### Projections

```java
try (CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:3857")) {
    Point2d m = t.forward(10, 20);                           // lon/lat to web mercator
    Box2d merc = t.forward(new Box2d(-10, -20, 10, 20));     // a box
    Box2d safe = t.forward(new Box2d(12, 40, 18, 50), 20);   // sample 20 points per edge for big boxes
}

try (Projection p = Projection.of("epsg:32633")) {
    p.isGeographic();
    p.forward(15, 0);       // lon/lat to this projection
    p.inverse(500000, 0);   // and back
}
```

A point that cannot be projected throws `MapnikException`. Use a `CoordinateTransform` to turn a longitude/latitude box into the extent for `zoomToBox` when the map is in another projection.

### Rendering

```java
byte[] png    = map.renderToBytes("png");
byte[] small  = map.renderToBytes("png8");              // indexed colour, much smaller
byte[] jpeg   = map.renderToBytes("jpeg90");
map.renderToFile(Paths.get("map.pdf"), "pdf");          // also svg and ps, through Cairo

RenderOptions hiDpi = RenderOptions.defaults().scaleFactor(2);  // thicker lines, bigger symbols and text
byte[] retina = map.renderToBytes("png", hiDpi);

try (Image img = map.renderToImage()) {                 // work with the pixels
    int argb = img.getArgb(10, 20);                     // 0xAARRGGBB
    byte[] again = img.toBytes("webp");
}

try (Image canvas = Image.create(map.width(), map.height())) {
    canvas.fill("white");
    map.render(canvas);                                  // blends over what is there
}
```

`RenderOptions.offset(x, y)` makes the image a window that starts at pixel (x, y) of a larger map image, which is how you render a big map in pieces. `Mapnik.hasCairo()` tells you whether PDF, SVG and PostScript output is available.

Styles are still defined in XML. Creating styles, rules and symbolizers in code is planned (see [docs/ROADMAP.md](docs/ROADMAP.md)).

Use `mapnik-config --input-plugins` and `mapnik-config --fonts` to find the plugin and font directories on your system. A CMake-installed Mapnik has no `mapnik-config`: look under its install prefix (`find <prefix> -name csv.input`) and set `MAPNIK_INPUT_PLUGINS`.

## Status

The wrapper covers the core of Mapnik in phases: maps, layers, datasources, features, queries, projections and rendering work today. Styling in code is planned. See [docs/ROADMAP.md](docs/ROADMAP.md).

## Examples

- `examples/render-demo`: a small app that depends on mapnik-java and renders a map to a PNG.
- `examples/wms-server`: a minimal WMS 1.1.1 and 1.3.0 server (GetCapabilities and GetMap, layer selection, EPSG:4326 and EPSG:3857) built on the wrapper.

Both have their own README.

## Testing

```bash
./gradlew test             # unit tests; skip if libmapnik_c is missing
./gradlew integrationTest  # renders real data through Mapnik; fails if Mapnik is missing
```

Build the shim first. Integration tests register the input plugins from `mapnik-config --input-plugins`, or from the `MAPNIK_INPUT_PLUGINS` environment variable, which wins when set and is required if Mapnik has no `mapnik-config`. They cover rendering a GeoJSON polygon and checking pixels, reading features and geometry output, projections, image formats and Cairo, output formats, zoom and resize, reprojection, layer selection, error handling, recovery after a failed render, and concurrent rendering with one map per thread.

The `Integration` workflow builds the targeted Mapnik version from source (cached per version), then runs both suites.

## Notes

- `MapnikMap` is not thread-safe. Use one instance per thread, or a pool.
- Map memory is native and not garbage collected. Always call `close()`, ideally with try-with-resources.
- Call `registerDatasources` and `registerFonts` once at startup, before you load a style that needs them.
- Only the important public parts of the Mapnik API are wrapped, in phases: see [docs/ROADMAP.md](docs/ROADMAP.md). Adding more means adding a function to `mapnik_c.h` and its `native/src` file, then the matching line in `NativeApi` and a method on the Java class.

## Layout

```
native/                       C shim and CMake build
src/main/java/dev/avelar/mapnik   Java wrapper
src/test/java/dev/avelar/mapnik   JUnit tests
build.gradle.kts              Gradle build and publishing
```

## License

MIT. See [LICENSE](LICENSE). Mapnik itself is licensed separately under the LGPL.
