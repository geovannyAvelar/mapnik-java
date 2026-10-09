package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Files in folders with accented and non-Latin names: styles, data, markers, pictures and the unpacked natives
 * themselves. A user name or a project folder like this is normal outside English-speaking countries.
 */
class NonAsciiPathIntegrationTest {
    /** Latin with accents, Portuguese, Japanese, Cyrillic: one name that needs every encoding to work. */
    private static final String NAME = "mapa-ação-ü-日本語-Привет";

    @BeforeAll
    static void setUp() throws Exception {
        Fixtures.dir();
    }

    /** Skips the test where the system's file names cannot hold the name (a C locale on Linux). */
    private static Path folder(Path parent) throws Exception {
        String jnu = System.getProperty("sun.jnu.encoding", "UTF-8");
        assumeTrue(Charset.forName(jnu).newEncoder().canEncode(NAME), "this system's file names cannot hold " + NAME + " (" + jnu + ")");
        Path dir = parent.resolve(NAME);
        Files.createDirectories(dir);
        return dir;
    }

    @Test
    void aStyleItsDataAndAMarkerInsideSuchAFolderLoadAndDraw(@TempDir Path tmp) throws Exception {
        Path dir = folder(tmp);
        Files.copy(Fixtures.dir().resolve("square.geojson"), dir.resolve("quadrado-日本.geojson"));
        String style = "<Map srs=\"epsg:4326\" background-color=\"white\"><Style name=\"fill\"><Rule><PolygonSymbolizer fill=\"red\"/></Rule></Style>"
            + "<Layer name=\"square\" srs=\"epsg:4326\"><StyleName>fill</StyleName><Datasource><Parameter name=\"type\">geojson</Parameter>"
            + "<Parameter name=\"file\">quadrado-日本.geojson</Parameter></Datasource></Layer></Map>";
        Path xml = dir.resolve("estilo-ação.xml");
        Files.write(xml, style.getBytes(StandardCharsets.UTF_8));
        try {
            Path out = dir.resolve("saída-Привет.png");
            try (MapnikMap map = new MapnikMap(100, 100).load(xml)) {
                map.zoomAll();
                map.renderToFile(out, "png");
            }
            assertTrue(Files.size(out) > 50, "the picture was written under the non-ASCII name");
            try (Image img = Image.load(out)) {
                assertEquals(100, img.width());
                assertEquals(0xFFFF0000, img.getArgb(50, 50), "the square was drawn in red");
            }
            Path svg = dir.resolve("marcador-ü.svg");
            Files.write(svg, "<svg xmlns='http://www.w3.org/2000/svg' width='12' height='9'><rect width='12' height='9'/></svg>".getBytes(StandardCharsets.UTF_8));
            assertEquals(12, Marker.inspect(svg).width(), 0.5);
            Map<String, Object> params = new HashMap<>();
            params.put("type", "geojson");
            params.put("file", dir.resolve("quadrado-日本.geojson").toString());
            try (Datasource ds = Datasource.create(params)) {
                assertTrue(ds.envelope().maxX() > ds.envelope().minX());
            }
        } finally {
            Mapnik.clearCaches();   // Windows will not delete a file that is still mapped
        }
    }

    @Test
    void theNativesCanBeUnpackedAndLoadedFromSuchAFolder(@TempDir Path tmp) throws Exception {
        assumeTrue(Mapnik.isBundled(), "needs the natives bundle: it is what gets unpacked");
        Path cache = folder(tmp);
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-Dmapnik.native.cache=" + cache);
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("dev.avelar.mapnik.NonAsciiSmoke");
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] b = new byte[4096];
            for (int n; (n = in.read(b)) > 0; ) {
                out.write(b, 0, n);
            }
        }
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "the child JVM finished");
        String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertEquals(0, p.exitValue(), "the natives load from a non-ASCII cache folder: " + text);
        assertTrue(text.contains("OK "), text);
    }
}
