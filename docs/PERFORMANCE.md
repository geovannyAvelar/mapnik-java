# Performance

Measured with the JMH benchmarks in [`benchmarks/`](../benchmarks) (`./gradlew jmh` there): JDK 21,
Linux x86_64, the prebuilt 4.3.2 natives, one fork, 3 warm-up and 5 measured iterations. They are
indicative: run them on your own hardware before drawing conclusions, and compare only runs made on the
same machine.

## What a call into the native library costs

| Call | Time per call |
| --- | --- |
| `Mapnik.version()` (returns a string) | about 0.2 µs |
| `Projection.isGeographic()` | about 0.3 µs |
| `Image.getArgb(x, y)` (one pixel) | about 0.6 µs (it was 1.2 µs while it re-read the image size on every call) |

Crossing the JNA boundary is a fraction of a microsecond to about a microsecond. Anything that does real
work in Mapnik, such as drawing, takes thousands of times longer, so the overhead only matters if you
call per pixel or per feature. Read pixels in bulk (`Image.toArgb`, `GrayImage.read`) rather than one
at a time.

## Drawing one 256 by 256 tile

Squares in a `MemoryDatasource`, a polygon fill and a line stroke, one map per thread, per tile:

| Features | To an `Image` | To JPEG | To PNG |
| --- | --- | --- | --- |
| 100 | 0.41 ms | 0.56 ms | 1.7 ms |
| 1 000 | 1.8 ms | 2.0 ms | 3.8 ms |
| 10 000 | 16 ms | 16 ms | 20 ms |

PNG encoding is the dominant extra cost for sparse tiles (about 1.3 ms here). If your tiles are mostly
empty, check `Image.isSolid()` and serve a cached blank tile instead.

## A datasource written in Java

The same tile with the features coming from a `JavaDatasource`:

| Features | Memory datasource | Java datasource | Extra |
| --- | --- | --- | --- |
| 100 | 0.41 ms | 0.49 ms | +20 % |
| 1 000 | 1.8 ms | 2.7 ms | +50 % |
| 10 000 | 16 ms | 25 ms | +50 % |

Each feature crosses from Java back into Mapnik during the render, which costs about 0.9 µs per
feature on top of drawing. That is cheap next to a database query. If the data is static and fits in
memory, a `MemoryDatasource` is faster; if you filter by the request area, a `FeatureSource` that
returns only what touches it usually wins by far.

## Many threads

Mapnik can render on several maps at once, one map per thread, so throughput grows with the number of
maps up to the number of cores. Share the maps with a `MapPool` sized to your core count.

`PoolBenchmark` draws a 256 by 256 tile of 1000 squares from a pool of N maps with N threads. Measured on an
Intel Core i5-9300H (4 cores, 8 threads), Linux x86_64, JDK 21, the prebuilt natives, 3 forks of 3 warm-up and
8 measured iterations each, with the machine otherwise idle:

| Pool size = threads | Tiles per second | Compared with one thread |
| --- | --- | --- |
| 1 | 217 ± 21 | 1.0x |
| 2 | 398 ± 42 | 1.8x |
| 4 | 805 ± 56 | 3.7x |
| 8 | 917 ± 32 | 4.2x |

Throughput scales almost linearly up to the number of physical cores. The 4 extra hardware threads of
hyper-threading add about 14 percent more, so a pool of the number of cores, or a little more, is the right size.
Beyond that, more maps only cost memory. Reproduce it with:

```bash
cd benchmarks && ./gradlew jmhJar
for n in 1 2 4 8; do
  java -Dmapnik.native.dir=../build/natives/linux-x86_64/lib -jar build/libs/mapnik-java-benchmarks-jmh.jar \
    PoolBenchmark -t $n -p poolSize=$n -wi 3 -i 8 -w 1s -r 3s -f 3
done
```

A laptop that is busy or hot gives much wider error bars (a first run with 5 iterations on one fork varied by
more than 50 percent), so run it on a quiet machine and look at the error column.

## Tips

- Keep one `MapnikMap` per pool slot and reuse it: loading a style is far more expensive than drawing.
- Prefer `renderToImage` plus your own encoder only if you need pixels; `renderToBytes("png")` is what
  Mapnik is tuned for. For PNG, `png8` and a palette like `png256` are smaller and often faster.
- Use `Layer.setMinimumScaleDenominator` and filters in styles so Mapnik skips work for data you would
  not see.
- Close what you create (`Image`, `Layer`, `Datasource`...). A leak shows as native memory growing while
  the Java heap stays flat: see `Mapnik.leakedHandles()`.
