package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Raster layers, the raster colorizer and image warping against a real Mapnik. */
class RasterIntegrationTest {
    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;
    private static final int WHITE = 0xFFFFFFFF;

    @BeforeAll
    static void setUp() throws IOException {
        Fixtures.dir();
    }

    private static int r(int p) { return (p >> 16) & 0xFF; }
    private static int g(int p) { return (p >> 8) & 0xFF; }
    private static int b(int p) { return p & 0xFF; }
    private static int a(int p) { return p >>> 24; }

    // ---------------------------------------------------------------- a grey raster layer

    /** A 100x100 8-bit grey PNG whose value grows from 0 at the left to 255 at the right. */
    private static Path greyRamp(Path dir) throws IOException {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < 100; y++) {
            for (int x = 0; x < 100; x++) {
                int v = Math.round(x * 255f / 99f);
                img.getRaster().setSample(x, y, 0, v);
            }
        }
        Path file = dir.resolve("ramp.png");
        ImageIO.write(img, "png", file.toFile());
        return file;
    }

    /** A map 100x100 over [-50, 50] with the ramp drawn by one raster symbolizer. One map pixel is one raster pixel. */
    private static MapnikMap rampMap(Path dir, Symbolizer raster) throws IOException {
        Map<String, Object> p = new HashMap<>();
        p.put("type", "raster");
        p.put("file", greyRamp(dir).toString());
        p.put("lox", -50.0);
        p.put("loy", -50.0);
        p.put("hix", 50.0);
        p.put("hiy", 50.0);
        MapnikMap map = new MapnikMap(100, 100).setSrs("epsg:4326").setBackground("white");
        try (Layer layer = Layer.create("ramp", "epsg:4326"); Datasource ds = Datasource.create(p)) {
            map.addStyle(Style.create("raster").add(Rule.create().add(raster)));
            layer.addStyle("raster").setDatasource(ds);
            map.addLayer(layer);
        } catch (RuntimeException e) {
            map.close();
            throw e;
        }
        return map.zoomToBox(-50, -50, 50, 50);
    }

    /**
     * The same ramp as a one-band TIFF read through GDAL. The colorizer only applies to single-band data,
     * and Mapnik's "raster" plugin decodes a PNG to RGBA, so the colorizer tests need this.
     */
    private static MapnikMap rampMapOneBand(Path dir, Symbolizer raster) throws IOException {
        assumeTrue(Mapnik.isDatasourceRegistered("gdal"), "this Mapnik has no gdal input plugin");
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < 100; y++) {
            for (int x = 0; x < 100; x++) {
                img.getRaster().setSample(x, y, 0, Math.round(x * 255f / 99f));
            }
        }
        Path tif = dir.resolve("ramp.tif");
        assertTrue(ImageIO.write(img, "tiff", tif.toFile()), "this JDK cannot write TIFF");
        Map<String, Object> p = new HashMap<>();
        p.put("type", "gdal");
        p.put("file", tif.toString());
        p.put("band", 1);
        p.put("extent", "-50,-50,50,50");
        MapnikMap map = new MapnikMap(100, 100).setSrs("epsg:4326").setBackground("white");
        try (Layer layer = Layer.create("ramp", "epsg:4326"); Datasource ds = Datasource.create(p)) {
            map.addStyle(Style.create("raster").add(Rule.create().add(raster)));
            layer.addStyle("raster").setDatasource(ds);
            map.addLayer(layer);
        } catch (RuntimeException e) {
            map.close();
            throw e;
        }
        return map.zoomToBox(-50, -50, 50, 50);
    }

    @Test
    void aRasterDatasourceIsARasterDatasource(@TempDir Path tmp) throws IOException {
        Map<String, Object> p = new HashMap<>();
        p.put("type", "raster");
        p.put("file", greyRamp(tmp).toString());
        p.put("lox", -50.0);
        p.put("loy", -50.0);
        p.put("hix", 50.0);
        p.put("hiy", 50.0);
        try (Datasource ds = Datasource.create(p)) {
            assertEquals(Datasource.Type.RASTER, ds.type());
            Box2d e = ds.envelope();
            assertEquals(-50, e.minX(), 1e-9);
            assertEquals(50, e.maxY(), 1e-9);
        }
    }

    @Test
    void aGreyRasterDrawsAsGrey(@TempDir Path tmp) throws IOException {
        try (MapnikMap map = rampMap(tmp, Symbolizer.raster()); Image img = map.renderToImage()) {
            int left = img.getArgb(2, 50);
            int right = img.getArgb(97, 50);
            assertEquals(r(left), g(left));
            assertEquals(g(left), b(left));
            assertTrue(r(left) < 30, "dark on the left: " + r(left));
            assertTrue(r(right) > 225, "bright on the right: " + r(right));
            assertTrue(r(img.getArgb(30, 50)) < r(img.getArgb(70, 50)), "grows to the right");
        }
    }

    @Test
    void rasterOpacity(@TempDir Path tmp) throws IOException {
        try (MapnikMap map = rampMap(tmp, Symbolizer.raster().opacity(0.5)); Image img = map.renderToImage()) {
            int right = img.getArgb(97, 50); // white-ish raster over white background stays bright
            int left = img.getArgb(2, 50);   // dark raster at half opacity over white is mid grey
            assertTrue(r(left) > 100 && r(left) < 200, "mid grey: " + r(left));
            assertTrue(r(right) > 240, "bright: " + r(right));
        }
    }

    // ---------------------------------------------------------------- the colorizer

    @Test
    void linearColorizerBlendsBetweenStops(@TempDir Path tmp) throws IOException {
        Symbolizer s = Symbolizer.raster().colorizer(RasterColorizer.create()
            .defaultMode(RasterColorizer.Mode.LINEAR).stop(0, "blue").stop(255, "red"));
        try (MapnikMap map = rampMapOneBand(tmp, s); Image img = map.renderToImage()) {
            int left = img.getArgb(1, 50);
            int middle = img.getArgb(50, 50);
            int right = img.getArgb(98, 50);
            assertTrue(b(left) > 240 && r(left) < 15, "almost pure blue: " + Integer.toHexString(left));
            assertTrue(r(right) > 240 && b(right) < 15, "almost pure red: " + Integer.toHexString(right));
            assertTrue(r(middle) > 90 && r(middle) < 170, "purple: " + Integer.toHexString(middle));
            assertTrue(b(middle) > 90 && b(middle) < 170, "purple: " + Integer.toHexString(middle));
            assertEquals(255, a(middle));
        }
    }

    @Test
    void discreteColorizerGivesFlatBands(@TempDir Path tmp) throws IOException {
        Symbolizer s = Symbolizer.raster().colorizer(RasterColorizer.create()
            .defaultMode(RasterColorizer.Mode.DISCRETE).stop(0, "blue").stop(128, "red"));
        try (MapnikMap map = rampMapOneBand(tmp, s); Image img = map.renderToImage()) {
            assertEquals(BLUE, img.getArgb(10, 50));
            assertEquals(BLUE, img.getArgb(45, 50));
            assertEquals(RED, img.getArgb(60, 50));
            assertEquals(RED, img.getArgb(95, 50));
        }
    }

    @Test
    void exactColorizerColoursOnlyTheStopValues(@TempDir Path tmp) throws IOException {
        // Raster value 0 is only in column 0. Everything else matches no stop and is left transparent.
        Symbolizer s = Symbolizer.raster().colorizer(RasterColorizer.create()
            .defaultMode(RasterColorizer.Mode.EXACT).defaultColor("transparent").epsilon(0.5).stop(0, "red"));
        try (MapnikMap map = rampMapOneBand(tmp, s); Image img = map.renderToImage()) {
            assertEquals(RED, img.getArgb(0, 50));
            assertEquals(WHITE, img.getArgb(50, 50), "no stop matches: the white background shows");
            assertEquals(WHITE, img.getArgb(97, 50));
        }
    }

    @Test
    void valuesBelowTheFirstStopUseTheDefaultColour(@TempDir Path tmp) throws IOException {
        Symbolizer s = Symbolizer.raster().colorizer(RasterColorizer.create()
            .defaultMode(RasterColorizer.Mode.DISCRETE).defaultColor("green").stop(128, "red"));
        try (MapnikMap map = rampMapOneBand(tmp, s); Image img = map.renderToImage()) {
            int low = img.getArgb(10, 50);
            assertTrue(g(low) > 100 && r(low) < 50, "green for the low values: " + Integer.toHexString(low));
            assertEquals(RED, img.getArgb(95, 50));
        }
    }

    @Test
    void colorizerXmlPassesMapniksOwnStrictParser() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            map.addStyle(Style.create("c").add(Rule.create().add(Symbolizer.raster().colorizer(RasterColorizer.create()
                .defaultMode(RasterColorizer.Mode.LINEAR).defaultColor(Color.TRANSPARENT).epsilon(0.01)
                .stop(0, "blue").stop(10, "green", RasterColorizer.Mode.DISCRETE, "ten").stop(20, Color.WHITE)))));
            assertTrue(map.hasStyle("c"));
            assertTrue(map.toXml().contains("RasterColorizer"), map.toXml());
        }
    }

    // ---------------------------------------------------------------- warping

    private static Image quadrants() {
        Image src = Image.create(100, 100);
        src.fill("white");
        for (int y = 0; y < 50; y++) {
            for (int x = 0; x < 50; x++) {
                src.setArgb(x, y, RED);
            }
        }
        for (int y = 50; y < 100; y++) {
            for (int x = 50; x < 100; x++) {
                src.setArgb(x, y, BLUE);
            }
        }
        return src;
    }

    @Test
    void warpingToAnotherProjectionKeepsTheContentInPlace() {
        Box2d geo = new Box2d(-10, -10, 10, 10);
        try (Image src = quadrants(); CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:3857")) {
            Box2d merc = t.forward(geo);
            try (Image dst = src.warp("epsg:4326", geo, "epsg:3857", merc, 100, 100, ScalingMethod.BILINEAR)) {
                assertEquals(100, dst.width());
                assertEquals(RED, dst.getArgb(10, 10), "top left stays red");
                assertEquals(BLUE, dst.getArgb(90, 90), "bottom right stays blue");
                assertEquals(WHITE, dst.getArgb(90, 10));
                assertEquals(WHITE, dst.getArgb(10, 90));
            }
        }
    }

    @Test
    void areasOutsideTheSourceAreTransparent() {
        Box2d geo = new Box2d(-10, -10, 10, 10);
        try (Image src = quadrants(); CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:3857")) {
            Box2d merc = t.forward(geo);
            Box2d wide = new Box2d(merc.minX() * 2, merc.minY() * 2, merc.maxX() * 2, merc.maxY() * 2);
            try (Image dst = src.warp("epsg:4326", geo, "epsg:3857", wide, 100, 100, ScalingMethod.BILINEAR)) {
                assertEquals(0, a(dst.getArgb(2, 2)), "outside the source");
                assertEquals(RED, dst.getArgb(35, 35));
                assertEquals(BLUE, dst.getArgb(65, 65));
            }
        }
    }

    @Test
    void aSmallerTargetExtentZoomsIn() {
        Box2d geo = new Box2d(-10, -10, 10, 10);
        try (Image src = quadrants(); CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:3857")) {
            Box2d topLeft = t.forward(new Box2d(-10, 0, 0, 10)); // just the red quadrant
            try (Image dst = src.warp("epsg:4326", geo, "epsg:3857", topLeft, 40, 40, ScalingMethod.NEAR)) {
                assertEquals(40, dst.width());
                assertEquals(RED, dst.getArgb(5, 5));
                assertEquals(RED, dst.getArgb(35, 35));
            }
        }
    }

    @Test
    void warpingBetweenTheSameProjectionResamples() {
        Box2d box = new Box2d(0, 0, 100, 100);
        try (Image src = quadrants(); Image dst = src.warp("epsg:3857", box, "epsg:3857", box, 50, 50, ScalingMethod.NEAR)) {
            assertEquals(50, dst.width());
            assertEquals(RED, dst.getArgb(10, 10));
            assertEquals(BLUE, dst.getArgb(40, 40));
        }
    }

    @Test
    void warpKeepsStraightAlpha() {
        Box2d geo = new Box2d(-10, -10, 10, 10);
        try (Image src = Image.create(50, 50); CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:3857")) {
            src.fill(new Color(255, 0, 0, 128));
            try (Image dst = src.warp("epsg:4326", geo, "epsg:3857", t.forward(geo), 50, 50, ScalingMethod.BILINEAR)) {
                int p = dst.getArgb(25, 25);
                assertEquals(128, a(p), 3);
                assertEquals(255, r(p), 3);
                assertEquals(0, g(p), 3);
            }
        }
    }

    @Test
    void everyScalingMethodWarps() {
        Box2d geo = new Box2d(-10, -10, 10, 10);
        try (Image src = quadrants(); CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:3857")) {
            Box2d merc = t.forward(geo);
            for (ScalingMethod m : ScalingMethod.values()) {
                try (Image dst = src.warp("epsg:4326", geo, "epsg:3857", merc, 40, 40, m)) {
                    assertEquals(40, dst.width(), m.toString());
                }
            }
        }
    }

    @Test
    void badWarpRequestsFail() {
        Box2d geo = new Box2d(-10, -10, 10, 10);
        try (Image src = quadrants()) {
            assertThrows(MapnikException.class, () -> src.warp("epsg:4326", geo, "epsg:3857", geo, 0, 10, ScalingMethod.NEAR));
            assertThrows(MapnikException.class, () -> src.warp("epsg:4326", geo, "epsg:3857", geo, 10, 10, ScalingMethod.NEAR, 0));
            assertThrows(MapnikException.class, () -> src.warp("+proj=nonsense", geo, "epsg:3857", geo, 10, 10, ScalingMethod.NEAR));
            assertThrows(MapnikException.class, () -> src.warp("epsg:4326", geo, "epsg:999999", geo, 10, 10, ScalingMethod.NEAR));
            assertEquals(RED, src.getArgb(1, 1), "the source is untouched");
        }
    }

    @Test
    void warpedImagesCanBeSavedAndReloaded(@TempDir Path tmp) throws IOException {
        Box2d geo = new Box2d(-10, -10, 10, 10);
        try (Image src = quadrants(); CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:3857");
             Image dst = src.warp("epsg:4326", geo, "epsg:3857", t.forward(geo), 100, 100, ScalingMethod.BILINEAR)) {
            Path file = tmp.resolve("warped.png");
            dst.save(file, "png");
            assertTrue(Files.size(file) > 0);
            try (Image again = Image.load(file)) {
                assertEquals(dst.getArgb(10, 10), again.getArgb(10, 10));
            }
        }
    }
}
