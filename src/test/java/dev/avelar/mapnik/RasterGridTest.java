package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RasterGridTest {
    private static double[] ramp(int w, int h, int bits, int format) {
        double[] v = new double[w * h];
        Random r = new Random(7);
        for (int i = 0; i < v.length; i++) {
            double base = (i % w) * 3 + (i / w) * 5 + r.nextInt(4);
            if (format == 3) {
                v[i] = bits == 32 ? (float) (base * 1.25 - 40.5) : base * 1.25 - 40.5;
            } else if (format == 2) {
                v[i] = bits == 8 ? (base % 200) - 100 : base - 500;
            } else {
                v[i] = bits == 8 ? base % 256 : base * 11;
            }
        }
        return v;
    }

    private static void check(TiffBuilder b) {
        RasterGrid g = RasterGrid.readGeoTiff(b.build());
        assertEquals(b.width, g.width());
        assertEquals(b.height, g.height());
        assertArrayEquals(b.values, g.values(), 0.0, describe(b));
    }

    private static String describe(TiffBuilder b) {
        return b.bits + "-bit format " + b.format + " compression " + b.compression + " predictor " + b.predictor
            + (b.tiled ? " tiled" : " strips") + (b.bigEndian ? " big" : " little") + " bands " + b.bands + " planar " + b.planar;
    }

    @Test
    void everySampleTypeInEveryByteOrderRoundTrips() {
        int[][] kinds = {{8, 1}, {8, 2}, {16, 1}, {16, 2}, {32, 1}, {32, 2}, {32, 3}, {64, 3}};
        for (int[] k : kinds) {
            for (boolean big : new boolean[] {false, true}) {
                TiffBuilder b = new TiffBuilder(37, 23, ramp(37, 23, k[0], k[1]));
                b.bits = k[0];
                b.format = k[1];
                b.bigEndian = big;
                check(b);
            }
        }
    }

    @Test
    void stripsAndTilesWithEveryCompression() {
        for (int compression : new int[] {1, 8, 32773, 5}) {
            for (boolean tiled : new boolean[] {false, true}) {
                TiffBuilder b = new TiffBuilder(53, 41, ramp(53, 41, 16, 1));
                b.bits = 16;
                b.compression = compression;
                b.tiled = tiled;
                b.tileW = 16;
                b.tileH = 16;
                b.rowsPerStrip = 7;
                check(b);
            }
        }
    }

    @Test
    void lzwSurvivesLongInputThatFillsAndResetsTheTable() {
        double[] v = new double[300 * 300];
        Random r = new Random(3);
        for (int i = 0; i < v.length; i++) {
            v[i] = r.nextInt(256);
        }
        TiffBuilder b = new TiffBuilder(300, 300, v);
        b.compression = 5;
        b.rowsPerStrip = 300;
        check(b);
    }

    @Test
    void predictors() {
        for (int predictor : new int[] {2, 3}) {
            for (int compression : new int[] {1, 8, 5}) {
                for (boolean big : new boolean[] {false, true}) {
                    int bits = predictor == 3 ? 32 : 16;
                    TiffBuilder b = new TiffBuilder(40, 30, ramp(40, 30, bits, predictor == 3 ? 3 : 1));
                    b.bits = bits;
                    b.format = predictor == 3 ? 3 : 1;
                    b.predictor = predictor;
                    b.compression = compression;
                    b.bigEndian = big;
                    check(b);
                }
            }
        }
        TiffBuilder f64 = new TiffBuilder(21, 9, ramp(21, 9, 64, 3));
        f64.bits = 64;
        f64.format = 3;
        f64.predictor = 3;
        f64.compression = 8;
        check(f64);
    }

    @Test
    void theFirstBandIsReadFromChunkyAndPlanarFiles() {
        for (int planar : new int[] {1, 2}) {
            TiffBuilder b = new TiffBuilder(30, 20, ramp(30, 20, 16, 1));
            b.bits = 16;
            b.bands = 3;
            b.planar = planar;
            b.predictor = planar == 1 ? 2 : 1;
            b.compression = 8;
            check(b);
        }
    }

    @Test
    void georeferencingComesFromTheTiepointAndScale() {
        TiffBuilder b = new TiffBuilder(10, 5, ramp(10, 5, 16, 1));
        b.bits = 16;
        b.origin = new double[] {-10, 50};
        b.pixelSize = new double[] {0.5, 0.25};
        b.epsg = 4326;
        b.geographic = true;
        b.nodata = "-9999";
        RasterGrid g = RasterGrid.readGeoTiff(b.build());
        assertEquals(new Box2d(-10, 48.75, -5, 50), g.extent());
        assertEquals("epsg:4326", g.srs());
        assertEquals(-9999, g.noData().getAsDouble());
        assertEquals(GrayImage.Type.UINT16, g.type());
        assertEquals(g.get(0, 0), g.valueAt(-9.9, 49.9));
        assertEquals(g.get(9, 4), g.valueAt(-5.1, 48.8));
        assertTrue(Double.isNaN(g.valueAt(0, 0)));
    }

    @Test
    void aPointPixelTiepointIsHalfAPixelOff() {
        TiffBuilder b = new TiffBuilder(4, 4, ramp(4, 4, 8, 1));
        b.origin = new double[] {100, 200};
        b.pixelSize = new double[] {10, 10};
        b.pixelIsPoint = true;
        b.epsg = 32633;
        RasterGrid g = RasterGrid.readGeoTiff(b.build());
        assertEquals(new Box2d(95, 165, 135, 205), g.extent());
        assertEquals("epsg:32633", g.srs());
    }

    @Test
    void aFileWithoutGeoreferencingHasNoExtentOrSrs() {
        RasterGrid g = RasterGrid.readGeoTiff(new TiffBuilder(3, 3, ramp(3, 3, 8, 1)).build());
        assertNull(g.extent());
        assertNull(g.srs());
        assertFalse(g.noData().isPresent());
    }

    @Test
    void badFilesAreRefusedWithAnIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> RasterGrid.readGeoTiff(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> RasterGrid.readGeoTiff("not a tiff at all".getBytes()));
        byte[] big = {'I', 'I', 43, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> RasterGrid.readGeoTiff(big));
        assertTrue(e.getMessage().contains("BigTIFF"), e.getMessage());
        byte[] ok = new TiffBuilder(8, 8, ramp(8, 8, 8, 1)).build();
        assertThrows(IllegalArgumentException.class, () -> RasterGrid.readGeoTiff(java.util.Arrays.copyOf(ok, ok.length / 2)));
        TiffBuilder jpeg = new TiffBuilder(8, 8, ramp(8, 8, 8, 1));
        jpeg.compression = 1;
        byte[] j = jpeg.build();
        // change the compression tag value to 7 (JPEG)
        patchShort(j, 259, 7);
        e = assertThrows(IllegalArgumentException.class, () -> RasterGrid.readGeoTiff(j));
        assertTrue(e.getMessage().contains("compression"), e.getMessage());
    }

    /** Overwrite the inline value of a SHORT tag in a little-endian file built by {@link TiffBuilder}. */
    private static void patchShort(byte[] file, int tag, int value) {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(file).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int ifd = b.getInt(4);
        int n = b.getShort(ifd);
        for (int i = 0; i < n; i++) {
            int at = ifd + 2 + 12 * i;
            if ((b.getShort(at) & 0xFFFF) == tag) {
                b.putShort(at + 8, (short) value);
                return;
            }
        }
        fail("tag " + tag + " not found");
    }

    @Test
    void damagedFilesNeverThrowAnythingButIllegalArgumentException() {
        Random r = new Random(11);
        TiffBuilder[] templates = new TiffBuilder[4];
        for (int i = 0; i < templates.length; i++) {
            TiffBuilder b = new TiffBuilder(40, 30, ramp(40, 30, 16, 1));
            b.bits = 16;
            b.compression = new int[] {1, 8, 5, 32773}[i];
            b.tiled = i % 2 == 0;
            b.predictor = i == 1 ? 2 : 1;
            b.origin = new double[] {0, 0};
            b.pixelSize = new double[] {1, 1};
            templates[i] = b;
        }
        int refused = 0;
        for (int round = 0; round < 3000; round++) {
            byte[] data = templates[round % templates.length].build().clone();
            int flips = 1 + r.nextInt(6);
            for (int f = 0; f < flips; f++) {
                data[r.nextInt(data.length)] = (byte) r.nextInt(256);
            }
            if (round % 7 == 0) {
                data = java.util.Arrays.copyOf(data, r.nextInt(data.length));
            }
            try {
                RasterGrid.readGeoTiff(data);
            } catch (IllegalArgumentException e) {
                refused++;
            }
        }
        assertTrue(refused > 100, "damage should often be noticed: " + refused);
    }

    // ------------------------------------------------------------------ ASCII grid

    private static final String ASC = "ncols 4\nnrows 3\nxllcorner 100\nyllcorner 200\ncellsize 10\nNODATA_value -9999\n"
        + "1 2 3 4\n5 6 7 8\n9 10 -9999 12\n";

    @Test
    void anAsciiGridIsReadWithItsExtentAndNoData() {
        RasterGrid g = RasterGrid.readAscii(ASC);
        assertEquals(4, g.width());
        assertEquals(3, g.height());
        assertEquals(new Box2d(100, 200, 140, 230), g.extent());
        assertEquals(-9999, g.noData().getAsDouble());
        assertEquals(1, g.get(0, 0));
        assertEquals(12, g.get(3, 2));
        assertArrayEquals(new double[] {1, 12}, g.range());
        assertEquals(GrayImage.Type.INT32, g.type());
    }

    @Test
    void centreCoordinatesShiftByHalfACellAndDecimalsKeepAllDigits() {
        RasterGrid g = RasterGrid.readAscii("NCOLS 2\nNROWS 1\nXLLCENTER 5\nYLLCENTER 5\nCELLSIZE 2\n1.5 2.25\n");
        assertEquals(new Box2d(4, 4, 8, 6), g.extent());
        assertEquals(GrayImage.Type.FLOAT64, g.type());
        assertEquals(2.25, g.get(1, 0));
    }

    @Test
    void badAsciiGridsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> RasterGrid.readAscii(""));
        assertThrows(IllegalArgumentException.class, () -> RasterGrid.readAscii("ncols 2\nnrows 2\n1 2 3 4\n"));
        assertThrows(IllegalArgumentException.class, () -> RasterGrid.readAscii(ASC.replace("1 2 3 4\n5 6 7 8\n", "1 2 3 4\n")));
        assertThrows(IllegalArgumentException.class, () -> RasterGrid.readAscii(ASC.replace("cellsize 10", "cellsize 0")));
        assertThrows(IllegalArgumentException.class, () -> RasterGrid.readAscii(ASC.replace("9 10", "9 ten")));
        assertThrows(IllegalArgumentException.class, () -> RasterGrid.readAscii(ASC.replace("ncols 4", "ncols 99999999")));
        assertThrows(IllegalArgumentException.class, () -> RasterGrid.readAscii(ASC.replace("ncols 4", "bogus 4")));
    }

    @Test
    void readChoosesTheFormatByExtension(@TempDir Path dir) throws Exception {
        Path asc = dir.resolve("a.ASC");
        Files.write(asc, ASC.getBytes());
        assertEquals(4, RasterGrid.read(asc).width());
        Path tif = dir.resolve("b.tif");
        Files.write(tif, new TiffBuilder(6, 5, ramp(6, 5, 8, 1)).build());
        assertEquals(6, RasterGrid.read(tif).width());
        assertThrows(IllegalArgumentException.class, () -> RasterGrid.read(dir.resolve("c.png")));
    }

    @Test
    void outsideTheGridIsAnError() {
        RasterGrid g = RasterGrid.readAscii(ASC);
        assertThrows(IndexOutOfBoundsException.class, () -> g.get(4, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> g.get(0, -1));
    }

    // ------------------------------------------------------------------ files written by GDAL (libtiff)

    private static byte[] resource(String name) throws Exception {
        try (java.io.InputStream in = RasterGridTest.class.getResourceAsStream("/geotiff/" + name)) {
            assertNotNull(in, name);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    @Test
    void filesWrittenByGdalReadBackAsTheReferenceGrid() throws Exception {
        RasterGrid ref = RasterGrid.readAscii(new String(resource("ref.asc")));
        RasterGrid ref8 = RasterGrid.readAscii(new String(resource("ref8.asc")));
        Object[][] cases = {
            {"f32_deflate_p3_tiled.tif", GrayImage.Type.FLOAT32, ref, -9999.0},
            {"i16_lzw_p2_strips.tif", GrayImage.Type.INT16, ref, -9999.0},
            {"f64_lzw_p3.tif", GrayImage.Type.FLOAT64, ref, -9999.0},
            {"i32_none_tiled.tif", GrayImage.Type.INT32, ref, -9999.0},
            {"u8_packbits.tif", GrayImage.Type.UINT8, ref8, 255.0},
        };
        for (Object[] c : cases) {
            String name = (String) c[0];
            RasterGrid want = (RasterGrid) c[2];
            RasterGrid got = RasterGrid.readGeoTiff(resource(name));
            assertEquals(64, got.width(), name);
            assertEquals(48, got.height(), name);
            assertArrayEquals(want.values(), got.values(), 0.0, name);
            assertEquals(c[1], got.type(), name);
            assertEquals(want.extent(), got.extent(), name);
            assertEquals("epsg:4326", got.srs(), name);
            if (c[3] == null) {
                assertFalse(got.noData().isPresent(), name);
            } else {
                assertEquals((Double) c[3], got.noData().getAsDouble(), name);
            }
        }
    }
}
