package dev.avelar.mapnik.bench;

import dev.avelar.mapnik.MapPool;
import dev.avelar.mapnik.MemoryDatasource;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;

import java.util.concurrent.TimeUnit;

/**
 * Tiles per second when many request threads share a {@link MapPool}. Run with different {@code -t}
 * (threads) values to see the scaling, for example {@code ./gradlew jmh -Pjmh.threads=8}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(4)   // override with -Pjmh.threads=N
public class PoolBenchmark {
    @Param({"1", "4"})
    public int poolSize;

    private MemoryDatasource ds;
    private MapPool pool;

    @Setup
    public void setUp() {
        ds = MemoryDatasource.create();
        ds.addAll(Data.squares(1000));
        // The datasource is shared by every map: Mapnik allows it, and a memory datasource only reads.
        pool = new MapPool(poolSize, 256, 256, map -> {
            map.setSrs(Data.SRS).setBackground("white");
            map.addStyle(Data.style());
            try (dev.avelar.mapnik.Layer layer = dev.avelar.mapnik.Layer.create("data", Data.SRS)) {
                layer.addStyle("s").setDatasource(ds);
                map.addLayer(layer);
            }
            map.zoomToBox(Data.WORLD);
        });
    }

    @TearDown
    public void tearDown() {
        pool.close();
        ds.close();
    }

    @Benchmark
    public byte[] tile() {
        return pool.withMap(map -> map.renderToPng());
    }
}
