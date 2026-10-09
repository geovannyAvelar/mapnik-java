# Changelog

Versions follow Mapnik: `<mapnik version>.<wrapper revision>`.

## 4.3.2.0 (2026-10-09)

First release, for Mapnik 4.3.2.

- Maps, layers, datasources (file, in-memory, implemented in Java), features and geometry, projections and coordinate transforms.
- Rendering to PNG, JPEG, WebP, TIFF and Cairo formats, image operations, UTFGrid, tile maths.
- Styles, rules and symbolizers built in code or read back from XML, text formats, raster colorizers.
- Expressions, path expressions, logging control, capabilities.
- Prebuilt natives for Linux x86_64 and aarch64 (glibc 2.28 or later: RHEL and AlmaLinux 8, Ubuntu 20.04, Debian 10 and newer), macOS Apple Silicon and Intel, and Windows x86_64, self-contained: `mapnik-java-natives-linux-x86_64`, `-linux-aarch64`, `-macos-aarch64`, `-macos-x86_64`, `-windows-x86_64`.
- Every natives jar's `NOTICE` names the source of each library inside it, how to rebuild the bundle, how to replace a library, and a written offer of the source; the release check fails without it.
- Leak detection for native handles that are garbage collected without `close()`.
- `MapPool`, a fixed set of maps shared by many threads, with a builder (warm-up, maximum wait) and `stats()` (borrows, waiting, timeouts, wait times).
- `MapLoadException` with the line, style, layer and element of a style problem, and `MapnikMap.validate` to check a style without keeping it.
- `RasterGrid`: reads GeoTIFF and Esri ASCII grids in pure Java (strips, tiles, deflate, LZW, PackBits, predictors, 8 to 64-bit samples, extent and EPSG from the tags), tested against files written by GDAL.
- Optional PostGIS natives add-on for Linux (`mapnik-java-natives-linux-x86_64-postgis`, `-linux-aarch64-postgis`), merged into the main bundle at run time.
- Vector tiles: `Datasource.mbtiles`, `pmtiles` and `tilesFromUrl` (HTTP and HTTPS), reading MVT through the `tiles` input plugin. HTTPS checks the server's certificate: Mapnik is patched to do it, and a list of trusted authorities is bundled (`Mapnik.setTrustedCertificates` to use another).
- `Mapnik.enableProjNetwork` lets PROJ download datum-shift grids on demand.
- `ImageFormat`: build PNG, PNG8 (palette size, quantizer, transparency, gamma), JPEG, WebP and TIFF format strings in code, with every value checked.
- `Marker.inspect`: the kind and size of an SVG or image marker file, with strict parsing to validate one.
- The GeoJSON, WKT and WKB readers refuse absurdly deep nesting instead of overflowing the stack (found by fuzz tests).
- Typed text placement helpers on `Symbolizer`: label placement, positions with fallback sizes, wrapping, alignment, line labels.
- Tests for non-ASCII folder names (styles, data, markers, the unpacked natives) and a soak test for native memory growth.
- `dev.avelar:mapnik-java-jts`: converts geometries to and from JTS (and so GeoTools).
- `Image.toBufferedImage()` and `Image.fromBufferedImage`.
- `examples/tile-server`: an XYZ tile server with metatiles, caching and rendering off the HTTP threads.
- `GrayImage`: single-band images (8 to 64-bit integers, 32 and 64-bit floats) coloured with a `RasterColorizer`, no GDAL needed.
- JMH benchmarks in `benchmarks/`, and guides: cookbook, performance, raster and GDAL, upgrading, GraalVM notes.
- `Automatic-Module-Name: dev.avelar.mapnik`. The minimum Java is 8.
