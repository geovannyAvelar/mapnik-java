package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Renders real data through a real Mapnik install. Unlike the unit tests, these fail
 * (not skip) when libmapnik_c or the Mapnik input plugins are missing.
 *
 * Fixture: a red 20x20 degree square centred on (0, 0) over a white background.
 * With a 200x200 map zoomed to [-20, 20] the square covers pixels 50..149 on both axes.
 */
class MapnikIntegrationTest {
    private static final int RED = 0xFFFF0000;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLUE = 0xFF0000FF;

    @TempDir
    static Path dir;

    @BeforeAll
    static void setUp() throws IOException {
        String plugins = System.getProperty("mapnik.input.plugins");
        assertTrue(plugins != null && !plugins.isEmpty(),
            "Mapnik input plugin directory unknown: put mapnik-config on PATH or set MAPNIK_INPUT_PLUGINS");
        Mapnik.registerDatasources(plugins);
        for (String f : new String[] {"square.xml", "square.geojson", "missing-data.xml", "two-layers.xml", "small.geojson"}) {
            copyResource(f, dir.resolve(f));
        }
    }

    @Test
    void loadedMapnikIsTheVersionTheWrapperTargets() {
        assertTrue(Mapnik.isCompatible(),
            "built for " + Mapnik.expectedVersion() + " but native is " + Mapnik.version());
    }

    @Test
    void rendersPolygonFromGeoJsonFile() throws IOException {
        try (MapnikMap map = squareMap(200, 200)) {
            BufferedImage img = decode(map.renderToPng());
            assertEquals(200, img.getWidth());
            assertEquals(200, img.getHeight());
            assertEquals(RED, img.getRGB(100, 100), "centre should be inside the square");
            assertEquals(RED, img.getRGB(60, 60));
            assertEquals(RED, img.getRGB(140, 140));
            assertEquals(WHITE, img.getRGB(10, 10), "corner should be background");
            assertEquals(WHITE, img.getRGB(190, 190));
            assertEquals(WHITE, img.getRGB(40, 100), "just outside the square");
        }
    }

    @Test
    void loadFromStringResolvesRelativeDatasourcePath() throws IOException {
        String xml = new String(Files.readAllBytes(dir.resolve("square.xml")), "UTF-8");
        try (MapnikMap map = new MapnikMap(200, 200)) {
            map.loadString(xml, dir).zoomToBox(-20, -20, 20, 20);
            assertEquals(RED, decode(map.renderToPng()).getRGB(100, 100));
        }
    }

    @Test
    void zoomingAwayFromTheDataLeavesOnlyBackground() throws IOException {
        try (MapnikMap map = new MapnikMap(100, 100)) {
            map.load(dir.resolve("square.xml")).zoomToBox(50, 50, 60, 60);
            BufferedImage img = decode(map.renderToPng());
            for (int p : new int[] {img.getRGB(0, 0), img.getRGB(50, 50), img.getRGB(99, 99)}) {
                assertEquals(WHITE, p);
            }
        }
    }

    @Test
    void zoomAllFitsTheLayerExtent() throws IOException {
        try (MapnikMap map = new MapnikMap(200, 200)) {
            map.load(dir.resolve("square.xml")).zoomAll();
            BufferedImage img = decode(map.renderToPng());
            assertEquals(RED, img.getRGB(100, 100));
            assertEquals(RED, img.getRGB(5, 5), "zoomAll should fill the frame with the square");
        }
    }

    @Test
    void resizeChangesOutputDimensions() throws IOException {
        try (MapnikMap map = squareMap(200, 200)) {
            map.resize(64, 32).zoomToBox(-20, -20, 20, 20);
            BufferedImage img = decode(map.renderToPng());
            assertEquals(64, img.getWidth());
            assertEquals(32, img.getHeight());
        }
    }

    @Test
    void renderToFileMatchesRenderToBytes() throws IOException {
        Path out = dir.resolve("out.png");
        try (MapnikMap map = squareMap(120, 120)) {
            map.renderToFile(out, "png");
            assertArrayEquals(map.renderToPng(), Files.readAllBytes(out));
        }
    }

    @Test
    void rendersJpeg() throws IOException {
        try (MapnikMap map = squareMap(64, 64)) {
            byte[] jpeg = map.renderToBytes("jpeg");
            assertEquals((byte) 0xFF, jpeg[0]);
            assertEquals((byte) 0xD8, jpeg[1]);
            assertNotNull(ImageIO.read(new ByteArrayInputStream(jpeg)));
        }
    }

    @Test
    void repeatedRendersAreIdentical() {
        try (MapnikMap map = squareMap(100, 100)) {
            byte[] first = map.renderToPng();
            for (int i = 0; i < 5; i++) {
                assertArrayEquals(first, map.renderToPng());
            }
        }
    }

    @Test
    void missingStyleFileThrows() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            MapnikException e = assertThrows(MapnikException.class,
                () -> map.load(dir.resolve("nope.xml")));
            assertFalse(e.getMessage().isEmpty());
        }
    }

    @Test
    void missingDatasourceFileThrows() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            MapnikException e = assertThrows(MapnikException.class,
                () -> map.load(dir.resolve("missing-data.xml")));
            assertFalse(e.getMessage().isEmpty());
        }
    }

    @Test
    void unknownOutputFormatThrows() {
        try (MapnikMap map = squareMap(10, 10)) {
            assertThrows(MapnikException.class, () -> map.renderToBytes("not-a-format"));
        }
    }

    @Test
    void mapRecoversAfterFailedRender() throws IOException {
        try (MapnikMap map = squareMap(50, 50)) {
            assertThrows(MapnikException.class, () -> map.renderToBytes("not-a-format"));
            assertEquals(RED, decode(map.renderToPng()).getRGB(25, 25));
        }
    }

    @Test
    void closedMapRejectsUse() {
        MapnikMap map = new MapnikMap(10, 10);
        map.close();
        map.close(); // idempotent
        assertThrows(IllegalStateException.class, map::renderToPng);
    }

    @Test
    void separateMapsRenderConcurrently() throws Exception {
        byte[] expected;
        try (MapnikMap map = squareMap(100, 100)) {
            expected = map.renderToPng();
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<byte[]>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                results.add(pool.submit((Callable<byte[]>) () -> {
                    try (MapnikMap map = squareMap(100, 100)) {
                        byte[] last = null;
                        for (int i = 0; i < 5; i++) {
                            last = map.renderToPng();
                        }
                        return last;
                    }
                }));
            }
            for (Future<byte[]> f : results) {
                assertTrue(Arrays.equals(expected, f.get()));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- projection

    @Test
    void srsComesFromTheStyle() {
        try (MapnikMap map = squareMap(10, 10)) {
            assertEquals("epsg:4326", map.srs());
        }
    }

    @Test
    void setSrsReprojectsLayersWhenRendering() throws IOException {
        try (MapnikMap map = new MapnikMap(400, 400)) {
            map.load(dir.resolve("square.xml")).setSrs("epsg:3857");
            assertEquals("epsg:3857", map.srs());
            // Whole web-mercator world. The +-10 degree square is about +-1.1e6 m,
            // so it covers pixels ~189..211 here, not the 50..149 it covers in the 4326 tests.
            map.zoomToBox(-20037508, -20037508, 20037508, 20037508);
            BufferedImage img = decode(map.renderToPng());
            assertEquals(RED, img.getRGB(200, 200));
            assertEquals(RED, img.getRGB(192, 192));
            assertEquals(WHITE, img.getRGB(180, 200));
            assertEquals(WHITE, img.getRGB(220, 200));
            assertEquals(WHITE, img.getRGB(100, 100));
        }
    }

    @Test
    void zoomExtentIsInTheMapProjection() throws IOException {
        // +-2226390 m is about +-20 degrees of longitude, so the +-10 degree square
        // covers half the frame: pixels 50..149, as in the 4326 tests.
        try (MapnikMap map = new MapnikMap(200, 200)) {
            map.load(dir.resolve("square.xml")).setSrs("epsg:3857");
            map.zoomToBox(-2226390, -2226390, 2226390, 2226390);
            BufferedImage img = decode(map.renderToPng());
            assertEquals(RED, img.getRGB(100, 100));
            assertEquals(RED, img.getRGB(60, 60));
            assertEquals(RED, img.getRGB(140, 140));
            assertEquals(WHITE, img.getRGB(40, 100));
            assertEquals(WHITE, img.getRGB(100, 40));
        }
    }

    @Test
    void invalidSrsFailsWhenRendering() {
        try (MapnikMap map = squareMap(10, 10)) {
            map.setSrs("+proj=nonsense");
            assertThrows(MapnikException.class, map::renderToPng);
        }
    }

    // ---------------------------------------------------------------- layers

    private static MapnikMap twoLayerMap() {
        MapnikMap map = new MapnikMap(200, 200);
        try {
            return map.load(dir.resolve("two-layers.xml")).zoomToBox(-20, -20, 20, 20);
        } catch (RuntimeException e) {
            map.close();
            throw e;
        }
    }

    @Test
    void layerNamesAreInDrawingOrder() {
        try (MapnikMap map = twoLayerMap()) {
            assertEquals(Arrays.asList("big", "small"), map.layerNames());
            assertTrue(map.isLayerActive("big"));
            assertTrue(map.isLayerActive("small"));
        }
    }

    @Test
    void allLayersDrawByDefaultWithLaterOnTop() throws IOException {
        try (MapnikMap map = twoLayerMap()) {
            BufferedImage img = decode(map.renderToPng());
            assertEquals(BLUE, img.getRGB(100, 100), "small is drawn over big");
            assertEquals(RED, img.getRGB(60, 60), "big shows around small");
        }
    }

    @Test
    void deactivatingALayerHidesIt() throws IOException {
        try (MapnikMap map = twoLayerMap()) {
            map.setLayerActive("small", false);
            assertFalse(map.isLayerActive("small"));
            BufferedImage img = decode(map.renderToPng());
            assertEquals(RED, img.getRGB(100, 100));

            map.setLayerActive("small", true).setLayerActive("big", false);
            img = decode(map.renderToPng());
            assertEquals(BLUE, img.getRGB(100, 100));
            assertEquals(WHITE, img.getRGB(60, 60));
        }
    }

    @Test
    void setActiveLayersKeepsOnlyTheNamedOnes() throws IOException {
        try (MapnikMap map = twoLayerMap()) {
            map.setActiveLayers(Arrays.asList("big"));
            assertTrue(map.isLayerActive("big"));
            assertFalse(map.isLayerActive("small"));
            assertEquals(RED, decode(map.renderToPng()).getRGB(100, 100));

            map.setActiveLayers(Arrays.<String>asList());
            assertEquals(WHITE, decode(map.renderToPng()).getRGB(100, 100));

            map.activateAllLayers();
            assertEquals(BLUE, decode(map.renderToPng()).getRGB(100, 100));
        }
    }

    @Test
    void setActiveLayersIgnoresRequestOrder() throws IOException {
        try (MapnikMap map = twoLayerMap()) {
            map.setActiveLayers(Arrays.asList("small", "big"));
            assertEquals(BLUE, decode(map.renderToPng()).getRGB(100, 100), "style order still wins");
        }
    }

    @Test
    void unknownLayerIsRejectedAndChangesNothing() {
        try (MapnikMap map = twoLayerMap()) {
            assertThrows(IllegalArgumentException.class, () -> map.setLayerActive("nope", false));
            assertThrows(IllegalArgumentException.class, () -> map.isLayerActive("nope"));
            assertThrows(IllegalArgumentException.class,
                () -> map.setActiveLayers(Arrays.asList("big", "nope")));
            assertTrue(map.isLayerActive("big"));
            assertTrue(map.isLayerActive("small"));
        }
    }

    private static MapnikMap squareMap(int w, int h) {
        MapnikMap map = new MapnikMap(w, h);
        try {
            return map.load(dir.resolve("square.xml")).zoomToBox(-20, -20, 20, 20);
        } catch (RuntimeException e) {
            map.close();
            throw e;
        }
    }

    private static BufferedImage decode(byte[] png) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(img, "output is not a decodable image");
        return img;
    }

    private static void copyResource(String name, Path target) throws IOException {
        try (InputStream in = MapnikIntegrationTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, "missing test resource " + name);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
