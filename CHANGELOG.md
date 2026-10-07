# Changelog

Versions follow Mapnik: `<mapnik version>.<wrapper revision>`.

## Unreleased (4.1.0.0)

First release, for Mapnik 4.1.0.

- Maps, layers, datasources (file, in-memory, implemented in Java), features and geometry, projections and coordinate transforms.
- Rendering to PNG, JPEG, WebP, TIFF and Cairo formats, image operations, UTFGrid, tile maths.
- Styles, rules and symbolizers built in code or read back from XML, text formats, raster colorizers.
- Expressions, path expressions, logging control, capabilities.
- Prebuilt natives for Linux x86_64 (`mapnik-java-natives-linux-x86_64`), self-contained, needs glibc 2.39 or later.
- Leak detection for native handles that are garbage collected without `close()`.
