package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Styles, rules and symbolizers built in code, rendered by a real Mapnik. */
class StylingIntegrationTest {
    private static final int RED = 0xFFFF0000;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLUE = 0xFF0000FF;

    // places.geojson has three points on a 400x400 map zoomed to [-50, 50]:
    //   alpha (1, 2)    count 3   at pixel (204, 192)
    //   beta  (10, 20)  count 7   at pixel (240, 120)
    //   gamma (-30, -40) count 0  at pixel (80, 360)
    private static final int[] ALPHA = {204, 192};
    private static final int[] BETA = {240, 120};
    private static final int[] GAMMA = {80, 360};

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

    /** A 400x400 white map over [-50, 50] with one layer of places, drawn with the named styles. */
    private static MapnikMap mapWith(String datafile, Style... styles) {
        MapnikMap map = new MapnikMap(400, 400).setSrs("epsg:4326").setBackground("white");
        try (Layer layer = Layer.create("data", "epsg:4326"); Datasource ds = geojson(datafile)) {
            for (Style s : styles) {
                map.addStyle(s);
                layer.addStyle(s.name());
            }
            layer.setDatasource(ds);
            map.addLayer(layer);
        } catch (RuntimeException e) {
            map.close();
            throw e;
        }
        return map.zoomToBox(-50, -50, 50, 50);
    }

    private static int px(Image img, int[] p) {
        return img.getArgb(p[0], p[1]);
    }

    private static Symbolizer dot(String fill) {
        return Symbolizer.markers().attr("marker-type", "ellipse").size(24, 24).fill(fill).allowOverlap(true);
    }

    // ---------------------------------------------------------------- basics

    @Test
    void polygonStyleFillsTheSquare() {
        Style s = Style.create("red").add(Rule.create().add(Symbolizer.polygon().fill("red")));
        try (MapnikMap map = mapWith("square.geojson", s); Image img = map.renderToImage()) {
            assertEquals(RED, img.getArgb(200, 200));
            assertEquals(WHITE, img.getArgb(10, 10));
        }
    }

    @Test
    void lineStyleStrokesTheOutlineOnly() {
        Style s = Style.create("outline").add(Rule.create().add(Symbolizer.line().stroke("black").strokeWidth(4)));
        try (MapnikMap map = mapWith("square.geojson", s); Image img = map.renderToImage()) {
            assertEquals(WHITE, img.getArgb(200, 200), "inside is not filled");
            // The square spans lon/lat -10..10 = pixels 160..239. Its left edge is at x = 160.
            assertEquals(0xFF000000, img.getArgb(160, 200));
            assertEquals(WHITE, img.getArgb(150, 200));
        }
    }

    @Test
    void severalSymbolizersInOneRuleDrawInOrder() {
        Style s = Style.create("both").add(Rule.create()
            .add(Symbolizer.polygon().fill("red"))
            .add(Symbolizer.line().stroke("blue").strokeWidth(6)));
        try (MapnikMap map = mapWith("square.geojson", s); Image img = map.renderToImage()) {
            assertEquals(RED, img.getArgb(200, 200));
            assertEquals(BLUE, img.getArgb(160, 200), "the outline is drawn over the fill");
        }
    }

    @Test
    void severalStylesOnOneLayerDrawInLayerOrder() {
        Style fill = Style.create("fill").add(Rule.create().add(Symbolizer.polygon().fill("red")));
        Style top = Style.create("top").add(Rule.create().add(Symbolizer.line().stroke("blue").strokeWidth(6)));
        try (MapnikMap map = mapWith("square.geojson", fill, top); Image img = map.renderToImage()) {
            assertEquals(RED, img.getArgb(200, 200));
            assertEquals(BLUE, img.getArgb(160, 200));
            assertEquals(java.util.Arrays.asList("fill", "top"), map.layer("data").styles());
        }
    }

    // ---------------------------------------------------------------- filters

    @Test
    void filtersChooseTheSymbolizerPerFeature() {
        Style s = Style.create("by-count")
            .add(Rule.create().filter("[count] > 2").add(dot("red")))
            .add(Rule.create().elseFilter().add(dot("blue")));
        try (MapnikMap map = mapWith("places.geojson", s); Image img = map.renderToImage()) {
            assertEquals(RED, px(img, ALPHA), "count 3");
            assertEquals(RED, px(img, BETA), "count 7");
            assertEquals(BLUE, px(img, GAMMA), "count 0 falls to the else rule");
            assertEquals(WHITE, img.getArgb(10, 10));
        }
    }

    @Test
    void filtersWithSpecialCharactersAreEscaped() {
        // < and ' are special in XML; the filter must survive them.
        Style s = Style.create("escaped").add(
            Rule.create().filter("[name] != 'alpha' and [count] < 5").add(dot("red")));
        try (MapnikMap map = mapWith("places.geojson", s); Image img = map.renderToImage()) {
            assertEquals(WHITE, px(img, ALPHA), "alpha is excluded by name");
            assertEquals(WHITE, px(img, BETA), "beta has count 7");
            assertEquals(RED, px(img, GAMMA), "only gamma matches");
        }
    }

    @Test
    void stringAndBooleanFilters() {
        Style s = Style.create("active").add(Rule.create().filter("[active] = true").add(dot("red")));
        try (MapnikMap map = mapWith("places.geojson", s); Image img = map.renderToImage()) {
            assertEquals(RED, px(img, ALPHA));
            assertEquals(WHITE, px(img, BETA), "beta is not active");
            assertEquals(RED, px(img, GAMMA));
        }
    }

    @Test
    void filterModeFirstStopsAtTheFirstMatch() {
        Rule firstRed = Rule.create().filter("[count] > 2").add(dot("red"));
        Rule alsoBlue = Rule.create().filter("[count] > 2").add(dot("blue"));
        Style all = Style.create("all").add(firstRed, alsoBlue);
        Style first = Style.create("first").filterMode(Style.FilterMode.FIRST).add(firstRed, alsoBlue);

        try (MapnikMap map = mapWith("places.geojson", all); Image img = map.renderToImage()) {
            assertEquals(BLUE, px(img, ALPHA), "both rules draw, the later one is on top");
        }
        try (MapnikMap map = mapWith("places.geojson", first); Image img = map.renderToImage()) {
            assertEquals(RED, px(img, ALPHA), "only the first matching rule draws");
        }
    }

    @Test
    void alsoFilterAddsDetailOnlyToFeaturesAnEarlierRuleMatched() {
        Style s = Style.create("also").add(
            Rule.create().filter("[count] > 2").add(dot("red")),
            Rule.create().alsoFilter().add(Symbolizer.markers().attr("marker-type", "ellipse").size(8, 8)
                .fill("blue").allowOverlap(true)));
        try (MapnikMap map = mapWith("places.geojson", s); Image img = map.renderToImage()) {
            assertEquals(BLUE, px(img, ALPHA), "matched by the first rule, so the small blue dot is added");
            assertEquals(RED, img.getArgb(ALPHA[0] + 9, ALPHA[1]), "the red dot is still around it");
            assertEquals(WHITE, px(img, GAMMA), "matched nothing, so nothing is drawn");
        }
    }

    @Test
    void alsoFilterDoesNothingInFirstMode() {
        Style s = Style.create("also").filterMode(Style.FilterMode.FIRST).add(
            Rule.create().filter("[count] > 2").add(dot("red")),
            Rule.create().alsoFilter().add(Symbolizer.markers().attr("marker-type", "ellipse").size(8, 8)
                .fill("blue").allowOverlap(true)));
        try (MapnikMap map = mapWith("places.geojson", s); Image img = map.renderToImage()) {
            assertEquals(RED, px(img, ALPHA));
        }
    }

    // ---------------------------------------------------------------- scale ranges

    @Test
    void ruleScaleRangesHideAndShow() {
        Style probe = Style.create("probe").add(Rule.create().add(dot("red")));
        double scale;
        try (MapnikMap map = mapWith("places.geojson", probe)) {
            scale = map.scaleDenominator();
        }

        Style shown = Style.create("shown").add(
            Rule.create().minScaleDenominator(scale / 2).maxScaleDenominator(scale * 2).add(dot("red")));
        Style tooFar = Style.create("tooFar").add(Rule.create().minScaleDenominator(scale * 2).add(dot("red")));
        Style tooClose = Style.create("tooClose").add(Rule.create().maxScaleDenominator(scale / 2).add(dot("red")));

        try (MapnikMap map = mapWith("places.geojson", shown); Image img = map.renderToImage()) {
            assertEquals(RED, px(img, ALPHA));
        }
        try (MapnikMap map = mapWith("places.geojson", tooFar); Image img = map.renderToImage()) {
            assertEquals(WHITE, px(img, ALPHA), "map is zoomed in past this rule's range");
        }
        try (MapnikMap map = mapWith("places.geojson", tooClose); Image img = map.renderToImage()) {
            assertEquals(WHITE, px(img, ALPHA), "map is zoomed out past this rule's range");
        }
    }

    // ---------------------------------------------------------------- style options

    @Test
    void styleOpacityBlendsWithTheBackground() {
        Style s = Style.create("faint").opacity(0.5).add(Rule.create().add(Symbolizer.polygon().fill("red")));
        try (MapnikMap map = mapWith("square.geojson", s); Image img = map.renderToImage()) {
            int c = img.getArgb(200, 200);
            assertEquals(255, (c >> 16) & 0xFF);
            assertEquals(128, (c >> 8) & 0xFF, 2);
            assertEquals(128, c & 0xFF, 2);
        }
    }

    @Test
    void symbolizerOpacityAndFillOpacity() {
        Style s = Style.create("faint2").add(Rule.create().add(Symbolizer.polygon().fill("red").fillOpacity(0.5)));
        try (MapnikMap map = mapWith("square.geojson", s); Image img = map.renderToImage()) {
            assertEquals(128, (img.getArgb(200, 200) >> 8) & 0xFF, 2);
        }
    }

    @Test
    void dashedLinesHaveGaps() {
        Style solid = Style.create("solid").add(Rule.create().add(Symbolizer.line().stroke("black").strokeWidth(3)));
        Style dashed = Style.create("dashed").add(Rule.create().add(
            Symbolizer.line().stroke("black").strokeWidth(3).strokeDasharray("8,8")));
        int solidCount;
        int dashedCount;
        try (MapnikMap map = mapWith("square.geojson", solid); Image img = map.renderToImage()) {
            solidCount = countBlack(img);
        }
        try (MapnikMap map = mapWith("square.geojson", dashed); Image img = map.renderToImage()) {
            dashedCount = countBlack(img);
        }
        assertTrue(dashedCount < solidCount * 0.7, solidCount + " vs " + dashedCount);
        assertTrue(dashedCount > solidCount * 0.3, solidCount + " vs " + dashedCount);
    }

    private static int countBlack(Image img) {
        int n = 0;
        for (int px : img.toArgb()) {
            if (px == 0xFF000000) {
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- text

    @Test
    void textSymbolizerWritesLabels() {
        assumeTrue(Fixtures.fonts(), "no Mapnik fonts directory known (set MAPNIK_FONTS)");
        Style s = Style.create("labels").add(Rule.create().add(
            Symbolizer.text("[name]").faceName("DejaVu Sans Book").fontSize(18).fill("black")));
        try (MapnikMap map = mapWith("places.geojson", s); Image img = map.renderToImage()) {
            int dark = 0;
            for (int p : img.toArgb()) {
                if ((p >> 16 & 0xFF) < 100 && (p >> 8 & 0xFF) < 100 && (p & 0xFF) < 100) {
                    dark++;
                }
            }
            assertTrue(dark > 100, "expected visible text, found " + dark + " dark pixels");
        }
    }

    @Test
    void textUsesTheFeatureAttributeNamedInTheExpression() {
        assumeTrue(Fixtures.fonts(), "no Mapnik fonts directory known (set MAPNIK_FONTS)");
        // Only label features with count 0: one label, near gamma at (80, 360).
        Style s = Style.create("one-label").add(Rule.create().filter("[count] = 0").add(
            Symbolizer.text("[name]").faceName("DejaVu Sans Book").fontSize(18).fill("black")));
        try (MapnikMap map = mapWith("places.geojson", s); Image img = map.renderToImage()) {
            int nearGamma = 0;
            int elsewhere = 0;
            for (int y = 0; y < 400; y++) {
                for (int x = 0; x < 400; x++) {
                    if ((img.getArgb(x, y) & 0xFF) < 100) {
                        if (Math.abs(x - GAMMA[0]) < 80 && Math.abs(y - GAMMA[1]) < 40) {
                            nearGamma++;
                        } else {
                            elsewhere++;
                        }
                    }
                }
            }
            assertTrue(nearGamma > 30, "label near gamma: " + nearGamma);
            assertEquals(0, elsewhere, "no other labels");
        }
    }

    @Test
    void haloMakesTextReadableOnADarkBackground() {
        assumeTrue(Fixtures.fonts(), "no Mapnik fonts directory known (set MAPNIK_FONTS)");
        Style s = Style.create("halo").add(Rule.create().add(
            Symbolizer.text("[name]").faceName("DejaVu Sans Book").fontSize(18).fill("black").halo("white", 2)));
        try (MapnikMap map = mapWith("places.geojson", s); Image img = map.renderToImage()) {
            int white = 0;
            for (int p : img.toArgb()) {
                if (p == WHITE) {
                    white++;
                }
            }
            assertTrue(white < 400 * 400, "some pixels are not background white");
        }
    }

    @Test
    void fontSetsCanBeAdded() {
        assumeTrue(Fixtures.fonts(), "no Mapnik fonts directory known (set MAPNIK_FONTS)");
        try (MapnikMap map = new MapnikMap(10, 10)) {
            map.addFontSet(FontSet.create("body", "DejaVu Sans Book"));
            assertTrue(map.toXml(true).contains("<FontSet name=\"body\""), map.toXml(true));
        }
    }

    // ---------------------------------------------------------------- other symbolizers load

    @Test
    void otherSymbolizerKindsAreAccepted() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            map.addStyle(Style.create("misc").add(
                Rule.create().add(Symbolizer.raster().opacity(0.5)),
                Rule.create().add(Symbolizer.dot().fill("red").size(3, 3)),
                Rule.create().add(Symbolizer.building().fill("gray").attr("height", 10)),
                Rule.create().add(Symbolizer.debug().attr("mode", "collision"))));
            assertTrue(map.hasStyle("misc"));
        }
    }

    @Test
    void lineSymbolizerOptionsAreAccepted() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            map.addStyle(Style.create("fancy").add(Rule.create().add(
                Symbolizer.line().stroke("black").strokeWidth(2).strokeOpacity(0.8).strokeLinejoin("round")
                    .strokeLinecap("round").strokeDasharray("4,2").compOp("multiply")
                    .attr("stroke-miterlimit", 4).attr("smooth", 0.5).attr("simplify", 1.5))));
            assertTrue(map.hasStyle("fancy"));
        }
    }

    // ---------------------------------------------------------------- error handling

    @Test
    void duplicateStyleNamesAreRejected() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            map.addStyle(Style.create("s").add(Rule.create().add(Symbolizer.polygon().fill("red"))));
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> map.addStyle(Style.create("s").add(Rule.create().add(Symbolizer.polygon().fill("blue")))));
            assertTrue(e.getMessage().contains("replaceStyle"), e.getMessage());
            assertTrue(map.toXml().contains("rgb(255,0,0)"), "the original is untouched");
            assertFalse(map.toXml().contains("rgb(0,0,255)"));
        }
    }

    @Test
    void replaceStyleSwapsTheRules() {
        Style red = Style.create("s").add(Rule.create().add(Symbolizer.polygon().fill("red")));
        Style blue = Style.create("s").add(Rule.create().add(Symbolizer.polygon().fill("blue")));
        try (MapnikMap map = mapWith("square.geojson", red)) {
            try (Image before = map.renderToImage()) {
                assertEquals(RED, before.getArgb(200, 200));
            }
            map.replaceStyle(blue);
            try (Image after = map.renderToImage()) {
                assertEquals(BLUE, after.getArgb(200, 200));
            }
            assertEquals(java.util.Collections.singletonList("s"), map.styleNames());

            map.replaceStyle(Style.create("new").add(Rule.create().add(Symbolizer.polygon().fill("red"))));
            assertTrue(map.hasStyle("new"));
        }
    }

    @Test
    void misspelledAttributesAreRejectedAndTheStyleIsNotKept() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            Style typo = Style.create("typo").add(Rule.create().add(
                Symbolizer.polygon().fill("red").attr("fil-opacity", 0.5)));
            MapnikException e = assertThrows(MapnikException.class, () -> map.addStyle(typo));
            assertTrue(e.getMessage().contains("fil-opacity"), e.getMessage());
            assertFalse(map.hasStyle("typo"), "the style must be rolled back");
        }
    }

    @Test
    void anAttributeThatDoesNotBelongOnThatSymbolizerIsRejected() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            // stroke-width is a line attribute, not a polygon one.
            MapnikException e = assertThrows(MapnikException.class, () -> map.addStyle(
                Style.create("wrong").add(Rule.create().add(Symbolizer.polygon().strokeWidth(2)))));
            assertTrue(e.getMessage().contains("stroke-width"), e.getMessage());
            assertFalse(map.hasStyle("wrong"));
        }
    }

    @Test
    void misspelledStyleLevelAttributesAreRejectedToo() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            Style typo = Style.create("s").compOp("multiply").add(Rule.create().add(Symbolizer.polygon()));
            map.addStyle(typo); // fine
            Style bad = Style.create("t").add(Rule.create().add(Symbolizer.polygon().attr("fill-color", "red")));
            assertThrows(MapnikException.class, () -> map.addStyle(bad));
        }
    }

    @Test
    void lenientModeKeepsAStyleWithAnUnknownAttribute() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            Style typo = Style.create("typo").add(Rule.create().add(
                Symbolizer.polygon().fill("red").attr("fil-opacity", 0.5)));
            map.addStyle(typo, false);
            assertTrue(map.hasStyle("typo"));
        }
    }

    @Test
    void strictModeAlsoCatchesAMissingFont() {
        assumeTrue(Fixtures.fonts(), "no Mapnik fonts directory known (set MAPNIK_FONTS)");
        try (MapnikMap map = new MapnikMap(10, 10)) {
            Style s = Style.create("labels").add(Rule.create().add(
                Symbolizer.text("[name]").faceName("No Such Font Face").fontSize(10)));
            MapnikException e = assertThrows(MapnikException.class, () -> map.addStyle(s));
            assertTrue(e.getMessage().contains("No Such Font Face"), e.getMessage());
            assertFalse(map.hasStyle("labels"));
        }
    }

    @Test
    void correctlySpelledAttributesAreAccepted() {
        // Every attribute used by the typed helpers must survive Mapnik's own round trip.
        try (MapnikMap map = new MapnikMap(10, 10)) {
            map.addStyle(Style.create("all").opacity(0.9).compOp("multiply").filterMode(Style.FilterMode.FIRST)
                .add(Rule.create().add(
                    Symbolizer.polygon().fill("red").fillOpacity(0.5).compOp("multiply"),
                    Symbolizer.line().stroke("black").strokeWidth(2).strokeOpacity(0.5).strokeDasharray("3,3")
                        .strokeLinejoin("round").strokeLinecap("square"),
                    Symbolizer.markers().fill("red").size(4, 4).allowOverlap(true).opacity(0.7).attr("marker-type", "arrow"))));
            assertTrue(map.hasStyle("all"));
        }
    }

    @Test
    void badFilterExpressionFailsWithMapnikMessageAndLeavesNoStyle() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            MapnikException e = assertThrows(MapnikException.class, () -> map.addStyle(
                Style.create("bad").add(Rule.create().filter("[broken").add(Symbolizer.polygon()))));
            assertTrue(e.getMessage().contains("[broken"), e.getMessage());
            assertFalse(map.hasStyle("bad"));
        }
    }

    @Test
    void stylesAddedInCodeAppearInTheMapXml() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            map.addStyle(Style.create("roads").add(Rule.create().filter("[kind] = 'motorway'")
                .add(Symbolizer.line().stroke("orange").strokeWidth(3))));
            String xml = map.toXml();
            assertTrue(xml.contains("<Style name=\"roads\""), xml);
            assertTrue(xml.contains("motorway"), xml);
            assertTrue(xml.contains("LineSymbolizer"), xml);
        }
    }

    @Test
    void addingAStyleKeepsEverythingElseOnTheMap() {
        try (MapnikMap map = new MapnikMap(300, 200)) {
            map.setSrs("epsg:3857").setBackground("#102030").setBufferSize(9).setAspectFixMode(AspectFixMode.RESPECT);
            map.addStyle(Style.create("s").add(Rule.create().add(Symbolizer.polygon().fill("red"))));
            assertEquals("epsg:3857", map.srs());
            assertEquals("rgb(16,32,48)", map.background().get());
            assertEquals(9, map.bufferSize());
            assertEquals(AspectFixMode.RESPECT, map.aspectFixMode());
            assertEquals(300, map.width());
        }
    }
}
