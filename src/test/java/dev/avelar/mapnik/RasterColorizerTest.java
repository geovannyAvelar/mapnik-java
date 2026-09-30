package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The raster colorizer XML and nested symbolizer elements. Needs no native library. */
class RasterColorizerTest {

    @Test
    void writesStopsInOrderWithTheirOptions() {
        RasterColorizer c = RasterColorizer.create()
            .defaultMode(RasterColorizer.Mode.LINEAR).defaultColor("transparent").epsilon(0.001)
            .stop(0, "blue").stop(500.5, "#00ff00", RasterColorizer.Mode.DISCRETE, "low & slow");
        assertEquals("<RasterColorizer default-mode=\"linear\" default-color=\"transparent\" epsilon=\"0.001\">"
                + "<stop value=\"0\" color=\"blue\"/>"
                + "<stop value=\"500.5\" color=\"#00ff00\" mode=\"discrete\" label=\"low &amp; slow\"/>"
                + "</RasterColorizer>",
            c.toXml());
        assertEquals(2, c.stopCount());
    }

    @Test
    void optionsAreOptional() {
        assertEquals("<RasterColorizer><stop value=\"1\" color=\"red\"/></RasterColorizer>",
            RasterColorizer.create().stop(1, "red").toXml());
    }

    @Test
    void coloursAreAcceptedAsColorObjects() {
        String xml = RasterColorizer.create().defaultColor(Color.TRANSPARENT).stop(0, Color.rgb(1, 2, 3)).toXml();
        assertTrue(xml.contains("default-color=\"rgba(0,0,0,0)\""), xml);
        assertTrue(xml.contains("color=\"rgba(1,2,3,1)\""), xml);
    }

    @Test
    void aColorizerNeedsAStop() {
        assertThrows(IllegalStateException.class, () -> RasterColorizer.create().toXml());
    }

    @Test
    void rasterSymbolizerWrapsItsColorizer() {
        String xml = Symbolizer.raster().opacity(0.5).colorizer(RasterColorizer.create().stop(0, "red")).toXml();
        assertEquals("<RasterSymbolizer opacity=\"0.5\"><RasterColorizer><stop value=\"0\" color=\"red\"/>"
            + "</RasterColorizer></RasterSymbolizer>", xml);
    }

    @Test
    void onlyARasterSymbolizerHasAColorizer() {
        assertThrows(IllegalStateException.class,
            () -> Symbolizer.polygon().colorizer(RasterColorizer.create().stop(0, "red")));
    }

    @Test
    void nestedElementsAreWrittenInOrder() {
        String xml = Symbolizer.polygon().fill("red").child("<A/>").child("<B x=\"1\"/>").toXml();
        assertEquals("<PolygonSymbolizer fill=\"red\"><A/><B x=\"1\"/></PolygonSymbolizer>", xml);
    }

    @Test
    void textContentAndChildrenCannotBeMixed() {
        assertThrows(IllegalStateException.class, () -> Symbolizer.text("[name]").child("<A/>"));
        assertThrows(IllegalArgumentException.class, () -> Symbolizer.polygon().child(""));
        assertThrows(IllegalArgumentException.class, () -> Symbolizer.polygon().child(null));
    }
}
