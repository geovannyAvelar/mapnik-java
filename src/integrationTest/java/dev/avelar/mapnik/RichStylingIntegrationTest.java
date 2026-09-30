package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Formatted text, placements and group symbolizers against a real Mapnik. */
class RichStylingIntegrationTest {
    private static final String FACE = "DejaVu Sans Book";

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

    /** One labelled point in the middle of a 300x100 map over [-150, 150] x [-50, 50]. */
    private static Image labelled(Symbolizer symbolizer) {
        try (MemoryDatasource ds = MemoryDatasource.create().add(Geometry.point(0, 0), attrs("name", "Hello", "pop", "World"));
             MapnikMap map = new MapnikMap(300, 100).setSrs("epsg:4326").setBackground("white");
             Layer layer = Layer.create("d", "epsg:4326")) {
            map.addStyle(Style.create("s").add(Rule.create().add(symbolizer)));
            layer.addStyle("s").setDatasource(ds);
            map.addLayer(layer).zoomToBox(-150, -50, 150, 50);
            return map.renderToImage();
        }
    }

    private static int count(Image img, java.util.function.IntPredicate test) {
        int n = 0;
        for (int p : img.toArgb()) {
            if (test.test(p)) {
                n++;
            }
        }
        return n;
    }

    private static boolean reddish(int p) { return ((p >> 16) & 0xFF) > 180 && ((p >> 8) & 0xFF) < 90 && (p & 0xFF) < 90; }

    private static boolean bluish(int p) { return (p & 0xFF) > 180 && ((p >> 16) & 0xFF) < 90 && ((p >> 8) & 0xFF) < 90; }

    private static boolean dark(int p) { return ((p >> 16) & 0xFF) < 100 && ((p >> 8) & 0xFF) < 100 && (p & 0xFF) < 100; }

    // ---------------------------------------------------------------- formatted text

    @Test
    void runsOfTextCanHaveTheirOwnColours() {
        assumeTrue(Fixtures.fonts(), "no Mapnik fonts directory known (set MAPNIK_FONTS)");
        Symbolizer s = Symbolizer.formattedText(TextFormat.of("[name]").fill("red"), TextFormat.of("' ' + [pop]").fill("blue"))
            .faceName(FACE).fontSize(24);
        try (Image img = labelled(s)) {
            assertTrue(count(img, RichStylingIntegrationTest::reddish) > 40, "red part: " + count(img, RichStylingIntegrationTest::reddish));
            assertTrue(count(img, RichStylingIntegrationTest::bluish) > 40, "blue part: " + count(img, RichStylingIntegrationTest::bluish));
        }
    }

    @Test
    void theRedRunComesBeforeTheBlueOne() {
        assumeTrue(Fixtures.fonts(), "no Mapnik fonts directory known (set MAPNIK_FONTS)");
        Symbolizer s = Symbolizer.formattedText(TextFormat.of("[name]").fill("red"), TextFormat.of("' ' + [pop]").fill("blue"))
            .faceName(FACE).fontSize(24);
        try (Image img = labelled(s)) {
            double redSum = 0;
            double blueSum = 0;
            int reds = 0;
            int blues = 0;
            for (int y = 0; y < img.height(); y++) {
                for (int x = 0; x < img.width(); x++) {
                    int p = img.getArgb(x, y);
                    if (reddish(p)) {
                        redSum += x;
                        reds++;
                    } else if (bluish(p)) {
                        blueSum += x;
                        blues++;
                    }
                }
            }
            assertTrue(redSum / reds < blueSum / blues, "red text is to the left of blue text");
        }
    }

    @Test
    void aRunCanUseItsOwnSize() {
        assumeTrue(Fixtures.fonts(), "no Mapnik fonts directory known (set MAPNIK_FONTS)");
        int small;
        int big;
        try (Image a = labelled(Symbolizer.formattedText(TextFormat.of("[name]").fill("black").fontSize(10)).faceName(FACE).fontSize(10));
             Image b = labelled(Symbolizer.formattedText(TextFormat.of("[name]").fill("black").fontSize(30)).faceName(FACE).fontSize(10))) {
            small = count(a, RichStylingIntegrationTest::dark);
            big = count(b, RichStylingIntegrationTest::dark);
        }
        assertTrue(big > small * 3, "the run's size wins over the symbolizer's: " + small + " vs " + big);
    }

    @Test
    void textTransformChangesWhatIsDrawn() {
        assumeTrue(Fixtures.fonts(), "no Mapnik fonts directory known (set MAPNIK_FONTS)");
        Symbolizer plain = Symbolizer.formattedText(TextFormat.of("[name]").fill("black")).faceName(FACE).fontSize(20);
        Symbolizer upper = Symbolizer.formattedText(TextFormat.of("[name]").fill("black").textTransform("uppercase"))
            .faceName(FACE).fontSize(20);
        try (Image a = labelled(plain); Image b = labelled(upper)) {
            assertNotEquals(Arrays.hashCode(a.toArgb()), Arrays.hashCode(b.toArgb()), "HELLO looks different from Hello");
        }
    }

    @Test
    void aHaloAddsPixelsAroundTheText() {
        assumeTrue(Fixtures.fonts(), "no Mapnik fonts directory known (set MAPNIK_FONTS)");
        Symbolizer s = Symbolizer.formattedText(TextFormat.of("[name]").fill("black").halo("red", 3)).faceName(FACE).fontSize(24);
        try (Image img = labelled(s)) {
            assertTrue(count(img, RichStylingIntegrationTest::reddish) > 100, "the halo is drawn");
            assertTrue(count(img, RichStylingIntegrationTest::dark) > 50, "and so is the text");
        }
    }

    @Test
    void formattedTextNeedsAFaceNameAndStrictModeSaysSo() {
        assumeTrue(Fixtures.fonts(), "no Mapnik fonts directory known (set MAPNIK_FONTS)");
        try (MapnikMap map = new MapnikMap(10, 10)) {
            MapnikException e = assertThrows(MapnikException.class, () -> map.addStyle(Style.create("s").add(Rule.create().add(
                Symbolizer.formattedText(TextFormat.of("[name]").faceName(FACE))))));
            assertTrue(e.getMessage().contains("face-name"), e.getMessage());
            assertFalse(map.hasStyle("s"), "rolled back");
        }
    }

    // ---------------------------------------------------------------- placements

    @Test
    void placementAlternativesAreAccepted() {
        assumeTrue(Fixtures.fonts(), "no Mapnik fonts directory known (set MAPNIK_FONTS)");
        try (MapnikMap map = new MapnikMap(10, 10)) {
            map.addStyle(Style.create("s").add(Rule.create().add(
                Symbolizer.formattedText(TextFormat.of("[name]")).faceName(FACE).fontSize(12)
                    .placementList(TextPlacement.create().fontSize(10), TextPlacement.create().fontSize(8)))));
            assertTrue(map.hasStyle("s"));
        }
    }

    @Test
    void placementPositionsAreAccepted() {
        assumeTrue(Fixtures.fonts(), "no Mapnik fonts directory known (set MAPNIK_FONTS)");
        try (MapnikMap map = new MapnikMap(10, 10)) {
            map.addStyle(Style.create("s").add(Rule.create().add(
                Symbolizer.text("[name]").faceName(FACE).fontSize(12).placementPositions("E,NE,SE,W,NW,SW"))));
            assertTrue(map.hasStyle("s"));
        }
    }

    // ---------------------------------------------------------------- groups

    @Test
    void groupSymbolizersAreAccepted() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            map.addStyle(Style.create("g").add(Rule.create().add(
                Symbolizer.group().simpleLayout(4)
                    .groupRule(GroupRule.create().filter("[n] > 1").add(Symbolizer.point("shape://ellipse"))))));
            assertTrue(map.hasStyle("g"));
            map.addStyle(Style.create("g2").add(Rule.create().add(
                Symbolizer.group().attr("num-columns", 2).attr("repeat-key", "[id]").pairLayout(3)
                    .groupRule(GroupRule.create().add(Symbolizer.point("shape://ellipse"))))));
            assertTrue(map.hasStyle("g2"));
        }
    }

    @Test
    void aGroupSymbolizerDrawsItsMembersWhenItHasColumnsAndAKey() {
        assertEquals(0, groupPixels(false), "without columns and a key Mapnik draws nothing for a group");
        assertTrue(groupPixels(true) > 100, "with them it draws the member marker");
    }

    private static int groupPixels(boolean withColumns) {
        try (MemoryDatasource ds = MemoryDatasource.create().add(Geometry.point(0, 0), attrs("id", 1));
             MapnikMap map = new MapnikMap(100, 100).setSrs("epsg:4326").setBackground("white");
             Layer layer = Layer.create("d", "epsg:4326")) {
            Symbolizer g = Symbolizer.group().simpleLayout(4).groupRule(GroupRule.create().add(
                Symbolizer.markers().attr("marker-type", "ellipse").size(20, 20).fill("red").allowOverlap(true)));
            if (withColumns) {
                g.groupColumns(1, 1, "[id]");
            }
            map.addStyle(Style.create("g").add(Rule.create().add(g)));
            layer.addStyle("g").setDatasource(ds);
            map.addLayer(layer).zoomToBox(-50, -50, 50, 50);
            try (Image img = map.renderToImage()) {
                assertEquals(0xFFFFFFFF, img.getArgb(2, 2));
                return count(img, RichStylingIntegrationTest::reddish);
            }
        }
    }

    // ---------------------------------------------------------------- reading them back

    @Test
    void nestedStylingSurvivesReadingTheStyleBack() {
        assumeTrue(Fixtures.fonts(), "no Mapnik fonts directory known (set MAPNIK_FONTS)");
        try (MapnikMap a = new MapnikMap(10, 10); MapnikMap b = new MapnikMap(10, 10)) {
            a.addStyle(Style.create("rich").add(
                Rule.create().add(Symbolizer.formattedText(TextFormat.of("[name]").fill("red"), TextFormat.of("[pop]").fontSize(9))
                    .faceName(FACE).fontSize(12)),
                Rule.create().add(Symbolizer.group().simpleLayout(3)
                    .groupRule(GroupRule.create().add(Symbolizer.point("shape://ellipse"))))));
            Style back = a.style("rich").get();
            Symbolizer text = back.rules().get(0).symbolizers().get(0);
            assertEquals("TextSymbolizer", text.element());
            assertEquals(2, text.children().size(), "both formats are kept as nested XML");
            assertTrue(text.children().get(0).startsWith("<Format"), text.children().get(0));
            Symbolizer group = back.rules().get(1).symbolizers().get(0);
            assertEquals("GroupSymbolizer", group.element());
            assertFalse(group.children().isEmpty());

            b.addStyle(back); // strict: Mapnik accepts what it wrote
            assertTrue(b.hasStyle("rich"));
        }
    }
}
