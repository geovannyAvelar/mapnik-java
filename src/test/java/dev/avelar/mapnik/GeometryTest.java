package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The geometry model and its WKT, WKB and GeoJSON readers and writers. Needs no native library. */
class GeometryTest {

    private static final double[] SQUARE = {0, 0, 10, 0, 10, 10, 0, 10, 0, 0};
    private static final double[] HOLE = {2, 2, 4, 2, 4, 4, 2, 4, 2, 2};

    private static Geometry[] samples() {
        return new Geometry[] {
            Geometry.point(1, 2),
            Geometry.point(-0.5, 1e-7),
            Geometry.lineString(0, 0, 1.5, 2.5, 3, 0),
            Geometry.polygon(SQUARE),
            Geometry.polygon(SQUARE, HOLE),
            Geometry.multiPoint(1, 1, 2, 2),
            Geometry.multiPoint(),
            Geometry.multiLineString(Geometry.lineString(0, 0, 1, 1), Geometry.lineString(5, 5, 6, 7)),
            Geometry.multiLineString(),
            Geometry.multiPolygon(Geometry.polygon(SQUARE), Geometry.polygon(SQUARE, HOLE)),
            Geometry.multiPolygon(),
            Geometry.collection(Geometry.point(1, 2), Geometry.lineString(0, 0, 1, 1),
                Geometry.collection(Geometry.point(3, 4))),
            Geometry.empty(),
        };
    }

    // ---------------------------------------------------------------- kinds and accessors

    @Test
    void kindsMatchTheType() {
        assertEquals(GeometryKind.POINT, Geometry.point(1, 2).kind());
        assertEquals(GeometryKind.LINE_STRING, Geometry.lineString(0, 0, 1, 1).kind());
        assertEquals(GeometryKind.POLYGON, Geometry.polygon(SQUARE).kind());
        assertEquals(GeometryKind.MULTI_POINT, Geometry.multiPoint(1, 1).kind());
        assertEquals(GeometryKind.MULTI_LINE_STRING, Geometry.multiLineString().kind());
        assertEquals(GeometryKind.MULTI_POLYGON, Geometry.multiPolygon().kind());
        assertEquals(GeometryKind.GEOMETRY_COLLECTION, Geometry.empty().kind());
    }

    @Test
    void pointAccessors() {
        Geometry.Point p = Geometry.point(3, 4);
        assertEquals(3, p.x(), 0);
        assertEquals(4, p.y(), 0);
        assertEquals(new Point2d(3, 4), p.toPoint2d());
    }

    @Test
    void lineStringAccessors() {
        Geometry.LineString l = Geometry.lineString(0, 0, 1, 2, 3, 4);
        assertEquals(3, l.numPoints());
        assertEquals(new Point2d(1, 2), l.point(1));
        assertArrayEquals(new double[] {0, 0, 1, 2, 3, 4}, l.coordinates(), 0);
    }

    @Test
    void lineStringFromPoints() {
        Geometry.LineString l = Geometry.lineString(Arrays.asList(new Point2d(0, 0), new Point2d(5, 6)));
        assertEquals("LINESTRING(0 0,5 6)", l.toWkt());
    }

    @Test
    void polygonAccessors() {
        Geometry.Polygon p = Geometry.polygon(SQUARE, HOLE);
        assertArrayEquals(SQUARE, p.exterior(), 0);
        assertEquals(1, p.holes().size());
        assertArrayEquals(HOLE, p.holes().get(0), 0);
        assertEquals(2, p.rings().size());
    }

    @Test
    void rectangleCoversABox() {
        Geometry.Polygon r = Geometry.rectangle(new Box2d(1, 2, 3, 4));
        assertEquals("POLYGON((1 2,3 2,3 4,1 4,1 2))", r.toWkt());
        assertEquals(new Box2d(1, 2, 3, 4), r.envelope());
    }

    @Test
    void accessorsReturnCopies() {
        Geometry.LineString l = Geometry.lineString(0, 0, 1, 1);
        l.coordinates()[0] = 99;
        assertEquals("LINESTRING(0 0,1 1)", l.toWkt());
        double[] ring = SQUARE.clone();
        Geometry.Polygon p = Geometry.polygon(ring);
        ring[0] = 99;
        assertEquals("POLYGON((0 0,10 0,10 10,0 10,0 0))", p.toWkt(), "the factory copies its input");
    }

    // ---------------------------------------------------------------- envelope and emptiness

    @Test
    void envelopes() {
        assertEquals(new Box2d(1, 2, 1, 2), Geometry.point(1, 2).envelope());
        assertEquals(new Box2d(0, 0, 3, 2.5), Geometry.lineString(0, 0, 1.5, 2.5, 3, 0).envelope());
        assertEquals(new Box2d(0, 0, 10, 10), Geometry.polygon(SQUARE, HOLE).envelope());
        assertEquals(new Box2d(1, 1, 6, 7),
            Geometry.collection(Geometry.point(1, 1), Geometry.lineString(5, 5, 6, 7)).envelope());
    }

    @Test
    void emptyGeometriesHaveNoEnvelope() {
        assertNull(Geometry.empty().envelope());
        assertNull(Geometry.multiPoint().envelope());
        assertNull(Geometry.multiPolygon().envelope());
        assertTrue(Geometry.empty().isEmpty());
        assertTrue(Geometry.multiLineString().isEmpty());
        assertTrue(Geometry.collection(Geometry.empty(), Geometry.multiPoint()).isEmpty());
        assertFalse(Geometry.collection(Geometry.point(1, 2)).isEmpty());
        assertFalse(Geometry.point(1, 2).isEmpty());
    }

    // ---------------------------------------------------------------- validation

    @Test
    void badCoordinatesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Geometry.point(Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> Geometry.point(1, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> Geometry.lineString(0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> Geometry.lineString(0, 0));
        assertThrows(IllegalArgumentException.class, () -> Geometry.lineString(0, 0, Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> Geometry.multiPoint(1, 2, 3));
    }

    @Test
    void ringsMustBeClosedAndLongEnough() {
        assertThrows(IllegalArgumentException.class, () -> Geometry.polygon(new double[] {0, 0, 1, 0, 1, 1, 0, 1}));
        assertThrows(IllegalArgumentException.class, () -> Geometry.polygon(new double[] {0, 0, 1, 0, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> Geometry.polygon(SQUARE, new double[] {2, 2, 3, 3, 4, 2}));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> Geometry.polygon(SQUARE, new double[] {2, 2, 4, 2, 4, 4, 2, 4}));
        assertTrue(e.getMessage().contains("hole 1"), e.getMessage());
    }

    @Test
    void listsCannotContainNull() {
        assertThrows(IllegalArgumentException.class, () -> Geometry.collection(Geometry.point(1, 2), null));
        assertThrows(IllegalArgumentException.class, () -> Geometry.multiPolygon((Geometry.Polygon) null));
    }

    @Test
    void multiGeometriesExposeTheirParts() {
        Geometry.MultiLineString ml = Geometry.multiLineString(Geometry.lineString(0, 0, 1, 1));
        assertEquals(1, ml.lines().size());
        assertThrows(UnsupportedOperationException.class, () -> ml.lines().clear());
        Geometry.MultiPolygon mp = Geometry.multiPolygon(Geometry.polygon(SQUARE));
        assertEquals(1, mp.polygons().size());
        Geometry.Collection c = Geometry.collection(Geometry.point(1, 2));
        assertEquals(1, c.geometries().size());
        assertEquals(1, Geometry.multiPoint(1, 1, 2, 2).numPoints() - 1);
    }

    // ---------------------------------------------------------------- WKT

    @Test
    void writesWkt() {
        assertEquals("POINT(1 2)", Geometry.point(1, 2).toWkt());
        assertEquals("POINT(-0.5 0.0000001)", Geometry.point(-0.5, 1e-7).toWkt());
        assertEquals("LINESTRING(0 0,1.5 2.5,3 0)", Geometry.lineString(0, 0, 1.5, 2.5, 3, 0).toWkt());
        assertEquals("POLYGON((0 0,10 0,10 10,0 10,0 0),(2 2,4 2,4 4,2 4,2 2))", Geometry.polygon(SQUARE, HOLE).toWkt());
        assertEquals("MULTIPOINT((1 1),(2 2))", Geometry.multiPoint(1, 1, 2, 2).toWkt());
        assertEquals("MULTILINESTRING((0 0,1 1),(5 5,6 7))",
            Geometry.multiLineString(Geometry.lineString(0, 0, 1, 1), Geometry.lineString(5, 5, 6, 7)).toWkt());
        assertEquals("MULTIPOLYGON(((0 0,10 0,10 10,0 10,0 0)),((0 0,10 0,10 10,0 10,0 0),(2 2,4 2,4 4,2 4,2 2)))",
            Geometry.multiPolygon(Geometry.polygon(SQUARE), Geometry.polygon(SQUARE, HOLE)).toWkt());
        assertEquals("GEOMETRYCOLLECTION(POINT(1 2),LINESTRING(0 0,1 1))",
            Geometry.collection(Geometry.point(1, 2), Geometry.lineString(0, 0, 1, 1)).toWkt());
        assertEquals("GEOMETRYCOLLECTION EMPTY", Geometry.empty().toWkt());
        assertEquals("MULTIPOINT EMPTY", Geometry.multiPoint().toWkt());
    }

    @Test
    void wktRoundTripsEveryType() {
        for (Geometry g : samples()) {
            Geometry back = Geometry.fromWkt(g.toWkt());
            assertEquals(g, back, g.toWkt());
            assertEquals(g.toWkt(), back.toWkt());
        }
    }

    @Test
    void readsWktLeniently() {
        assertEquals(Geometry.point(1, 2), Geometry.fromWkt("point(1 2)"));
        assertEquals(Geometry.point(1, 2), Geometry.fromWkt("  POINT  (  1   2  )  "));
        assertEquals(Geometry.point(1, 2), Geometry.fromWkt("POINT(1\n2)"));
        assertEquals(Geometry.point(-1.5, 2000), Geometry.fromWkt("POINT(-1.5 2e3)"));
        assertEquals(Geometry.point(0.5, -2), Geometry.fromWkt("POINT(+.5 -2.)"));
        assertEquals(Geometry.lineString(0, 0, 1, 1), Geometry.fromWkt("LineString(0 0, 1 1)"));
    }

    @Test
    void multiPointAcceptsBothSpellings() {
        Geometry a = Geometry.fromWkt("MULTIPOINT((1 1),(2 2))");
        Geometry b = Geometry.fromWkt("MULTIPOINT(1 1,2 2)");
        Geometry c = Geometry.fromWkt("MULTIPOINT( (1 1) , 2 2 )");
        assertEquals(a, b);
        assertEquals(a, c);
        assertEquals(Geometry.multiPoint(1, 1, 2, 2), a);
    }

    @Test
    void readsEmptyKeyword() {
        assertTrue(Geometry.fromWkt("GEOMETRYCOLLECTION EMPTY").isEmpty());
        assertEquals(GeometryKind.MULTI_POLYGON, Geometry.fromWkt("multipolygon empty").kind());
        assertEquals(GeometryKind.MULTI_LINE_STRING, Geometry.fromWkt("MULTILINESTRING EMPTY").kind());
        assertEquals(GeometryKind.MULTI_POINT, Geometry.fromWkt("MULTIPOINT EMPTY").kind());
    }

    @Test
    void readsNestedCollections() {
        Geometry g = Geometry.fromWkt("GEOMETRYCOLLECTION(POINT(1 2),GEOMETRYCOLLECTION(LINESTRING(0 0,1 1)),"
            + "POLYGON((0 0,1 0,1 1,0 0)))");
        assertEquals(3, ((Geometry.Collection) g).geometries().size());
        assertEquals(new Box2d(0, 0, 1, 2), g.envelope());
    }

    @Test
    void badWktIsRejectedWithAPosition() {
        String[] bad = {
            "", "POINT", "POINT()", "POINT(1)", "POINT(1 2", "POINT 1 2", "POINT(1 2))", "POINT(1 2) extra",
            "POINT(a b)", "CIRCLE(1 2)", "POINT EMPTY", "LINESTRING EMPTY", "POLYGON EMPTY",
            "LINESTRING(0 0)", "POLYGON((0 0,1 0,1 1))", "POLYGON((0 0,1 0,1 1,0 1))",
            "MULTIPOLYGON(((0 0,1 0,1 1,0 0)),)", "GEOMETRYCOLLECTION(POINT(1 2),)", "POINT(1e 2)"};
        for (String w : bad) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Geometry.fromWkt(w), w);
            assertTrue(e.getMessage().startsWith("invalid WKT at position"), w + " -> " + e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> Geometry.fromWkt(null));
    }

    @Test
    void wktWithZOrMIsRejected() {
        String[] zm = {"POINT Z (1 2 3)", "POINT(1 2 3)", "POINTZ(1 2 3)", "LINESTRING(0 0 0,1 1 1)", "POINT M (1 2 3)",
            "POINT ZM (1 2 3 4)", "POLYGON((0 0 0,1 0 0,1 1 0,0 0 0))"};
        for (String w : zm) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Geometry.fromWkt(w), w);
            assertTrue(e.getMessage().contains("2D"), w + " -> " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- WKB

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    @Test
    void writesStandardLittleEndianWkb() {
        // POINT(1 2): byte order 01, type 1, then two doubles. 1.0 = 3FF0..., 2.0 = 4000...
        assertEquals("0101000000000000000000f03f0000000000000040", hex(Geometry.point(1, 2).toWkb()));
        assertEquals("010200000002000000000000000000f03f000000000000f03f00000000000008400000000000001040",
            hex(Geometry.lineString(1, 1, 3, 4).toWkb()));
    }

    @Test
    void readsBigEndianWkb() {
        Geometry p = Geometry.fromWkb(unhex("00000000013ff00000000000004000000000000000"));
        assertEquals(Geometry.point(1, 2), p);
    }

    @Test
    void readsEwkbWithAnSridAndIgnoresTheSrid() {
        // 0x20000001 = point with SRID flag, then SRID 4326 (e6100000), then x and y.
        Geometry p = Geometry.fromWkb(unhex("0101000020e6100000000000000000f03f0000000000000040"));
        assertEquals(Geometry.point(1, 2), p);
    }

    @Test
    void wkbRoundTripsEveryType() {
        for (Geometry g : samples()) {
            assertEquals(g, Geometry.fromWkb(g.toWkb()), g.toWkt());
            assertArrayEquals(g.toWkb(), Geometry.fromWkb(g.toWkb()).toWkb());
        }
    }

    @Test
    void wktAndWkbAgree() {
        for (Geometry g : samples()) {
            assertEquals(Geometry.fromWkt(g.toWkt()).toWkt(), Geometry.fromWkb(g.toWkb()).toWkt());
        }
    }

    @Test
    void malformedWkbIsRejected() {
        byte[] good = Geometry.lineString(0, 0, 1, 1).toWkb();
        assertThrows(IllegalArgumentException.class, () -> Geometry.fromWkb(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> Geometry.fromWkb(Arrays.copyOf(good, good.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> Geometry.fromWkb(Arrays.copyOf(good, good.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> Geometry.fromWkb(unhex("0201000000")));        // byte order 2
        assertThrows(IllegalArgumentException.class, () -> Geometry.fromWkb(unhex("01ff000000")));        // type 255
        assertThrows(IllegalArgumentException.class, () -> Geometry.fromWkb(null));
    }

    @Test
    void wkbWithAHugeCountDoesNotAllocate() {
        // A line string claiming 4 billion points in 9 bytes.
        assertThrows(IllegalArgumentException.class, () -> Geometry.fromWkb(unhex("0102000000ffffffff")));
        // A polygon claiming a million rings.
        assertThrows(IllegalArgumentException.class, () -> Geometry.fromWkb(unhex("010300000040420f00")));
    }

    @Test
    void wkbWithZOrMIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Geometry.fromWkb(unhex("01e9030000")));       // ISO point Z (1001)
        assertThrows(IllegalArgumentException.class, () -> Geometry.fromWkb(unhex("0101000080")));       // EWKB Z flag
        assertThrows(IllegalArgumentException.class, () -> Geometry.fromWkb(unhex("0101000040")));       // EWKB M flag
    }

    @Test
    void wkbMultiGeometriesRejectWrongParts() {
        // multi point whose part is a line string
        assertThrows(IllegalArgumentException.class, () -> Geometry.fromWkb(unhex(
            "0104000000" + "01000000" + "0102000000" + "00000000")));
    }

    // ---------------------------------------------------------------- GeoJSON

    @Test
    void writesGeoJson() {
        assertEquals("{\"type\":\"Point\",\"coordinates\":[1,2]}", Geometry.point(1, 2).toGeoJson());
        assertEquals("{\"type\":\"LineString\",\"coordinates\":[[0,0],[1.5,2.5],[3,0]]}",
            Geometry.lineString(0, 0, 1.5, 2.5, 3, 0).toGeoJson());
        assertEquals("{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[10,0],[10,10],[0,10],[0,0]],"
                + "[[2,2],[4,2],[4,4],[2,4],[2,2]]]}",
            Geometry.polygon(SQUARE, HOLE).toGeoJson());
        assertEquals("{\"type\":\"MultiPoint\",\"coordinates\":[[1,1],[2,2]]}", Geometry.multiPoint(1, 1, 2, 2).toGeoJson());
        assertEquals("{\"type\":\"GeometryCollection\",\"geometries\":[]}", Geometry.empty().toGeoJson());
        assertEquals("{\"type\":\"GeometryCollection\",\"geometries\":[{\"type\":\"Point\",\"coordinates\":[1,2]}]}",
            Geometry.collection(Geometry.point(1, 2)).toGeoJson());
    }

    @Test
    void geoJsonRoundTripsEveryType() {
        for (Geometry g : samples()) {
            assertEquals(g, Geometry.fromGeoJson(g.toGeoJson()), g.toGeoJson());
        }
    }

    @Test
    void readsGeoJsonWithWhitespaceAndExtraMembers() {
        Geometry g = Geometry.fromGeoJson("{ \"type\" : \"Point\",\n \"coordinates\" : [ 1 , 2 ],\n \"bbox\": [0,0,1,1],"
            + " \"crs\": {\"type\":\"name\"} }");
        assertEquals(Geometry.point(1, 2), g);
    }

    @Test
    void geoJsonElevationIsIgnored() {
        assertEquals(Geometry.point(1, 2), Geometry.fromGeoJson("{\"type\":\"Point\",\"coordinates\":[1,2,99]}"));
        assertEquals(Geometry.lineString(0, 0, 1, 1),
            Geometry.fromGeoJson("{\"type\":\"LineString\",\"coordinates\":[[0,0,5],[1,1,6]]}"));
    }

    @Test
    void aNullGeoJsonGeometryIsEmpty() {
        assertTrue(Geometry.fromGeoJson("null").isEmpty());
    }

    @Test
    void readsGeoJsonNumbersInEveryForm() {
        assertEquals(Geometry.point(-1.5e3, 0.25), Geometry.fromGeoJson("{\"type\":\"Point\",\"coordinates\":[-1.5E3,2.5e-1]}"));
        assertEquals(Geometry.point(0, -0.0), Geometry.fromGeoJson("{\"type\":\"Point\",\"coordinates\":[0,-0]}"));
    }

    @Test
    void badGeoJsonIsRejected() {
        String[] bad = {
            "", "{", "[]", "{\"coordinates\":[1,2]}", "{\"type\":\"Point\"}", "{\"type\":\"Point\",\"coordinates\":[1]}",
            "{\"type\":\"Point\",\"coordinates\":[\"a\",2]}", "{\"type\":\"Point\",\"coordinates\":1}",
            "{\"type\":\"Circle\",\"coordinates\":[1,2]}", "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[1,0],[1,1]]]}",
            "{\"type\":\"LineString\",\"coordinates\":[[0,0]]}", "{\"type\":\"GeometryCollection\"}",
            "{\"type\":\"Point\",\"coordinates\":[1,2]} extra", "\"Point\"", "42"};
        for (String j : bad) {
            assertThrows(IllegalArgumentException.class, () -> Geometry.fromGeoJson(j), j);
        }
        assertThrows(IllegalArgumentException.class, () -> Geometry.fromGeoJson(null));
    }

    // ---------------------------------------------------------------- equality

    @Test
    void equalityIsByShape() {
        assertEquals(Geometry.point(1, 2), Geometry.fromWkt("POINT(1 2)"));
        assertEquals(Geometry.point(1, 2).hashCode(), Geometry.fromWkt("POINT(1 2)").hashCode());
        assertNotEquals(Geometry.point(1, 2), Geometry.point(2, 1));
        assertNotEquals(Geometry.point(1, 2), Geometry.multiPoint(1, 2));
        assertNotEquals(Geometry.point(1, 2), "POINT(1 2)");
        assertEquals("POINT(1 2)", Geometry.point(1, 2).toString());
    }

    // ---------------------------------------------------------------- the small JSON reader

    @Test
    @SuppressWarnings("unchecked")
    void jsonReaderHandlesEveryValueType() {
        Map<String, Object> o = (Map<String, Object>) Json.parse(
            "{\"s\":\"a\\nb\\u0041\\\"\",\"n\":-1.5e2,\"t\":true,\"f\":false,\"z\":null,\"a\":[1,[2],{}],\"o\":{\"k\":1}}");
        assertEquals("a\nbA\"", o.get("s"));
        assertEquals(-150.0, (Double) o.get("n"), 0);
        assertEquals(Boolean.TRUE, o.get("t"));
        assertEquals(Boolean.FALSE, o.get("f"));
        assertTrue(o.containsKey("z") && o.get("z") == null);
        assertEquals(3, ((List<Object>) o.get("a")).size());
        assertEquals(1.0, (Double) ((Map<String, Object>) o.get("o")).get("k"), 0);
        assertEquals("[s, n, t, f, z, a, o]", o.keySet().toString(), "keeps key order");
    }

    @Test
    void jsonReaderRejectsBadInput() {
        String[] bad = {"", "{", "[1,", "{\"a\"}", "{\"a\":}", "{a:1}", "[1 2]", "\"abc", "tru", "01x", "-", "[1,]x",
            "{\"a\":1,}", "\"\\x\"", "\"\\u12\"", "nul", "1 2"};
        for (String j : bad) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Json.parse(j), j);
            assertTrue(e.getMessage().startsWith("invalid JSON"), j + " -> " + e.getMessage());
        }
    }

    @Test
    void jsonReaderAcceptsTopLevelScalars() {
        assertEquals(1.0, (Double) Json.parse(" 1 "), 0);
        assertEquals("x", Json.parse("\"x\""));
        assertNull(Json.parse("null"));
        assertEquals(Collections.emptyList(), Json.parse("[]"));
    }
}
