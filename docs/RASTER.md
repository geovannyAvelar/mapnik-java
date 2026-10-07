# Raster data, elevation and GDAL

## What works out of the box

The prebuilt natives include Mapnik's `raster` input plugin and no GDAL. That gives you:

- **Georeferenced images.** The `raster` plugin draws an image file (PNG, JPEG, TIFF or WebP) at the
  extent you give in the datasource parameters (`lox`, `loy`, `hix`, `hiy`). Use it for scanned maps
  and pre-rendered imagery.
- **GeoTIFF and ASCII grids.** `RasterGrid` reads them in pure Java (see below).
- **Elevation and other single-band data you already hold as numbers.** Put it in a `GrayImage`
  (8, 16, 32 and 64-bit integers, or 32 and 64-bit floats), colour it with a `RasterColorizer` and you
  get an RGBA `Image`. No GDAL is involved. See the elevation recipe in [COOKBOOK.md](COOKBOOK.md).
- **Image operations** on the result: scaling, warping to another projection, compositing, filters.

## What needs GDAL

- Reading raster formats other than GeoTIFF and ASCII grid, files over 100 million pixels, BigTIFF, JPEG-compressed
  TIFF, and bands other than the first.
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

## Reading GeoTIFF and ASCII grids without GDAL

`RasterGrid` reads them in pure Java:

```java
RasterGrid dem = RasterGrid.read(Paths.get("dem.tif"));     // or .asc
dem.extent();        // Box2d in the file's projection, from the GeoTIFF tags
dem.srs();           // "epsg:4326", or null if the file has none
dem.noData();        // OptionalDouble
try (GrayImage g = dem.toGrayImage()) {                     // the file's own pixel type
    Image picture = ramp.colorize(g);
}
```

Supported: classic TIFF (not BigTIFF), strips or tiles, 8, 16, 32 and 64-bit samples (unsigned, signed,
floating point), compression none, deflate, LZW and PackBits, the horizontal and floating-point
predictors, and the first band. The extent and EPSG code come from the GeoTIFF tags. Esri ASCII grids
(`.asc`) are read too. Anything else (JPEG or other compression, BigTIFF, rotated grids) is refused with
an `IllegalArgumentException` that says why, and damaged files do the same, never anything worse.

A grid is limited to 100 million pixels, because it holds `double`s. For larger files, or other
formats, use the GDAL plugin as above.
