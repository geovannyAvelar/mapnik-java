# Benchmarks

JMH benchmarks for mapnik-java: the cost of a JNA call, rendering a tile, a Java-implemented
datasource, and many threads sharing a `MapPool`.

```bash
cd benchmarks
./gradlew jmh                          # everything, a few minutes
./gradlew jmh -Pjmh.includes=Render    # only benchmarks whose name matches
```

It uses the native bundle in `../build/natives/<platform>` when there is one (build it with
`scripts/build-natives-linux.sh`), else `../build/native/libmapnik_c`. Maps use `+proj=longlat`, so no PROJ data is needed. Results go to `build/results/jmh/results.json`.

Numbers depend heavily on the machine, so compare runs on the same one. The figures in the main
README were measured as described in `docs/PERFORMANCE.md`.
