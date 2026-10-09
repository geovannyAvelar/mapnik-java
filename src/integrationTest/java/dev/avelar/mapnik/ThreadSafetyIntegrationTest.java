package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** One projection or transform used from many threads at once gives the same answers as one thread. */
class ThreadSafetyIntegrationTest {
    private static final int THREADS = 8;
    private static final int POINTS = 20_000;

    @BeforeAll
    static void setUp() throws Exception {
        Fixtures.dir();
    }

    private static double[][] points() {
        Random r = new Random(5);
        double[][] p = new double[POINTS][2];
        for (double[] q : p) {
            q[0] = -179 + r.nextDouble() * 358;
            q[1] = -84 + r.nextDouble() * 168;
        }
        return p;
    }

    private static <T> List<T> inParallel(List<Callable<T>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        try {
            List<Future<T>> futures = pool.invokeAll(jobs);
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get(120, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void oneCoordinateTransformSharedByManyThreads() throws Exception {
        double[][] pts = points();
        double[][] expected = new double[POINTS][];
        try (CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:3857")) {
            for (int i = 0; i < POINTS; i++) {
                Point2d p = t.forward(pts[i][0], pts[i][1]);
                expected[i] = new double[] {p.x(), p.y()};
            }
            List<Callable<Integer>> jobs = new ArrayList<>();
            for (int th = 0; th < THREADS; th++) {
                jobs.add(() -> {
                    int wrong = 0;
                    for (int i = 0; i < POINTS; i++) {
                        Point2d p = t.forward(pts[i][0], pts[i][1]);
                        if (p.x() != expected[i][0] || p.y() != expected[i][1]) {
                            wrong++;
                        }
                    }
                    return wrong;
                });
            }
            int wrong = 0;
            for (int w : inParallel(jobs)) {
                wrong += w;
            }
            assertEquals(0, wrong, "answers that differed from the single-threaded ones");
        }
    }

    @Test
    void oneProjectionSharedByManyThreads() throws Exception {
        double[][] pts = points();
        double[][] expected = new double[POINTS][];
        try (Projection p = Projection.of("+proj=merc +a=6378137 +b=6378137 +lat_ts=0 +lon_0=0 +x_0=0 +y_0=0 +k=1 +units=m +nadgrids=@null +no_defs")) {
            for (int i = 0; i < POINTS; i++) {
                Point2d q = p.forward(pts[i][0], pts[i][1]);
                expected[i] = new double[] {q.x(), q.y()};
            }
            List<Callable<Integer>> jobs = new ArrayList<>();
            for (int th = 0; th < THREADS; th++) {
                jobs.add(() -> {
                    int wrong = 0;
                    for (int i = 0; i < POINTS; i++) {
                        Point2d q = p.forward(pts[i][0], pts[i][1]);
                        if (q.x() != expected[i][0] || q.y() != expected[i][1]) {
                            wrong++;
                        }
                    }
                    return wrong;
                });
            }
            int wrong = 0;
            for (int w : inParallel(jobs)) {
                wrong += w;
            }
            assertEquals(0, wrong, "answers that differed from the single-threaded ones");
        }
    }

    @Test
    void manyThreadsEachMakingTheirOwnTransformsAreFine() throws Exception {
        double[][] pts = points();
        List<Callable<Integer>> jobs = new ArrayList<>();
        for (int th = 0; th < THREADS; th++) {
            jobs.add(() -> {
                int n = 0;
                for (int i = 0; i < 200; i++) {
                    try (CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:" + (i % 2 == 0 ? "3857" : "32633"))) {
                        t.forward(12 + (i % 6), 40 + (i % 20) * 0.1);   // inside UTM zone 33, which suits both
                        n++;
                    }
                }
                return n;
            });
        }
        for (int n : inParallel(jobs)) {
            assertEquals(200, n);
        }
    }
}
