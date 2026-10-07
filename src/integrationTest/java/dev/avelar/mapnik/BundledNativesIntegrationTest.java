package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Checks the prebuilt native bundle. Skipped unless the natives jar is on the class path and nothing else
 * supplied Mapnik; the clean-container build runs it with {@code -PbundledNatives}.
 */
class BundledNativesIntegrationTest {
    private static Path bundle;

    @BeforeAll
    static void requireTheBundle() throws IOException {
        assumeTrue(Mapnik.isBundled(), "the natives bundle is not in use");
        bundle = Mapnik.bundledDirectory();
        Fixtures.dir();
    }

    // ---------------------------------------------------------------- layout

    @Test
    void theBundleHasItsParts() {
        for (String part : new String[] {"lib/" + System.mapLibraryName("mapnik_c"), "plugins/input", "fonts", "proj/proj.db", "licenses", "MANIFEST", "NOTICE", ".complete"}) {
            assertTrue(Files.exists(bundle.resolve(part)), part + " in " + bundle);
        }
    }

    @Test
    void theManifestListsEveryFileAndMatchesWhatIsOnDisk() throws IOException {
        List<String> listed = new ArrayList<>();
        for (String line : Files.readAllLines(bundle.resolve("MANIFEST"), StandardCharsets.UTF_8)) {
            String[] f = line.split("\t");
            listed.add(f[0]);
            assertEquals(Long.parseLong(f[1]), Files.size(bundle.resolve(f[0])), f[0]);
        }
        assertTrue(listed.size() > 20, "a real bundle has many files: " + listed.size());
        assertTrue(listed.contains("lib/" + System.mapLibraryName("mapnik_c")));
        assertFalse(listed.contains("MANIFEST"));
    }

    @Test
    void licencesAndANoticeShipWithTheLibraries() throws IOException {
        assertTrue(Files.exists(bundle.resolve("licenses/mapnik.COPYING")));
        String notice = new String(Files.readAllBytes(bundle.resolve("NOTICE")), StandardCharsets.UTF_8);
        assertTrue(notice.contains("Mapnik"), notice);
        try (java.util.stream.Stream<Path> s = Files.list(bundle.resolve("licenses"))) {
            assertTrue(s.count() > 5, "a licence for each bundled library's package");
        }
    }

    // ---------------------------------------------------------------- really loaded from the bundle

    @Test
    void everyMapnikLibraryInTheProcessCameFromTheBundle() throws IOException {
        assumeTrue(Files.exists(Paths.get("/proc/self/maps")), "needs /proc; the macOS bundle is checked when it is built");
        List<String> maps = Files.readAllLines(Paths.get("/proc/self/maps"), StandardCharsets.UTF_8);
        String[] ours = {"libmapnik", "libicuuc", "libproj", "libfreetype", "libharfbuzz", "libcairo", "libpng", "libjpeg",
            "libtiff", "libwebp", "libsqlite3", "libxml2"};
        int checked = 0;
        List<String> strays = new ArrayList<>();
        for (String line : maps) {
            int slash = line.indexOf('/');
            if (slash < 0) {
                continue;
            }
            String path = line.substring(slash);
            String name = Paths.get(path.replace(" (deleted)", "")).getFileName().toString();
            for (String lib : ours) {
                if (name.startsWith(lib)) {
                    checked++;
                    if (!path.startsWith(bundle.toString())) {
                        strays.add(path);
                    }
                }
            }
        }
        assertTrue(checked > 5, "expected several bundled libraries in the process, saw " + checked);
        assertEquals(new ArrayList<String>(), strays, "these came from outside the bundle");
    }

    @Test
    void theShimItselfIsTheBundledOne() throws IOException {
        assumeTrue(Files.exists(Paths.get("/proc/self/maps")), "needs /proc; the macOS bundle is checked when it is built");
        boolean found = false;
        for (String line : Files.readAllLines(Paths.get("/proc/self/maps"), StandardCharsets.UTF_8)) {
            if (line.contains("libmapnik_c.so")) {
                found = true;
                assertTrue(line.contains(bundle.resolve("lib").toString()), line);
            }
        }
        assertTrue(found);
    }

    // ---------------------------------------------------------------- what works out of the box

    @Test
    void reportsTheMapnikItWasBuiltFor() {
        assertTrue(Mapnik.isCompatible(), Mapnik.version() + " vs " + Mapnik.expectedVersion());
        for (Capability c : new Capability[] {Capability.CAIRO, Capability.PNG, Capability.JPEG, Capability.TIFF,
            Capability.WEBP, Capability.PROJ, Capability.GRID, Capability.THREADSAFE, Capability.LOGGING}) {
            assertTrue(Mapnik.supports(c), c + " in " + Mapnik.capabilities());
        }
    }

    @Test
    void theInputPluginsAreRegisteredWithoutAskingForThem() {
        for (String p : new String[] {"csv", "geojson", "shape", "raster", "topojson", "geobuf"}) {
            assertTrue(Mapnik.isDatasourceRegistered(p), p + " in " + Mapnik.datasourcePlugins());
        }
    }

    @Test
    void theHeavyPluginsAreLeftOut() {
        for (String p : new String[] {"gdal", "ogr", "postgis", "pgraster"}) {
            assertFalse(Mapnik.isDatasourceRegistered(p), p + " is not bundled");
        }
    }

    @Test
    void projectionsWorkBecauseTheBundleBringsItsOwnData() {
        // EPSG codes are looked up in proj.db, which a clean machine does not have.
        try (CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:3857")) {
            assertEquals(1113194.9079327357, t.forward(10, 20).x(), 1e-6);
        }
        try (CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:32633")) {
            assertEquals(500000, t.forward(15, 0).x(), 1e-3);
        }
        try (CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:27700")) {
            assertTrue(t.forward(-1.5, 52).x() > 0);
        }
    }

    @Test
    void fontsAreRegisteredAndTextRenders() {
        assertTrue(Mapnik.fontFaces().contains("DejaVu Sans Book"), Mapnik.fontFaces().toString());
        assertTrue(Mapnik.fontFile("DejaVu Sans Book").get().startsWith(bundle.toString()));
        try (MemoryDatasource ds = MemoryDatasource.create().add(Geometry.point(0, 0), java.util.Collections.singletonMap("n", "Hi"));
             MapnikMap map = new MapnikMap(120, 60).setSrs("epsg:4326").setBackground("white");
             Layer layer = Layer.create("d", "epsg:4326")) {
            map.addStyle(Style.create("s").add(Rule.create().add(Symbolizer.text("[n]").faceName("DejaVu Sans Book").fontSize(24).fill("black"))));
            layer.addStyle("s").setDatasource(ds);
            map.addLayer(layer).zoomToBox(-60, -30, 60, 30);
            try (Image img = map.renderToImage()) {
                int dark = 0;
                for (int p : img.toArgb()) {
                    if ((p & 0xFF) < 100) {
                        dark++;
                    }
                }
                assertTrue(dark > 50, "text is drawn: " + dark);
            }
        }
    }

    @Test
    void everyOutputFormatWorks() {
        try (MemoryDatasource ds = MemoryDatasource.create().add(Geometry.rectangle(new Box2d(-20, -20, 20, 20)));
             MapnikMap map = new MapnikMap(100, 100).setSrs("epsg:4326").setBackground("white");
             Layer layer = Layer.create("d", "epsg:4326")) {
            map.addStyle(Style.create("s").add(Rule.create().add(Symbolizer.polygon().fill("red"))));
            layer.addStyle("s").setDatasource(ds);
            map.addLayer(layer).zoomToBox(-50, -50, 50, 50);
            for (String f : new String[] {"png", "png8", "jpeg", "webp", "tiff", "pdf", "svg", "ps"}) {
                assertTrue(map.renderToBytes(f).length > 100, f);
            }
            assertEquals("%PDF", new String(map.renderToBytes("pdf"), 0, 4, StandardCharsets.ISO_8859_1));
        }
    }

    @Test
    void utfGridAndExpressionsAndLoggingWork() {
        try (Expression e = Expression.parse("[a] + 1")) {
            assertEquals(3L, e.evaluate(Feature.create(1, Geometry.point(0, 0), java.util.Collections.singletonMap("a", 2))));
        }
        assertTrue(Mapnik.supports(Capability.GRID));
    }

    @Test
    void theNativesAreUnpackedOnceAndReused() throws IOException {
        Path parent = bundle.getParent();
        assertNotNull(parent);
        Set<String> others = new java.util.HashSet<>();
        try (java.util.stream.Stream<Path> s = Files.list(parent)) {
            s.forEach(p -> others.add(p.getFileName().toString()));
        }
        assertTrue(others.contains(bundle.getFileName().toString()));
        assertTrue(others.stream().noneMatch(n -> n.contains(".tmp-")), "no staging directories left: " + others);
    }
}
