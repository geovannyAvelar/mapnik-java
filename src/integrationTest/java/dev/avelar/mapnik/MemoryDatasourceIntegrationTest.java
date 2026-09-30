package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Rendering and querying data that never touched a file, against a real Mapnik. */
class MemoryDatasourceIntegrationTest {
    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;
    private static final int WHITE = 0xFFFFFFFF;

    private static final double[] SQUARE = {-10, -10, 10, -10, 10, 10, -10, 10, -10, -10};
    private static final double[] HOLE = {-5, -5, -5, 5, 5, 5, 5, -5, -5, -5};

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

    /** A 200x200 white map over [-20, 20] with the datasource drawn by the given symbolizers. */
    private static MapnikMap mapOf(Datasource ds, Rule... rules) {
        MapnikMap map = new MapnikMap(200, 200).setSrs("epsg:4326").setBackground("white");
        try (Layer layer = Layer.create("data", "epsg:4326")) {
            map.addStyle(Style.create("s").add(rules));
            layer.addStyle("s").setDatasource(ds);
            map.addLayer(layer);
        } catch (RuntimeException e) {
            map.close();
            throw e;
        }
        return map.zoomToBox(-20, -20, 20, 20);
    }

    private static Rule fill(String color) {
        return Rule.create().add(Symbolizer.polygon().fill(color));
    }

    // ---------------------------------------------------------------- basics

    @Test
    void startsEmptyAndCountsWhatYouAdd() {
        try (MemoryDatasource ds = MemoryDatasource.create()) {
            assertEquals(0, ds.size());
            assertEquals(Datasource.Type.VECTOR, ds.type());
            ds.add(Geometry.point(1, 2)).add(Geometry.point(3, 4), attrs("a", 1));
            ds.add(Feature.create(50, Geometry.point(5, 6)));
            assertEquals(3, ds.size());
            ds.clear();
            assertEquals(0, ds.size());
        }
    }

    @Test
    void featuresComeBackWithTheirIdsGeometryAndTypedAttributes() {
        try (MemoryDatasource ds = MemoryDatasource.create()) {
            ds.add(Geometry.point(1, 2), attrs("name", "alpha", "count", 3, "ratio", 1.5, "active", true));
            ds.add(Geometry.point(10, 20), attrs("name", "beta", "count", 7, "ratio", 2.5, "active", false));

            try (Featureset fs = ds.features(new Box2d(-100, -100, 100, 100))) {
                List<Feature> all = fs.toList();
                assertEquals(2, all.size());
                Feature alpha = all.get(0);
                assertEquals(1, alpha.id());
                assertEquals("POINT(1 2)", alpha.geometryWkt());
                assertEquals("alpha", alpha.attribute("name"));
                assertEquals(Long.valueOf(3), alpha.attribute("count"));
                assertEquals(Double.valueOf(1.5), alpha.attribute("ratio"));
                assertEquals(Boolean.TRUE, alpha.attribute("active"));
                assertEquals(2, all.get(1).id());
                assertEquals(Boolean.FALSE, all.get(1).attribute("active"));
            }
        }
    }

    @Test
    void explicitIdsAreKeptAndAutomaticOnesFollowThem() {
        try (MemoryDatasource ds = MemoryDatasource.create()) {
            ds.add(Feature.create(40, Geometry.point(1, 1)));
            ds.add(Geometry.point(2, 2));
            try (Featureset fs = ds.features(new Box2d(-10, -10, 10, 10))) {
                List<Feature> all = fs.toList();
                assertEquals(40, all.get(0).id());
                assertEquals(41, all.get(1).id());
            }
        }
    }

    @Test
    void theEnvelopeFollowsTheFeaturesUnlessOverridden() {
        try (MemoryDatasource ds = MemoryDatasource.create()) {
            ds.add(Geometry.point(1, 2)).add(Geometry.point(10, 20));
            assertEquals(new Box2d(1, 2, 10, 20), ds.envelope());
            ds.setEnvelope(new Box2d(-5, -5, 5, 5));
            assertEquals(new Box2d(-5, -5, 5, 5), ds.envelope());
        }
    }

    @Test
    void theGeometryTypeIsAlwaysReportedAsACollection() {
        // Mapnik does not inspect in-memory features, whatever they hold.
        try (MemoryDatasource points = MemoryDatasource.create(); MemoryDatasource shapes = MemoryDatasource.create()) {
            points.add(Geometry.point(1, 2));
            shapes.add(Geometry.polygon(SQUARE));
            assertEquals(Datasource.GeometryType.COLLECTION, points.geometryType());
            assertEquals(Datasource.GeometryType.COLLECTION, shapes.geometryType());
        }
    }

    @Test
    void featuresAtPointAndBoxQueriesWork() {
        try (MemoryDatasource ds = MemoryDatasource.create()) {
            ds.add(Geometry.point(1, 2), attrs("name", "near"));
            ds.add(Geometry.point(50, 50), attrs("name", "far"));
            try (Featureset fs = ds.features(new Box2d(0, 0, 5, 5))) {
                List<Feature> r = fs.toList();
                assertEquals(1, r.size());
                assertEquals("near", r.get(0).attribute("name"));
            }
            try (Featureset fs = ds.featuresAtPoint(1.1, 2.1, 0.5)) {
                assertEquals(1, fs.toList().size());
            }
            try (Featureset fs = ds.features(new Box2d(100, 100, 110, 110))) {
                assertTrue(fs.toList().isEmpty());
            }
        }
    }

    @Test
    void textSurvivesUnicode() {
        String name = "Zürich ☃ 東京 \"quoted\"";
        try (MemoryDatasource ds = MemoryDatasource.create()) {
            ds.add(Geometry.point(0, 0), attrs("name", name));
            try (Featureset fs = ds.features(new Box2d(-1, -1, 1, 1))) {
                assertEquals(name, fs.next().attribute("name"));
            }
        }
    }

    @Test
    void nullAttributesStayNull() {
        try (MemoryDatasource ds = MemoryDatasource.create()) {
            ds.add(Geometry.point(0, 0), attrs("a", null, "b", "x"));
            try (Featureset fs = ds.features(new Box2d(-1, -1, 1, 1))) {
                Feature f = fs.next();
                assertTrue(f.attributes().containsKey("a"));
                assertNull(f.attribute("a"));
                assertEquals("x", f.attribute("b"));
            }
        }
    }

    @Test
    void aFeatureWithoutGeometryIsKept() {
        try (MemoryDatasource ds = MemoryDatasource.create()) {
            ds.add(Feature.create(1, Geometry.empty(), attrs("k", 1)));
            assertEquals(1, ds.size());
        }
    }

    @Test
    void addAllTakesAnyIterable() {
        try (MemoryDatasource ds = MemoryDatasource.create()) {
            ds.addAll(Arrays.asList(Feature.create(1, Geometry.point(1, 1)), Feature.create(2, Geometry.point(2, 2))));
            assertEquals(2, ds.size());
        }
    }

    @Test
    void closedMemoryDatasourceRejectsUse() {
        MemoryDatasource ds = MemoryDatasource.create();
        ds.close();
        assertThrows(IllegalStateException.class, () -> ds.add(Geometry.point(0, 0)));
        assertThrows(IllegalStateException.class, ds::size);
    }

    // ---------------------------------------------------------------- GeoJSON in

    @Test
    void addGeoJsonReadsAFeatureCollection() {
        String json = "{\"type\":\"FeatureCollection\",\"features\":["
            + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[1,2]},\"properties\":{\"name\":\"a\",\"n\":1}},"
            + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[0,0],[5,5]]},\"properties\":{\"name\":\"b\"}}]}";
        try (MemoryDatasource ds = MemoryDatasource.create().addGeoJson(json)) {
            assertEquals(2, ds.size());
            try (Featureset fs = ds.features(new Box2d(-10, -10, 10, 10))) {
                List<Feature> all = fs.toList();
                assertEquals("a", all.get(0).attribute("name"));
                assertEquals(GeometryKind.POINT, all.get(0).geometryKind());
                assertEquals(GeometryKind.LINE_STRING, all.get(1).geometryKind());
            }
        }
    }

    @Test
    void addGeoJsonRejectsBadInputAndKeepsWhatWasThere() {
        try (MemoryDatasource ds = MemoryDatasource.create()) {
            ds.add(Geometry.point(1, 1));
            assertThrows(IllegalArgumentException.class, () -> ds.addGeoJson("{\"type\":\"Nope\"}"));
            assertEquals(1, ds.size());
        }
    }

    // ---------------------------------------------------------------- rendering

    @Test
    void polygonsRenderWithTheirHoles() {
        try (MemoryDatasource ds = MemoryDatasource.create().add(Geometry.polygon(SQUARE, HOLE));
             MapnikMap map = mapOf(ds, fill("red")); Image img = map.renderToImage()) {
            assertEquals(RED, img.getArgb(100 - 15 * 5 / 2, 100), "in the ring, between hole and edge");
            assertEquals(WHITE, img.getArgb(100, 100), "the hole is empty");
            assertEquals(WHITE, img.getArgb(10, 10), "outside");
        }
    }

    @Test
    void filtersSeeInMemoryAttributes() {
        // 200 px over 40 units: 5 px per unit. alpha (1,2) -> (105, 90); beta (10,20) -> (150, 0).
        try (MemoryDatasource ds = MemoryDatasource.create()
                 .add(Geometry.point(1, 2), attrs("count", 3))
                 .add(Geometry.point(-10, -10), attrs("count", 0));
             MapnikMap map = mapOf(ds,
                 Rule.create().filter("[count] > 2").add(dot("red")),
                 Rule.create().elseFilter().add(dot("blue")));
             Image img = map.renderToImage()) {
            assertEquals(RED, img.getArgb(105, 90));
            assertEquals(BLUE, img.getArgb(50, 150));
            assertEquals(WHITE, img.getArgb(10, 10));
        }
    }

    private static Symbolizer dot(String color) {
        return Symbolizer.markers().attr("marker-type", "ellipse").size(20, 20).fill(color).allowOverlap(true);
    }

    @Test
    void linesRender() {
        try (MemoryDatasource ds = MemoryDatasource.create().add(Geometry.lineString(-20, 0, 20, 0));
             MapnikMap map = mapOf(ds, Rule.create().add(Symbolizer.line().stroke("red").strokeWidth(6)));
             Image img = map.renderToImage()) {
            assertEquals(RED, img.getArgb(100, 100));
            assertEquals(WHITE, img.getArgb(100, 50));
        }
    }

    @Test
    void multiPolygonsAndCollectionsRender() {
        Geometry left = Geometry.rectangle(new Box2d(-15, -5, -5, 5));
        Geometry right = Geometry.rectangle(new Box2d(5, -5, 15, 5));
        try (MemoryDatasource ds = MemoryDatasource.create()
                 .add(Geometry.multiPolygon((Geometry.Polygon) left, (Geometry.Polygon) right))
                 .add(Geometry.collection(Geometry.rectangle(new Box2d(-2, 10, 2, 14))));
             MapnikMap map = mapOf(ds, fill("red")); Image img = map.renderToImage()) {
            assertEquals(RED, img.getArgb(50, 100), "left part");
            assertEquals(RED, img.getArgb(150, 100), "right part");
            assertEquals(WHITE, img.getArgb(100, 100), "between them");
            assertEquals(RED, img.getArgb(100, 40), "polygon inside a collection");
        }
    }

    @Test
    void featuresAddedAfterTheLayerIsSetUpAreDrawn() {
        try (MemoryDatasource ds = MemoryDatasource.create(); MapnikMap map = mapOf(ds, fill("red"))) {
            try (Image empty = map.renderToImage()) {
                assertEquals(WHITE, empty.getArgb(100, 100));
            }
            ds.add(Geometry.rectangle(new Box2d(-10, -10, 10, 10)));
            try (Image full = map.renderToImage()) {
                assertEquals(RED, full.getArgb(100, 100));
            }
            ds.clear();
            try (Image again = map.renderToImage()) {
                assertEquals(WHITE, again.getArgb(100, 100));
            }
        }
    }

    @Test
    void theLayerKeepsTheDatasourceAliveAfterTheHandleIsClosed() {
        MapnikMap map;
        try (MemoryDatasource ds = MemoryDatasource.create().add(Geometry.rectangle(new Box2d(-10, -10, 10, 10)))) {
            map = mapOf(ds, fill("red"));
        }
        try (MapnikMap m = map; Image img = m.renderToImage()) {
            assertEquals(RED, img.getArgb(100, 100));
        }
    }

    @Test
    void aMemoryDatasourceIsAnOrdinaryDatasourceToLayers() {
        try (MemoryDatasource ds = MemoryDatasource.create().add(Geometry.point(3, 4)); Layer l = Layer.create("m")) {
            l.setDatasource(ds);
            assertEquals(new Box2d(3, 4, 3, 4), l.envelope());
            try (Datasource back = l.datasource().get()) {
                assertEquals(Datasource.Type.VECTOR, back.type());
            }
        }
    }

    // ---------------------------------------------------------------- Java and native agree

    @Test
    void everyGeometryTypeSurvivesTheTripThroughMapnik() {
        Geometry[] samples = {
            Geometry.point(1.25, -2.5),
            Geometry.lineString(0, 0, 1.5, 2.5, 3, 0),
            Geometry.polygon(SQUARE, HOLE),
            Geometry.multiPoint(1, 1, 2, 2),
            Geometry.multiLineString(Geometry.lineString(0, 0, 1, 1), Geometry.lineString(5, 5, 6, 7)),
            Geometry.multiPolygon(Geometry.polygon(SQUARE), Geometry.polygon(new double[] {20, 20, 25, 20, 25, 25, 20, 20})),
            Geometry.collection(Geometry.point(1, 2), Geometry.lineString(0, 0, 1, 1)),
        };
        for (Geometry g : samples) {
            try (MemoryDatasource ds = MemoryDatasource.create().add(g);
                 Featureset fs = ds.features(new Box2d(-1000, -1000, 1000, 1000))) {
                Feature back = fs.next();
                assertEquals(g.toWkt(), back.geometryWkt(), "Mapnik's WKT writer for " + g.toWkt());
                assertEquals(g.toGeoJson(), back.geometryGeoJson(), "Mapnik's GeoJSON writer for " + g.toWkt());
                assertEquals(g, back.geometry(), "as a Java geometry");
                assertEquals(g.envelope(), back.envelope(), "envelope of " + g.toWkt());
            }
        }
    }

    @Test
    void aJavaFeatureAndTheSameFeatureReadBackWriteIdenticalGeoJson() {
        Feature built = Feature.create(7, Geometry.polygon(SQUARE, HOLE),
            attrs("name", "Zürich \"☃\"\n", "n", 12, "r", 0.25, "ok", true, "none", null));
        try (MemoryDatasource ds = MemoryDatasource.create().add(built);
             Featureset fs = ds.features(new Box2d(-100, -100, 100, 100))) {
            Feature back = fs.next();
            assertEquals(built.toGeoJson(), back.toGeoJson());
            assertEquals(built.attributes(), back.attributes());
        }
    }
}
