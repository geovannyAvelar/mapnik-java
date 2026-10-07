package dev.avelar.mapnik.bench;

import dev.avelar.mapnik.Image;
import dev.avelar.mapnik.Mapnik;
import dev.avelar.mapnik.Projection;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.util.concurrent.TimeUnit;

/** What one trip into the native library costs, for calls that do almost nothing there. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class CallBenchmark {
    private Image image;
    private Projection projection;

    @Setup
    public void setUp() {
        image = Image.create(64, 64);
        projection = Projection.of(Data.SRS);
    }

    @TearDown
    public void tearDown() {
        image.close();
        projection.close();
    }

    /** One JNA call that reads a pixel. */
    @Benchmark
    public int readOnePixel() {
        return image.getArgb(10, 10);
    }

    /** One JNA call that returns a string. */
    @Benchmark
    public String versionString() {
        return Mapnik.version();
    }

    /** A call that crosses the boundary and also builds a Java object from the result. */
    @Benchmark
    public boolean isGeographic() {
        return projection.isGeographic();
    }
}
