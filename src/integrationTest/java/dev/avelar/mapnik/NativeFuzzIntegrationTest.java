package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Damaged and hostile input to the parts of Mapnik that read text and files: the XML loader, expressions, projections,
 * the image decoders and the file datasources. A bad input must end in a {@link MapnikException} (or an
 * {@link IllegalArgumentException} from this library's own checks). A crash would take the whole test JVM down with it,
 * which is the failure this test exists to find.
 */
class NativeFuzzIntegrationTest {
    private static final String SYNTAX = "()[]{}<>\"'/\\:,. -+eE01\n\t&;=#%$@!*";

    @BeforeAll
    static void setUp() throws Exception {
        Fixtures.dir();
    }

    private static String mutate(String s, Random r) {
        StringBuilder sb = new StringBuilder(s);
        int edits = 1 + r.nextInt(5);
        for (int i = 0; i < edits && sb.length() > 0; i++) {
            int at = r.nextInt(sb.length());
            switch (r.nextInt(5)) {
                case 0: sb.deleteCharAt(at); break;
                case 1: sb.insert(at, SYNTAX.charAt(r.nextInt(SYNTAX.length()))); break;
                case 2: sb.setCharAt(at, SYNTAX.charAt(r.nextInt(SYNTAX.length()))); break;
                case 3: sb.setLength(at); break;
                default: sb.insert(at, sb.substring(at, Math.min(sb.length(), at + 1 + r.nextInt(15))));
            }
        }
        return sb.toString();
    }

    private static byte[] mutate(byte[] data, Random r) {
        byte[] b = data.clone();
        int edits = 1 + r.nextInt(8);
        for (int i = 0; i < edits; i++) {
            b[r.nextInt(b.length)] = (byte) r.nextInt(256);
        }
        if (r.nextInt(4) == 0) {
            b = java.util.Arrays.copyOf(b, 1 + r.nextInt(b.length));
        }
        return b;
    }

    private static void refusesOrAccepts(String what, Object input, Runnable action) {
        try {
            action.run();
        } catch (MapnikException | IllegalArgumentException expected) {
            // refused: fine
        } catch (Throwable t) {
            fail(what + " threw " + t + " for " + (input instanceof byte[] ? "bytes" : input), t);
        }
    }

    @Test
    void damagedStylesAreRefusedOrLoaded() throws Exception {
        String styleFromBuilders = Style.create("s").add(Rule.create().filter("[a] > 3")
            .add(Symbolizer.polygon().fill("#112233").fillOpacity(0.5))
            .add(Symbolizer.line().stroke("red").strokeWidth(2).strokeDasharray("4,2"))
            .add(Symbolizer.text("[name]").faceName("DejaVu Sans Book").fontSize(10))).toXml();
        String[] seeds = {
            "<Map srs=\"epsg:4326\" background-color=\"white\">" + styleFromBuilders + "</Map>",
            new String(Files.readAllBytes(Fixtures.dir().resolve("square.xml")), StandardCharsets.UTF_8),
            new String(Files.readAllBytes(Fixtures.dir().resolve("two-layers.xml")), StandardCharsets.UTF_8)
        };
        Random r = new Random(11);
        for (int i = 0; i < 4_000; i++) {
            final String xml = mutate(seeds[i % seeds.length], r);
            final boolean strict = i % 2 == 0;
            refusesOrAccepts("style XML", xml, () -> {
                try (MapnikMap map = new MapnikMap(32, 32)) {
                    map.loadString(xml, Fixtures.dirUnchecked(), strict);
                }
            });
        }
    }

    @Test
    void damagedExpressionsProjectionsAndPathsAreRefused() {
        Random r = new Random(12);
        String[] expressions = {"[a] > 3 and [b] = 'x'", "[name].length() > 4", "[pop] * 2 + 1", "not ([a] != 1 or [b] <= 2)",
            "([x] >= 1) && ([y] < 2)", "'a' + [b]", "[a] % 3 = 0"};
        String[] projections = {"epsg:4326", "epsg:3857", "+proj=longlat +datum=WGS84 +no_defs",
            "+proj=merc +a=6378137 +b=6378137 +lat_ts=0.0 +lon_0=0.0 +x_0=0.0 +y_0=0 +k=1.0 +units=m +nadgrids=@null +no_defs"};
        String[] paths = {"images/[name].png", "[a]/[b]/file.svg", "x[a]y"};
        String[] transforms = {"translate(10, 20)", "rotate(45) scale(2)", "matrix(1 0 0 1 5 5)", "skewX(10)"};
        for (int i = 0; i < 4_000; i++) {
            final String e = mutate(expressions[i % expressions.length], r);
            refusesOrAccepts("expression", e, () -> Expression.parse(e).close());
            final String p = mutate(projections[i % projections.length], r);
            refusesOrAccepts("projection", p, () -> Projection.of(p).close());
            final String path = mutate(paths[i % paths.length], r);
            refusesOrAccepts("path expression", path, () -> PathExpression.parse(path).close());
            final String t = mutate(transforms[i % transforms.length], r);
            refusesOrAccepts("transform", t, () -> Transforms.check(t));
        }
    }

    @Test
    void damagedPicturesAreRefusedByTheDecoders() {
        Random r = new Random(13);
        java.util.List<byte[]> seeds = new java.util.ArrayList<>();
        try (Image img = Image.create(40, 40)) {
            for (int y = 0; y < 40; y++) {
                for (int x = 0; x < 40; x++) {
                    img.setArgb(x, y, 0xFF000000 | (x * 6 << 16) | (y * 6 << 8) | (x ^ y));
                }
            }
            for (String format : new String[] {"png", "png8", "jpeg", "webp", "tiff"}) {
                seeds.add(img.toBytes(format));
            }
        }
        for (int i = 0; i < 3_000; i++) {
            final byte[] data = mutate(seeds.get(i % seeds.size()), r);
            refusesOrAccepts("image decode", data, () -> Image.load(data).close());
            refusesOrAccepts("image probe", data, () -> Image.probe(data));
        }
    }

    @Test
    void damagedDataFilesAreRefusedByTheDatasources(@TempDir Path dir) throws Exception {
        String geojson = new String(Files.readAllBytes(Fixtures.dir().resolve("places.geojson")), StandardCharsets.UTF_8);
        String csv = "name,lon,lat,pop\nA,10.5,20.25,100\nB,-3,4,\"7,000\"\nC,1e1,2e1,0\n";
        Random r = new Random(14);
        try {
            for (int i = 0; i < 1_500; i++) {
                boolean json = i % 2 == 0;
                String content = mutate(json ? geojson : csv, r);
                // A new file each time: Mapnik keeps data files memory-mapped (even after the datasource is closed), and
                // overwriting a mapped file in place crashes the process. See "Data files" in the README.
                Path f = dir.resolve("data" + i + (json ? ".geojson" : ".csv"));
                Files.write(f, content.getBytes(StandardCharsets.UTF_8));
                Map<String, Object> params = new HashMap<>();
                params.put("type", json ? "geojson" : "csv");
                params.put("file", f.toString());
                refusesOrAccepts(json ? "GeoJSON file" : "CSV file", content, () -> {
                    try (Datasource ds = Datasource.create(params)) {
                        ds.envelope();
                        ds.fields();
                    }
                });
            }
        } finally {
            Mapnik.clearCaches();   // let go of the mapped files, which Windows will not delete while they are mapped
        }
    }

    /** The rule the test above follows, shown to fail when broken is not something a test can do: it kills the JVM. */
    @Test
    void replacingADataFileByRenameIsSafe(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("places.geojson");
        Files.copy(Fixtures.dir().resolve("places.geojson"), data);
        Map<String, Object> params = new HashMap<>();
        params.put("type", "geojson");
        params.put("file", data.toString());
        try (Datasource ds = Datasource.create(params)) {
            assertTrue(ds.envelope().maxX() > ds.envelope().minX());
        }
        // write a new file next to it, then rename it over the old one: the mapped old file stays valid
        Path next = dir.resolve("places.geojson.new");
        Files.write(next, ("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{},"
            + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[50,60]}}]}").getBytes(StandardCharsets.UTF_8));
        Files.move(next, data, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        Mapnik.clearCaches();
        try (Datasource ds = Datasource.create(params)) {
            assertEquals(50, ds.envelope().minX(), 1e-9, "the new file is read, not the old mapped one");
            assertEquals(60, ds.envelope().minY(), 1e-9);
        }
        Mapnik.clearCaches();
    }
}
