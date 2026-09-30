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

## Phase 5: styling in code (done)

- `Style`, `Rule` and `Symbolizer` builders, plus `FontSet`. They write Mapnik's own XML and load it
  through Mapnik's XML loader, so every symbolizer and attribute Mapnik supports is reachable.
  Typed helpers exist for the common attributes, and `attr(name, value)` sets any other.
- Symbolizers: polygon, line, markers, point, polygon pattern, line pattern, raster, building, dot,
  debug, text and shield.
- Rules: filter expressions, `elseFilter`, `alsoFilter`, scale ranges. Styles: opacity, compositing
  mode, filter mode, image filters.
- `MapnikMap.addStyle` loads in Mapnik's strict mode, which rejects misspelled attributes, attributes
  on the wrong kind of symbolizer, unknown fonts and missing files, and rolls the style back. Mapnik's
  plain loader would silently ignore all of those. `replaceStyle`, `hasStyle`, `addFontSet`.
- Why XML and not the C++ symbolizer objects: Mapnik's symbolizer properties are a large templated
  variant model with no string-based setter, and the XML loader is its supported way to set them.
- Not done: raster colorizer stops, group symbolizers, and text placement options that need nested
  XML elements. Use an XML style for those.

## Phase 6: gaps in the classes already wrapped

- `Layer`: blend mode (`comp_op`), child layers.
- `MapnikMap`: background image blend mode, extra parameters, font directory, pixel and map
  coordinate conversion (`view_transform`).
- `Mapnik.scaleDenominator(...)`.
- `Datasource`: layer name, encoding, parameters read back.
- `Color`: parse a colour string, read its components, format it, and accept it where strings are
  accepted today.

## Phase 7: runtime and diagnostics

- Compiled-in capabilities (Cairo, PNG, JPEG, WebP, TIFF, PROJ) and readable image formats.
- Logging: severity, format, per-object severity.
- Fonts: registered face names, register one font file, list font directories.
- Plugins: register one plugin file, list plugin directories. Clear the marker cache.

## Phase 8: image operations

- Filters (blur, sharpen, emboss, edge detect, invert, and the rest of Mapnik's list), compositing
  with Mapnik's blend modes, scaling with the different resampling methods, crop, colour to alpha,
  opacity, premultiply.
- `Image.probe`: size and format without decoding.

## Phase 9: rasters

- Single-band images (gray and float) in `Image`.
- Raster colorizer stops for the raster symbolizer, and `warp` to reproject a raster.

## Phase 10: geometry and feature building

- `Geometry` types built from coordinates in Java, with WKT, WKB and GeoJSON read and write
  (hand-written, so no dependency on Mapnik's static parser libraries).
- `Feature` builder and a memory datasource, so data can be rendered straight from Java.
- Algorithms: centroid, closest point, interior point, validity and simplicity checks, ring
  orientation fix, simplify, parallel offset. Reproject a geometry or feature.

## Phase 11: expressions

- `Expression`: parse and validate a filter, and evaluate it against a `Feature`. Path expressions.
- Parse transforms.

## Phase 12: more rendering

- Render a region with a buffer (`request`) and XYZ tile helpers.
- Render a single layer or a layer subset.
- UTFGrid output.

## Phase 13: richer styling

- Nested elements in symbolizers: text placements and formats, raster colorizer, group symbolizer.
- Read a style back as `Style`, `Rule` and `Symbolizer` objects.

## Phase 14: Java-implemented datasource

- A Mapnik datasource that calls back into Java for its features.

## Later

Collision detector queries, transliteration, and anything users ask for.
