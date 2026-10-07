package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class LoadDiagnosticsIntegrationTest {
    private static MapLoadException failing(String xml, boolean strict) {
        try (MapnikMap m = new MapnikMap(10, 10)) {
            m.loadString(xml, null, strict);
        } catch (MapLoadException e) {
            return e;
        }
        return fail("expected the style to be refused");
    }

    @Test
    void aBadColourNamesTheLineStyleAndElement() {
        MapLoadException e = failing("<Map>\n<Style name=\"roads\">\n<Rule><PolygonSymbolizer fill=\"nonsense\"/></Rule>\n</Style></Map>", false);
        assertEquals(3, e.line());
        assertEquals("roads", e.style());
        assertEquals("PolygonSymbolizer", e.element());
        assertTrue(e.getMessage().contains("nonsense"), e.getMessage());
    }

    @Test
    void aBadLayerProjectionNamesTheLayer() {
        MapLoadException e = failing("<Map>\n<Layer name=\"water\" srs=\"bogus\"></Layer>\n</Map>", false);
        assertEquals("water", e.layer());
        assertEquals(2, e.line());
    }

    @Test
    void brokenXmlHasALine() {
        MapLoadException e = failing("<Map><Style name=\"a\"></Map>", false);
        assertEquals(1, e.line());
        assertTrue(e.getMessage().contains("not well formed"), e.getMessage());
    }

    @Test
    void strictLoadListsEachProblem() {
        MapLoadException e = failing("<Map>\n<Style name=\"a\"><Rule><PolygonSymbolizer bogus=\"1\" other=\"2\"/></Rule></Style></Map>", true);
        assertTrue(e.problems().size() >= 2, e.problems().toString());
        assertTrue(e.problems().get(0).contains("bogus"), e.problems().toString());
    }

    @Test
    void validateReturnsNothingForAGoodStyleAndProblemsForABadOne() {
        assertEquals(0, MapnikMap.validate("<Map><Style name=\"a\"><Rule/></Style></Map>", null).size());
        List<String> problems = MapnikMap.validate("<Map><Style name=\"a\"><Rule><PolygonSymbolizer bogus=\"1\"/></Rule></Style></Map>", null);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("bogus"), problems.toString());
    }

    @Test
    void itIsStillAMapnikException() {
        assertThrows(MapnikException.class, () -> {
            try (MapnikMap m = new MapnikMap(10, 10)) {
                m.loadString("<Map", null);
            }
        });
    }
}
