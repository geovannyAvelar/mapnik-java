# Contributing

- Build the shim: `cmake -S native -B build/cmake && cmake --build build/cmake` (needs Mapnik installed).
- Unit tests: `./gradlew test`. Integration tests: `./gradlew integrationTest`, or against the bundle with `-PbundledNatives`.
- Adding a native function: declare it in `native/mapnik_c.h`, implement it in `native/src`, add the matching line to `NativeApi`, then the Java method. `NativeApiConsistencyTest` fails if the two sides differ.
- Wrap every native call that can throw in `guarded()` in the shim, and free every handle you create.
- Close native handles in Java, in tests too. The suite runs with leak detection and should report none.
- Use Conventional Commits (`feat:`, `fix:`, `docs:`).
