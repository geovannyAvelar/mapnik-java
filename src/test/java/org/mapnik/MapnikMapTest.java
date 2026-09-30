package org.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MapnikMapTest {
    private static final String STYLE =
        "<Map background-color=\"steelblue\" srs=\"epsg:4326\"></Map>";

    @Test
    void versionIsReported() {
        assertFalse(Mapnik.version().isBlank());
    }

    @Test
    void rendersPng() {
        try (MapnikMap map = new MapnikMap(256, 256)) {
            map.loadString(STYLE, Path.of("."))
               .zoomToBox(-180, -90, 180, 90);
            byte[] png = map.renderToPng();
            assertTrue(png.length > 8);
            assertEquals((byte) 0x89, png[0]);
            assertEquals((byte) 'P', png[1]);
        }
    }

    @Test
    void badStyleThrows() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            assertThrows(MapnikException.class, () -> map.loadString("<nope", null));
        }
    }
}
