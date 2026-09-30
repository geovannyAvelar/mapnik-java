package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A datasource whose features come from Java callbacks, against a real Mapnik. */
class JavaDatasourceIntegrationTest {
    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;
    private static final int WHITE = 0xFFFFFFFF;

    private static final Box2d WORLD = new Box2d(-50, -50, 50, 50);

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

    private static Feature square(long id, double cx, double cy, double half, Map<String, Object> a) {
        return Feature.create(id, Geometry.rectangle(new Box2d(cx - half, cy - half, cx + half, cy + half)), a);
    }

    /** A 100x100 map over [-50, 50]: one map unit is one pixel. */
    private static MapnikMap mapOf(Datasource ds, Rule... rules) {
        MapnikMap map = new MapnikMap(100, 100).setSrs("epsg:4326").setBackground("white");
        try (Layer layer = Layer.create("data", "epsg:4326")) {
            map.addStyle(Style.create("s").add(rules));
            layer.addStyle("s").setDatasource(ds);
            map.addLayer(layer);
        } catch (RuntimeException e) {
            map.close();
            throw e;
        }
        return map.zoomToBox(-50, -50, 50, 50);
    }

    private static Rule red() {
        return Rule.create().add(Symbolizer.polygon().fill("red"));
    }

    // ---------------------------------------------------------------- features from Java reach the picture

    @Test
    void rendersFeaturesReturnedByTheSource() {
        FeatureSource source = r -> Collections.singletonList(square(1, 0, 0, 20, attrs()));
        try (JavaDatasource ds = JavaDatasource.create(source, WORLD); MapnikMap map = mapOf(ds, red()); Image img = map.renderToImage()) {
            assertEquals(RED, img.getArgb(50, 50));
            assertEquals(WHITE, img.getArgb(5, 5));
        }
    }

    @Test
    void theSourceIsAskedForTheAreaBeingDrawn() {
        List<FeatureRequest> seen = new CopyOnWriteArrayList<>();
        FeatureSource source = r -> {
            seen.add(r);
            return Collections.<Feature>emptyList();
        };
        try (JavaDatasource ds = JavaDatasource.create(source, WORLD); MapnikMap map = mapOf(ds, red())) {
            map.renderToPng();
            assertEquals(1, seen.size(), "one request per layer per render");
            FeatureRequest r = seen.get(0);
            assertEquals(-50, r.bbox().minX(), 1.0);
            assertEquals(50, r.bbox().maxX(), 1.0);
            assertEquals(-50, r.bbox().minY(), 1.0);
            assertEquals(50, r.bbox().maxY(), 1.0);
            assertTrue(r.resolutionX() > 0 && r.resolutionY() > 0, r.toString());
            assertTrue(r.scaleDenominator() > 0, r.toString());
            assertTrue(r.toString().contains("FeatureRequest"));
        }
    }

    @Test
    void zoomingChangesWhatIsRequested() {
        List<Box2d> boxes = new CopyOnWriteArrayList<>();
        FeatureSource source = r -> {
            boxes.add(r.bbox());
            return Collections.<Feature>emptyList();
        };
        try (JavaDatasource ds = JavaDatasource.create(source, WORLD); MapnikMap map = mapOf(ds, red())) {
            map.renderToPng();
            map.zoomToBox(-10, -10, 10, 10);
            map.renderToPng();
            assertEquals(2, boxes.size());
            assertTrue(boxes.get(1).width() < boxes.get(0).width() / 2, boxes.get(0) + " then " + boxes.get(1));
        }
    }

    @Test
    void theSourceCanReturnJustWhatIsInView() {
        List<Feature> all = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            all.add(square(i + 1, i * 10 - 400, 0, 4, attrs("n", i))); // a long row of squares
        }
        AtomicInteger returned = new AtomicInteger();
        FeatureSource source = r -> {
            List<Feature> hit = new ArrayList<>();
            for (Feature f : all) {
                Box2d e = f.envelope();
                if (e.maxX() >= r.bbox().minX() && e.minX() <= r.bbox().maxX()) {
                    hit.add(f);
                }
            }
            returned.set(hit.size());
            return hit;
        };
        try (JavaDatasource ds = JavaDatasource.create(source, new Box2d(-400, -5, 600, 5)); MapnikMap map = mapOf(ds, red()); Image img = map.renderToImage()) {
            assertTrue(returned.get() < 20, "only the squares in view were fetched: " + returned.get());
            assertEquals(RED, img.getArgb(50, 50), "the square at x 0");
        }
    }

    // ---------------------------------------------------------------- data can change between renders

    @Test
    void eachRenderAsksAgain() {
        AtomicInteger calls = new AtomicInteger();
        FeatureSource source = r -> {
            double x = calls.getAndIncrement() % 2 == 0 ? -25 : 25;
            return Collections.singletonList(square(1, x, 0, 10, attrs()));
        };
        try (JavaDatasource ds = JavaDatasource.create(source, WORLD); MapnikMap map = mapOf(ds, red())) {
            try (Image a = map.renderToImage(); Image b = map.renderToImage()) {
                assertEquals(RED, a.getArgb(25, 50), "first render: the square is on the left");
                assertEquals(WHITE, a.getArgb(75, 50));
                assertEquals(RED, b.getArgb(75, 50), "second render: it moved right");
                assertEquals(WHITE, b.getArgb(25, 50));
            }
            assertEquals(2, calls.get());
        }
    }

    // ---------------------------------------------------------------- attributes and geometry types

    @Test
    void filtersSeeTheAttributesTheSourceGives() {
        FeatureSource source = r -> Arrays.asList(
            square(1, -25, 0, 10, attrs("count", 3, "name", "Zürich ☃")),
            square(2, 25, 0, 10, attrs("count", 0, "name", "other")));
        try (JavaDatasource ds = JavaDatasource.create(source, WORLD);
             MapnikMap map = mapOf(ds,
                 Rule.create().filter("[count] > 2 and [name] = 'Zürich ☃'").add(Symbolizer.polygon().fill("red")),
                 Rule.create().elseFilter().add(Symbolizer.polygon().fill("blue")));
             Image img = map.renderToImage()) {
            assertEquals(RED, img.getArgb(25, 50));
            assertEquals(BLUE, img.getArgb(75, 50));
        }
    }

    @Test
    void everyAttributeTypeSurvivesTheTrip() {
        FeatureSource source = r -> Collections.singletonList(Feature.create(7, Geometry.point(0, 0),
            attrs("s", "text ü", "i", 5, "d", 2.5, "b", true, "n", null)));
        try (JavaDatasource ds = JavaDatasource.create(source, WORLD); Featureset fs = ds.features(WORLD)) {
            Feature f = fs.next();
            assertEquals(7, f.id());
            assertEquals("text ü", f.attribute("s"));
            assertEquals(Long.valueOf(5), f.attribute("i"));
            assertEquals(Double.valueOf(2.5), f.attribute("d"));
            assertEquals(Boolean.TRUE, f.attribute("b"));
            assertTrue(f.attributes().containsKey("n"));
            assertNull(f.attribute("n"));
        }
    }

    @Test
    void everyGeometryTypeSurvivesTheTrip() {
        Geometry[] samples = {
            Geometry.point(1.25, -2.5), Geometry.lineString(0, 0, 1.5, 2.5, 3, 0),
            Geometry.polygon(new double[] {0, 0, 10, 0, 10, 10, 0, 10, 0, 0}, new double[] {2, 2, 2, 4, 4, 4, 4, 2, 2, 2}),
            Geometry.multiPoint(1, 1, 2, 2),
            Geometry.multiLineString(Geometry.lineString(0, 0, 1, 1), Geometry.lineString(5, 5, 6, 7)),
            Geometry.multiPolygon(Geometry.polygon(new double[] {0, 0, 1, 0, 1, 1, 0, 0})),
            Geometry.collection(Geometry.point(1, 2), Geometry.lineString(0, 0, 1, 1))};
        for (Geometry g : samples) {
            FeatureSource source = r -> Collections.singletonList(Feature.create(1, g));
            try (JavaDatasource ds = JavaDatasource.create(source, WORLD); Featureset fs = ds.features(WORLD)) {
                assertEquals(g, fs.next().geometry(), g.toWkt());
            }
        }
    }

    @Test
    void featuresWithNoGeometryAreAccepted() {
        FeatureSource source = r -> Collections.singletonList(Feature.create(1, Geometry.empty(), attrs("a", 1)));
        try (JavaDatasource ds = JavaDatasource.create(source, WORLD); Featureset fs = ds.features(WORLD)) {
            Feature f = fs.next();
            assertEquals(Long.valueOf(1), f.attribute("a"));
            assertEquals(GeometryKind.UNKNOWN, f.geometryKind());
        }
    }

    @Test
    void anEmptyOrNullResultIsFine() {
        try (JavaDatasource a = JavaDatasource.create(r -> Collections.<Feature>emptyList(), WORLD);
             JavaDatasource b = JavaDatasource.create(r -> null, WORLD)) {
            try (Featureset fs = a.features(WORLD)) {
                assertTrue(fs.toList().isEmpty());
            }
            try (Featureset fs = b.features(WORLD)) {
                assertTrue(fs.toList().isEmpty());
            }
        }
    }

    // ---------------------------------------------------------------- the datasource's own description

    @Test
    void reportsTheEnvelopeAndTypeItWasGiven() {
        try (JavaDatasource ds = JavaDatasource.create(r -> Collections.<Feature>emptyList(), new Box2d(-5, -6, 7, 8))) {
            assertEquals(new Box2d(-5, -6, 7, 8), ds.envelope());
            assertEquals(Datasource.Type.VECTOR, ds.type());
            assertEquals(Datasource.GeometryType.COLLECTION, ds.geometryType());
            ds.setEnvelope(new Box2d(0, 0, 1, 1));
            assertEquals(new Box2d(0, 0, 1, 1), ds.envelope());
        }
    }

    @Test
    void declaredFieldsAreListed() {
        List<Datasource.Field> fields = Arrays.asList(
            Datasource.Field.of("name", Datasource.FieldType.STRING), Datasource.Field.of("count", Datasource.FieldType.INTEGER),
            Datasource.Field.of("ratio", Datasource.FieldType.DOUBLE));
        try (JavaDatasource ds = JavaDatasource.create(r -> Collections.<Feature>emptyList(), WORLD, fields)) {
            List<Datasource.Field> got = ds.fields();
            assertEquals(3, got.size());
            assertEquals("name", got.get(0).name(), "in the order declared");
            assertEquals(Datasource.FieldType.STRING, got.get(0).type());
            assertEquals("count", got.get(1).name());
            assertEquals(Datasource.FieldType.INTEGER, got.get(1).type());
            assertEquals("ratio", got.get(2).name());
            assertEquals(Datasource.FieldType.DOUBLE, got.get(2).type());
        }
    }

    @Test
    void fieldsNeedANameAndAType() {
        assertThrows(IllegalArgumentException.class, () -> Datasource.Field.of("", Datasource.FieldType.STRING));
        assertThrows(IllegalArgumentException.class, () -> Datasource.Field.of("a", null));
        assertThrows(IllegalArgumentException.class, () -> JavaDatasource.create(null, WORLD));
        assertThrows(IllegalArgumentException.class, () -> JavaDatasource.create(r -> null, null));
    }

    @Test
    void zoomAllUsesTheReportedEnvelope() {
        try (JavaDatasource ds = JavaDatasource.create(r -> Collections.<Feature>emptyList(), new Box2d(10, 20, 30, 40));
             MapnikMap map = mapOf(ds, red())) {
            map.zoomAll();
            Box2d e = map.extent();
            assertTrue(e.minX() <= 10 && e.maxX() >= 30 && e.minY() <= 20 && e.maxY() >= 40, e.toString());
        }
    }

    // ---------------------------------------------------------------- queries through the Datasource API

    @Test
    void boxQueriesCallTheSource() {
        List<Box2d> boxes = new CopyOnWriteArrayList<>();
        FeatureSource source = r -> {
            boxes.add(r.bbox());
            return Collections.singletonList(square(1, 0, 0, 1, attrs()));
        };
        try (JavaDatasource ds = JavaDatasource.create(source, WORLD); Featureset fs = ds.features(new Box2d(-3, -4, 5, 6))) {
            assertEquals(1, fs.toList().size());
            assertEquals(new Box2d(-3, -4, 5, 6), boxes.get(0));
        }
    }

    @Test
    void pointQueriesAskForTheToleranceAroundThePoint() {
        List<Box2d> boxes = new CopyOnWriteArrayList<>();
        FeatureSource source = r -> {
            boxes.add(r.bbox());
            return Collections.<Feature>emptyList();
        };
        try (JavaDatasource ds = JavaDatasource.create(source, WORLD); Featureset fs = ds.featuresAtPoint(10, 20, 2)) {
            fs.toList();
            assertEquals(new Box2d(8, 18, 12, 22), boxes.get(0));
        }
    }

    // ---------------------------------------------------------------- errors

    @Test
    void anExceptionInTheSourceFailsTheRenderWithItsMessage() {
        FeatureSource source = r -> {
            throw new IllegalStateException("the database is down");
        };
        try (JavaDatasource ds = JavaDatasource.create(source, WORLD); MapnikMap map = mapOf(ds, red())) {
            MapnikException e = assertThrows(MapnikException.class, map::renderToPng);
            assertTrue(e.getMessage().contains("the database is down"), e.getMessage());
            assertTrue(e.getMessage().contains("IllegalStateException"), e.getMessage());
        }
    }

    @Test
    void theMapKeepsWorkingAfterASourceFailure() {
        AtomicInteger calls = new AtomicInteger();
        FeatureSource source = r -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("first call fails");
            }
            return Collections.singletonList(square(1, 0, 0, 20, attrs()));
        };
        try (JavaDatasource ds = JavaDatasource.create(source, WORLD); MapnikMap map = mapOf(ds, red())) {
            assertThrows(MapnikException.class, map::renderToPng);
            try (Image img = map.renderToImage()) {
                assertEquals(RED, img.getArgb(50, 50));
            }
        }
    }

    @Test
    void aSourceFailureReachesFeatureQueriesToo() {
        try (JavaDatasource ds = JavaDatasource.create(r -> {
            throw new UnsupportedOperationException("nope");
        }, WORLD)) {
            MapnikException e = assertThrows(MapnikException.class, () -> ds.features(WORLD).toList());
            assertTrue(e.getMessage().contains("nope"), e.getMessage());
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    void theSourceOutlivesTheHandleWhileALayerUsesIt() {
        MapnikMap map;
        try (JavaDatasource ds = JavaDatasource.create(r -> Collections.singletonList(square(1, 0, 0, 20, attrs())), WORLD)) {
            map = mapOf(ds, red());
        }
        // The handle is closed, but the layer still holds the datasource, so Mapnik can still call the source.
        try (MapnikMap m = map; Image img = m.renderToImage()) {
            assertEquals(RED, img.getArgb(50, 50));
        }
    }

    @Test
    void theSourceIsReleasedWhenMapnikIsDone() {
        int before = JavaDatasource.liveSources();
        for (int i = 0; i < 5; i++) {
            try (JavaDatasource ds = JavaDatasource.create(r -> Collections.<Feature>emptyList(), WORLD);
                 MapnikMap map = mapOf(ds, red())) {
                map.renderToPng();
                assertEquals(before + 1, JavaDatasource.liveSources());
            }
        }
        assertEquals(before, JavaDatasource.liveSources(), "nothing is left registered once every handle and map is closed");
    }

    @Test
    void closedHandlesAreRejected() {
        JavaDatasource ds = JavaDatasource.create(r -> Collections.<Feature>emptyList(), WORLD);
        ds.close();
        assertThrows(IllegalStateException.class, () -> ds.setEnvelope(WORLD));
        assertEquals(JavaDatasource.liveSources(), JavaDatasource.liveSources());
    }

    // ---------------------------------------------------------------- threads and volume

    @Test
    void manyThreadsCanRenderAtOnce() throws Exception {
        FeatureSource source = r -> Collections.singletonList(square(1, 0, 0, 20, attrs("n", 1)));
        try (JavaDatasource ds = JavaDatasource.create(source, WORLD)) {
            byte[] expected;
            try (MapnikMap map = mapOf(ds, red())) {
                expected = map.renderToPng();
            }
            ExecutorService pool = Executors.newFixedThreadPool(6);
            try {
                List<Future<byte[]>> results = new ArrayList<>();
                for (int t = 0; t < 12; t++) {
                    results.add(pool.submit((Callable<byte[]>) () -> {
                        try (MapnikMap map = mapOf(ds, red())) {
                            byte[] last = null;
                            for (int i = 0; i < 5; i++) {
                                last = map.renderToPng();
                            }
                            return last;
                        }
                    }));
                }
                for (Future<byte[]> f : results) {
                    assertArrayEquals(expected, f.get());
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void thousandsOfFeaturesWork() {
        List<Feature> all = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            all.add(Feature.create(i + 1, Geometry.point(-49 + (i % 100) * 0.98, -49 + (i / 100) * 0.98), attrs("n", i)));
        }
        try (JavaDatasource ds = JavaDatasource.create(r -> all, WORLD);
             MapnikMap map = mapOf(ds, Rule.create().add(Symbolizer.markers().attr("marker-type", "ellipse").size(3, 3)
                 .fill("red").allowOverlap(true)));
             Image img = map.renderToImage()) {
            int red = 0;
            for (int p : img.toArgb()) {
                if (p != WHITE) {
                    red++;
                }
            }
            assertTrue(red > 1000, "5000 dots leave a lot of ink: " + red);
            try (Featureset fs = ds.features(WORLD)) {
                assertEquals(5000, fs.toList().size());
            }
        }
    }
}
