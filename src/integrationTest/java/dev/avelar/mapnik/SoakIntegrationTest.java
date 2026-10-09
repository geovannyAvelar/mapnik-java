package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Native memory does not grow without bound when a program renders for a long time. Java's garbage collector cannot
 * see native memory, so a missing free shows only as the process getting bigger: this watches the resident memory of
 * the process while thousands of maps, images, datasources and projections are made and closed.
 *
 * <p>{@code -Dmapnik.soak.iterations=100000} runs it for much longer, for a leak that is slow.
 */
class SoakIntegrationTest {
    private static final int ITERATIONS = Integer.getInteger("mapnik.soak.iterations", 2500);
    /** What the process may grow between the end of warm-up and the end, in megabytes. A real leak is far above this. */
    private static final long ALLOWED_GROWTH_MB = 60;

    @BeforeAll
    static void setUp() throws Exception {
        Fixtures.dir();
    }

    /** Resident memory of this process in megabytes, or -1 if it cannot be read here. */
    static long residentMegabytes() {
        try {
            if (Files.exists(Paths.get("/proc/self/status"))) {
                for (String line : Files.readAllLines(Paths.get("/proc/self/status"), StandardCharsets.UTF_8)) {
                    if (line.startsWith("VmRSS:")) {
                        return Long.parseLong(line.replaceAll("[^0-9]", "")) / 1024;
                    }
                }
            }
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("mac")) {
                Process p = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(ProcessHandle.current().pid())).start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    return Long.parseLong(r.readLine().trim()) / 1024;
                }
            }
        } catch (Exception | LinkageError e) {
            // fall through
        }
        return -1;
    }

    private static List<Feature> features(Random r, int n) {
        List<Feature> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double x = -170 + r.nextDouble() * 340;
            double y = -80 + r.nextDouble() * 160;
            out.add(Feature.create(i + 1, Geometry.rectangle(new Box2d(x, y, x + 3, y + 2)),
                Collections.<String, Object>singletonMap("name", "feature " + i)));
        }
        return out;
    }

    /** One pass of everything a program does: build a map, draw it, encode it, query it, close it. */
    private static int oneRound(Random r, boolean text) {
        int bytes = 0;
        try (MemoryDatasource ds = MemoryDatasource.create();
             MapnikMap map = new MapnikMap(256, 256).setSrs("+proj=longlat +datum=WGS84").setBackground("white");
             Layer layer = Layer.create("data", "+proj=longlat +datum=WGS84")) {
            ds.addAll(features(r, 150));
            Rule rule = Rule.create().add(Symbolizer.polygon().fill("#3b82f6")).add(Symbolizer.line().stroke("#1e3a8a").strokeWidth(0.5));
            if (text) {
                rule.add(Symbolizer.text("[name]").faceName("DejaVu Sans Book").fontSize(9).fill("black").halo("white", 1));
            }
            map.addStyle(Style.create("s").add(rule));
            layer.addStyle("s").setDatasource(ds);
            map.addLayer(layer).zoomToBox(-180, -90, 180, 90);
            bytes += map.renderToPng().length;
            try (Image img = map.renderToImage()) {
                img.filter("agg-stack-blur(2,2)");
                try (Image small = img.scaled(64, 64)) {
                    bytes += small.toBytes(ImageFormat.png8().colors(32)).length;
                }
            }
            try (Featureset fs = ds.features(new Box2d(-50, -50, 50, 50))) {
                bytes += fs.toList().size();
            }
        }
        try (CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:3857")) {
            bytes += (int) t.forward(10, 20).x();
        }
        try (GrayImage g = GrayImage.create(GrayImage.Type.FLOAT32, 64, 64, 3.5);
             Image coloured = RasterColorizer.create().stop(0, "#000000").stop(10, "#ffffff").colorize(g)) {
            bytes += coloured.width();
        }
        return bytes;
    }

    @Test
    void memoryDoesNotGrowOverThousandsOfRounds() {
        long before = residentMegabytes();
        assumeTrue(before >= 0, "this system does not let the test read its own memory use");
        boolean text = Fixtures.fonts();
        Random r = new Random(1);
        int warmup = Math.max(200, ITERATIONS / 8);
        for (int i = 0; i < warmup; i++) {
            oneRound(r, text);
        }
        System.gc();
        long start = residentMegabytes();
        long peak = start;
        for (int i = 0; i < ITERATIONS; i++) {
            oneRound(r, text);
            if (i % 250 == 0) {
                peak = Math.max(peak, residentMegabytes());
            }
        }
        System.gc();
        long end = residentMegabytes();
        System.out.println("SOAK " + ITERATIONS + " rounds: resident " + start + " MB after warm-up, " + end + " MB at the end, peak " + peak + " MB");
        assertTrue(end - start <= ALLOWED_GROWTH_MB,
            "resident memory grew from " + start + " MB to " + end + " MB over " + ITERATIONS + " rounds: a native leak?");
        assertEquals(0, Mapnik.leakedHandles(), "handles garbage collected without being closed");
    }

    @Test
    void memoryDoesNotGrowWhenManyThreadsShareAPool() throws Exception {
        long before = residentMegabytes();
        assumeTrue(before >= 0, "this system does not let the test read its own memory use");
        String style = "<Map srs=\"+proj=longlat +datum=WGS84\" background-color=\"#336699\"><Style name=\"s\"><Rule/></Style></Map>";
        try (MapPool pool = new MapPool(4, 128, 128, m -> m.loadString(style, null))) {
            ExecutorService threads = Executors.newFixedThreadPool(8);
            try {
                runPool(pool, threads, 300);   // warm-up
                System.gc();
                long start = residentMegabytes();
                runPool(pool, threads, 3000);
                System.gc();
                long end = residentMegabytes();
                System.out.println("SOAK pool: resident " + start + " MB after warm-up, " + end + " MB at the end");
                assertTrue(end - start <= ALLOWED_GROWTH_MB, "resident memory grew from " + start + " to " + end + " MB");
            } finally {
                threads.shutdownNow();
            }
        }
        assertEquals(0, Mapnik.leakedHandles());
    }

    private static void runPool(MapPool pool, ExecutorService threads, int tiles) throws Exception {
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < tiles; i++) {
            final int n = i;
            results.add(threads.submit(() -> pool.withMap(m -> {
                m.zoomToBox(-180 + n % 10, -90, 180, 90 - n % 7);
                return m.renderToPng().length;
            })));
        }
        for (Future<Integer> f : results) {
            assertTrue(f.get(120, TimeUnit.SECONDS) > 50);
        }
    }
}
