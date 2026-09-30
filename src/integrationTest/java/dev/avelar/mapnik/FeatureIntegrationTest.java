package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Features, queries and geometry output against a real Mapnik. */
class FeatureIntegrationTest {
    private static Path dir;

    @BeforeAll
    static void setUp() throws IOException {
        dir = Fixtures.dir();
    }

    private static Datasource geojson(String file) {
        Map<String, Object> p = new HashMap<>();
        p.put("type", "geojson");
        p.put("file", dir.resolve(file).toString());
        return Datasource.create(p);
    }

    private static Feature only(String file) {
        try (Datasource ds = geojson(file); Featureset fs = ds.features(ds.envelope())) {
            List<Feature> all = fs.toList();
            assertEquals(1, all.size(), file);
            return all.get(0);
        }
    }

    // ---------------------------------------------------------------- reading features

    @Test
    void readsAllFeaturesWithTypedAttributes() {
        try (Datasource ds = geojson("places.geojson"); Featureset fs = ds.features(ds.envelope())) {
            List<Feature> all = fs.toList();
            assertEquals(3, all.size());

            Feature alpha = all.get(0);
            assertEquals("alpha", alpha.attribute("name"));
            assertEquals(Long.valueOf(3), alpha.attribute("count"));
            assertEquals(Double.valueOf(1.5), alpha.attribute("ratio"));
            assertEquals(Boolean.TRUE, alpha.attribute("active"));

            Feature beta = all.get(1);
            assertEquals("beta \"quoted\"", beta.attribute("name"));
            assertEquals(Boolean.FALSE, beta.attribute("active"));

            Feature gamma = all.get(2);
            assertEquals(Long.valueOf(0), gamma.attribute("count"));
            assertEquals(Double.valueOf(-0.25), gamma.attribute("ratio"));
        }
    }

    @Test
    void attributesAreSortedByNameAndReadOnly() {
        Feature f = only("poly-hole.geojson");
        assertEquals("donut", f.attribute("name"));
        assertNull(f.attribute("missing"));
        assertThrows(UnsupportedOperationException.class, () -> f.attributes().put("x", 1));

        try (Datasource ds = geojson("places.geojson"); Featureset fs = ds.features(ds.envelope())) {
            assertEquals("[active, count, name, ratio]", fs.next().attributes().keySet().toString());
        }
    }

    @Test
    void idsAreAssignedInFileOrder() {
        try (Datasource ds = geojson("places.geojson"); Featureset fs = ds.features(ds.envelope())) {
            List<Feature> all = fs.toList();
            assertEquals(1, all.get(0).id());
            assertEquals(2, all.get(1).id());
            assertEquals(3, all.get(2).id());
        }
    }

    @Test
    void featuresOutliveTheirFeatureset() {
        Feature f;
        try (Datasource ds = geojson("places.geojson"); Featureset fs = ds.features(ds.envelope())) {
            f = fs.next();
        }
        assertEquals("alpha", f.attribute("name"));
        assertEquals("POINT(1 2)", f.geometryWkt());
    }

    // ---------------------------------------------------------------- queries

    @Test
    void boxQueryReturnsOnlyFeaturesInTheBox() {
        try (Datasource ds = geojson("places.geojson")) {
            try (Featureset fs = ds.features(new Box2d(0, 0, 5, 5))) {
                List<Feature> r = fs.toList();
                assertEquals(1, r.size());
                assertEquals("alpha", r.get(0).attribute("name"));
            }
            try (Featureset fs = ds.features(new Box2d(-50, -50, 50, 50))) {
                assertEquals(3, fs.toList().size());
            }
            try (Featureset fs = ds.features(new Box2d(100, 100, 110, 110))) {
                assertTrue(fs.toList().isEmpty());
            }
        }
    }

    @Test
    void queryBuilderOptionsAreAccepted() {
        FeatureQuery q = FeatureQuery.within(new Box2d(-50, -50, 50, 50))
            .resolution(2, 2).scaleDenominator(1000).properties("name");
        try (Datasource ds = geojson("places.geojson"); Featureset fs = ds.features(q)) {
            List<Feature> r = fs.toList();
            assertEquals(3, r.size());
            assertNotNull(r.get(0).attribute("name"));
        }
    }

    @Test
    void featuresAtPointUsesATolerance() {
        try (Datasource ds = geojson("places.geojson")) {
            try (Featureset fs = ds.featuresAtPoint(10.2, 20.1, 1.0)) {
                List<Feature> r = fs.toList();
                assertEquals(1, r.size());
                assertEquals(2, r.get(0).id());
            }
            try (Featureset fs = ds.featuresAtPoint(50, 50, 0.5)) {
                assertTrue(fs.toList().isEmpty());
            }
        }
    }

    // ---------------------------------------------------------------- geometry output

    @Test
    void pointGeometry() {
        try (Datasource ds = geojson("places.geojson"); Featureset fs = ds.features(ds.envelope())) {
            Feature p = fs.next();
            assertEquals(GeometryKind.POINT, p.geometryKind());
            assertEquals("POINT(1 2)", p.geometryWkt());
            assertEquals("{\"type\":\"Point\",\"coordinates\":[1,2]}", p.geometryGeoJson());
            assertEquals(new Box2d(1, 2, 1, 2), p.envelope());
        }
    }

    @Test
    void lineStringGeometry() {
        Feature f = only("line.geojson");
        assertEquals(GeometryKind.LINE_STRING, f.geometryKind());
        assertEquals("LINESTRING(0 0,1.5 2.5,3 0)", f.geometryWkt());
        assertEquals("{\"type\":\"LineString\",\"coordinates\":[[0,0],[1.5,2.5],[3,0]]}", f.geometryGeoJson());
        assertEquals(new Box2d(0, 0, 3, 2.5), f.envelope());
    }

    @Test
    void polygonWithAHoleHasTwoRings() {
        Feature f = only("poly-hole.geojson");
        assertEquals(GeometryKind.POLYGON, f.geometryKind());
        // Mapnik normalises ring orientation, so the hole comes back reversed from the file.
        assertEquals("POLYGON((0 0,10 0,10 10,0 10,0 0),(2 2,2 4,4 4,4 2,2 2))", f.geometryWkt());
        assertTrue(f.geometryGeoJson().startsWith("{\"type\":\"Polygon\",\"coordinates\":[[[0,0]"));
        assertTrue(f.geometryGeoJson().contains("],[[2,2],[2,4],[4,4],[4,2],[2,2]]]"), f.geometryGeoJson());
        assertEquals(new Box2d(0, 0, 10, 10), f.envelope());
    }

    @Test
    void multiGeometries() {
        Feature mp = only("multi-point.geojson");
        assertEquals(GeometryKind.MULTI_POINT, mp.geometryKind());
        assertEquals("MULTIPOINT((1 1),(2 2))", mp.geometryWkt());
        assertEquals("{\"type\":\"MultiPoint\",\"coordinates\":[[1,1],[2,2]]}", mp.geometryGeoJson());

        Feature ml = only("multi-line.geojson");
        assertEquals(GeometryKind.MULTI_LINE_STRING, ml.geometryKind());
        assertEquals("MULTILINESTRING((0 0,1 1),(5 5,6 7))", ml.geometryWkt());
        assertEquals("{\"type\":\"MultiLineString\",\"coordinates\":[[[0,0],[1,1]],[[5,5],[6,7]]]}",
            ml.geometryGeoJson());

        Feature mpoly = only("multi-polygon.geojson");
        assertEquals(GeometryKind.MULTI_POLYGON, mpoly.geometryKind());
        assertEquals("MULTIPOLYGON(((0 0,1 0,1 1,0 1,0 0)),((5 5,6 5,6 6,5 6,5 5)))", mpoly.geometryWkt());
        assertTrue(mpoly.geometryGeoJson().startsWith("{\"type\":\"MultiPolygon\",\"coordinates\":[[[[0,0]"));
        assertEquals(new Box2d(0, 0, 6, 6), mpoly.envelope());
    }

    @Test
    void wholeFeatureGeoJson() {
        try (Datasource ds = geojson("places.geojson"); Featureset fs = ds.features(ds.envelope())) {
            fs.next();
            String json = fs.next().toGeoJson(); // beta, with a quote in its name
            assertTrue(json.startsWith("{\"type\":\"Feature\",\"id\":2,\"geometry\":{\"type\":\"Point\""), json);
            assertTrue(json.contains("\"coordinates\":[10,20]"), json);
            assertTrue(json.contains("\"name\":\"beta \\\"quoted\\\"\""), json);
            assertTrue(json.contains("\"count\":7"), json);
            assertTrue(json.contains("\"ratio\":2.5"), json);
            assertTrue(json.contains("\"active\":false"), json);
            assertTrue(json.endsWith("}}"), json);
        }
    }

    // ---------------------------------------------------------------- featureset behaviour

    @Test
    void iteratesWithForEachOnce() {
        try (Datasource ds = geojson("places.geojson"); Featureset fs = ds.features(ds.envelope())) {
            int n = 0;
            for (Feature f : fs) {
                assertNotNull(f.attribute("name"));
                n++;
            }
            assertEquals(3, n);
            assertThrows(IllegalStateException.class, fs::iterator);
        }
    }

    @Test
    void nextPastTheEndThrows() {
        try (Datasource ds = geojson("poly-hole.geojson"); Featureset fs = ds.features(ds.envelope())) {
            assertTrue(fs.hasNext());
            fs.next();
            assertFalse(fs.hasNext());
            assertThrows(NoSuchElementException.class, fs::next);
        }
    }

    @Test
    void closedFeaturesetRejectsUse() {
        Featureset fs;
        try (Datasource ds = geojson("places.geojson")) {
            fs = ds.features(ds.envelope());
        }
        fs.close();
        fs.close();
        assertThrows(IllegalStateException.class, fs::hasNext);
    }

    @Test
    void closingEarlyDiscardsTheRest() {
        try (Datasource ds = geojson("places.geojson")) {
            Featureset fs = ds.features(ds.envelope());
            fs.next();
            fs.close();
        }
    }

    // ---------------------------------------------------------------- map queries

    private static MapnikMap placesMap() {
        MapnikMap map = new MapnikMap(400, 400);
        map.loadString("<Map srs=\"epsg:4326\"><Style name=\"s\"><Rule><PolygonSymbolizer fill=\"red\"/></Rule></Style></Map>", dir);
        try (Layer layer = Layer.create("donut", "epsg:4326"); Datasource ds = geojson("poly-hole.geojson")) {
            layer.setDatasource(ds).addStyle("s").setQueryable(true);
            map.addLayer(layer);
        }
        map.zoomToBox(-20, -20, 20, 20);
        return map;
    }

    @Test
    void queryPointFindsTheFeatureUnderAMapCoordinate() {
        try (MapnikMap map = placesMap()) {
            try (Featureset fs = map.queryPoint("donut", 8, 8)) {
                List<Feature> r = fs.toList();
                assertEquals(1, r.size());
                assertEquals("donut", r.get(0).attribute("name"));
            }
            try (Featureset fs = map.queryPoint("donut", 15, 15)) {
                assertTrue(fs.toList().isEmpty());
            }
        }
    }

    @Test
    void queryMapPointFindsTheFeatureUnderAPixel() {
        try (MapnikMap map = placesMap()) {
            // 400 px over 40 units: lon 8 is x = 280; lat 8 is y = 120.
            try (Featureset fs = map.queryMapPoint("donut", 280, 120)) {
                assertEquals(1, fs.toList().size());
            }
            try (Featureset fs = map.queryMapPoint("donut", 10, 10)) {
                assertTrue(fs.toList().isEmpty());
            }
        }
    }

    @Test
    void queryUnknownLayerThrows() {
        try (MapnikMap map = placesMap()) {
            assertThrows(IllegalArgumentException.class, () -> map.queryPoint("nope", 0, 0));
            assertThrows(IllegalArgumentException.class, () -> map.queryMapPoint("nope", 0, 0));
        }
    }

    @Test
    void queryNeedsAnExtent() {
        try (MapnikMap map = new MapnikMap(10, 10); Layer l = Layer.create("empty")) {
            map.addLayer(l);
            assertThrows(MapnikException.class, () -> map.queryPoint("empty", 0, 0));
        }
    }

    @Test
    void layerWithoutDatasourceYieldsNothing() {
        try (MapnikMap map = new MapnikMap(10, 10); Layer l = Layer.create("empty")) {
            map.addLayer(l);
            map.zoomToBox(-1, -1, 1, 1);
            try (Featureset fs = map.queryPoint("empty", 0, 0)) {
                assertTrue(fs.toList().isEmpty());
            }
        }
    }
}
