package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Registers the input plugins once and copies the test resources to a temp directory. */
final class Fixtures {
    private static Path dir;

    private Fixtures() {}

    static synchronized Path dir() throws IOException {
        if (dir == null) {
            String plugins = System.getProperty("mapnik.input.plugins");
            assertTrue(plugins != null && !plugins.isEmpty(),
                "Mapnik input plugin directory unknown: put mapnik-config on PATH or set MAPNIK_INPUT_PLUGINS");
            Mapnik.registerDatasources(plugins);
            dir = Files.createTempDirectory("mapnik-java-it");
            for (String f : new String[] {"square.xml", "square.geojson", "small.geojson", "two-layers.xml",
                                          "missing-data.xml", "places.geojson", "poly-hole.geojson", "line.geojson",
                                          "multi-point.geojson", "multi-line.geojson", "multi-polygon.geojson"}) {
                try (InputStream in = Fixtures.class.getResourceAsStream("/" + f)) {
                    assertNotNull(in, "missing test resource " + f);
                    Files.copy(in, dir.resolve(f), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return dir;
    }
}
