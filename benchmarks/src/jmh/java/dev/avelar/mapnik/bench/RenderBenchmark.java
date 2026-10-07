package dev.avelar.mapnik.bench;

import dev.avelar.mapnik.Image;
import dev.avelar.mapnik.MapnikMap;
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

import java.util.concurrent.TimeUnit;

/** Drawing one 256 by 256 tile of squares held in a memory datasource. One map per thread. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class RenderBenchmark {
    @Param({"100", "1000", "10000"})
    public int features;

    private MemoryDatasource ds;
    private MapnikMap map;

    @Setup
    public void setUp() {
        ds = MemoryDatasource.create();
        ds.addAll(Data.squares(features));
        map = Data.map(ds);
    }

    @TearDown
    public void tearDown() {
        map.close();
        ds.close();
    }

    @Benchmark
    public Image renderToImage() {
        try (Image img = map.renderToImage()) {
            return img;
        }
    }

    @Benchmark
    public byte[] renderToPng() {
        return map.renderToPng();
    }

    @Benchmark
    public byte[] renderToJpeg() {
        return map.renderToBytes("jpeg");
    }
}
