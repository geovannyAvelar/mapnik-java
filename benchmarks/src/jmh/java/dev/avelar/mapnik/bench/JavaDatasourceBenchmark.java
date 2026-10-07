package dev.avelar.mapnik.bench;

import dev.avelar.mapnik.Feature;
import dev.avelar.mapnik.Image;
import dev.avelar.mapnik.JavaDatasource;
import dev.avelar.mapnik.MapnikMap;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The same tile as {@link RenderBenchmark}, with the features coming from a Java {@code FeatureSource}
 * instead. The difference between the two is the price of a datasource implemented in Java: each
 * feature crosses back from Java into Mapnik during the render.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class JavaDatasourceBenchmark {
    @Param({"100", "1000", "10000"})
    public int features;

    private JavaDatasource ds;
    private MapnikMap map;

    @Setup
    public void setUp() {
        List<Feature> all = Data.squares(features);
        ds = JavaDatasource.create(request -> all, Data.WORLD);
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
}
