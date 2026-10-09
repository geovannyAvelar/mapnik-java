package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Damaged and hostile input to the parsers written in Java: WKT, WKB, GeoJSON and style XML. Whatever is thrown at
 * them, the answer is a result or an {@link IllegalArgumentException}, never a stack overflow, a hang, an out of
 * memory error or any other exception: callers parse text that users send them.
 */
class ParserFuzzTest {
    private static final String[] WKT = {
        "POINT(1 2)", "POINT Z (1 2 3)", "LINESTRING(0 0, 1 1, 2 0)", "POLYGON((0 0, 4 0, 4 4, 0 4, 0 0), (1 1, 2 1, 2 2, 1 1))",
        "MULTIPOINT((0 0), (1 1))", "MULTILINESTRING((0 0, 1 1), (2 2, 3 3))",
        "MULTIPOLYGON(((0 0, 1 0, 1 1, 0 0)), ((5 5, 6 5, 6 6, 5 5)))",
        "GEOMETRYCOLLECTION(POINT(1 2), LINESTRING(0 0, 1 1))", "POINT EMPTY", "POLYGON EMPTY"
    };
    private static final String[] GEOJSON = {
        "{\"type\":\"Point\",\"coordinates\":[1,2]}",
        "{\"type\":\"LineString\",\"coordinates\":[[0,0],[1,1],[2,0]]}",
        "{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[4,0],[4,4],[0,4],[0,0]]]}",
        "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[0,0],[1,0],[1,1],[0,0]]]]}",
        "{\"type\":\"GeometryCollection\",\"geometries\":[{\"type\":\"Point\",\"coordinates\":[1,2]}]}",
        "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"id\":7,\"properties\":{\"a\":\"b\",\"n\":1.5,\"t\":true,\"z\":null},"
            + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[1,2]}}]}"
    };
    private static final String STYLE = Style.create("roads").add(Rule.create().filter("[kind] = 'motorway'")
        .add(Symbolizer.line().stroke("#d1322b").strokeWidth(3))).add(Rule.create().elseFilter()
        .add(Symbolizer.polygon().fill("#888"))).toXml();
    private static final String SYNTAX = "()[]{}<>\"'/\\:,. -+eE01\n\t&;=#%";

    private static String mutate(String s, Random r) {
        StringBuilder sb = new StringBuilder(s);
        int edits = 1 + r.nextInt(6);
        for (int i = 0; i < edits && sb.length() > 0; i++) {
            int at = r.nextInt(sb.length());
            switch (r.nextInt(6)) {
                case 0: sb.deleteCharAt(at); break;
                case 1: sb.insert(at, SYNTAX.charAt(r.nextInt(SYNTAX.length()))); break;
                case 2: sb.setCharAt(at, SYNTAX.charAt(r.nextInt(SYNTAX.length()))); break;
                case 3: sb.setLength(at); break;                                  // truncate
                case 4: sb.insert(at, sb.substring(at, Math.min(sb.length(), at + 1 + r.nextInt(20)))); break;   // repeat
                default: sb.setCharAt(at, (char) r.nextInt(0x2FF));
            }
        }
        return sb.toString();
    }

    /** Runs the parser: a result or IllegalArgumentException is fine; anything else fails the test with the input. */
    private static void accepts(String what, String input, java.util.function.Consumer<String> parser) {
        try {
            parser.accept(input);
        } catch (IllegalArgumentException expected) {
            // refused: fine
        } catch (Throwable t) {
            fail(what + " threw " + t + " for input: " + (input.length() > 300 ? input.substring(0, 300) + "..." : input), t);
        }
    }

    @Test
    void damagedWktIsRefusedNotCrashedOn() {
        Random r = new Random(1);
        for (int i = 0; i < 20_000; i++) {
            accepts("WKT", mutate(WKT[i % WKT.length], r), Geometry::fromWkt);
        }
    }

    @Test
    void damagedGeoJsonIsRefusedNotCrashedOn() {
        Random r = new Random(2);
        for (int i = 0; i < 20_000; i++) {
            String in = mutate(GEOJSON[i % GEOJSON.length], r);
            accepts("GeoJSON geometry", in, Geometry::fromGeoJson);
            accepts("GeoJSON features", in, Feature::listFromGeoJson);
        }
    }

    @Test
    void damagedWkbIsRefusedNotCrashedOn() {
        Random r = new Random(3);
        List<byte[]> seeds = new ArrayList<>();
        for (String wkt : WKT) {
            try {
                seeds.add(Geometry.fromWkt(wkt).toWkb());
            } catch (IllegalArgumentException cannotBeWritten) {
                // an empty geometry or a coordinate kind WKB output does not do: not a seed
            }
        }
        assertTrue(seeds.size() >= 6, "seeds: " + seeds.size());
        for (int i = 0; i < 20_000; i++) {
            byte[] b = seeds.get(i % seeds.size()).clone();
            int edits = 1 + r.nextInt(5);
            for (int e = 0; e < edits; e++) {
                b[r.nextInt(b.length)] = (byte) r.nextInt(256);
            }
            if (r.nextInt(5) == 0) {
                b = java.util.Arrays.copyOf(b, r.nextInt(b.length));
            }
            final byte[] data = b;
            try {
                Geometry.fromWkb(data);
            } catch (IllegalArgumentException expected) {
                // refused
            } catch (Throwable t) {
                fail("WKB threw " + t + " for " + java.util.Arrays.toString(data), t);
            }
        }
    }

    @Test
    void damagedStyleXmlIsRefusedNotCrashedOn() {
        Random r = new Random(4);
        for (int i = 0; i < 10_000; i++) {
            accepts("style XML", mutate(STYLE, r), Style::fromXml);
        }
    }

    @Test
    void absurdNestingIsRefusedNotAStackOverflow() {
        int depth = 200_000;
        StringBuilder json = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            json.append('[');
        }
        String deepArray = "{\"type\":\"Point\",\"coordinates\":" + json;
        accepts("deep GeoJSON array", deepArray, Geometry::fromGeoJson);
        StringBuilder objects = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            objects.append("{\"a\":");
        }
        accepts("deep GeoJSON object", "{\"type\":\"Feature\",\"properties\":" + objects, Feature::fromGeoJson);
        StringBuilder collections = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            collections.append("{\"type\":\"GeometryCollection\",\"geometries\":[");
        }
        accepts("deep geometry collection", collections.toString(), Geometry::fromGeoJson);
        StringBuilder wkt = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            wkt.append("GEOMETRYCOLLECTION(");
        }
        accepts("deep WKT collection", wkt.toString(), Geometry::fromWkt);
        // WKB: collections that each hold a collection, two hundred thousand deep
        java.nio.ByteBuffer wkb = java.nio.ByteBuffer.allocate(depth * 9).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < depth; i++) {
            wkb.put((byte) 1).putInt(7).putInt(1);
        }
        try {
            Geometry.fromWkb(wkb.array());
            fail("a collection nested that deep must be refused");
        } catch (IllegalArgumentException expected) {
            // fine
        } catch (Throwable t) {
            fail("deep WKB threw " + t, t);
        }
        StringBuilder xml = new StringBuilder("<Style name=\"a\">");
        for (int i = 0; i < depth; i++) {
            xml.append("<Rule>");
        }
        accepts("deep XML", xml.toString(), Style::fromXml);
    }

    @Test
    void hugeDeclaredSizesDoNotAllocateGigabytes() {
        // a WKB that claims two billion points, a WKT with a huge exponent, a long run of digits
        byte[] wkb = {1, 2, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F};      // little-endian LineString, 2^31-1 points
        try {
            Geometry.fromWkb(wkb);
            fail("a truncated WKB must be refused");
        } catch (IllegalArgumentException expected) {
            // fine
        }
        accepts("WKT exponent", "POINT(1e999999999 1)", Geometry::fromWkt);
        accepts("long digits", "POINT(" + new String(new char[100_000]).replace('\0', '9') + " 1)", Geometry::fromWkt);
        accepts("WKT huge count", "MULTIPOINT" + new String(new char[50_000]).replace("\0", "(1 1),"), Geometry::fromWkt);
    }

    @Test
    void invalidUtf8AndOddCharactersAreFine() {
        String odd = new String(new byte[] {(byte) 0xC3, (byte) 0x28, (byte) 0xFF, 0, 1, 2}, StandardCharsets.UTF_8);
        accepts("odd WKT", "POINT(" + odd + ")", Geometry::fromWkt);
        accepts("odd GeoJSON", "{\"type\":\"" + odd + "\"}", Geometry::fromGeoJson);
        accepts("odd XML", "<Style name=\"" + odd + "\"/>", Style::fromXml);
    }
}
