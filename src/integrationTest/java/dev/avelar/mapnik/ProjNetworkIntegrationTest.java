package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * NAD27 to NAD83 is exact only with a datum-shift grid, which the bundle does not carry. Without the network PROJ
 * uses an approximate shift; with it, PROJ fetches the grid and the point lands somewhere measurably different.
 */
class ProjNetworkIntegrationTest {
    @AfterAll
    static void off() {
        Mapnik.enableProjNetwork(false);
    }

    private static boolean cdnReachable() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://cdn.proj.org/us_noaa_conus.tif").openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** PROJ reads its settings when it first starts on a thread, so each run gets a thread of its own. */
    private static Point2d transformed() throws Exception {
        ExecutorService one = Executors.newSingleThreadExecutor();
        try {
            Callable<Point2d> work = () -> {
                try (CoordinateTransform t = CoordinateTransform.between("epsg:4267", "epsg:4269")) {
                    return t.forward(-100.0, 40.0);
                }
            };
            return one.submit(work).get();
        } finally {
            one.shutdownNow();
        }
    }

    @Test
    void theNetworkLetsPROJUseTheGridItLacks() throws Exception {
        assumeTrue(Mapnik.isBundled(), "needs the bundle, which has PROJ with network support");
        assumeTrue(!Files.exists(Mapnik.bundledDirectory().resolve("proj").resolve("us_noaa_conus.tif")),
            "this bundle already carries the grid, so there is nothing for the network to add");
        assumeTrue(cdnReachable(), "cdn.proj.org is not reachable from here");
        Mapnik.enableProjNetwork(false);
        Point2d approximate = transformed();
        Mapnik.enableProjNetwork(true);
        Point2d exact = transformed();
        double metres = Math.hypot(exact.x() - approximate.x(), (exact.y() - approximate.y())) * 111_000;
        assertTrue(metres > 0.5, "the grid changes the answer by metres, not " + metres + " m");
        assertTrue(metres < 100, "and by a plausible amount: " + metres + " m");
    }

    @Test
    void switchingItIsHarmlessWithoutANetwork() {
        Mapnik.enableProjNetwork(true);
        Mapnik.enableProjNetwork(false);
    }
}
