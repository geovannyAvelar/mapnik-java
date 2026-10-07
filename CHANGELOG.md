# Changelog

Versions follow Mapnik: `<mapnik version>.<wrapper revision>`.

## Unreleased (4.3.2.0)

First release, for Mapnik 4.3.2.

- Maps, layers, datasources (file, in-memory, implemented in Java), features and geometry, projections and coordinate transforms.
- Rendering to PNG, JPEG, WebP, TIFF and Cairo formats, image operations, UTFGrid, tile maths.
- Styles, rules and symbolizers built in code or read back from XML, text formats, raster colorizers.
- Expressions, path expressions, logging control, capabilities.
- Prebuilt natives for Linux x86_64 and aarch64 (glibc 2.39 or later) and macOS Apple Silicon and Intel, self-contained: `mapnik-java-natives-linux-x86_64`, `-linux-aarch64`, `-macos-aarch64`, `-macos-x86_64`.
- Leak detection for native handles that are garbage collected without `close()`.
- `MapPool`, a fixed set of maps shared by many threads.
- `MapLoadException` with the line, style, layer and element of a style problem, and `MapnikMap.validate` to check a style without keeping it.
- `GrayImage`: single-band images (8 to 64-bit integers, 32 and 64-bit floats) coloured with a `RasterColorizer`, no GDAL needed.
- JMH benchmarks in `benchmarks/`, and guides: cookbook, performance, raster and GDAL, upgrading, GraalVM notes.
- `Automatic-Module-Name: dev.avelar.mapnik`. The minimum Java is 8.
