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

Releases are published to Maven Central. There are two ways to get a working Mapnik.

### Prebuilt natives: nothing to install

On Linux, x86_64 or aarch64, add the natives artifact for your platform next to the library (`mapnik-java-natives-linux-x86_64` or `mapnik-java-natives-linux-aarch64`; the examples use x86_64):

```kotlin
// Gradle
implementation("dev.avelar:mapnik-java:4.3.2.0")
runtimeOnly("dev.avelar:mapnik-java-natives-linux-x86_64:4.3.2.0")
```

```xml
<!-- Maven -->
<dependency>
  <groupId>dev.avelar</groupId>
  <artifactId>mapnik-java</artifactId>
  <version>4.3.2.0</version>
</dependency>
<dependency>
  <groupId>dev.avelar</groupId>
  <artifactId>mapnik-java-natives-linux-x86_64</artifactId>
  <version>4.3.2.0</version>
  <scope>runtime</scope>
</dependency>
```

That is all. The jar holds Mapnik, everything it depends on (ICU, PROJ, FreeType, HarfBuzz, Cairo, libpng, libjpeg, libtiff, libwebp and more), the `csv`, `geojson`, `geobuf`, `topojson`, `shape`, `raster`, `sqlite` and `tiles` input plugins, the DejaVu fonts and PROJ's data. On first use it unpacks to `~/.cache/mapnik-java/<id>` (set `-Dmapnik.native.cache=/some/dir` to change that), checks every file against a SHA-256 list, and reuses the directory afterwards. Plugins and fonts are registered for you, so `Mapnik.isBundled()` is true and you can start drawing.

What to know:

- **Host:** Linux x86_64 or aarch64 with glibc 2.39 or later and the C++ runtime from GCC 13 or later: Ubuntu 24.04, Debian 13, Fedora 40 and newer. The C and C++ runtimes are the host's, not bundled. The bundle is built on Ubuntu 24.04 because Mapnik 4.3 needs Boost 1.83, HarfBuzz 8.3 and PROJ 9.4. macOS and Windows have no prebuilt natives yet.
- **Plugins left out:** `gdal`, `ogr`, `postgis` and `pgraster`, because each pulls in a very large dependency tree. To use them, build against a Mapnik of your own (below).
- **Licences:** Mapnik is LGPL and the bundled libraries have their own licences. The jar carries each one under `licenses/`, and a `NOTICE` listing the packages. The libraries are separate shared files, which you can replace.
- **Size:** tens of megabytes.

### Your own Mapnik

Without the natives artifact, the library loads `libmapnik_c` from the system. Build it against your Mapnik as described in [Build](#build), then make it available to JNA, for example with `-Djna.library.path=build/native` or `-Dmapnik.native.dir=/path`.

Which one is used, first match wins:

1. The directory in `-Dmapnik.native.dir` or `MAPNIK_JAVA_NATIVE_DIR`.
2. A directory in `jna.library.path` that holds `libmapnik_c`.
3. The natives artifact on the class path.
4. The system's library path.

### Building the natives yourself

```bash
scripts/build-natives-linux.sh     # needs Docker; compiles Mapnik on the first run; add linux-aarch64 for that platform
scripts/test-natives-linux.sh      # runs the integration tests on a machine with no Mapnik
./gradlew nativesJar               # packages every bundle under build/natives as a jar
```

## Versioning

Releases follow Mapnik: `<mapnik version>.<wrapper revision>`.

- `4.3.2.0` is the first release for Mapnik 4.3.2. `4.3.2.1` is a wrapper-only fix for the same Mapnik.
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

### Styling in code

```java
Style roads = Style.create("roads")
    .add(Rule.create().filter("[kind] = 'motorway'")
             .add(Symbolizer.line().stroke("#d1322b").strokeWidth(3)))
    .add(Rule.create().elseFilter()
             .add(Symbolizer.line().stroke("#888").strokeWidth(1).strokeDasharray("4,2")));

Style labels = Style.create("labels").add(Rule.create().maxScaleDenominator(50000).add(
    Symbolizer.text("[name]").faceName("DejaVu Sans Book").fontSize(12).fill("black").halo("white", 2)));

map.addStyle(roads).addStyle(labels);
layer.addStyle("roads").addStyle("labels");
```

Builders write Mapnik's own XML, so every symbolizer and attribute Mapnik supports is available: typed helpers cover the common ones and `attr("stroke-linejoin", "round")` sets any other. Symbolizers: `polygon`, `line`, `markers`, `point`, `polygonPattern`, `linePattern`, `raster`, `building`, `dot`, `debug`, `text` and `shield`.

`addStyle` loads in Mapnik's strict mode. Mapnik's normal loader silently ignores a misspelled attribute, an attribute on the wrong kind of symbolizer, an unknown font or a missing image, and the style then draws nothing. Strict mode turns each of those into a `MapnikException` that names the problem, and the style is not kept. Pass `false` as a second argument for the lenient behaviour. A style name that already exists throws; use `replaceStyle`.

`Rule.alsoFilter()` adds detail to features an earlier rule matched, and does nothing in `Style.FilterMode.FIRST` mode.

### Geometry and data from Java

```java
Geometry poly = Geometry.polygon(new double[] {0, 0, 10, 0, 10, 10, 0, 10, 0, 0});
Geometry line = Geometry.fromWkt("LINESTRING(0 0, 5 5, 10 0)");
Geometry any  = Geometry.fromGeoJson("{\"type\":\"Point\",\"coordinates\":[1,2]}");
poly.toWkt();  poly.toGeoJson();  poly.toWkb();        // and back with fromWkb

poly.centroid();              poly.interiorPoint();    // interiorPoint is a good label position
poly.closestPoint(15, 5);     // the point and its distance
poly.isValid();               poly.validityReason();
line.simplify(SimplifyAlgorithm.DOUGLAS_PEUCKER, 0.5);
line.offset(2);               // a parallel line
poly.reproject(transform);    // with a CoordinateTransform

try (MemoryDatasource ds = MemoryDatasource.create()) {     // render data that is not in a file
    ds.add(Geometry.point(10, 20), Collections.singletonMap("name", "Depot"));
    ds.addGeoJson(featureCollectionText);
    layer.setDatasource(ds);
}
```

Geometries are immutable and 2D. A memory datasource reports its geometry type as `COLLECTION` and no fields, because Mapnik does not inspect in-memory features, but filters and labels see every attribute.

### Expressions

```java
try (Expression e = Expression.parse("[population] > 1000 and [kind] = 'city'")) {
    e.matches(feature);                       // filter features in Java the way Mapnik will
    e.evaluate(feature, Collections.singletonMap("zoom", 5));   // values for @zoom
}
Expression.isValid("[broken");                // false: check a filter before using it in a style
PathExpression.parse("icons/[type].png").evaluate(feature);
Transforms.isValid("translate(10,20) rotate(45)");
```

Mapnik's `/` divides whole numbers to a whole number, `match()` must match the whole value, and a missing attribute is `null`.

### Images, rasters and colour

```java
try (Image img = map.renderToImage()) {
    img.filter(ImageFilters.stackBlur(4, 4), ImageFilters.gray());
    img.composite(overlay, BlendMode.MULTIPLY, 0.8, 10, 10);
    try (Image small = img.scaled(128, 128, ScalingMethod.LANCZOS)) { ... }
    img.crop(0, 0, 100, 100);
}
Image.probe(bytes);                           // size and format without decoding
Color.parse("rebeccapurple").toHex();         // "#663399"

Symbolizer.raster().colorizer(RasterColorizer.create()
    .defaultMode(RasterColorizer.Mode.LINEAR).stop(0, "blue").stop(500, "green").stop(3000, "white"));
image.warp("epsg:4326", sourceExtent, "epsg:3857", targetExtent, 512, 512, ScalingMethod.BILINEAR);
```

`Image` is 8-bit RGBA with straight alpha. The raster colorizer only applies to single-band data, which a datasource such as GDAL supplies to the renderer; Mapnik's `raster` plugin decodes a PNG to RGBA.

### Tiles and UTFGrid

```java
Tiles.Tile t = Tiles.tileAt(-0.1276, 51.5072, 10);     // 10/511/340
t.bounds();  t.lonLatBounds();  t.quadKey();  t.parent();  t.children();
Tiles.covering(new Box2d(-1, 50, 1, 52), 8);

byte[] png = map.renderTileToBytes(t, "png", 32);      // 32 px metatile margin so labels line up
UtfGrid grid = map.renderGrid("places", "__id__", Arrays.asList("name", "pop"), 4);
grid.attributesAt(120, 80);                            // what is under this pixel
```

The map must be square to render tiles. `renderTile` puts it in Web Mercator for the render and restores its projection, extent, size, aspect mode and buffer afterwards.

### Richer styling and reading styles back

```java
Symbolizer label = Symbolizer.formattedText(
        TextFormat.of("[name]").fontSize(14).fill("black"),
        TextFormat.of("' ' + [pop]").fontSize(10).fill("gray"))
    .faceName("DejaVu Sans Bold")
    .placementList(TextPlacement.create().fontSize(11), TextPlacement.create().fontSize(9));

Symbolizer cluster = Symbolizer.group().groupColumns(1, 1, "[cluster]").simpleLayout(4)
    .groupRule(GroupRule.create().add(Symbolizer.markers().fill("red")));

Style s = map.style("roads").get();            // read back: rules, filters, symbolizers
s.rules().get(0).filterExpression();           // Mapnik's own spelling of the filter
map.replaceStyle(s);
Style.fromXml(xmlText);
```

Mapnik draws a group symbolizer only when it has a column range and a key, which `groupColumns` sets.

### Your own datasource

```java
FeatureSource source = request -> myIndex.query(request.bbox());   // a List<Feature>; called per render
try (JavaDatasource ds = JavaDatasource.create(source, worldExtent)) {
    layer.setDatasource(ds);
}
```

Mapnik calls the source while it renders and each render asks again, so data can change between renders. The source must be thread-safe, because maps rendering on different threads call it at the same time. An exception in it fails the render with a `MapnikException` carrying its message.

### Diagnostics

```java
Mapnik.supports(Capability.CAIRO);            // what this Mapnik was built with
Mapnik.fontFaces();                           // names to use as face-name
Logging.setSeverity(Logging.Severity.WARN);   // Mapnik explains why a style draws nothing
Logging.toFile(Paths.get("mapnik.log"));
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


Use `mapnik-config --input-plugins` and `mapnik-config --fonts` to find the plugin and font directories on your system. A CMake-installed Mapnik has no `mapnik-config`: look under its install prefix (`find <prefix> -name csv.input`) and set `MAPNIK_INPUT_PLUGINS`.

## Status

The wrapper covers the core of Mapnik in phases: maps, layers, datasources, features, queries, projections, rendering, styling in code, images, geometry, expressions, tiles, UTFGrid, richer styling and your own datasources work today. See the roadmap for what is left out. See [docs/ROADMAP.md](docs/ROADMAP.md).

## Examples

- `examples/render-demo`: a small app that depends on mapnik-java and renders a map to a PNG.
- `examples/wms-server`: a minimal WMS 1.1.1 and 1.3.0 server (GetCapabilities and GetMap, layer selection, EPSG:4326 and EPSG:3857) built on the wrapper.

Both have their own README.

## Testing

```bash
./gradlew test             # unit tests; skip if libmapnik_c is missing
./gradlew integrationTest  # renders real data through Mapnik; fails if Mapnik is missing
```

Build the shim first. Integration tests register the input plugins from `mapnik-config --input-plugins`, or from the `MAPNIK_INPUT_PLUGINS` environment variable, which wins when set and is required if Mapnik has no `mapnik-config`. They cover rendering a GeoJSON polygon and checking pixels, reading features and geometry output, projections, image formats and Cairo, image operations, rasters, geometry and memory datasources, expressions, tiles stitched against a full render, UTFGrid, output formats, zoom and resize, reprojection, layer selection, error handling, recovery after a failed render, styles built in code, and concurrent rendering with one map per thread.

Text tests need fonts: they use `MAPNIK_FONTS`, or `mapnik-config --fonts`, and skip themselves if neither is available.

The `Integration` workflow builds the targeted Mapnik version from source (cached per version), then runs both suites.

## Notes

- Threads: `MapnikMap`, `Layer`, `Image`, `Featureset`, `Feature` builders and `Datasource` are not thread-safe. Use one map per thread (or a pool), and do not share an object between threads without your own locking. Several maps may render at the same time. `Projection` and `CoordinateTransform` have not been tested from several threads, so treat them as not thread-safe too. Pure-Java classes (`Geometry`, `Feature`, `Box2d`, `Tiles`, `Style` builders) are immutable or hold no native state.
- Native memory is not garbage collected. Close everything you create (`MapnikMap`, `Layer`, `Image`, `Datasource`, `Projection`, `Featureset`, `Expression` and so on), ideally with try-with-resources. The library does not free a forgotten handle from the garbage collector, because that could crash the JVM while a native call is still using it. It reports one instead: a warning is logged the first few times, and `Mapnik.leakedHandles()` counts them. Run with `-Dmapnik.leakTrace=true` to see where a leaked handle was created.
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
