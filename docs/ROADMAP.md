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

## Phase 6: gaps in the classes already wrapped (done)

- `Layer`: blend mode, child layers. `MapnikMap`: background image blend mode, extra parameters, font
  directory, pixel and map coordinate conversion. `Mapnik.scaleDenominator`.
- `Datasource`: layer name, encoding, parameters read back.
- `Color`: parse, components, formatting, and accepted wherever a colour string is.

## Phase 7: runtime and diagnostics (done)

- `Capability` and `Mapnik.supports`. `Logging`: severity, per-object severity, format, file or console.
- Fonts: list face names, find a face's file, register one font file. Plugins: register one file,
  check a plugin, list directories. `Mapnik.clearCaches`.

## Phase 8: image operations (done)

- `Image.filter` with `ImageFilters`, `composite` with `BlendMode`, `scaled` with `ScalingMethod`,
  `crop`, `copy`, `applyOpacity`, `colorToAlpha`, and `Image.probe` for size and format without decoding.
- Premultiplication is handled around each Mapnik call, so `Image` is always straight alpha.

## Phase 9: rasters (done, narrowed)

- `RasterColorizer` with stops and modes, through nested elements in `Symbolizer`. `Image.warp`.
- Not done: single-band (gray and float) pixel types in `Image`. `Image` stays 8-bit RGBA. A
  single-band raster still colours correctly when a datasource such as GDAL supplies it to the
  renderer, which is how the colorizer tests work.

## Phase 10: geometry and features (done)

- `Geometry` types built from coordinates, with hand-written WKT, WKB and GeoJSON readers and writers,
  so parsing no longer depends on Mapnik's static libraries.
- `Feature.create`, GeoJSON features and collections, and `MemoryDatasource` to render data that is
  not in a file.
- Algorithms: centroid, interior point, closest point, validity, simple test, winding correction,
  simplification (four algorithms), line offset, and reprojection of geometries and features.

## Phase 11: expressions (done, narrowed)

- `Expression`: parse and check, evaluate against a `Feature` with variables, `matches` for filters.
  `PathExpression` and `Transforms.check`.
- Not done: Mapnik's own normalised spelling of an expression or transform. That function's signature
  contains an ICU type, so it only links when Mapnik's headers and library use the same ICU version.

## Phase 12: more rendering (done)

- `Tiles` for the XYZ grid, `MapnikMap.renderTile` with a metatile buffer that restores the map
  afterwards, `renderTileToBytes`, `renderLayers`.
- `renderGrid` and `UtfGrid`: UTFGrid output with keys by id or attribute, chosen fields and a
  resolution, and a decoder that reads cells by pixel.

## Phase 13: richer styling (done)

- Nested symbolizer elements: `TextFormat` runs (mixed fonts, sizes and colours in one label),
  `TextPlacement` alternatives, placement positions, and group symbolizers with `GroupRule`, layouts
  and the column range and key Mapnik needs before it draws a group.
- Read styles back: `MapnikMap.styles()`, `style(name)` and `Style.fromXml` give `Style`, `Rule` and
  `Symbolizer` objects with getters. A SAX reader keeps attribute order and refuses DTDs. What Mapnik
  writes is accepted again by the strict parser and draws the same.

## Phase 14: datasource implemented in Java (done)

- `JavaDatasource` with a `FeatureSource` that Mapnik calls while it renders or queries, given the
  area, resolution and scale. Optional declared fields, an envelope you can update, errors in the
  source reported as `MapnikException`, and safe for several maps rendering at once.
- One static pair of native handlers and an id registry keep the source reachable for as long as
  Mapnik uses the datasource, and a release callback lets go of it afterwards.

## Prebuilt natives (done: Linux x86_64 and aarch64, macOS aarch64 and x86_64)

- A Docker pipeline builds Mapnik, the shim, its libraries, plugins, fonts and PROJ data into one
  bundle, with a MANIFEST of sizes and SHA-256 sums, licences and a NOTICE.
- Published as `mapnik-java-natives-linux-x86_64` and `-linux-aarch64`, each built and tested on a native runner. `NativeLoader` unpacks it once into a private cache,
  verifies every file and points PROJ at the bundled data.
- The integration tests run against it in a clean Ubuntu 24.04 container (`scripts/test-natives-linux.sh`).
- Left out: gdal, ogr, postgis and pgraster plugins. Needs glibc 2.39 or later.
- macOS is built on Mac runners with Homebrew libraries (`natives/macos/build.sh`, `bundle.py`): install names are rewritten to `@loader_path`, files are signed ad hoc, and a second fresh runner runs the integration tests.
- Not done: Windows, a smaller bundle (libproj pulls in curl and gnutls).

## Later

Collision detector queries, transliteration, raster colorizer and text placement options not covered by a builder (use `Symbolizer.child` with raw XML), and anything users ask for.
