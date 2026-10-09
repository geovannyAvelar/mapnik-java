package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarkerIntegrationTest {
    @BeforeAll
    static void setUp() throws Exception {
        Fixtures.dir();
    }

    private static final String SVG = "<svg xmlns='http://www.w3.org/2000/svg' width='40' height='20' version='1.1'>"
        + "<rect x='0' y='0' width='40' height='20' fill='red'/></svg>";

    @Test
    void anSvgFileHasASize(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("pin.svg");
        Files.write(f, SVG.getBytes(StandardCharsets.UTF_8));
        Marker m = Marker.inspect(f);
        assertEquals(Marker.Kind.SVG, m.kind());
        assertEquals(40, m.width(), 0.5);
        assertEquals(20, m.height(), 0.5);
        assertEquals(40, m.declaredWidth(), 0.5);
        assertEquals(20, m.declaredHeight(), 0.5);
    }

    @Test
    void svgInMemoryIsTheSame() {
        Marker m = Marker.inspectSvg(SVG, true);
        assertEquals(Marker.Kind.SVG, m.kind());
        assertEquals(40, m.width(), 0.5);
    }

    @Test
    void aPictureHasItsPixelSize(@TempDir Path dir) {
        Path f = dir.resolve("icon.png");
        try (Image img = Image.create(24, 16)) {
            img.fill("#00ff00");
            img.save(f, "png");
        }
        Marker m = Marker.inspect(f);
        assertEquals(Marker.Kind.RASTER, m.kind());
        assertEquals(24, m.width());
        assertEquals(16, m.height());
    }

    @Test
    void whatIsNotAMarkerIsRefused(@TempDir Path dir) throws Exception {
        assertThrows(MapnikException.class, () -> Marker.inspect(dir.resolve("missing.svg")));
        Path text = dir.resolve("not.svg");
        Files.write(text, "this is not an svg".getBytes(StandardCharsets.UTF_8));
        assertThrows(MapnikException.class, () -> Marker.inspect(text, true));
        assertThrows(MapnikException.class, () -> Marker.inspectSvg("<svg", true));
    }

    @Test
    void strictRefusesWhatLenientSkips() {
        // a colour, path data or size Mapnik cannot read: skipped (or drawn wrong) when lenient, an error when strict
        String[] broken = {
            "<rect width='10' height='10' fill='notacolor'/>",
            "<path d='M 0 0 L foo 10'/>",
            "<rect width='abc' height='10'/>",
            "<rect width='10' height='10' style='fill:#zz'/>"
        };
        for (String body : broken) {
            String svg = "<svg xmlns='http://www.w3.org/2000/svg' width='10' height='10'>" + body + "</svg>";
            assertEquals(Marker.Kind.SVG, Marker.inspectSvg(svg, false).kind(), body);
            assertThrows(MapnikException.class, () -> Marker.inspectSvg(svg, true), body);
        }
    }

    @Test
    void anUnknownElementIsSkippedEvenWhenStrict() {
        String svg = "<svg xmlns='http://www.w3.org/2000/svg' width='10' height='10'><bogus-element/><rect width='10' height='10'/></svg>";
        assertEquals(10, Marker.inspectSvg(svg, true).width(), 0.5);
    }
}
