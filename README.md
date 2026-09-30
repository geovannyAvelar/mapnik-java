# mapnik-java

Java bindings for [Mapnik](https://mapnik.org), the C++ map rendering library.

Mapnik has no C API, so Java cannot call it directly. This project puts a small `extern "C"` shim in front of the Mapnik C++ API and binds the shim with [JNA](https://github.com/java-native-access/jna). It works on Java 17 and later and needs no code generation.

## How it works

```
Java (MapnikMap)  ->  JNA (NativeApi)  ->  libmapnik_c.so (C shim)  ->  Mapnik (C++)
```

- `native/` holds the C shim (`mapnik_c.h`, `mapnik_c.cpp`) and its CMake build. The shim catches every C++ exception and exposes the message through `mapnik_last_error()`, so no exception crosses the FFI boundary.
- `src/main/java/org/mapnik/` holds the Java side:
  - `Mapnik`: global setup (version, datasource plugins, fonts).
  - `MapnikMap`: load a style, set the extent, render to a file or to bytes.
  - `NativeApi`: the raw JNA mapping of `mapnik_c.h`.
  - `MapnikException`: thrown when a native call fails.

## Requirements

- Mapnik 4.x with development files (`mapnik-config` on your `PATH`)
- Mapnik's own development dependencies (on Debian or Ubuntu, for example `libharfbuzz-dev` and `libcairo2-dev`)
- CMake 3.16 or later and a C++20 compiler
- JDK 17 or later and Maven

## Build

```bash
cmake -S native -B target/cmake
cmake --build target/cmake
mvn test
```

The shared library is written to `target/native/libmapnik_c.so`. Maven passes that directory to the tests through `jna.library.path`.

## Usage

```java
Mapnik.registerDatasources("/usr/local/lib/mapnik/input");
Mapnik.registerFonts("/usr/local/lib/mapnik/fonts");

try (MapnikMap map = new MapnikMap(1024, 768)) {
    map.load(Path.of("style.xml")).zoomAll();

    map.renderToFile(Path.of("out.png"), "png");
    byte[] png = map.renderToPng();
}
```

To use the library from your own application, put `libmapnik_c.so` on the JNA search path:

```bash
java -Djna.library.path=target/native -cp ... YourApp
```

Use `mapnik-config --input-plugins` and `mapnik-config --fonts` to find the plugin and font directories on your system.

## Notes

- `MapnikMap` is not thread-safe. Use one instance per thread, or a pool.
- Map memory is native and not garbage collected. Always call `close()`, ideally with try-with-resources.
- Call `registerDatasources` and `registerFonts` once at startup, before you load a style that needs them.
- Only the subset of the Mapnik API needed to load a style and render it is wrapped so far. Adding more means adding a function to `mapnik_c.h`, then the matching lines in `NativeApi` and `MapnikMap`.

## Layout

```
native/                  C shim and CMake build
src/main/java/org/mapnik Java wrapper
src/test/java/org/mapnik JUnit tests
pom.xml                  Maven build
```

## License

MIT. See [LICENSE](LICENSE). Mapnik itself is licensed separately under the LGPL.
