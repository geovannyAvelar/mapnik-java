# mapnik-java

Java bindings for [Mapnik](https://mapnik.org), the C++ map rendering library.

Mapnik has no C API, so Java cannot call it directly. This project puts a small `extern "C"` shim in front of the Mapnik C++ API and binds the shim with [JNA](https://github.com/java-native-access/jna). It works on Java 8 and later and needs no code generation.

## How it works

```
Java (MapnikMap)  ->  JNA (NativeApi)  ->  libmapnik_c.so (C shim)  ->  Mapnik (C++)
```

- `native/` holds the C shim (`mapnik_c.h`, `mapnik_c.cpp`) and its CMake build. The shim catches every C++ exception and exposes the message through `mapnik_last_error()`, so no exception crosses the FFI boundary.
- `src/main/java/dev/avelar/mapnik/` holds the Java side:
  - `Mapnik`: global setup (version, datasource plugins, fonts).
  - `MapnikMap`: load a style, set the extent, render to a file or to bytes.
  - `NativeApi`: the raw JNA mapping of `mapnik_c.h`.
  - `MapnikException`: thrown when a native call fails.

## Requirements

- Mapnik 4.x with development files (`mapnik-config` on your `PATH`)
- Mapnik's own development dependencies (on Debian or Ubuntu, for example `libharfbuzz-dev` and `libcairo2-dev`)
- CMake 3.16 or later and a C++20 compiler
- JDK 8 or later (the Gradle wrapper is included)

## Build

```bash
cmake -S native -B build/cmake
cmake --build build/cmake
./gradlew build
```

The shared library is written to `build/native/libmapnik_c.so`. Gradle passes that directory to the tests through `jna.library.path`. Tests skip themselves when the library is missing.

## Installation

Releases of the Java jar are published to Maven Central. It does **not** contain the native shim, because the shim has to be compiled against the Mapnik installed on your machine. Build `libmapnik_c.so` as described above and make it available to JNA.

```kotlin
// Gradle
implementation("dev.avelar:mapnik-java:4.1.0.0")
```

```xml
<!-- Maven -->
<dependency>
  <groupId>dev.avelar</groupId>
  <artifactId>mapnik-java</artifactId>
  <version>4.1.0.0</version>
</dependency>
```

## Versioning

Releases follow Mapnik: `<mapnik version>.<wrapper revision>`.

- `4.1.0.0` is the first release for Mapnik 4.1.0. `4.1.0.1` is a wrapper-only fix for the same Mapnik.
- Use the release whose major.minor matches the Mapnik you have installed. The shim is compiled against your Mapnik, so a mismatch can fail to compile or behave differently.
- The wrapper logs a warning the first time you create a `MapnikMap` if the loaded Mapnik differs in major.minor from the one the release was built for. `Mapnik.expectedVersion()`, `Mapnik.version()` and `Mapnik.isCompatible()` expose the same check.
- The CMake build prints a warning for the same mismatch.
- A weekly workflow checks for new Mapnik releases and opens an issue with an upgrade checklist.

## Usage

```java
Mapnik.registerDatasources("/usr/local/lib/mapnik/input");
Mapnik.registerFonts("/usr/local/lib/mapnik/fonts");

try (MapnikMap map = new MapnikMap(1024, 768)) {
    map.load(Paths.get("style.xml")).zoomAll();

    map.renderToFile(Paths.get("out.png"), "png");
    byte[] png = map.renderToPng();
}
```

To use the library from your own application, put `libmapnik_c.so` on the JNA search path:

```bash
java -Djna.library.path=build/native -cp ... YourApp
```

Use `mapnik-config --input-plugins` and `mapnik-config --fonts` to find the plugin and font directories on your system.

## Examples

- `examples/render-demo`: a small app that depends on mapnik-java and renders a map to a PNG.
- `examples/wms-server`: a minimal WMS 1.1.1 and 1.3.0 server (GetCapabilities and GetMap) built on the wrapper.

Both have their own README.

## Testing

```bash
./gradlew test             # unit tests; skip if libmapnik_c is missing
./gradlew integrationTest  # renders real data through Mapnik; fails if Mapnik is missing
```

Build the shim first. Integration tests register the input plugins from `mapnik-config --input-plugins`, or from the `MAPNIK_INPUT_PLUGINS` environment variable if set. They cover rendering a GeoJSON polygon and checking pixels, output formats, zoom and resize, error handling, recovery after a failed render, and concurrent rendering with one map per thread.

The `Integration` workflow builds the targeted Mapnik version from source (cached per version), then runs both suites.

## Notes

- `MapnikMap` is not thread-safe. Use one instance per thread, or a pool.
- Map memory is native and not garbage collected. Always call `close()`, ideally with try-with-resources.
- Call `registerDatasources` and `registerFonts` once at startup, before you load a style that needs them.
- Only the subset of the Mapnik API needed to load a style and render it is wrapped so far. Adding more means adding a function to `mapnik_c.h`, then the matching lines in `NativeApi` and `MapnikMap`.

## Layout

```
native/                       C shim and CMake build
src/main/java/dev/avelar/mapnik   Java wrapper
src/test/java/dev/avelar/mapnik   JUnit tests
build.gradle.kts              Gradle build and publishing
```

## License

MIT. See [LICENSE](LICENSE). Mapnik itself is licensed separately under the LGPL.
