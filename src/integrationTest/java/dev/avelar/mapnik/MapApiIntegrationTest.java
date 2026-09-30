package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Map, Layer and Datasource API against a real Mapnik. */
class MapApiIntegrationTest {
    private static final int RED = 0xFFFF0000;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLUE = 0xFF0000FF;

    private static Path dir;

    @BeforeAll
    static void setUp() throws IOException {
        dir = Fixtures.dir();
    }

    private static MapnikMap twoLayerMap() {
        return new MapnikMap(200, 200).load(dir.resolve("two-layers.xml")).zoomToBox(-20, -20, 20, 20);
    }

    private static Datasource geojson(String file) {
        Map<String, Object> p = new HashMap<>();
        p.put("type", "geojson");
        p.put("file", dir.resolve(file).toString());
        return Datasource.create(p);
    }

    private static BufferedImage decode(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    // ---------------------------------------------------------------- runtime

    @Test
    void versionNumberMatchesVersionString() {
        String[] v = Mapnik.version().split("\\.");
        int expected = Integer.parseInt(v[0]) * 100000 + Integer.parseInt(v[1]) * 100 + Integer.parseInt(v[2]);
        assertEquals(expected, Mapnik.versionNumber());
    }

    @Test
    void registeredPluginsIncludeGeoJson() {
        assertTrue(Mapnik.datasourcePlugins().contains("geojson"), Mapnik.datasourcePlugins().toString());
    }

    // ---------------------------------------------------------------- size, background, buffer

    @Test
    void sizeAndResize() {
        try (MapnikMap map = new MapnikMap(300, 150)) {
            assertEquals(300, map.width());
            assertEquals(150, map.height());
            map.resize(64, 32);
            assertEquals(64, map.width());
            assertEquals(32, map.height());
        }
    }

    @Test
    void backgroundColour() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            assertFalse(map.background().isPresent());
            map.setBackground("steelblue");
            assertTrue(map.background().isPresent());
            map.setBackground("#ff0000");
            assertEquals("rgb(255,0,0)", map.background().get());
            map.setBackground("rgba(0,0,255,0.5)");
            assertTrue(map.background().get().startsWith("rgba(0,0,255,0.5"), map.background().get());
        }
    }

    @Test
    void backgroundIsPaintedWhenRendering() throws IOException {
        try (MapnikMap map = new MapnikMap(20, 20)) {
            map.setBackground("#ff0000").zoomToBox(-1, -1, 1, 1);
            assertEquals(RED, decode(map.renderToPng()).getRGB(10, 10));
        }
    }

    @Test
    void invalidColourIsRejected() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            assertThrows(MapnikException.class, () -> map.setBackground("not-a-colour"));
        }
    }

    @Test
    void backgroundImageAndOpacity() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            assertFalse(map.backgroundImage().isPresent());
            assertEquals(1.0, map.backgroundImageOpacity(), 1e-6);
            map.setBackgroundImage(dir.resolve("bg.png")).setBackgroundImageOpacity(0.25);
            assertEquals(dir.resolve("bg.png").toString(), map.backgroundImage().get());
            assertEquals(0.25, map.backgroundImageOpacity(), 1e-6);
        }
    }

    @Test
    void bufferSizeAndBasePath() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            assertEquals(0, map.bufferSize());
            map.setBufferSize(16).setBasePath(dir);
            assertEquals(16, map.bufferSize());
            assertEquals(dir.toString(), map.basePath());
        }
    }

    // ---------------------------------------------------------------- extent, zoom, scale

    @Test
    void maximumExtentCanBeSetAndReset() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            assertFalse(map.maximumExtent().isPresent());
            map.setMaximumExtent(new Box2d(-1, -2, 3, 4));
            assertEquals(new Box2d(-1, -2, 3, 4), map.maximumExtent().get());
            map.resetMaximumExtent();
            assertFalse(map.maximumExtent().isPresent());
        }
    }

    @Test
    void aspectFixModeControlsHowABoxIsFitted() {
        try (MapnikMap map = new MapnikMap(200, 200)) {
            assertEquals(AspectFixMode.GROW_BBOX, map.aspectFixMode());

            map.zoomToBox(-20, -10, 20, 10);
            assertEquals(40, map.extent().height(), 1e-6, "box grown to the square map");

            map.setAspectFixMode(AspectFixMode.RESPECT);
            assertEquals(AspectFixMode.RESPECT, map.aspectFixMode());
            map.zoomToBox(-20, -10, 20, 10);
            assertEquals(new Box2d(-20, -10, 20, 10), map.extent(), "box used exactly");
        }
    }

    @Test
    void zoomToBoxAcceptsABox() {
        try (MapnikMap map = new MapnikMap(100, 100)) {
            map.zoomToBox(new Box2d(-5, -5, 5, 5));
            assertEquals(new Box2d(-5, -5, 5, 5), map.extent());
        }
    }

    @Test
    void scaleFollowsTheExtent() {
        try (MapnikMap map = new MapnikMap(100, 100)) {
            map.zoomToBox(-50, -50, 50, 50);
            assertEquals(1.0, map.scale(), 1e-9, "100 map units over 100 pixels");
            assertTrue(map.scaleDenominator() > 0);
            double before = map.scaleDenominator();
            map.zoom(0.5); // below 1 zooms in
            assertEquals(0.5, map.scale(), 1e-9);
            assertEquals(before / 2, map.scaleDenominator(), before * 1e-9);
            map.zoom(4); // above 1 zooms out
            assertEquals(2.0, map.scale(), 1e-9);
        }
    }

    @Test
    void panMovesTheView() {
        try (MapnikMap map = new MapnikMap(100, 100)) {
            map.zoomToBox(0, 0, 100, 100);
            map.pan(60, 50); // pan to centre on pixel (60, 50)
            Box2d e = map.extent();
            assertEquals(100, e.width(), 1e-6);
            assertEquals(10, e.minX(), 1e-6);
        }
    }

    @Test
    void panAndZoomCentresAndZooms() {
        try (MapnikMap map = new MapnikMap(100, 100)) {
            map.zoomToBox(0, 0, 100, 100);
            map.panAndZoom(50, 50, 0.5);
            assertEquals(50, map.extent().width(), 1e-6);
            assertEquals((map.extent().minX() + map.extent().maxX()) / 2, 50, 1e-6);
        }
    }

    @Test
    void bufferedExtentIsWiderByTheBuffer() {
        try (MapnikMap map = new MapnikMap(100, 100)) {
            map.zoomToBox(0, 0, 100, 100).setBufferSize(10);
            Box2d b = map.bufferedExtent();
            assertEquals(-10, b.minX(), 1e-6);
            assertEquals(110, b.maxX(), 1e-6);
            assertEquals(map.extent(), new Box2d(0, 0, 100, 100));
        }
    }

    // ---------------------------------------------------------------- xml and styles

    @Test
    void toXmlRoundTrips() {
        String xml;
        try (MapnikMap a = twoLayerMap()) {
            xml = a.toXml();
        }
        assertTrue(xml.contains("<Layer name=\"big\""), xml);
        try (MapnikMap b = new MapnikMap(200, 200)) {
            b.loadString(xml, dir);
            assertEquals(Arrays.asList("big", "small"), b.layerNames());
            assertEquals(Arrays.asList("blue", "red"), b.styleNames());
        }
    }

    @Test
    void saveWritesAnXmlFile(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("map.xml");
        try (MapnikMap map = twoLayerMap()) {
            map.save(out, false);
        }
        String xml = new String(Files.readAllBytes(out), "UTF-8");
        assertTrue(xml.startsWith("<?xml") || xml.contains("<Map"), xml);
        assertTrue(xml.contains("<Layer name=\"small\""));
    }

    @Test
    void stylesCanBeListedAndRemoved() {
        try (MapnikMap map = twoLayerMap()) {
            assertEquals(Arrays.asList("blue", "red"), map.styleNames());
            map.removeStyle("blue");
            assertEquals(Collections.singletonList("red"), map.styleNames());
        }
    }

    // ---------------------------------------------------------------- layers in a map

    @Test
    void layerViewsReadAndWriteTheMap() throws IOException {
        try (MapnikMap map = twoLayerMap()) {
            Layer small = map.layer("small");
            assertEquals("small", small.name());
            assertEquals(Collections.singletonList("blue"), small.styles());
            small.setActive(false);
            assertFalse(map.isLayerActive("small"));
            assertEquals(RED, decode(map.renderToPng()).getRGB(100, 100));
        }
    }

    @Test
    void layerByIndexAndUnknownLookups() {
        try (MapnikMap map = twoLayerMap()) {
            assertEquals("big", map.layer(0).name());
            assertEquals("small", map.layer(1).name());
            assertThrows(IndexOutOfBoundsException.class, () -> map.layer(2));
            assertThrows(IndexOutOfBoundsException.class, () -> map.layer(-1));
            assertThrows(IllegalArgumentException.class, () -> map.layer("nope"));
        }
    }

    @Test
    void removeLayerByNameAndIndex() throws IOException {
        try (MapnikMap map = twoLayerMap()) {
            map.removeLayer("small");
            assertEquals(Collections.singletonList("big"), map.layerNames());
            assertEquals(RED, decode(map.renderToPng()).getRGB(100, 100));
            assertThrows(IndexOutOfBoundsException.class, () -> map.removeLayer(5));
            map.removeLayer(0);
            assertEquals(0, map.layerCount());
        }
    }

    @Test
    void removeAllClearsLayersAndStyles() {
        try (MapnikMap map = twoLayerMap()) {
            map.removeAll();
            assertEquals(0, map.layerCount());
            assertTrue(map.styleNames().isEmpty());
        }
    }

    @Test
    void layerViewsGoStaleWhenTheLayerListChanges() {
        try (MapnikMap map = twoLayerMap()) {
            Layer big = map.layer("big");
            try (Layer extra = Layer.create("extra")) {
                map.addLayer(extra);
            }
            assertThrows(IllegalStateException.class, big::name);
            assertEquals("big", map.layer("big").name(), "a fresh view works");
        }
    }

    @Test
    void layerViewsGoStaleWhenTheMapIsClosed() {
        MapnikMap map = twoLayerMap();
        Layer big = map.layer("big");
        map.close();
        assertThrows(IllegalStateException.class, big::name);
    }

    // ---------------------------------------------------------------- building a map in code

    @Test
    void buildsAMapFromALayerAndADatasource() throws IOException {
        String styles = "<Map><Style name=\"red\"><Rule><PolygonSymbolizer fill=\"red\"/></Rule></Style></Map>";
        try (MapnikMap map = new MapnikMap(200, 200);
             Layer layer = Layer.create("square", "epsg:4326");
             Datasource ds = geojson("square.geojson")) {
            map.loadString(styles, dir).setSrs("epsg:4326").setBackground("white");
            layer.setDatasource(ds).addStyle("red");
            map.addLayer(layer);
            map.zoomToBox(-20, -20, 20, 20);

            assertEquals(Collections.singletonList("square"), map.layerNames());
            BufferedImage img = decode(map.renderToPng());
            assertEquals(RED, img.getRGB(100, 100));
            assertEquals(WHITE, img.getRGB(10, 10));
        }
    }

    @Test
    void addLayerCopiesSoTheOriginalCanBeClosedFirst() throws IOException {
        String styles = "<Map><Style name=\"blue\"><Rule><PolygonSymbolizer fill=\"blue\"/></Rule></Style></Map>";
        try (MapnikMap map = new MapnikMap(100, 100)) {
            map.loadString(styles, dir).setBackground("white");
            try (Layer layer = Layer.create("copy"); Datasource ds = geojson("square.geojson")) {
                layer.setDatasource(ds).addStyle("blue");
                map.addLayer(layer);
            }
            map.zoomToBox(-20, -20, 20, 20);
            assertEquals(BLUE, decode(map.renderToPng()).getRGB(50, 50));
        }
    }

    // ---------------------------------------------------------------- layer properties

    @Test
    void layerPropertiesRoundTrip() {
        try (Layer l = Layer.create("roads", "epsg:3857")) {
            assertEquals("roads", l.name());
            assertEquals("epsg:3857", l.srs());
            l.setName("highways").setSrs("epsg:4326");
            assertEquals("highways", l.name());
            assertEquals("epsg:4326", l.srs());

            assertTrue(l.isActive());
            assertFalse(l.setActive(false).isActive());

            assertFalse(l.isQueryable());
            assertTrue(l.setQueryable(true).isQueryable());

            l.setClearLabelCache(true).setCacheFeatures(true).setGroupBy("kind").setOpacity(0.5);
            assertTrue(l.clearsLabelCache());
            assertTrue(l.cachesFeatures());
            assertEquals("kind", l.groupBy());
            assertEquals(0.5, l.opacity(), 1e-9);

            l.addStyle("a").addStyle("b");
            assertEquals(Arrays.asList("a", "b"), l.styles());
        }
    }

    @Test
    void layerDefaultSrsIsWgs84() {
        try (Layer l = Layer.create("x")) {
            assertEquals("epsg:4326", l.srs());
        }
    }

    @Test
    void layerScaleRangeControlsVisibility() {
        try (Layer l = Layer.create("x")) {
            l.setMinimumScaleDenominator(1000).setMaximumScaleDenominator(10000);
            assertEquals(1000, l.minimumScaleDenominator(), 1e-9);
            assertEquals(10000, l.maximumScaleDenominator(), 1e-9);
            assertTrue(l.isVisibleAt(5000));
            assertFalse(l.isVisibleAt(500));
            assertFalse(l.isVisibleAt(50000));
            l.setActive(false);
            assertFalse(l.isVisibleAt(5000), "inactive layers are never visible");
        }
    }

    @Test
    void layerScaleRangeHidesItWhenRendering() throws IOException {
        try (MapnikMap map = twoLayerMap()) {
            double now = map.scaleDenominator();
            map.layer("small").setMinimumScaleDenominator(now * 10); // only visible when zoomed far out
            assertEquals(RED, decode(map.renderToPng()).getRGB(100, 100), "small is out of range");
        }
    }

    @Test
    void layerBufferSizeAndMaximumExtent() {
        try (Layer l = Layer.create("x")) {
            assertFalse(l.bufferSize().isPresent());
            assertEquals(8, l.setBufferSize(8).bufferSize().get().intValue());
            assertFalse(l.resetBufferSize().bufferSize().isPresent());

            assertFalse(l.maximumExtent().isPresent());
            l.setMaximumExtent(new Box2d(0, 0, 1, 1));
            assertEquals(new Box2d(0, 0, 1, 1), l.maximumExtent().get());
            assertFalse(l.resetMaximumExtent().maximumExtent().isPresent());
        }
    }

    @Test
    void closedLayerRejectsUse() {
        Layer l = Layer.create("x");
        l.close();
        l.close(); // idempotent
        assertThrows(IllegalStateException.class, l::name);
    }

    // ---------------------------------------------------------------- datasource

    @Test
    void datasourceDescribesItself() {
        try (Datasource ds = geojson("square.geojson")) {
            assertEquals(Datasource.Type.VECTOR, ds.type());
            assertEquals(Datasource.GeometryType.POLYGON, ds.geometryType());
            Box2d e = ds.envelope();
            assertEquals(-10, e.minX(), 1e-9);
            assertEquals(-10, e.minY(), 1e-9);
            assertEquals(10, e.maxX(), 1e-9);
            assertEquals(10, e.maxY(), 1e-9);

            List<Datasource.Field> fields = ds.fields();
            assertEquals(1, fields.size());
            assertEquals("name", fields.get(0).name());
            assertEquals(Datasource.FieldType.STRING, fields.get(0).type());
        }
    }

    @Test
    void layerExposesItsDatasourceAndEnvelope() {
        try (Layer l = Layer.create("x"); Datasource ds = geojson("small.geojson")) {
            assertFalse(l.datasource().isPresent());
            assertThrows(MapnikException.class, l::envelope);

            l.setDatasource(ds);
            assertEquals(new Box2d(-5, -5, 5, 5), l.envelope());
            try (Datasource again = l.datasource().get()) {
                assertEquals(new Box2d(-5, -5, 5, 5), again.envelope());
            }
        }
    }

    @Test
    void layerKeepsItsDatasourceAfterTheHandleIsClosed() {
        try (Layer l = Layer.create("x")) {
            try (Datasource ds = geojson("small.geojson")) {
                l.setDatasource(ds);
            }
            assertEquals(new Box2d(-5, -5, 5, 5), l.envelope());
        }
    }

    @Test
    void datasourceParametersAcceptTypedValues() {
        Map<String, Object> p = new HashMap<>();
        p.put("type", "geojson");
        p.put("file", dir.resolve("square.geojson").toString());
        p.put("cache_features", true);
        p.put("extra_int", 3);
        p.put("extra_double", 1.5);
        p.put("extra_long", 4L);
        try (Datasource ds = Datasource.create(p)) {
            assertNotNull(ds.envelope());
        }
    }

    @Test
    void datasourceErrors() {
        Map<String, Object> noType = new HashMap<>();
        noType.put("file", "x");
        assertThrows(MapnikException.class, () -> Datasource.create(noType));

        Map<String, Object> badPlugin = new HashMap<>();
        badPlugin.put("type", "no-such-plugin");
        assertThrows(MapnikException.class, () -> Datasource.create(badPlugin));

        Map<String, Object> missingFile = new HashMap<>();
        missingFile.put("type", "geojson");
        missingFile.put("file", dir.resolve("nope.geojson").toString());
        assertThrows(MapnikException.class, () -> Datasource.create(missingFile));

        Map<String, Object> badType = new HashMap<>();
        badType.put("type", "geojson");
        badType.put("file", new Object());
        assertThrows(IllegalArgumentException.class, () -> Datasource.create(badType));
    }

    @Test
    void closedDatasourceRejectsUse() {
        Datasource ds = geojson("square.geojson");
        ds.close();
        ds.close();
        assertThrows(IllegalStateException.class, ds::envelope);
    }
}
