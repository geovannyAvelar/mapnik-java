package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

/** The text placement helpers write attribute names Mapnik's strict loader accepts, and they change what is drawn. */
class TextPlacementIntegrationTest {
    @BeforeAll
    static void fonts() throws Exception {
        Fixtures.dir();
        Assumptions.assumeFalse(Mapnik.fontFaces().isEmpty(), "no fonts registered");
    }

    private static String face() {
        for (String f : Mapnik.fontFaces()) {
            if (f.contains("DejaVu Sans")) {
                return f;
            }
        }
        return Mapnik.fontFaces().get(0);
    }

    @Test
    void everyHelperIsAcceptedByMapnikStrictly() {
        Symbolizer s = Symbolizer.text("[name]").faceName(face()).fontSize(12)
            .labelPlacement(Symbolizer.LabelPlacement.POINT)
            .positions(Symbolizer.Position.NORTH_EAST, Symbolizer.Position.SOUTH_EAST, Symbolizer.Position.EXACT)
            .placementSizes(12, 10, 8)
            .margin(5).minimumPadding(2).avoidEdges(true).repeatDistance(100).labelSpacing(50)
            .minimumPathLength(20).maxCharAngleDelta(30).labelPositionTolerance(4).largestBoxOnly(true)
            .wrapWidth(80).wrapCharacter(" ").wrapBefore(false).justify("center")
            .horizontalAlignment("middle").verticalAlignment("middle").upright("auto").displacement(1, -2)
            .rotation(15).textTransform("uppercase").allowOverlap(false);
        assertEquals(Arrays.asList(), MapnikMap.validate("<Map>" + Style.create("t").add(Rule.create().add(s)).toXml() + "</Map>", null));
    }

    @Test
    void everyLabelPlacementValueIsKnownToMapnik() {
        for (Symbolizer.LabelPlacement p : Symbolizer.LabelPlacement.values()) {
            Symbolizer s = Symbolizer.text("[name]").faceName(face()).labelPlacement(p);
            assertEquals(Arrays.asList(), MapnikMap.validate("<Map>" + Style.create("t").add(Rule.create().add(s)).toXml() + "</Map>", null), p.name());
        }
    }

    @Test
    void aMisspeltValueIsStillCaughtByStrictLoading() {
        Symbolizer s = Symbolizer.text("[name]").faceName(face()).attr("placement-typo", "x");
        assertFalse(MapnikMap.validate("<Map>" + Style.create("t").add(Rule.create().add(s)).toXml() + "</Map>", null).isEmpty());
    }

    @Test
    void sizesNeedPositionsFirstAndMustBePositive() {
        assertThrows(IllegalStateException.class, () -> Symbolizer.text("[n]").placementSizes(10));
        assertThrows(IllegalArgumentException.class, () -> Symbolizer.text("[n]").positions(Symbolizer.Position.NORTH).placementSizes(0));
        assertThrows(IllegalArgumentException.class, () -> Symbolizer.text("[n]").positions());
    }

    private static int inkRows(String placement, int mapHeight) {
        // a point label near the right edge: with avoid-edges it must vanish, without it it is cut
        try (MemoryDatasource ds = MemoryDatasource.create();
             MapnikMap map = new MapnikMap(100, mapHeight).setSrs("+proj=longlat +datum=WGS84").setBackground("white");
             Layer layer = Layer.create("l", "+proj=longlat +datum=WGS84")) {
            ds.add(Geometry.point(49.5, 0), java.util.Collections.singletonMap("name", "EDGE LABEL"));
            Symbolizer text = Symbolizer.text("[name]").faceName(face()).fontSize(14).fill("black");
            if ("avoid".equals(placement)) {
                text.avoidEdges(true);
            }
            map.addStyle(Style.create("s").add(Rule.create().add(text)));
            layer.addStyle("s").setDatasource(ds);
            map.addLayer(layer).zoomToBox(-50, -25, 50, 25);
            try (Image img = map.renderToImage()) {
                int ink = 0;
                for (int y = 0; y < img.height(); y++) {
                    for (int x = 0; x < img.width(); x++) {
                        if ((img.getArgb(x, y) & 0xFFFFFF) != 0xFFFFFF) {
                            ink++;
                        }
                    }
                }
                return ink;
            }
        }
    }

    @Test
    void avoidEdgesDropsALabelThatWouldBeCut() {
        assertTrue(inkRows("plain", 50) > 0, "the label is drawn, cut off at the edge");
        assertEquals(0, inkRows("avoid", 50), "with avoid-edges it is not drawn");
    }
}
