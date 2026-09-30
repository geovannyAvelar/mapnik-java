package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MapnikMapTest {
    private static final String STYLE =
        "<Map background-color=\"steelblue\" srs=\"epsg:4326\"></Map>";

    @BeforeAll
    static void requireNativeLibrary() {
        boolean available;
        try {
            Mapnik.version();
            available = true;
        } catch (UnsatisfiedLinkError | NoClassDefFoundError e) {
            available = false;
        }
        assumeTrue(available, "libmapnik_c not found; build it with CMake (see README)");
    }

    @Test
    void versionIsReported() {
        assertFalse(Mapnik.version().isEmpty());
    }

    @Test
    void rendersPng() {
        try (MapnikMap map = new MapnikMap(256, 256)) {
            map.loadString(STYLE, Paths.get("."))
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
