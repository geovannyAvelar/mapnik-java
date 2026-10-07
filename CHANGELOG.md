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
