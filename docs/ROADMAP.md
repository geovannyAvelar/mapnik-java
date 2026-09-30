# API coverage roadmap

Goal: wrap the important public parts of Mapnik, not every method. Internals (AGG, vertex
converters, the expression AST, parser grammars, renderer plumbing) are out of scope.

The design stays the same throughout: a hand-written `extern "C"` shim in `native/src/`, mapped with
JNA in `NativeApi`, and idiomatic Java classes on top. Each phase ships with integration tests that
check real output, such as pixels and XML.

## Phase 1: map, layers, datasources (done)

- **Map:** size, projection, background colour and image, buffer size, base path, maximum extent,
  aspect fix mode, zoom, pan, scale, current and buffered extent, XML load and save, style names,
  font registration, add and remove layers.
- **Layer:** name, projection, styles, active, queryable, scale range and visibility, opacity, label
  and feature caching, group-by, buffer size, maximum extent, datasource, envelope.
- **Datasource:** create from typed parameters, type, geometry type, extent, attribute fields.
- **Runtime:** version number, registered plugin names.

## Phase 2: features and queries

- Featureset iteration and `Feature`: id, attributes as Java types, geometry as WKT and GeoJSON.
- `Query`: bounding box, resolution, scale denominator, property names.
- `Datasource.featuresAtPoint`, `Datasource.features(query)`, `MapnikMap.queryPoint` and
  `queryMapPoint`.

## Phase 3: projections and geometry

- `Projection` and forward and backward transforms of points and boxes.
- Parse and write WKT, WKB and GeoJSON geometries.

## Phase 4: rendering

- Render options: scale factor, offset, image format options such as `png8` and `jpeg90`.
- `Image`: create, load, save, size, pixel access, compose.
- Cairo output (PDF, SVG, PostScript) and UTFGrid.

## Phase 5: styling in code

- `Style` and `Rule`, filters and expressions given as strings.
- Symbolizers: polygon, line, marker, point, text, raster, polygon and line pattern.
- Fontsets, registered font face names.

## Later

Logging severity, marker and image caches, extra parameters on maps, and anything users ask for.
