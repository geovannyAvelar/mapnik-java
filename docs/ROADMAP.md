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

## Phase 2: features and queries (done)

- `Featureset` (iterate once, close) and `Feature`: id, typed attributes (`Boolean`, `Long`,
  `Double`, `String`, null), geometry kind, envelope, geometry as WKT and GeoJSON, whole feature as
  GeoJSON. Features are plain snapshots with nothing to close.
- `FeatureQuery`: bounding box, resolution, scale denominator, property names.
- `Datasource.features(...)`, `Datasource.featuresAtPoint(...)`, `MapnikMap.queryPoint(...)` and
  `queryMapPoint(...)`.
- WKT and GeoJSON are written by the shim itself. Mapnik's own writers live in static libraries that
  are tied to the ICU version Mapnik was built with.

## Phase 3: projections (done)

- `Projection`: definition, description, geographic or not, area of use, and conversion between
  longitude/latitude and projected coordinates.
- `CoordinateTransform`: points and boxes between any two projections, in both directions, with
  optional edge sampling for boxes.
- `Point2d`. Points that cannot be projected, such as the far side of an orthographic projection,
  raise `MapnikException` instead of returning infinity.
- Not done: parsing WKT, WKB and GeoJSON into Mapnik geometries. Mapnik's parsers are in static
  libraries tied to the ICU version Mapnik was built with, so they are not safe to link into the
  shim. Geometry output (WKT and GeoJSON) is done, in phase 2.

## Phase 4: rendering (done)

- `RenderOptions`: scale factor (line widths, symbols and text) and offset (the image as a window
  onto a larger map, for tiling).
- Formats: `png`, `png8`, `png256`, `jpeg`, `jpeg90`, `webp`, `tiff`, `png:z=9` and the other format
  strings Mapnik understands, plus `pdf`, `svg` and `ps` through Cairo.
- `Image`: create, load from a file or bytes, fill, pixels as ARGB, encode, save. Straight alpha.
- `MapnikMap.renderToImage()` and `render(Image)`, which blends over what is already in the image.
- `Mapnik.hasCairo()`.
- Not done: UTFGrid output, which needs a grid renderer and its own encoder. Ask if you need it.

## Phase 5: styling in code

- `Style` and `Rule`, filters and expressions given as strings.
- Symbolizers: polygon, line, marker, point, text, raster, polygon and line pattern.
- Fontsets, registered font face names.

## Later

Logging severity, marker and image caches, extra parameters on maps, and anything users ask for.
