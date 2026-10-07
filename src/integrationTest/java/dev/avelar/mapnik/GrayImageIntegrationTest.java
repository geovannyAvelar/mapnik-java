package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GrayImageIntegrationTest {
    @Test
    void everyTypeKeepsItsValues() {
        double[][] samples = {
            {0, 255}, {-128, 127}, {0, 65535}, {-32768, 32767}, {0, 4294967295.0}, {-2147483648.0, 2147483647.0},
            {-1.5, 3.25}, {0, 9007199254740992.0}, {-9007199254740992.0, 12345}, {-1e300, 1e-300}
        };
        GrayImage.Type[] types = GrayImage.Type.values();
        assertEquals(samples.length, types.length);
        for (int i = 0; i < types.length; i++) {
            try (GrayImage g = GrayImage.create(types[i], 2, 1)) {
                assertEquals(types[i], g.type());
                g.set(0, 0, samples[i][0]).set(1, 0, samples[i][1]);
                assertEquals(samples[i][0], g.get(0, 0), 0, types[i] + " low");
                assertEquals(samples[i][1], g.get(1, 0), types[i] == GrayImage.Type.FLOAT32 ? 1e-6 : 0, types[i] + " high");
            }
        }
    }

    @Test
    void valuesThatDoNotFitAreRefusedNotWrapped() {
        try (GrayImage g = GrayImage.create(GrayImage.Type.UINT8, 2, 2)) {
            assertThrows(MapnikException.class, () -> g.set(0, 0, 256));
            assertThrows(MapnikException.class, () -> g.set(0, 0, -1));
            assertThrows(MapnikException.class, () -> g.set(0, 0, Double.NaN));
            assertEquals(0, g.get(0, 0));
            assertThrows(MapnikException.class, () -> g.write(new double[] {1, 2, 3, 300}));
            assertArrayEquals(new double[4], g.read(), "a refused write changes nothing");
        }
        try (GrayImage f = GrayImage.create(GrayImage.Type.FLOAT32, 1, 1)) {
            f.set(0, 0, Double.NaN);
            assertTrue(Double.isNaN(f.get(0, 0)));
            assertThrows(MapnikException.class, () -> f.set(0, 0, 1e300));
        }
    }

    @Test
    void positionsAndSizesAreChecked() {
        try (GrayImage g = GrayImage.create(GrayImage.Type.INT16, 3, 2)) {
            assertEquals(3, g.width());
            assertEquals(2, g.height());
            assertThrows(MapnikException.class, () -> g.get(3, 0));
            assertThrows(MapnikException.class, () -> g.get(0, -1));
            assertThrows(MapnikException.class, () -> g.set(0, 2, 1));
            assertThrows(MapnikException.class, () -> g.write(new double[5]));
        }
        assertThrows(MapnikException.class, () -> GrayImage.create(GrayImage.Type.UINT8, 0, 5));
        assertThrows(MapnikException.class, () -> GrayImage.create(GrayImage.Type.UINT8, 100000, 100000));
    }

    @Test
    void bulkReadAndWriteGoRowByRow() {
        double[] in = {1, 2, 3, 4, 5, 6};
        try (GrayImage g = GrayImage.of(GrayImage.Type.INT32, 3, 2, in)) {
            assertEquals(4, g.get(0, 1));
            assertEquals(3, g.get(2, 0));
            assertArrayEquals(in, g.read());
        }
    }

    @Test
    void rangeSkipsNaNAndNodata() {
        try (GrayImage g = GrayImage.of(GrayImage.Type.FLOAT64, 2, 2, new double[] {-9999, 5, Double.NaN, 12})) {
            assertArrayEquals(new double[] {-9999, 12}, g.range());
            assertArrayEquals(new double[] {5, 12}, g.range(-9999));
        }
        try (GrayImage allNaN = GrayImage.of(GrayImage.Type.FLOAT32, 1, 1, new double[] {Double.NaN})) {
            assertThrows(MapnikException.class, allNaN::range);
        }
    }

    @Test
    void linearColourBlendsAcrossAnElevationRamp() {
        double[] h = new double[11];
        for (int i = 0; i < h.length; i++) {
            h[i] = i * 100; // 0 .. 1000
        }
        RasterColorizer ramp = RasterColorizer.create().defaultMode(RasterColorizer.Mode.LINEAR)
            .stop(0, "#000000").stop(1000, "#ffffff");
        try (GrayImage g = GrayImage.of(GrayImage.Type.FLOAT32, 11, 1, h); Image img = ramp.colorize(g)) {
            assertEquals(11, img.width());
            assertEquals(1, img.height());
            int low = img.getArgb(0, 0);
            int mid = img.getArgb(5, 0);
            int high = img.getArgb(10, 0);
            assertEquals(0xFF000000, low);
            assertEquals(0xFFFFFFFF, high);
            int grey = mid & 0xFF;
            assertTrue(grey > 100 && grey < 155, "half way is mid grey: " + grey);
            assertEquals(grey, (mid >> 8) & 0xFF);
        }
    }

    @Test
    void discreteExactAndNodataModes() {
        double[] v = {0, 5, 10, -1};
        RasterColorizer discrete = RasterColorizer.create().defaultColor("#00000000")
            .stop(0, "#ff0000", RasterColorizer.Mode.DISCRETE, null).stop(10, "#00ff00", RasterColorizer.Mode.DISCRETE, null);
        try (GrayImage g = GrayImage.of(GrayImage.Type.INT16, 4, 1, v); Image img = g.colorize(discrete, -1.0)) {
            assertEquals(0xFFFF0000, img.getArgb(0, 0));
            assertEquals(0xFFFF0000, img.getArgb(1, 0), "5 takes the stop at or below it");
            assertEquals(0xFF00FF00, img.getArgb(2, 0));
            assertEquals(0, img.getArgb(3, 0) >>> 24, "nodata is transparent");
        }
        RasterColorizer exact = RasterColorizer.create().defaultColor("#00000000")
            .stop(5, "#0000ff", RasterColorizer.Mode.EXACT, null);
        try (GrayImage g = GrayImage.of(GrayImage.Type.UINT8, 3, 1, new double[] {4, 5, 6}); Image img = exact.colorize(g)) {
            assertEquals(0, img.getArgb(0, 0) >>> 24);
            assertEquals(0xFF0000FF, img.getArgb(1, 0));
            assertEquals(0, img.getArgb(2, 0) >>> 24);
        }
    }

    @Test
    void colorizingNeedsStops() {
        try (GrayImage g = GrayImage.create(GrayImage.Type.UINT8, 1, 1)) {
            assertThrows(IllegalStateException.class, () -> g.colorize(RasterColorizer.create()));
        }
    }

    @Test
    void aClosedImageRefusesUse() {
        GrayImage g = GrayImage.create(GrayImage.Type.UINT8, 1, 1);
        g.close();
        g.close();
        assertThrows(IllegalStateException.class, g::width);
    }

    @Test
    void aGeoTiffBecomesAColouredPicture() throws Exception {
        java.nio.file.Path tif = java.nio.file.Paths.get("src/test/resources/geotiff/f32_deflate_p3_tiled.tif");
        RasterGrid grid = RasterGrid.read(tif);
        double nodata = grid.noData().getAsDouble();
        double[] range = grid.range();
        RasterColorizer ramp = RasterColorizer.create().stop(range[0], "#000000").stop(range[1], "#ffffff");
        try (GrayImage g = grid.toGrayImage(); Image img = g.colorize(ramp, nodata)) {
            assertEquals(GrayImage.Type.FLOAT32, g.type());
            assertEquals(64, img.width());
            assertEquals(48, img.height());
            assertArrayEquals(grid.values(), g.read());
            int missing = 0;
            for (int y = 0; y < 48; y++) {
                for (int x = 0; x < 64; x++) {
                    double v = grid.get(x, y);
                    int argb = img.getArgb(x, y);
                    if (v == nodata) {
                        missing++;
                        assertEquals(0, argb >>> 24, "nodata is transparent at " + x + "," + y);
                    } else {
                        assertEquals(255, argb >>> 24);
                    }
                }
            }
            assertTrue(missing > 10, "the fixture has no-data cells: " + missing);
        }
    }
}
