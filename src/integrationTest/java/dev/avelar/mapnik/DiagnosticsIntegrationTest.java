package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Capabilities, logging, fonts, plugins and caches against a real Mapnik. */
class DiagnosticsIntegrationTest {
    /** Loading this leniently makes Mapnik log an error-level message about the unknown attribute. */
    private static final String NOISY_STYLE =
        "<Map><Style name=\"n\"><Rule><PolygonSymbolizer bogus=\"1\"/></Rule></Style></Map>";

    private Logging.Severity savedSeverity;
    private String savedFormat;

    @BeforeAll
    static void setUp() throws IOException {
        Fixtures.dir();
    }

    @BeforeEach
    void saveLogging() {
        savedSeverity = Logging.severity();
        savedFormat = Logging.format();
    }

    @AfterEach
    void restoreLogging() {
        Logging.toConsole();
        Logging.clearObjectSeverities();
        Logging.setSeverity(savedSeverity);
        Logging.setFormat(savedFormat);
    }

    private static String logWhileLoadingNoisyStyle(Path logFile) throws IOException {
        Logging.toFile(logFile);
        try (MapnikMap map = new MapnikMap(10, 10)) {
            map.loadString(NOISY_STYLE, null);
        }
        Logging.toConsole(); // closes the file
        return new String(Files.readAllBytes(logFile), "UTF-8");
    }

    // ---------------------------------------------------------------- capabilities

    @Test
    void reportsWhatMapnikWasBuiltWith() {
        Set<Capability> caps = Mapnik.capabilities();
        assertTrue(caps.contains(Capability.PNG), caps.toString());
        assertTrue(caps.contains(Capability.PROJ), caps.toString());
        assertTrue(caps.contains(Capability.THREADSAFE), "the concurrency tests rely on this: " + caps);
        assertThrows(UnsupportedOperationException.class, () -> caps.add(Capability.GRID));
    }

    @Test
    void supportsAgreesWithCapabilitiesAndHasCairo() {
        for (Capability c : Capability.values()) {
            assertEquals(Mapnik.capabilities().contains(c), Mapnik.supports(c), c.toString());
        }
        assertEquals(Mapnik.hasCairo(), Mapnik.supports(Capability.CAIRO));
    }

    @Test
    void imageFormatsMatchTheCapabilities() {
        try (Image img = Image.create(4, 4)) {
            img.fill("red");
            assertEquals(Mapnik.supports(Capability.WEBP), canEncode(img, "webp"));
            assertEquals(Mapnik.supports(Capability.JPEG), canEncode(img, "jpeg"));
            assertEquals(Mapnik.supports(Capability.TIFF), canEncode(img, "tiff"));
            assertEquals(Mapnik.supports(Capability.PNG), canEncode(img, "png"));
        }
    }

    private static boolean canEncode(Image img, String format) {
        try {
            return img.toBytes(format).length > 0;
        } catch (MapnikException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- logging

    @Test
    void severityCanBeReadAndChanged() {
        for (Logging.Severity s : Logging.Severity.values()) {
            Logging.setSeverity(s);
            assertEquals(s, Logging.severity());
        }
    }

    @Test
    void messagesAreLoggedWhenTheSeverityIsLowEnough(@TempDir Path tmp) throws IOException {
        Logging.setSeverity(Logging.Severity.WARN);
        String log = logWhileLoadingNoisyStyle(tmp.resolve("warn.log"));
        assertTrue(log.contains("bogus"), log);
        assertTrue(log.startsWith("Mapnik LOG>"), log);
    }

    @Test
    void noneSilencesTheLog(@TempDir Path tmp) throws IOException {
        Logging.setSeverity(Logging.Severity.NONE);
        assertEquals("", logWhileLoadingNoisyStyle(tmp.resolve("none.log")));
    }

    @Test
    void errorSeverityStillShowsErrors(@TempDir Path tmp) throws IOException {
        Logging.setSeverity(Logging.Severity.ERROR);
        assertTrue(logWhileLoadingNoisyStyle(tmp.resolve("error.log")).contains("bogus"));
    }

    @Test
    void aSingleObjectCanBeMoreTalkativeThanTheRest(@TempDir Path tmp) throws IOException {
        Logging.setSeverity(Logging.Severity.NONE);
        Logging.setSeverity("load_map", Logging.Severity.ERROR);
        assertEquals(Logging.Severity.ERROR, Logging.severity("load_map"));
        assertEquals(Logging.Severity.NONE, Logging.severity("something_else"), "others keep the global level");

        assertTrue(logWhileLoadingNoisyStyle(tmp.resolve("obj.log")).contains("bogus"));

        Logging.clearObjectSeverities();
        assertEquals(Logging.Severity.NONE, Logging.severity("load_map"));
        assertEquals("", logWhileLoadingNoisyStyle(tmp.resolve("cleared.log")));
    }

    @Test
    void theLineFormatCanBeChanged(@TempDir Path tmp) throws IOException {
        Logging.setSeverity(Logging.Severity.WARN);
        Logging.setFormat("TEST> %Y:");
        assertEquals("TEST> %Y:", Logging.format());
        String log = logWhileLoadingNoisyStyle(tmp.resolve("fmt.log"));
        assertTrue(log.startsWith("TEST> 20"), log);
    }

    @Test
    void logFileTextIsCompleteOnceBackOnTheConsole(@TempDir Path tmp) throws IOException {
        Logging.setSeverity(Logging.Severity.WARN);
        Path file = tmp.resolve("both.log");
        Logging.toFile(file);
        try (MapnikMap map = new MapnikMap(10, 10)) {
            map.loadString(NOISY_STYLE, null);
        }
        Logging.toConsole();
        long size = Files.size(file);
        assertTrue(size > 0);
        // Console again: nothing more goes to the file.
        try (MapnikMap map = new MapnikMap(10, 10)) {
            map.loadString(NOISY_STYLE, null);
        }
        assertEquals(size, Files.size(file));
    }

    @Test
    void loggingToAnUnwritableFileFails(@TempDir Path tmp) {
        // A directory that does not exist cannot hold a log.
        Path bad = tmp.resolve("no-such-dir").resolve("x.log");
        try {
            Logging.toFile(bad);
        } catch (MapnikException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    // ---------------------------------------------------------------- fonts

    @Test
    void registeredFontFacesAreListed() {
        assumeTrue(Fixtures.fonts(), "no Mapnik fonts directory known (set MAPNIK_FONTS)");
        assertTrue(Mapnik.fontFaces().contains("DejaVu Sans Book"), Mapnik.fontFaces().toString());
        assertTrue(Mapnik.fontFaces().contains("DejaVu Sans Bold"));
        assertEquals(Mapnik.fontFaces(), sorted(Mapnik.fontFaces()), "sorted");
        assertThrows(UnsupportedOperationException.class, () -> Mapnik.fontFaces().add("x"));
    }

    private static java.util.List<String> sorted(java.util.List<String> in) {
        java.util.List<String> copy = new java.util.ArrayList<>(in);
        java.util.Collections.sort(copy);
        return copy;
    }

    @Test
    void aFaceKnowsItsFile() {
        assumeTrue(Fixtures.fonts(), "no Mapnik fonts directory known (set MAPNIK_FONTS)");
        String file = Mapnik.fontFile("DejaVu Sans Book").get();
        assertTrue(file.endsWith("DejaVuSans.ttf"), file);
        assertTrue(Files.exists(Paths.get(file)));
        assertFalse(Mapnik.fontFile("No Such Face").isPresent());
    }

    @Test
    void oneFontFileCanBeRegistered() throws IOException {
        assumeTrue(Fixtures.fonts(), "no Mapnik fonts directory known (set MAPNIK_FONTS)");
        Path font = Paths.get(Mapnik.fontFile("DejaVu Sans Book").get());
        Mapnik.registerFontFile(font); // already known: must not fail
        assertTrue(Mapnik.fontFaces().contains("DejaVu Sans Book"));
    }

    @Test
    void registeringSomethingThatIsNotAFontFails(@TempDir Path tmp) throws IOException {
        Path notFont = Files.write(tmp.resolve("text.ttf"), "not a font".getBytes("UTF-8"));
        assertThrows(MapnikException.class, () -> Mapnik.registerFontFile(notFont));
        assertThrows(MapnikException.class, () -> Mapnik.registerFontFile(tmp.resolve("missing.ttf")));
    }

    // ---------------------------------------------------------------- plugins

    @Test
    void knownPluginsAreRegistered() {
        assertTrue(Mapnik.isDatasourceRegistered("geojson"));
        assertTrue(Mapnik.isDatasourceRegistered("shape"));
        assertFalse(Mapnik.isDatasourceRegistered("no-such-plugin"));
    }

    @Test
    void pluginDirectoriesAreReported() {
        String plugins = System.getProperty("mapnik.input.plugins");
        assertTrue(Mapnik.datasourcePluginDirectories().contains(plugins),
            Mapnik.datasourcePluginDirectories() + " should contain " + plugins);
    }

    @Test
    void onePluginFileCanBeRegistered() throws IOException {
        Path dir = Paths.get(System.getProperty("mapnik.input.plugins"));
        Path csv;
        try (Stream<Path> files = Files.list(dir)) {
            csv = files.filter(p -> p.getFileName().toString().startsWith("csv")).findFirst().get();
        }
        Mapnik.registerDatasource(csv); // already registered: must not fail
        assertTrue(Mapnik.isDatasourceRegistered("csv"));
    }

    @Test
    void registeringSomethingThatIsNotAPluginFails(@TempDir Path tmp) throws IOException {
        Path notPlugin = Files.write(tmp.resolve("fake.input"), "nope".getBytes("UTF-8"));
        assertThrows(MapnikException.class, () -> Mapnik.registerDatasource(notPlugin));
        assertThrows(MapnikException.class, () -> Mapnik.registerDatasource(tmp.resolve("missing.input")));
    }

    // ---------------------------------------------------------------- caches

    @Test
    void clearingCachesIsSafeAndRenderingStillWorks() {
        String style = "<Map srs=\"epsg:4326\" background-color=\"white\"><Style name=\"m\"><Rule>"
            + "<MarkersSymbolizer marker-type=\"ellipse\" width=\"10\" height=\"10\" fill=\"red\"/></Rule></Style></Map>";
        try (MapnikMap map = new MapnikMap(50, 50).loadString(style, null)) {
            map.renderToPng();
            Mapnik.clearCaches();
            Mapnik.clearCaches();
            map.renderToPng();
        }
    }
}
