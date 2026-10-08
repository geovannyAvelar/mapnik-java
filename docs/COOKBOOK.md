# Cookbook

Short recipes for common jobs. Every snippet assumes `import dev.avelar.mapnik.*;`. With the prebuilt
natives on the class path, plugins and fonts are already registered. With your own Mapnik, call
`Mapnik.registerDatasources(...)` and `Mapnik.registerFonts(...)` once at startup.

## Render a style file to a PNG

```java
try (MapnikMap map = new MapnikMap(1024, 768)) {
    map.load(Paths.get("style.xml")).zoomAll();
    map.renderToFile(Paths.get("out.png"), "png");
}
```

`zoomAll()` fits the map to the extent of its layers. Use `zoomToBox(Box2d)` for a fixed area, in the
map's projection.

## A slippy-map tile server

Web map tiles are 256 by 256 pixels in Web Mercator. Keep a pool of maps that already have the style
loaded, and for each request borrow one, point it at the tile and draw.

```java
MapPool pool = new MapPool(Runtime.getRuntime().availableProcessors(), 256, 256,
    map -> map.load(Paths.get("style.xml")).setSrs("epsg:3857"));

// For a server, also set how long a request may wait, and draw once on each map before taking traffic:
//   MapPool.builder(n, 256, 256).setup(...).warmup(m -> m.zoomToBox(Tiles.bounds(0, 0, 0)).renderToPng())
//       .maxWait(2, TimeUnit.SECONDS).build();
// A request that waits longer gets MapPool.ExhaustedException: answer 503. pool.stats() reports how busy it is.

byte[] tile(int z, int x, int y) {
    Tiles.Tile tile = new Tiles.Tile(z, x, y);          // throws IllegalArgumentException if off the grid
    return pool.withMap(map -> map.zoomToBox(tile.bounds()).renderToPng());
}
```

Labels and symbols that cross a tile edge are cut off unless the map has a buffer around the tile. Set
`<Map buffer-size="64">` in the style, or call `setBufferSize(64)` on the map in the pool setup. Serve
`Cache-Control` headers and cache tiles: drawing is fast, but not free, and tiles rarely change.

If many tiles are empty, `try (Image img = map.renderToImage()) { if (img.isSolid()) ... }` lets you
detect and share one blank tile instead of encoding the same PNG again and again.

## A WMS-style endpoint

The [`examples/wms-server`](../examples/wms-server) project is a complete small WMS (GetCapabilities and
GetMap) on the JDK's own HTTP server. The core of a GetMap is:

```java
byte[] getMap(String srs, Box2d bbox, int width, int height, List<String> layers) {
    return pool.withMap(map -> {
        map.setSrs(srs).resize(width, height).setActiveLayers(layers);
        return map.zoomToBox(bbox).renderToPng();
    });
}
```

Maps in a pool are reused, so set everything a request can vary (size, projection, layers, extent) on
each use, as above, and never leave a request's choices behind for the next one.

## Draw your own data without a file

```java
try (MemoryDatasource ds = MemoryDatasource.create();
     Layer layer = Layer.create("places", "epsg:4326");
     MapnikMap map = new MapnikMap(800, 400)) {

    ds.addGeoJson("{\"type\":\"FeatureCollection\",\"features\":["
        + "{\"type\":\"Feature\",\"properties\":{\"name\":\"Depot\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[10,20]}}]}");

    map.setSrs("epsg:4326").addStyle(Style.create("pts").add(Rule.create().add(
        Symbolizer.markers().fill("red").size(10, 10))));
    layer.addStyle("pts").setDatasource(ds);
    map.addLayer(layer);
    byte[] png = map.zoomToBox(new Box2d(-30, -10, 50, 50)).renderToPng();
}
```

To produce features while Mapnik draws, for example from a database filtered by the visible area, use
a `JavaDatasource` and a `FeatureSource`. It receives the area and scale being drawn:

```java
JavaDatasource ds = JavaDatasource.create(request -> repository.find(request.bbox()), world);   // world: the data's Box2d
```

Make the source thread-safe: maps drawing on different threads call it at the same time.

## Style in code and check it

```java
Style roads = Style.create("roads")
    .add(Rule.create().filter("[kind] = 'motorway'").add(Symbolizer.line().stroke("#d1322b").strokeWidth(3)))
    .add(Rule.create().elseFilter().add(Symbolizer.line().stroke("#888").strokeWidth(1)));
map.addStyle(roads);       // refused with a MapLoadException that names the problem if Mapnik objects
```

To check XML you did not write without keeping it:

```java
List<String> problems = MapnikMap.validate(xml, basePath);   // empty if fine
```

A `MapLoadException` also has `line()`, `style()`, `layer()` and `element()`, which a style editor can use
to point at the fault.

## Colour elevation data (no GDAL)

Read a GeoTIFF or ASCII grid with `RasterGrid`, or hold your own numbers in a `GrayImage`, and colour it
with a `RasterColorizer`:

```java
RasterGrid grid = RasterGrid.read(Paths.get("dem.tif"));
try (GrayImage dem = grid.toGrayImage()) {
    double[] range = grid.range();                                               // ignores no-data and NaN
    RasterColorizer ramp = RasterColorizer.create()
        .stop(range[0], "#2b83ba").stop((range[0] + range[1]) / 2, "#ffffbf").stop(range[1], "#d7191c");
    try (Image picture = dem.colorize(ramp, grid.noData().orElse(Double.NaN))) {
        Files.write(Paths.get("dem.png"), picture.toPng());
    }
}
```

Formats `RasterGrid` does not read need the GDAL input plugin: see [RASTER.md](RASTER.md).

## Labels and text

```java
Style labels = Style.create("labels").add(Rule.create().maxScaleDenominator(50000).add(
    Symbolizer.text("[name]").faceName("DejaVu Sans Book").fontSize(12).fill("black").halo("white", 2)));
```

Placement is controlled with typed helpers: `labelPlacement(Symbolizer.LabelPlacement.LINE)` for labels along
roads, `positions(NORTH_EAST, SOUTH_EAST, ...)` and `placementSizes(12, 10, 8)` to try other spots and smaller
sizes when a point label does not fit, `wrapWidth`, `avoidEdges`, `repeatDistance` and more.

`MapnikMap.addStyle` loads in strict mode, so an unknown font fails right there instead of drawing no
text. `Mapnik.fontFaces()` lists the faces Mapnik knows.

## Find out why nothing is drawn

1. Load strictly (`map.load(path, true)`) or use `MapnikMap.validate`.
2. Turn Mapnik's log up: `Logging.setSeverity(Logging.Severity.DEBUG)` (needs a Mapnik built with logging,
   as the prebuilt natives are; check `Mapnik.supports(Capability.LOGGING)`).
3. Check the layer: `layer.envelope()` against `map.extent()`, and the layer's `srs` against the data's.
4. Check scale: `map.scaleDenominator()` against the rules' min and max scale denominators.

## Vector tiles

Mapnik reads Mapnik Vector Tiles (MVT) from MBTiles, PMTiles and tile servers through its `tiles` input
plugin, which the prebuilt natives include:

```java
try (Datasource ds = Datasource.mbtiles(Paths.get("world.mbtiles"), "places");   // the tile layer to draw
     Layer layer = Layer.create("places", "epsg:3857")) {                         // tiles are Web Mercator
    layer.addStyle("s").setDatasource(ds);
    map.addLayer(layer);
}
```

`Datasource.pmtiles` does the same for a PMTiles archive, and `Datasource.tilesFromUrl("http://host/{z}/{x}/{y}.pbf", "layer")`
reads from a server, over HTTP or HTTPS. The tiles must come uncompressed (Mapnik does not unzip a tile fetched this way); servers do that unless you ask for gzip. HTTPS checks the server's certificate against the bundled list of trusted authorities, so a server with a private authority needs `Mapnik.setTrustedCertificates(Paths.get("ca.pem"))` first (or the `SSL_CERT_FILE` variable). Vector tiles need the layer name. The attributes of the tile features are available
to filters and labels. Mapnik cannot write vector tiles.
