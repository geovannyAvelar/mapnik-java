# Upgrading

## Which version to use

Versions are `<Mapnik version>.<wrapper revision>`, for example `4.3.2.0`: Mapnik 4.3.2, first revision
of the wrapper. The first three numbers say which Mapnik the library was built and tested against.

- **Wrapper revision up** (`4.3.2.0` to `4.3.2.1`): fixes and additions for the same Mapnik. Upgrade
  freely.
- **Mapnik version up** (`4.3.2.x` to `4.4.0.0`): a newer Mapnik. Read the notes for that release below.
- The natives artifacts (`mapnik-java-natives-<platform>`) carry the same version as the library. Keep
  them equal: a library and natives of different versions can disagree about the native API.

If you use your own Mapnik instead of the natives artifact, use the release whose major and minor match
the Mapnik you have installed, and rebuild the shim against it.

## Checklist for a Mapnik upgrade

1. Change `mapnik.version` in `gradle.properties` and reset `wrapper.revision` to 0.
2. Rebuild the shim against the new Mapnik (`cmake -S native -B build/cmake`). Compile errors point to
   API changes; fix them in `native/src` (the Java API rarely needs to change).
3. Run `./gradlew test integrationTest`. `NativeApiConsistencyTest` fails if the header and the Java
   mapping disagree.
4. Check the build requirements of the new Mapnik (CMake, Boost, ICU, HarfBuzz, PROJ versions) and
   update `natives/linux/in-container.sh`, `natives/macos/build.sh`, `natives/windows/build.sh` and `.github/workflows/integration.yml`.
5. Build and test the natives for every platform (`natives.yml`).
6. Add a line to `CHANGELOG.md` and tag when everything is green.

## Release notes

### 4.3.2.0 (first release)

For people who built the shim against an older Mapnik by hand before the first release:

- Mapnik 4.3 needs CMake 3.30 or later, and `libavif` and Boost's `url`, `context` and `json` libraries
  when the `tiles` input plugin is built.
- Mapnik 4.3 renamed some CMake options. The GDAL, OGR, PostGIS and pgraster plugins are now also
  switched through `USE_PLUGIN_INPUT_GDAL_OGR` and `USE_PLUGIN_INPUT_POSTGIS_PGRASTER`, which default
  to on.
- `Mapnik.datasourcePluginDirectories()` returns the directories joined with `:`, because Mapnik 4.3
  returns a list.
- The `datasource_cache::plugin_directories()` change is the only API difference the shim needed.

## Moving between ways of getting Mapnik

- **From your own Mapnik to the natives artifact:** add `mapnik-java-natives-<platform>` and remove
  `-Djna.library.path` and any `registerDatasources`/`registerFonts` calls for the bundled plugins and
  fonts. If `jna.library.path` still contains a `libmapnik_c`, it wins over the artifact (see
  [Installation](../README.md#installation)).
- **From the natives artifact to your own Mapnik:** remove the artifact and set `-Dmapnik.native.dir` or
  `-Djna.library.path`, then register plugins and fonts yourself.
