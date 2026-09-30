package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Building features in Java and reading them from GeoJSON. Needs no native library. */
class FeatureBuildingTest {

    private static Map<String, Object> attrs() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "Depot");
        m.put("count", 7);
        m.put("ratio", 1.5f);
        m.put("active", true);
        m.put("note", null);
        return m;
    }

    // ---------------------------------------------------------------- create

    @Test
    void createNormalisesAttributeTypesAndSortsByName() {
        Feature f = Feature.create(5, Geometry.point(1, 2), attrs());
        assertEquals("[active, count, name, note, ratio]", f.attributes().keySet().toString());
        assertEquals(Long.valueOf(7), f.attribute("count"));
        assertEquals(Double.valueOf(1.5), f.attribute("ratio"));
        assertEquals(Boolean.TRUE, f.attribute("active"));
        assertEquals("Depot", f.attribute("name"));
        assertNull(f.attribute("note"));
        assertTrue(f.attributes().containsKey("note"));
        assertNull(f.attribute("missing"));
    }

    @Test
    void createAcceptsEveryIntegerType() {
        Map<String, Object> m = new HashMap<>();
        m.put("i", 1);
        m.put("l", 2L);
        m.put("s", (short) 3);
        m.put("b", (byte) 4);
        Feature f = Feature.create(1, Geometry.point(0, 0), m);
        for (String k : m.keySet()) {
            assertTrue(f.attribute(k) instanceof Long, k);
        }
    }

    @Test
    void createDescribesTheGeometry() {
        Feature f = Feature.create(9, Geometry.polygon(new double[] {0, 0, 4, 0, 4, 3, 0, 3, 0, 0}));
        assertEquals(9, f.id());
        assertEquals(GeometryKind.POLYGON, f.geometryKind());
        assertEquals(new Box2d(0, 0, 4, 3), f.envelope());
        assertEquals("POLYGON((0 0,4 0,4 3,0 3,0 0))", f.geometryWkt());
        assertTrue(f.geometryGeoJson().startsWith("{\"type\":\"Polygon\""));
        assertEquals(Geometry.fromWkt(f.geometryWkt()), f.geometry());
    }

    @Test
    void anEmptyGeometryMeansNoGeometry() {
        Feature f = Feature.create(1, Geometry.empty());
        assertEquals(GeometryKind.UNKNOWN, f.geometryKind());
        assertNull(f.envelope());
        assertEquals("null", f.geometryGeoJson());
        assertEquals("{\"type\":\"Feature\",\"id\":1,\"geometry\":null,\"properties\":{}}", f.toGeoJson());
        assertTrue(f.geometry().isEmpty());
    }

    @Test
    void writesTheWholeFeatureAsGeoJson() {
        Feature f = Feature.create(3, Geometry.point(10, 20), attrs());
        assertEquals("{\"type\":\"Feature\",\"id\":3,\"geometry\":{\"type\":\"Point\",\"coordinates\":[10,20]},"
                + "\"properties\":{\"active\":true,\"count\":7,\"name\":\"Depot\",\"note\":null,\"ratio\":1.5}}",
            f.toGeoJson());
    }

    @Test
    void stringsInGeoJsonAreEscaped() {
        Feature f = Feature.create(1, Geometry.point(0, 0), Collections.singletonMap("q", "a\"b\\c\nd\te\u0001"));
        assertTrue(f.toGeoJson().contains("\"q\":\"a\\\"b\\\\c\\nd\\te\\u0001\""), f.toGeoJson());
    }

    @Test
    void badAttributesAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> Feature.create(1, Geometry.point(0, 0), Collections.singletonMap("x", new BigDecimal("1.5"))));
        assertThrows(IllegalArgumentException.class,
            () -> Feature.create(1, Geometry.point(0, 0), Collections.singletonMap("x", Arrays.asList(1, 2))));
        assertThrows(IllegalArgumentException.class,
            () -> Feature.create(1, Geometry.point(0, 0), Collections.singletonMap("x", Double.NaN)));
        assertThrows(IllegalArgumentException.class,
            () -> Feature.create(1, Geometry.point(0, 0), Collections.singletonMap("x", Float.POSITIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class, () -> Feature.create(1, null));
    }

    @Test
    void attributesAreReadOnlyAndCopied() {
        Map<String, Object> in = new HashMap<>();
        in.put("a", 1);
        Feature f = Feature.create(1, Geometry.point(0, 0), in);
        in.put("b", 2);
        assertEquals(1, f.attributes().size());
        assertThrows(UnsupportedOperationException.class, () -> f.attributes().put("c", 3));
    }

    // ---------------------------------------------------------------- GeoJSON in

    @Test
    void readsAGeoJsonFeature() {
        Feature f = Feature.fromGeoJson("{\"type\":\"Feature\",\"id\":42,\"geometry\":{\"type\":\"Point\","
            + "\"coordinates\":[1,2]},\"properties\":{\"name\":\"x\",\"n\":3,\"r\":2.5,\"ok\":false,\"none\":null}}");
        assertEquals(42, f.id());
        assertEquals(Geometry.point(1, 2), f.geometry());
        assertEquals("x", f.attribute("name"));
        assertEquals(Long.valueOf(3), f.attribute("n"));
        assertEquals(Double.valueOf(2.5), f.attribute("r"));
        assertEquals(Boolean.FALSE, f.attribute("ok"));
        assertTrue(f.attributes().containsKey("none"));
    }

    @Test
    void nestedPropertiesBecomeJsonText() {
        Feature f = Feature.fromGeoJson("{\"type\":\"Feature\",\"geometry\":null,\"properties\":"
            + "{\"tags\":[\"a\",\"b\"],\"meta\":{\"k\":1,\"z\":[true]}}}");
        assertEquals("[\"a\",\"b\"]", f.attribute("tags"));
        assertEquals("{\"k\":1,\"z\":[true]}", f.attribute("meta"));
    }

    @Test
    void theIdDefaultsWhenMissingOrNotAWholeNumber() {
        assertEquals(1, Feature.fromGeoJson("{\"type\":\"Feature\",\"geometry\":null,\"properties\":null}").id());
        assertEquals(77, Feature.fromGeoJson("{\"type\":\"Feature\",\"geometry\":null,\"properties\":null}", 77).id());
        assertEquals(5, Feature.fromGeoJson("{\"type\":\"Feature\",\"id\":1.5,\"geometry\":null}", 5).id());
        assertEquals(5, Feature.fromGeoJson("{\"type\":\"Feature\",\"id\":\"abc\",\"geometry\":null}", 5).id());
    }

    @Test
    void aNullGeometryOrMissingPropertiesIsFine() {
        Feature f = Feature.fromGeoJson("{\"type\":\"Feature\",\"geometry\":null}");
        assertTrue(f.geometry().isEmpty());
        assertTrue(f.attributes().isEmpty());
    }

    @Test
    void readsAFeatureCollectionAndNumbersByPosition() {
        List<Feature> all = Feature.listFromGeoJson("{\"type\":\"FeatureCollection\",\"features\":["
            + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[1,2]},\"properties\":{\"a\":1}},"
            + "{\"type\":\"Feature\",\"id\":10,\"geometry\":{\"type\":\"Point\",\"coordinates\":[3,4]},\"properties\":{}},"
            + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[5,6]},\"properties\":{}}]}");
        assertEquals(3, all.size());
        assertEquals(1, all.get(0).id());
        assertEquals(10, all.get(1).id());
        assertEquals(3, all.get(2).id());
        assertEquals(Geometry.point(5, 6), all.get(2).geometry());
    }

    @Test
    void aSingleFeatureReadsAsAList() {
        List<Feature> all = Feature.listFromGeoJson("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[1,2]}}");
        assertEquals(1, all.size());
    }

    @Test
    void anEmptyCollectionReadsAsAnEmptyList() {
        assertTrue(Feature.listFromGeoJson("{\"type\":\"FeatureCollection\",\"features\":[]}").isEmpty());
    }

    @Test
    void badGeoJsonFeaturesAreRejected() {
        String[] bad = {
            "{\"type\":\"Point\",\"coordinates\":[1,2]}", "[]", "{\"type\":\"FeatureCollection\"}",
            "{\"type\":\"FeatureCollection\",\"features\":[1]}", "{\"type\":\"Feature\",\"properties\":[1]}",
            "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Circle\"}}", "not json", ""};
        for (String j : bad) {
            assertThrows(IllegalArgumentException.class, () -> Feature.listFromGeoJson(j), j);
        }
        assertThrows(IllegalArgumentException.class, () -> Feature.fromGeoJson("{\"type\":\"FeatureCollection\",\"features\":[]}"));
    }

    @Test
    void geoJsonRoundTrips() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "Zürich ☃");
        m.put("n", 12);
        m.put("r", 0.25);
        m.put("ok", true);
        m.put("none", null);
        Feature original = Feature.create(8, Geometry.polygon(new double[] {0, 0, 1, 0, 1, 1, 0, 0}), m);
        Feature again = Feature.fromGeoJson(original.toGeoJson());
        assertEquals(original.id(), again.id());
        assertEquals(original.attributes(), again.attributes());
        assertEquals(original.geometry(), again.geometry());
        assertEquals(original.toGeoJson(), again.toGeoJson());
    }

    // ---------------------------------------------------------------- the JSON writer

    @Test
    void jsonEscapeMatchesTheNativeWriter() {
        assertEquals("\"plain\"", Json.escape("plain"));
        assertEquals("\"a\\\"b\"", Json.escape("a\"b"));
        assertEquals("\"\\\\\"", Json.escape("\\"));
        assertEquals("\"\\n\\r\\t\"", Json.escape("\n\r\t"));
        assertEquals("\"\\u0001\\u001f\"", Json.escape("\u0001\u001f"));
        assertEquals("\"ü☃\"", Json.escape("ü☃"));
    }

    @Test
    void jsonStringifyRoundTripsWhatItReads() {
        String text = "{\"a\":[1,2.5,\"x\",true,null,{\"b\":{}}],\"c\":\"q\\\"\"}";
        assertEquals(text, Json.stringify(Json.parse(text)));
    }
}
