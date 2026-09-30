package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** UTFGrid output against a real Mapnik. */
class UtfGridIntegrationTest {
    private static final int RED = 0xFFFF0000;
    private static final int WHITE = 0xFFFFFFFF;

    @BeforeAll
    static void setUp() throws IOException {
        Fixtures.dir();
    }

    private static Map<String, Object> attrs(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** A 100x100 map over [-50, 50]: one map unit is one pixel. */
    private static MapnikMap mapOf(MemoryDatasource ds) {
        MapnikMap map = new MapnikMap(100, 100).setSrs("epsg:4326").setBackground("white");
        try (Layer layer = Layer.create("places", "epsg:4326")) {
            map.addStyle(Style.create("s").add(Rule.create().add(Symbolizer.polygon().fill("red"))));
            layer.addStyle("s").setDatasource(ds);
            map.addLayer(layer);
        } catch (RuntimeException e) {
            map.close();
            throw e;
        }
        return map.zoomToBox(-50, -50, 50, 50);
    }

    /**
     * Three squares, in pixels of the 100x100 map: A at x 20..39, B at x 60..79, and C at x 70..89 which
     * overlaps B's right half. All cover y 40..59.
     */
    private static MemoryDatasource threeSquares() {
        return MemoryDatasource.create()
            .add(Feature.create(1, Geometry.rectangle(new Box2d(-30, -10, -10, 10)), attrs("name", "Alpha", "pop", 100)))
            .add(Feature.create(2, Geometry.rectangle(new Box2d(10, -10, 30, 10)), attrs("name", "Beta", "pop", 250)))
            .add(Feature.create(3, Geometry.rectangle(new Box2d(20, -10, 40, 10)), attrs("name", "Gamma", "pop", 7)));
    }

    // ---------------------------------------------------------------- which feature is where

    @Test
    void eachCellNamesTheFeatureDrawnThere() {
        try (MemoryDatasource ds = threeSquares(); MapnikMap map = mapOf(ds)) {
            UtfGrid g = map.renderGrid("places");
            assertEquals("1", g.keyAt(30, 50), "inside A");
            assertEquals("2", g.keyAt(65, 50), "inside B only");
            assertEquals("3", g.keyAt(85, 50), "inside C only");
            assertEquals("", g.keyAt(5, 5), "nothing there");
            assertEquals("", g.keyAt(50, 50), "between the squares");
        }
    }

    @Test
    void theFeatureDrawnLastWinsWhereTheyOverlap() {
        try (MemoryDatasource ds = threeSquares(); MapnikMap map = mapOf(ds)) {
            UtfGrid g = map.renderGrid("places");
            assertEquals("3", g.keyAt(75, 50), "B and C overlap here; C is drawn after B");
        }
    }

    @Test
    void keysAreListedInOrderOfAppearance() {
        try (MemoryDatasource ds = threeSquares(); MapnikMap map = mapOf(ds)) {
            assertEquals(Arrays.asList("1", "2", "3"), map.renderGrid("places").keys());
        }
    }

    @Test
    void aLayerWithNothingInViewGivesAnEmptyGrid() {
        try (MemoryDatasource ds = MemoryDatasource.create().add(Geometry.rectangle(new Box2d(100, 100, 120, 120)));
             MapnikMap map = mapOf(ds)) {
            UtfGrid g = map.renderGrid("places");
            assertTrue(g.keys().isEmpty());
            assertEquals("", g.keyAt(50, 50));
        }
    }

    // ---------------------------------------------------------------- naming and data

    @Test
    void featuresCanBeNamedByAnAttribute() {
        try (MemoryDatasource ds = threeSquares(); MapnikMap map = mapOf(ds)) {
            UtfGrid g = map.renderGrid("places", "name", Collections.<String>emptyList(), 4);
            assertEquals("Alpha", g.keyAt(30, 50));
            assertEquals("Beta", g.keyAt(65, 50));
            assertEquals("Gamma", g.keyAt(85, 50));
            assertEquals(Arrays.asList("Alpha", "Beta", "Gamma"), g.keys());
        }
    }

    @Test
    void requestedFieldsArriveWithTheirTypes() {
        try (MemoryDatasource ds = threeSquares(); MapnikMap map = mapOf(ds)) {
            UtfGrid g = map.renderGrid("places", "__id__", Arrays.asList("name", "pop"), 4);
            Map<String, Object> a = g.attributesAt(30, 50);
            assertEquals("Alpha", a.get("name"));
            assertEquals(Long.valueOf(100), a.get("pop"));
            assertEquals(Long.valueOf(250), g.attributesAt(65, 50).get("pop"));
            assertEquals("Gamma", g.attributesAt(75, 50).get("name"));
            assertTrue(g.attributesAt(5, 5).isEmpty());
        }
    }

    @Test
    void onlyRequestedFieldsAreIncluded() {
        try (MemoryDatasource ds = threeSquares(); MapnikMap map = mapOf(ds)) {
            UtfGrid g = map.renderGrid("places", "__id__", Collections.singletonList("pop"), 4);
            Map<String, Object> a = g.attributesAt(30, 50);
            assertEquals(1, a.size(), a.toString());
            assertEquals(Long.valueOf(100), a.get("pop"));
            assertFalse(a.containsKey("name"));
        }
    }

    @Test
    void noFieldsMeansEmptyData() {
        try (MemoryDatasource ds = threeSquares(); MapnikMap map = mapOf(ds)) {
            UtfGrid g = map.renderGrid("places");
            assertTrue(g.attributesAt(30, 50).isEmpty());
            assertEquals("1", g.keyAt(30, 50));
        }
    }

    @Test
    void theJsonHasTheThreePartsOfTheSpec() {
        try (MemoryDatasource ds = threeSquares(); MapnikMap map = mapOf(ds)) {
            String json = map.renderGrid("places", "__id__", Collections.singletonList("name"), 4).toJson();
            assertTrue(json.startsWith("{\"grid\":["), json.substring(0, Math.min(40, json.length())));
            assertTrue(json.contains("\"keys\":[\"\",\"1\",\"2\",\"3\"]"), json);
            assertTrue(json.contains("\"data\":{\"1\":{\"name\":\"Alpha\"}"), json);
        }
    }

    // ---------------------------------------------------------------- resolution

    @Test
    void resolutionSetsTheNumberOfCells() {
        try (MemoryDatasource ds = threeSquares(); MapnikMap map = mapOf(ds)) {
            assertEquals(100, map.renderGrid("places", "__id__", Collections.<String>emptyList(), 1).columns());
            assertEquals(50, map.renderGrid("places", "__id__", Collections.<String>emptyList(), 2).columns());
            UtfGrid four = map.renderGrid("places", "__id__", Collections.<String>emptyList(), 4);
            assertEquals(25, four.columns());
            assertEquals(25, four.rows());
            UtfGrid seven = map.renderGrid("places", "__id__", Collections.<String>emptyList(), 7);
            assertEquals(15, seven.columns(), "100 / 7 rounded up");
            assertEquals("1", seven.keyAt(30, 50));
            assertEquals("", seven.keyAt(99, 99), "the ragged last cell is readable");
        }
    }

    @Test
    void resolutionMustBePositive() {
        try (MemoryDatasource ds = threeSquares(); MapnikMap map = mapOf(ds)) {
            assertThrows(IllegalArgumentException.class,
                () -> map.renderGrid("places", "__id__", Collections.<String>emptyList(), 0));
        }
    }

    // ---------------------------------------------------------------- agrees with the picture

    @Test
    void theGridMatchesTheImageAtFullResolution() {
        try (MemoryDatasource ds = threeSquares(); MapnikMap map = mapOf(ds); Image img = map.renderToImage()) {
            UtfGrid g = map.renderGrid("places", "__id__", Collections.<String>emptyList(), 1);
            int mismatches = 0;
            for (int y = 0; y < 100; y++) {
                for (int x = 0; x < 100; x++) {
                    boolean drawn = img.getArgb(x, y) == RED;
                    boolean inGrid = !g.keyAt(x, y).isEmpty();
                    if (drawn != inGrid) {
                        mismatches++;
                    }
                }
            }
            assertEquals(0, mismatches, "a pixel is coloured exactly where the grid names a feature");
            assertEquals(WHITE, img.getArgb(5, 5));
        }
    }

    // ---------------------------------------------------------------- many features, spec characters

    @Test
    void manyFeaturesSurviveTheCharacterMapping() {
        // 120 small squares on a 240x200 map, one map unit per pixel. Their keys pass through the
        // codes the spec skips, '"' and '\'.
        int cols = 12;
        int rows = 10;
        try (MemoryDatasource ds = MemoryDatasource.create()) {
            for (int i = 0; i < cols * rows; i++) {
                int col = i % cols;
                int row = i / cols;
                double x0 = col * 20 + 2;
                double y0 = (rows - 1 - row) * 20 + 2;
                ds.add(Feature.create(i + 1, Geometry.rectangle(new Box2d(x0, y0, x0 + 16, y0 + 16)), attrs("n", i)));
            }
            MapnikMap map = new MapnikMap(240, 200).setSrs("epsg:4326").setBackground("white");
            try (Layer layer = Layer.create("grid", "epsg:4326")) {
                map.addStyle(Style.create("s").add(Rule.create().add(Symbolizer.polygon().fill("red"))));
                layer.addStyle("s").setDatasource(ds);
                map.addLayer(layer);
            }
            try (MapnikMap m = map) {
                m.zoomToBox(0, 0, 240, 200);
                UtfGrid g = m.renderGrid("grid", "__id__", Collections.singletonList("n"), 1);
                assertEquals(cols * rows, g.keys().size());
                for (int i = 0; i < cols * rows; i++) {
                    int px = (i % cols) * 20 + 10;
                    int py = (i / cols) * 20 + 10;
                    assertEquals(Long.valueOf(i), g.attributesAt(px, py).get("n"), "square " + i + " at " + px + "," + py);
                    assertEquals(String.valueOf(i + 1), g.keyAt(px, py));
                }
                assertEquals("", g.keyAt(0, 0));
            }
        }
    }

    // ---------------------------------------------------------------- options and errors

    @Test
    void renderOptionsApply() {
        try (MemoryDatasource ds = threeSquares(); MapnikMap map = mapOf(ds)) {
            UtfGrid g = map.renderGrid("places", "__id__", Collections.<String>emptyList(), 1,
                RenderOptions.defaults().offset(20, 0));
            assertEquals("1", g.keyAt(10, 50), "the window moved 20 pixels right, so A is 20 pixels further left");
        }
    }

    @Test
    void unknownLayersAreRejected() {
        try (MemoryDatasource ds = threeSquares(); MapnikMap map = mapOf(ds)) {
            assertThrows(IllegalArgumentException.class, () -> map.renderGrid("nope"));
        }
    }

    @Test
    void aLayerWithoutADatasourceGivesAnEmptyGrid() {
        try (MapnikMap map = new MapnikMap(50, 50).setSrs("epsg:4326"); Layer l = Layer.create("empty", "epsg:4326")) {
            map.addLayer(l);
            map.zoomToBox(-10, -10, 10, 10);
            assertTrue(map.renderGrid("empty").keys().isEmpty());
        }
    }
}
