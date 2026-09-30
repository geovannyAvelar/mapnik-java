package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/** Registers the input plugins once and copies the test resources to a temp directory. */
final class Fixtures {
    private static Path dir;

    private Fixtures() {}

    private static boolean fontsRegistered;

    /** Registers the fonts directory once. Returns false if none is known, so callers can skip. */
    static synchronized boolean fonts() {
        String fonts = System.getProperty("mapnik.fonts");
        if (fonts == null || fonts.isEmpty() || !Files.isDirectory(Paths.get(fonts))) {
            // A natives bundle registers its own fonts when it loads.
            return Mapnik.fontFaces().contains("DejaVu Sans Book");
        }
        if (!fontsRegistered) {
            Mapnik.registerFonts(fonts);
            fontsRegistered = true;
        }
        return true;
    }

    static synchronized Path dir() throws IOException {
        if (dir == null) {
            String plugins = System.getProperty("mapnik.input.plugins");
            if (plugins != null && !plugins.isEmpty()) {
                Mapnik.registerDatasources(plugins);
            }
            // A natives bundle registers its own plugins when it loads.
            assertTrue(Mapnik.isDatasourceRegistered("geojson"),
                "no input plugins: put mapnik-config on PATH, set MAPNIK_INPUT_PLUGINS, or use the bundled natives");
            dir = Files.createTempDirectory("mapnik-java-it");
            for (String f : new String[] {"square.xml", "square.geojson", "small.geojson", "two-layers.xml",
                                          "missing-data.xml", "places.geojson", "poly-hole.geojson", "line.geojson",
                                          "multi-point.geojson", "multi-line.geojson", "multi-polygon.geojson",
                                          "outline.xml", "transparent-square.xml", "half-red.xml"}) {
                try (InputStream in = Fixtures.class.getResourceAsStream("/" + f)) {
                    assertNotNull(in, "missing test resource " + f);
                    Files.copy(in, dir.resolve(f), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return dir;
    }
}
