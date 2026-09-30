package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The tile grid maths. Needs no native library. */
class TilesTest {
    private static final double S = Tiles.ORIGIN_SHIFT;

    @Test
    void zoomZeroIsTheWholeWorld() {
        Box2d b = Tiles.bounds(0, 0, 0);
        assertEquals(-S, b.minX(), 1e-6);
        assertEquals(-S, b.minY(), 1e-6);
        assertEquals(S, b.maxX(), 1e-6);
        assertEquals(S, b.maxY(), 1e-6);
        Box2d ll = Tiles.lonLatBounds(0, 0, 0);
        assertEquals(-180, ll.minX(), 1e-9);
        assertEquals(180, ll.maxX(), 1e-9);
        assertEquals(-Tiles.MAX_LATITUDE, ll.minY(), 1e-6);
        assertEquals(Tiles.MAX_LATITUDE, ll.maxY(), 1e-6);
    }

    @Test
    void tilesSplitInFourEachZoomWithYGrowingDown() {
        Box2d topLeft = Tiles.bounds(1, 0, 0);
        assertEquals(-S, topLeft.minX(), 1e-6);
        assertEquals(0, topLeft.minY(), 1e-6);
        assertEquals(0, topLeft.maxX(), 1e-6);
        assertEquals(S, topLeft.maxY(), 1e-6);
        Box2d bottomRight = Tiles.bounds(1, 1, 1);
        assertEquals(0, bottomRight.minX(), 1e-6);
        assertEquals(-S, bottomRight.minY(), 1e-6);
        assertEquals(S, bottomRight.maxX(), 1e-6);
        assertEquals(0, bottomRight.maxY(), 1e-6);
        assertEquals(S / 2, Tiles.bounds(2, 0, 0).width(), 1e-6);
    }

    @Test
    void neighbouringTilesShareAnEdge() {
        for (int z = 1; z <= 6; z++) {
            for (int x = 0; x < (1 << z) - 1; x += Math.max(1, (1 << z) / 4)) {
                assertEquals(Tiles.bounds(z, x, 0).maxX(), Tiles.bounds(z, x + 1, 0).minX(), 1e-6, z + "/" + x);
            }
            assertEquals(Tiles.bounds(z, 0, 0).minY(), Tiles.bounds(z, 0, 1).maxY(), 1e-6);
        }
    }

    @Test
    void findsTheTileForALocation() {
        assertEquals(new Tiles.Tile(10, 511, 340), Tiles.tileAt(-0.1276, 51.5072, 10), "London");
        assertEquals(new Tiles.Tile(1, 1, 1), Tiles.tileAt(0, 0, 1));
        assertEquals(new Tiles.Tile(1, 0, 0), Tiles.tileAt(-90, 45, 1));
        assertEquals(new Tiles.Tile(1, 1, 0), Tiles.tileAt(90, 45, 1));
        assertEquals(new Tiles.Tile(0, 0, 0), Tiles.tileAt(12, 34, 0));
    }

    @Test
    void locationsOutsideTheGridAreClamped() {
        assertEquals(new Tiles.Tile(2, 0, 0), Tiles.tileAt(-200, 89, 2));
        assertEquals(new Tiles.Tile(2, 3, 3), Tiles.tileAt(200, -89, 2));
        assertEquals(new Tiles.Tile(2, 3, 0), Tiles.tileAt(180, 85.0511, 2), "the very edge belongs to the last tile");
        assertThrows(IllegalArgumentException.class, () -> Tiles.tileAt(Double.NaN, 0, 3));
        assertThrows(IllegalArgumentException.class, () -> Tiles.tileAt(0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> Tiles.tileAt(0, 0, Tiles.MAX_ZOOM + 1));
    }

    @Test
    void theCentreOfATileBelongsToThatTile() {
        for (int z : new int[] {0, 1, 3, 7, 12}) {
            int n = 1 << z;
            for (int i = 0; i < 8; i++) {
                int x = (int) ((long) i * (n - 1) / 7);
                int y = (int) ((long) (7 - i) * (n - 1) / 7);
                Box2d b = Tiles.lonLatBounds(z, x, y);
                Tiles.Tile t = Tiles.tileAt((b.minX() + b.maxX()) / 2, (b.minY() + b.maxY()) / 2, z);
                assertEquals(new Tiles.Tile(z, x, y), t);
            }
        }
    }

    @Test
    void coveringListsTilesRowByRow() {
        List<Tiles.Tile> world = Tiles.covering(new Box2d(-180, -85, 180, 85), 1);
        assertEquals(4, world.size());
        assertEquals(new Tiles.Tile(1, 0, 0), world.get(0));
        assertEquals(new Tiles.Tile(1, 1, 0), world.get(1));
        assertEquals(new Tiles.Tile(1, 0, 1), world.get(2));
        assertEquals(new Tiles.Tile(1, 1, 1), world.get(3));
        assertEquals(1, Tiles.covering(new Box2d(-0.2, 51.4, -0.1, 51.5), 10).size());
        assertEquals(4, Tiles.covering(new Box2d(-0.1, -0.1, 0.1, 0.1), 5).size(), "straddling the equator and meridian");
    }

    @Test
    void coveringRefusesHugeRequests() {
        assertThrows(IllegalArgumentException.class, () -> Tiles.covering(new Box2d(-180, -85, 180, 85), 12));
    }

    @Test
    void everyTileOfACoveringTouchesTheBox() {
        Box2d box = new Box2d(10, 40, 12, 42);
        Set<Tiles.Tile> seen = new HashSet<>(Tiles.covering(box, 7));
        for (Tiles.Tile t : seen) {
            Box2d b = t.lonLatBounds();
            assertTrue(b.minX() <= box.maxX() && b.maxX() >= box.minX() && b.minY() <= box.maxY() && b.maxY() >= box.minY(), t.toString());
        }
        assertTrue(seen.contains(Tiles.tileAt(11, 41, 7)));
    }

    @Test
    void tilesMustBeOnTheGrid() {
        assertThrows(IllegalArgumentException.class, () -> new Tiles.Tile(1, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> new Tiles.Tile(1, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new Tiles.Tile(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Tiles.Tile(Tiles.MAX_ZOOM + 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> Tiles.bounds(0, 1, 0));
        assertEquals(1 << 29, new Tiles.Tile(Tiles.MAX_ZOOM, (1 << 29), 0).x());
    }

    @Test
    void tmsCountsFromTheBottom() {
        assertEquals(0, new Tiles.Tile(0, 0, 0).tmsY());
        assertEquals(1, new Tiles.Tile(1, 0, 0).tmsY());
        assertEquals(0, new Tiles.Tile(1, 0, 1).tmsY());
        assertEquals(7 - 5, new Tiles.Tile(3, 3, 5).tmsY());
    }

    @Test
    void parentsAndChildren() {
        Tiles.Tile t = new Tiles.Tile(3, 3, 5);
        assertEquals(new Tiles.Tile(2, 1, 2), t.parent());
        List<Tiles.Tile> kids = t.children();
        assertEquals(4, kids.size());
        assertEquals(new Tiles.Tile(4, 6, 10), kids.get(0));
        assertEquals(new Tiles.Tile(4, 7, 11), kids.get(3));
        for (Tiles.Tile k : kids) {
            assertEquals(t, k.parent());
        }
        assertThrows(IllegalStateException.class, () -> new Tiles.Tile(0, 0, 0).parent());
        assertThrows(IllegalStateException.class, () -> new Tiles.Tile(Tiles.MAX_ZOOM, 0, 0).children());
    }

    @Test
    void childrenFillTheirParent() {
        Tiles.Tile t = new Tiles.Tile(4, 9, 6);
        Box2d p = t.bounds();
        List<Tiles.Tile> kids = t.children();
        assertEquals(p.minX(), kids.get(0).bounds().minX(), 1e-6);
        assertEquals(p.maxY(), kids.get(0).bounds().maxY(), 1e-6);
        assertEquals(p.maxX(), kids.get(3).bounds().maxX(), 1e-6);
        assertEquals(p.minY(), kids.get(3).bounds().minY(), 1e-6);
    }

    @Test
    void quadKeys() {
        assertEquals("", new Tiles.Tile(0, 0, 0).quadKey());
        assertEquals("213", new Tiles.Tile(3, 3, 5).quadKey());
        assertEquals("0", new Tiles.Tile(1, 0, 0).quadKey());
        assertEquals("3", new Tiles.Tile(1, 1, 1).quadKey());
        assertEquals(new Tiles.Tile(3, 3, 5), Tiles.Tile.fromQuadKey("213"));
        assertEquals(new Tiles.Tile(0, 0, 0), Tiles.Tile.fromQuadKey(""));
        assertThrows(IllegalArgumentException.class, () -> Tiles.Tile.fromQuadKey("214"));
        assertThrows(IllegalArgumentException.class, () -> Tiles.Tile.fromQuadKey("2a"));
    }

    @Test
    void quadKeysRoundTrip() {
        for (int z = 0; z <= 8; z++) {
            int n = 1 << z;
            for (int x = 0; x < n; x += Math.max(1, n / 5)) {
                for (int y = 0; y < n; y += Math.max(1, n / 5)) {
                    Tiles.Tile t = new Tiles.Tile(z, x, y);
                    assertEquals(t, Tiles.Tile.fromQuadKey(t.quadKey()));
                }
            }
        }
    }

    @Test
    void pixelSizeHalvesEachZoom() {
        assertEquals(156543.03392804097, Tiles.resolution(0, 256), 1e-6);
        assertEquals(Tiles.resolution(3, 256) / 2, Tiles.resolution(4, 256), 1e-9);
        assertEquals(Tiles.resolution(3, 256) / 2, Tiles.resolution(3, 512), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> Tiles.resolution(0, 0));
        assertThrows(IllegalArgumentException.class, () -> Tiles.resolution(-1, 256));
    }

    @Test
    void tilesHaveValueSemantics() {
        assertEquals(new Tiles.Tile(2, 1, 3), new Tiles.Tile(2, 1, 3));
        assertEquals(new Tiles.Tile(2, 1, 3).hashCode(), new Tiles.Tile(2, 1, 3).hashCode());
        assertNotEquals(new Tiles.Tile(2, 1, 3), new Tiles.Tile(2, 3, 1));
        assertEquals("2/1/3", new Tiles.Tile(2, 1, 3).toString());
        assertEquals(2, new Tiles.Tile(2, 1, 3).z());
        assertEquals(new Box2d(0, 0, 0, 0), new Box2d(0, 0, 0, 0));
    }
}
