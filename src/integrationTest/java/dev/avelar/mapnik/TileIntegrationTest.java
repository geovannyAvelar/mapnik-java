package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Tile rendering and layer subsets against a real Mapnik. */
class TileIntegrationTest {
    private static final int RED = 0xFFFF0000;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLUE = 0xFF0000FF;

    private static Path dir;

    @BeforeAll
    static void setUp() throws IOException {
        dir = Fixtures.dir();
    }

    /** A square tile map in EPSG:4326 with one layer drawn by the given rule. */
    private static MapnikMap tileMap(int size, MemoryDatasource ds, Rule rule) {
        MapnikMap map = new MapnikMap(size, size).setSrs("epsg:4326").setBackground("white");
        try (Layer layer = Layer.create("data", "epsg:4326")) {
            map.addStyle(Style.create("s").add(rule));
            layer.addStyle("s").setDatasource(ds);
            map.addLayer(layer);
        } catch (RuntimeException e) {
            map.close();
            throw e;
        }
        return map;
    }

    private static Rule redBox() {
        return Rule.create()
            .add(Symbolizer.polygon().fill("red"))
            .add(Symbolizer.line().stroke("black").strokeWidth(6));
    }

    private static int[] pixels(Image img) {
        return img.toArgb();
    }

    // ---------------------------------------------------------------- tiles stitch into the whole map

    @Test
    void fourTilesStitchIntoTheSameImageAsOneBigRender() {
        Geometry box = Geometry.rectangle(new Box2d(-60, -45, 100, 50));
        try (MemoryDatasource ds = MemoryDatasource.create().add(box);
             MapnikMap tiles = tileMap(256, ds, redBox());
             MapnikMap whole = tileMap(512, ds, redBox())) {
            whole.setSrs("epsg:3857").setAspectFixMode(AspectFixMode.RESPECT)
                .zoomToBox(Tiles.bounds(0, 0, 0));
            try (Image big = whole.renderToImage()) {
                int[] expected = pixels(big);
                int different = 0;
                for (Tiles.Tile t : new Tiles.Tile(0, 0, 0).children()) {
                    try (Image tile = tiles.renderTile(t, 0)) {
                        int ox = (t.x() - t.parent().x() * 2) * 256;
                        int oy = (t.y() - t.parent().y() * 2) * 256;
                        int[] got = pixels(tile);
                        for (int y = 0; y < 256; y++) {
                            for (int x = 0; x < 256; x++) {
                                if (got[y * 256 + x] != expected[(oy + y) * 512 + ox + x]) {
                                    different++;
                                }
                            }
                        }
                    }
                }
                assertEquals(0, different, "tiles and the big render must agree pixel for pixel");
            }
        }
    }

    @Test
    void theTileShowsWhatIsInsideItAndNothingElse() {
        // A box covering the top-left quadrant of the world only.
        try (MemoryDatasource ds = MemoryDatasource.create().add(Geometry.rectangle(new Box2d(-170, 10, -10, 80)));
             MapnikMap map = tileMap(256, ds, Rule.create().add(Symbolizer.polygon().fill("red")))) {
            try (Image topLeft = map.renderTile(1, 0, 0, 0); Image bottomRight = map.renderTile(1, 1, 1, 0)) {
                assertEquals(RED, topLeft.getArgb(128, 128));
                assertEquals(WHITE, bottomRight.getArgb(128, 128));
            }
        }
    }

    @Test
    void aLocationLandsWhereTheTileMathSaysItShould() {
        double lon = 13.405;
        double lat = 52.52;
        Tiles.Tile t = Tiles.tileAt(lon, lat, 6);
        try (MemoryDatasource ds = MemoryDatasource.create().add(Geometry.point(lon, lat));
             MapnikMap map = tileMap(256, ds, Rule.create().add(Symbolizer.markers()
                 .attr("marker-type", "ellipse").size(14, 14).fill("red").allowOverlap(true)));
             CoordinateTransform tr = CoordinateTransform.between("epsg:4326", "epsg:3857");
             Image img = map.renderTile(t, 0)) {
            Box2d b = t.bounds();
            Point2d m = tr.forward(lon, lat);
            int px = (int) Math.round((m.x() - b.minX()) / b.width() * 256);
            int py = (int) Math.round((b.maxY() - m.y()) / b.height() * 256);
            assertEquals(RED, img.getArgb(px, py), "at " + px + "," + py);
            assertEquals(WHITE, img.getArgb(Math.min(255, px + 30), py));
        }
    }

    // ---------------------------------------------------------------- the map is left as it was

    @Test
    void renderingATileLeavesTheMapAsItWas() {
        try (MemoryDatasource ds = MemoryDatasource.create().add(Geometry.rectangle(new Box2d(-20, -20, 20, 20)));
             MapnikMap map = tileMap(200, ds, redBox())) {
            map.setBufferSize(5).setAspectFixMode(AspectFixMode.GROW_BBOX).zoomToBox(-30, -30, 30, 30);
            Box2d extent = map.extent();
            byte[] before = map.renderToPng();

            map.renderTile(3, 4, 2, 16).close();

            assertEquals("epsg:4326", map.srs());
            assertEquals(200, map.width());
            assertEquals(200, map.height());
            assertEquals(5, map.bufferSize());
            assertEquals(AspectFixMode.GROW_BBOX, map.aspectFixMode());
            assertEquals(extent.minX(), map.extent().minX(), 1e-9);
            assertEquals(extent.maxY(), map.extent().maxY(), 1e-9);
            assertArrayEquals(before, map.renderToPng(), "it draws exactly what it drew before");
        }
    }

    @Test
    void theMapIsRestoredEvenIfTheRenderFails() {
        // A buffer this big asks for a canvas that cannot exist, so the render fails after the map was changed.
        try (MemoryDatasource ds = MemoryDatasource.create().add(Geometry.point(1, 1));
             MapnikMap map = tileMap(64, ds, Rule.create().add(Symbolizer.polygon().fill("red")))) {
            map.setBufferSize(3).zoomToBox(-10, -10, 10, 10);
            Box2d extent = map.extent();

            assertThrows(MapnikException.class, () -> map.renderTile(2, 1, 1, 100_000_000));

            assertEquals("epsg:4326", map.srs());
            assertEquals(64, map.width());
            assertEquals(64, map.height());
            assertEquals(3, map.bufferSize());
            assertEquals(AspectFixMode.GROW_BBOX, map.aspectFixMode());
            assertEquals(extent.minX(), map.extent().minX(), 1e-9);
            assertEquals(extent.maxX(), map.extent().maxX(), 1e-9);
            map.renderToPng(); // and it still works
        }
    }

    // ---------------------------------------------------------------- the metatile buffer

    @Test
    void theBufferBringsInMarkersJustOutsideTheTile() {
        // A marker centred a few pixels left of tile 1/1/0, which starts at longitude 0.
        Tiles.Tile t = new Tiles.Tile(1, 1, 0);
        double degreesPerPixel = 180.0 / 256;
        double lon = -5 * degreesPerPixel;
        double lat = 45;
        Rule marker = Rule.create().add(Symbolizer.markers().attr("marker-type", "ellipse")
            .size(40, 40).fill("blue").allowOverlap(true));
        try (MemoryDatasource ds = MemoryDatasource.create().add(Geometry.point(lon, lat));
             MapnikMap map = tileMap(256, ds, marker)) {
            int row;
            try (CoordinateTransform tr = CoordinateTransform.between("epsg:4326", "epsg:3857")) {
                row = (int) Math.round((t.bounds().maxY() - tr.forward(lon, lat).y()) / t.bounds().height() * 256);
            }
            try (Image plain = map.renderTile(t, 0); Image buffered = map.renderTile(t, 32)) {
                assertEquals(WHITE, plain.getArgb(3, row), "without a buffer the off-tile marker is not drawn");
                assertEquals(BLUE, buffered.getArgb(3, row), "with a buffer its edge shows inside the tile");
                assertEquals(256, buffered.width());
            }
        }
    }

    @Test
    void theBufferDoesNotChangeTheTileSize() {
        try (MemoryDatasource ds = MemoryDatasource.create().add(Geometry.rectangle(new Box2d(-20, -20, 20, 20)));
             MapnikMap map = tileMap(128, ds, redBox())) {
            for (int buffer : new int[] {0, 1, 17, 64}) {
                try (Image img = map.renderTile(0, 0, 0, buffer)) {
                    assertEquals(128, img.width(), "buffer " + buffer);
                    assertEquals(128, img.height(), "buffer " + buffer);
                }
            }
        }
    }

    @Test
    void theBufferLeavesPolygonsUntouched() {
        try (MemoryDatasource ds = MemoryDatasource.create().add(Geometry.rectangle(new Box2d(-60, -45, 100, 50)));
             MapnikMap map = tileMap(256, ds, redBox())) {
            try (Image a = map.renderTile(2, 1, 1, 0); Image b = map.renderTile(2, 1, 1, 24)) {
                assertArrayEquals(pixels(a), pixels(b), "a polygon draws the same either way");
            }
        }
    }

    // ---------------------------------------------------------------- bytes and errors

    @Test
    void tilesCanBeEncoded() {
        try (MemoryDatasource ds = MemoryDatasource.create().add(Geometry.rectangle(new Box2d(-20, -20, 20, 20)));
             MapnikMap map = tileMap(256, ds, redBox())) {
            byte[] png = map.renderTileToBytes(new Tiles.Tile(2, 1, 1), "png", 0);
            assertEquals(0x89, png[0] & 0xFF);
            assertEquals('P', png[1]);
            Image.Info info = Image.probe(png);
            assertEquals(256, info.width());
            assertEquals(256, info.height());
            byte[] small = map.renderTileToBytes(new Tiles.Tile(2, 1, 1), "png8", 0);
            assertTrue(small.length < png.length || small.length > 0);
        }
    }

    @Test
    void aTileMapMustBeSquareAndTheTileMustExist() {
        try (MapnikMap wide = new MapnikMap(200, 100)) {
            assertThrows(IllegalStateException.class, () -> wide.renderTile(0, 0, 0, 0));
        }
        try (MapnikMap square = new MapnikMap(64, 64)) {
            assertThrows(IllegalArgumentException.class, () -> square.renderTile(1, 2, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> square.renderTile(0, 0, 0, -1));
        }
    }

    // ---------------------------------------------------------------- layer subsets

    @Test
    void renderLayersDrawsOnlyTheNamedOnes() {
        try (MapnikMap map = new MapnikMap(200, 200).load(dir.resolve("two-layers.xml")).zoomToBox(-20, -20, 20, 20)) {
            try (Image both = map.renderToImage(); Image big = map.renderLayers(Collections.singletonList("big"));
                 Image small = map.renderLayers(Collections.singletonList("small"))) {
                assertEquals(BLUE, both.getArgb(100, 100));
                assertEquals(RED, big.getArgb(100, 100), "only the red layer");
                assertEquals(RED, big.getArgb(60, 60));
                assertEquals(BLUE, small.getArgb(100, 100), "only the blue layer");
                assertEquals(WHITE, small.getArgb(60, 60), "the red layer is not drawn");
            }
        }
    }

    @Test
    void renderLayersRestoresTheActiveFlags() {
        try (MapnikMap map = new MapnikMap(100, 100).load(dir.resolve("two-layers.xml")).zoomToBox(-20, -20, 20, 20)) {
            map.setLayerActive("small", false);
            map.renderLayers(Collections.singletonList("big")).close();
            assertTrue(map.isLayerActive("big"));
            assertFalse(map.isLayerActive("small"), "it was off before, so it is off after");
            map.renderLayers(Arrays.asList("small", "big")).close();
            assertTrue(map.isLayerActive("big"));
            assertFalse(map.isLayerActive("small"));
        }
    }

    @Test
    void renderLayersWithAnUnknownNameChangesNothing() {
        try (MapnikMap map = new MapnikMap(100, 100).load(dir.resolve("two-layers.xml")).zoomToBox(-20, -20, 20, 20)) {
            assertThrows(IllegalArgumentException.class, () -> map.renderLayers(Arrays.asList("big", "nope")));
            assertTrue(map.isLayerActive("big"));
            assertTrue(map.isLayerActive("small"));
        }
    }

    @Test
    void renderLayersWithNoNamesDrawsOnlyTheBackground() {
        try (MapnikMap map = new MapnikMap(100, 100).load(dir.resolve("two-layers.xml")).zoomToBox(-20, -20, 20, 20);
             Image img = map.renderLayers(Collections.<String>emptyList())) {
            assertEquals(WHITE, img.getArgb(50, 50));
        }
    }
}
