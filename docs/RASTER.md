# Raster data, elevation and GDAL

## What works out of the box

The prebuilt natives include Mapnik's `raster` input plugin and no GDAL. That gives you:

- **Georeferenced images.** The `raster` plugin draws an image file (PNG, JPEG, TIFF or WebP) at the
  extent you give in the datasource parameters (`lox`, `loy`, `hix`, `hiy`). Use it for scanned maps
  and pre-rendered imagery.
- **Elevation and other single-band data you already hold as numbers.** Put it in a `GrayImage`
  (8, 16, 32 and 64-bit integers, or 32 and 64-bit floats), colour it with a `RasterColorizer` and you
  get an RGBA `Image`. No GDAL is involved. See the elevation recipe in [COOKBOOK.md](COOKBOOK.md).
- **Image operations** on the result: scaling, warping to another projection, compositing, filters.

## What needs GDAL

- Reading **GeoTIFF**, DEM, ASCII grid and other raster formats directly, with their georeferencing,
  nodata values and band layout.
- Reading vector formats through OGR (GeoPackage, file geodatabases and so on). The `shape`, `geojson`,
  `csv`, `topojson`, `geobuf` and `sqlite` plugins are bundled and need no GDAL.

The GDAL and OGR plugins are not bundled because they pull in a very large tree of libraries (the
bundle would grow by hundreds of megabytes, and GDAL needs many optional drivers). A natives artifact
with GDAL is not published.

## Using GDAL anyway

Build the shim against a Mapnik of your own that has the plugins, as in [Your own
Mapnik](../README.md#your-own-mapnik), then register its plugin directory:

```java
Mapnik.registerDatasources("/usr/lib/mapnik/input");   // contains gdal.input
```

```java
Map<String, Object> params = new HashMap<>();
params.put("type", "gdal");
params.put("file", "/data/dem.tif");
params.put("band", 1);
try (Datasource ds = Datasource.create(params);
     Layer layer = Layer.create("dem", "epsg:4326")) {
    layer.setDatasource(ds).addStyle("dem");
    // a "dem" style with a RasterSymbolizer and a RasterColorizer
}
```

`Symbolizer.raster().colorizer(RasterColorizer)` colours a band by value when drawing a map.

## Reading a GeoTIFF without GDAL

`Image.load(Path)` reads a TIFF that Mapnik's reader supports, typically 8-bit RGB or RGBA, as RGBA.
For elevation in 16-bit or floating point, read the values with a library you already use (for example
the JDK's ImageIO with a TIFF plugin, or a GeoTIFF reader), fill a `GrayImage` with
`GrayImage.of(...)` and draw it with the colorizer. This keeps the native dependencies small.
