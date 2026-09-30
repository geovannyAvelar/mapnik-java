package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Colour, scale, map and layer extras, pixel conversion and datasource readback. */
class ApiGapsIntegrationTest {
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

    // ---------------------------------------------------------------- Color

    @Test
    void parsesColourNamesAndHex() {
        assertEquals(new Color(255, 0, 0, 255), Color.parse("red"));
        assertEquals(new Color(0x33, 0x66, 0x99, 255), Color.parse("#336699"));
        assertEquals(new Color(0x33, 0x66, 0x99, 255), Color.parse("#369"));
        assertEquals(new Color(102, 51, 153, 255), Color.parse("rebeccapurple"));
    }

    @Test
    void parsesRgbaWithFractionalAlpha() {
        Color c = Color.parse("rgba(0,0,255,0.5)");
        assertEquals(0, c.red());
        assertEquals(255, c.blue());
        assertTrue(c.alpha() == 127 || c.alpha() == 128, "alpha " + c.alpha());
        assertFalse(c.isOpaque());
    }

    @Test
    void invalidColourStringsAreRejected() {
        assertThrows(MapnikException.class, () -> Color.parse("not-a-colour"));
        assertThrows(MapnikException.class, () -> Color.parse("#12"));
    }

    @Test
    void channelsAreRangeChecked() {
        assertThrows(IllegalArgumentException.class, () -> new Color(256, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Color(0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Color(0, 0, 0, 300));
    }

    @Test
    void formatsColours() {
        assertEquals("#ff0000", Color.rgb(255, 0, 0).toHex());
        assertEquals("rgb(255,0,0)", Color.rgb(255, 0, 0).toString());
        assertEquals("#ff000080", new Color(255, 0, 0, 128).toHex());
        assertTrue(new Color(255, 0, 0, 128).toString().startsWith("rgba(255,0,0,0.5"));
    }

    @Test
    void argbRoundTripsAndEqualityIsByValue() {
        Color c = new Color(10, 20, 30, 77);
        assertEquals(0x4D0A141E, c.toArgb());
        assertEquals(c, Color.fromArgb(c.toArgb()));
        assertEquals(c.hashCode(), Color.fromArgb(c.toArgb()).hashCode());
        assertNotEquals(c, Color.rgb(10, 20, 30));
    }

    @Test
    void everyAlphaValueSurvivesTheStringMapnikReceives() {
        for (int a = 0; a <= 255; a++) {
            Color c = new Color(1, 2, 3, a);
            assertEquals(c, Color.parse(c.toStyleString()), "alpha " + a + " via " + c.toStyleString());
        }
    }

    @Test
    void coloursWorkWhereStringsDo() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            map.setBackground(new Color(16, 32, 48, 255));
            assertEquals("rgb(16,32,48)", map.background().get());
        }
        try (Image img = Image.create(2, 2)) {
            img.fill(new Color(10, 20, 30, 77));
            assertEquals(0x4D0A141E, img.getArgb(1, 1), "exact alpha");
        }
        assertTrue(Symbolizer.polygon().fill(Color.rgb(1, 2, 3)).toXml().contains("fill=\"rgba(1,2,3,1)\""));
        assertTrue(Symbolizer.line().stroke(new Color(1, 2, 3, 255)).toXml().contains("stroke="));
        assertTrue(Symbolizer.text("[n]").halo(Color.WHITE, 2).toXml().contains("halo-fill=\"rgba(255,255,255,1)\""));
    }

    @Test
    void namedConstants() {
        assertEquals(Color.parse("white"), Color.WHITE);
        assertEquals(Color.parse("black"), Color.BLACK);
        assertEquals(0, Color.TRANSPARENT.alpha());
    }

    // ---------------------------------------------------------------- scale denominator

    @Test
    void scaleDenominatorMatchesTheMapsOwn() {
        try (MapnikMap geo = new MapnikMap(100, 100)) {
            geo.setSrs("epsg:4326").zoomToBox(0, 0, 1, 1);
            assertEquals(geo.scaleDenominator(), Mapnik.scaleDenominator(geo.scale(), true), geo.scaleDenominator() * 1e-9);
        }
        try (MapnikMap merc = new MapnikMap(100, 100)) {
            merc.setSrs("epsg:3857").zoomToBox(0, 0, 100000, 100000);
            assertEquals(merc.scaleDenominator(), Mapnik.scaleDenominator(merc.scale(), false), merc.scaleDenominator() * 1e-9);
        }
    }

    @Test
    void scaleDenominatorGrowsWithScale() {
        assertTrue(Mapnik.scaleDenominator(2, false) > Mapnik.scaleDenominator(1, false));
        assertTrue(Mapnik.scaleDenominator(1, true) > Mapnik.scaleDenominator(1, false) * 1000,
            "a degree is far larger than a metre");
    }

    // ---------------------------------------------------------------- map extras

    @Test
    void backgroundImageBlendMode() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            map.setBackgroundImageCompOp("multiply");
            assertEquals("multiply", map.backgroundImageCompOp().get());
            map.setBackgroundImageCompOp("screen");
            assertEquals("screen", map.backgroundImageCompOp().get());
            assertThrows(MapnikException.class, () -> map.setBackgroundImageCompOp("not-a-mode"));
            assertEquals("screen", map.backgroundImageCompOp().get(), "unchanged after a failure");
        }
    }

    @Test
    void fontDirectory() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            assertFalse(map.fontDirectory().isPresent());
            map.setFontDirectory(dir);
            assertEquals(dir.toString(), map.fontDirectory().get());
        }
    }

    @Test
    void parametersHoldTypedValues() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            assertTrue(map.parameters().isEmpty());
            map.setParameter("title", "World").setParameter("zoom", 7).setParameter("ratio", 1.5)
                .setParameter("public", true).setParameter("big", 1L << 40);

            Map<String, Object> p = map.parameters();
            assertEquals("World", p.get("title"));
            assertEquals(Long.valueOf(7), p.get("zoom"));
            assertEquals(Double.valueOf(1.5), p.get("ratio"));
            assertEquals(Boolean.TRUE, p.get("public"));
            assertEquals(Long.valueOf(1L << 40), p.get("big"));
            assertEquals("[big, public, ratio, title, zoom]", p.keySet().toString(), "key order");
        }
    }

    @Test
    void parametersCanBeReplacedAndRemoved() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            map.setParameter("a", 1).setParameter("a", "now text");
            assertEquals("now text", map.parameters().get("a"));
            map.removeParameter("a").removeParameter("never-there");
            assertTrue(map.parameters().isEmpty());
        }
    }

    @Test
    void parametersAreReadOnlyAndTypeChecked() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            map.setParameter("a", 1);
            assertThrows(UnsupportedOperationException.class, () -> map.parameters().put("b", 2));
            assertThrows(IllegalArgumentException.class, () -> map.setParameter("x", new Object()));
            assertThrows(IllegalArgumentException.class, () -> map.setParameter("x", null));
        }
    }

    @Test
    void parametersTravelThroughMapXml() {
        String xml;
        try (MapnikMap a = new MapnikMap(10, 10)) {
            a.setParameter("author", "Ada").setParameter("version", 3);
            xml = a.toXml();
        }
        assertTrue(xml.contains("<Parameters>"), xml);
        assertTrue(xml.contains("Ada"), xml);
        try (MapnikMap b = new MapnikMap(10, 10)) {
            b.loadString(xml, null);
            assertEquals("Ada", b.parameters().get("author"));
            assertNotNull(b.parameters().get("version"));
        }
    }

    // ---------------------------------------------------------------- pixel and map coordinates

    @Test
    void worldAndPixelCoordinatesConvertBothWays() {
        try (MapnikMap map = new MapnikMap(200, 100)) {
            map.zoomToBox(0, 0, 200, 100);
            // Map y grows up; pixel y grows down from the top.
            assertEquals(new Point2d(0, 100), map.toPixel(0, 0));
            assertEquals(new Point2d(200, 0), map.toPixel(200, 100));
            assertEquals(new Point2d(50, 25), map.toPixel(50, 75));

            Point2d back = map.toWorld(50, 25);
            assertEquals(50, back.x(), 1e-9);
            assertEquals(75, back.y(), 1e-9);
            assertEquals(map.toWorld(map.toPixel(33.3, 44.4)).x(), 33.3, 1e-9);
            assertEquals(map.toWorld(new Point2d(100, 50)).y(), 50, 1e-9);
        }
    }

    @Test
    void pixelConversionFollowsZoomAndPan() {
        try (MapnikMap map = new MapnikMap(100, 100)) {
            map.zoomToBox(0, 0, 100, 100);
            Point2d before = map.toPixel(25, 75);
            map.zoom(0.5); // zoom in about the centre
            Point2d after = map.toPixel(25, 75);
            assertNotEquals(before, after);
            assertEquals(map.toPixel(25, 75).x(), after.x(), 1e-9);
        }
    }

    // ---------------------------------------------------------------- layer extras

    @Test
    void layerBlendMode() {
        try (Layer l = Layer.create("x")) {
            assertFalse(l.compOp().isPresent());
            l.setCompOp("multiply");
            assertEquals("multiply", l.compOp().get());
            assertThrows(MapnikException.class, () -> l.setCompOp("not-a-mode"));
            assertEquals("multiply", l.compOp().get());
        }
    }

    @Test
    void layerBlendModeReachesTheXml() {
        try (MapnikMap map = new MapnikMap(10, 10); Layer l = Layer.create("blend")) {
            l.setCompOp("screen");
            map.addLayer(l);
            assertTrue(map.toXml().contains("comp-op=\"screen\""), map.toXml());
        }
    }

    @Test
    void childLayers() {
        try (Layer parent = Layer.create("group"); Layer a = Layer.create("a"); Layer b = Layer.create("b")) {
            assertEquals(0, parent.childCount());
            parent.addChild(a).addChild(b);
            assertEquals(2, parent.childCount());
            try (Layer copy = parent.childCopy(1)) {
                assertEquals("b", copy.name());
                copy.setName("changed");
            }
            try (Layer again = parent.childCopy(1)) {
                assertEquals("b", again.name(), "a copy does not change the parent");
            }
            assertThrows(IndexOutOfBoundsException.class, () -> parent.childCopy(2));
            assertThrows(IndexOutOfBoundsException.class, () -> parent.childCopy(-1));
        }
    }

    @Test
    void addChildCopiesSoTheChildCanBeClosedFirst() {
        try (Layer parent = Layer.create("group")) {
            try (Layer child = Layer.create("kid")) {
                parent.addChild(child);
            }
            try (Layer copy = parent.childCopy(0)) {
                assertEquals("kid", copy.name());
            }
        }
    }

    // ---------------------------------------------------------------- datasource readback

    @Test
    void datasourceReadsBackItsParameters() {
        try (Datasource ds = geojson("square.geojson")) {
            Map<String, Object> p = ds.parameters();
            assertEquals("geojson", p.get("type"));
            assertEquals(dir.resolve("square.geojson").toString(), p.get("file"));
            assertThrows(UnsupportedOperationException.class, () -> p.put("x", 1));
        }
    }

    @Test
    void datasourceParameterTypesSurvive() {
        Map<String, Object> in = new HashMap<>();
        in.put("type", "geojson");
        in.put("file", dir.resolve("square.geojson").toString());
        in.put("flag", true);
        in.put("count", 5);
        in.put("ratio", 2.5);
        try (Datasource ds = Datasource.create(in)) {
            Map<String, Object> p = ds.parameters();
            assertEquals(Boolean.TRUE, p.get("flag"));
            assertEquals(Long.valueOf(5), p.get("count"));
            assertEquals(Double.valueOf(2.5), p.get("ratio"));
        }
    }

    @Test
    void datasourceEncodingAndLayerName() {
        try (Datasource ds = geojson("square.geojson")) {
            assertNotNull(ds.layerName());
            assertFalse(ds.encoding().isEmpty());
            assertEquals("utf-8", ds.encoding().toLowerCase());
        }
    }
}
