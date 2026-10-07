package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Reading Mapnik Vector Tiles from an MBTiles file with the tiles input plugin. */
class VectorTilesIntegrationTest {
    private static final Path FILE = Paths.get("src/test/resources/tiles/square.mbtiles");

    @BeforeAll
    static void needThePlugin() throws Exception {
        Fixtures.dir();
        assumeTrue(Mapnik.isDatasourceRegistered("tiles"), "this Mapnik has no tiles input plugin");
    }

    private static MapnikMap mapOver(Datasource ds) {
        MapnikMap map = new MapnikMap(256, 256).setSrs("epsg:3857").setBackground("white");
        try (Layer layer = Layer.create("tiles", "epsg:3857")) {
            map.addStyle(Style.create("s").add(Rule.create().add(Symbolizer.polygon().fill("#ff0000"))));
            layer.addStyle("s").setDatasource(ds);
            map.addLayer(layer);
        } catch (RuntimeException e) {
            map.close();
            throw e;
        }
        return map.zoomToBox(Tiles.bounds(0, 0, 0));
    }

    @Test
    void anMbtilesLayerDrawsItsPolygon() {
        try (Datasource ds = Datasource.mbtiles(FILE, "places"); MapnikMap map = mapOver(ds); Image img = map.renderToImage()) {
            // the polygon covers the middle half of the tile (1024..3072 of 4096)
            assertEquals(0xFFFF0000, img.getArgb(128, 128));
            assertEquals(0xFFFF0000, img.getArgb(70, 190));
            assertEquals(0xFFFFFFFF, img.getArgb(10, 10));
            assertEquals(0xFFFFFFFF, img.getArgb(245, 245));
        }
    }

    @Test
    void theAttributesOfTheTileAreVisibleToFilters() {
        try (Datasource ds = Datasource.mbtiles(FILE, "places");
             MapnikMap map = new MapnikMap(256, 256).setSrs("epsg:3857").setBackground("white");
             Layer layer = Layer.create("tiles", "epsg:3857")) {
            map.addStyle(Style.create("s").add(
                Rule.create().filter("[name] = 'Square'").add(Symbolizer.polygon().fill("#00ff00"))));
            layer.addStyle("s").setDatasource(ds);
            map.addLayer(layer).zoomToBox(Tiles.bounds(0, 0, 0));
            try (Image img = map.renderToImage()) {
                assertEquals(0xFF00FF00, img.getArgb(128, 128));
            }
        }
    }

    @Test
    void anUnknownLayerIsRefusedAndAMissingFileToo() {
        MapnikException e = assertThrows(MapnikException.class, () -> Datasource.mbtiles(FILE, "nope"));
        assertTrue(e.getMessage().contains("nope"), e.getMessage());
        assertThrows(MapnikException.class, () -> Datasource.mbtiles(Paths.get("/no/such.mbtiles"), "places"));
        assertThrows(IllegalArgumentException.class, () -> Datasource.pmtiles(FILE, "places"));
        assertThrows(IllegalArgumentException.class, () -> Datasource.mbtiles(Paths.get("a.pmtiles"), null));
    }
}
