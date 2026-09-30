package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Reading UTFGrid JSON. Needs no native library. */
class UtfGridTest {

    // Characters: ' ' (32) is key 0, '!' (33) key 1, '#' (35) key 2: the spec skips '"' (34).
    private static final String JSON =
        "{\"grid\":[\"  !!\",\"  !#\",\"####\"],\"keys\":[\"\",\"a\",\"b\"],"
        + "\"data\":{\"a\":{\"name\":\"Alpha\",\"n\":3,\"r\":1.5,\"ok\":true},\"b\":{\"name\":\"Beta\"}}}";

    private static UtfGrid grid() {
        return UtfGrid.parse(JSON, 2, 8, 6);
    }

    @Test
    void readsCellsAndKeys() {
        UtfGrid g = grid();
        assertEquals(4, g.columns());
        assertEquals(3, g.rows());
        assertEquals(2, g.resolution());
        assertEquals(8, g.imageWidth());
        assertEquals(6, g.imageHeight());
        assertEquals(Arrays.asList("a", "b"), g.keys());
    }

    @Test
    void looksUpPixelsThroughTheResolution() {
        UtfGrid g = grid();
        assertEquals("", g.keyAt(0, 0));
        assertEquals("", g.keyAt(3, 1), "still the first cell of the row");
        assertEquals("a", g.keyAt(4, 0));
        assertEquals("a", g.keyAt(7, 1));
        assertEquals("a", g.keyAt(4, 2), "second row, third cell");
        assertEquals("b", g.keyAt(6, 2));
        assertEquals("b", g.keyAt(0, 5));
        assertEquals("b", g.keyAt(7, 5));
    }

    @Test
    void lookingOutsideTheImageFails() {
        UtfGrid g = grid();
        assertThrows(IndexOutOfBoundsException.class, () -> g.keyAt(8, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> g.keyAt(0, 6));
        assertThrows(IndexOutOfBoundsException.class, () -> g.keyAt(-1, 0));
    }

    @Test
    void readsFeatureData() {
        UtfGrid g = grid();
        assertEquals("Alpha", g.data("a").get("name"));
        assertEquals(Long.valueOf(3), g.data("a").get("n"));
        assertEquals(Double.valueOf(1.5), g.data("a").get("r"));
        assertEquals(Boolean.TRUE, g.data("a").get("ok"));
        assertNull(g.data("zzz"));
        assertEquals("Beta", g.attributesAt(6, 2).get("name"));
        assertTrue(g.attributesAt(0, 0).isEmpty(), "no feature, no attributes");
        assertThrows(UnsupportedOperationException.class, () -> g.data("a").put("x", 1));
    }

    @Test
    void decodesCodesAroundTheSkippedCharacters() {
        // Keys 0..70 written with the spec's mapping: add 32, then skip '"' (34) and '\' (92).
        StringBuilder row = new StringBuilder();
        StringBuilder keys = new StringBuilder("[\"\"");
        for (int i = 1; i <= 70; i++) {
            keys.append(",\"k").append(i).append('"');
        }
        keys.append(']');
        for (int i = 0; i <= 70; i++) {
            int c = i + 32;
            if (c >= 34) {
                c++;
            }
            if (c >= 92) {
                c++;
            }
            row.appendCodePoint(c);
        }
        String json = "{\"grid\":[" + Json.escape(row.toString()) + "],\"keys\":" + keys + ",\"data\":{}}";
        UtfGrid g = UtfGrid.parse(json, 1, 71, 1);
        for (int i = 0; i <= 70; i++) {
            assertEquals(i == 0 ? "" : "k" + i, g.keyAt(i, 0), "cell " + i);
        }
    }

    @Test
    void theLastCellCoversARaggedEdge() {
        // Three cells of 4 pixels cover 10 pixels: the last cell is only 2 wide.
        UtfGrid g = UtfGrid.parse("{\"grid\":[\" !#\"],\"keys\":[\"\",\"a\",\"b\"],\"data\":{}}", 4, 10, 1);
        assertEquals("b", g.keyAt(9, 0));
    }

    @Test
    void badGridsAreRejected() {
        String[] bad = {
            "[]", "{}", "{\"grid\":[],\"keys\":\"x\"}", "{\"grid\":[1],\"keys\":[\"\"]}", "{\"grid\":[\" \"],\"keys\":[1]}",
            "{\"grid\":[\"~\"],\"keys\":[\"\"]}", "not json"};
        for (String j : bad) {
            assertThrows(IllegalArgumentException.class, () -> UtfGrid.parse(j, 1, 10, 10), j);
        }
        assertThrows(IllegalArgumentException.class, () -> UtfGrid.parse(JSON, 0, 8, 6));
    }

    @Test
    void anEmptyGridIsFine() {
        UtfGrid g = UtfGrid.parse("{\"grid\":[],\"keys\":[\"\"],\"data\":{}}", 4, 0, 0);
        assertEquals(0, g.rows());
        assertEquals(0, g.columns());
        assertTrue(g.keys().isEmpty());
    }

    @Test
    void keepsTheOriginalJson() {
        assertEquals(JSON, grid().toJson());
        assertTrue(grid().toString().contains("2 features"), grid().toString());
    }
}
