package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Projections and coordinate transforms against a real Mapnik (and PROJ). */
class ProjectionIntegrationTest {
    // Web mercator of lon 10, lat 20: a standard reference value.
    private static final double MERC_X = 1113194.9079327357;
    private static final double MERC_Y = 2273030.926987689;

    @BeforeAll
    static void setUp() throws IOException {
        Fixtures.dir(); // registers plugins; also makes sure the library loads
    }

    @Test
    void describesAGeographicProjection() {
        try (Projection p = Projection.of("epsg:4326")) {
            assertEquals("epsg:4326", p.params());
            assertTrue(p.isGeographic());
            assertFalse(p.definition().isEmpty());
            assertFalse(p.description().isEmpty());
        }
    }

    @Test
    void describesAProjectedProjection() {
        try (Projection p = Projection.of("epsg:3857")) {
            assertFalse(p.isGeographic());
            assertFalse(p.definition().isEmpty());
            assertTrue(p.description().contains("Mercator"), p.description() + " / " + p.definition());
        }
    }

    @Test
    void acceptsAProjString() {
        try (Projection p = Projection.of("+proj=longlat +datum=WGS84 +no_defs")) {
            assertTrue(p.isGeographic());
        }
    }

    @Test
    void areaOfUseIsReportedWhenKnown() {
        try (Projection p = Projection.of("epsg:4326")) {
            Optional<Box2d> area = p.areaOfUse();
            if (area.isPresent()) {
                assertTrue(area.get().width() > 0 && area.get().height() > 0, area.toString());
            }
        }
    }

    @Test
    void invalidProjectionIsRejected() {
        assertThrows(MapnikException.class, () -> Projection.of("+proj=nonsense"));
        assertThrows(MapnikException.class, () -> Projection.of("epsg:999999"));
    }

    @Test
    void forwardAndInverseConvertGeographicAndMercator() {
        try (Projection p = Projection.of("epsg:3857")) {
            Point2d m = p.forward(10, 20);
            assertEquals(MERC_X, m.x(), 1e-6);
            assertEquals(MERC_Y, m.y(), 1e-6);

            Point2d g = p.inverse(m.x(), m.y());
            assertEquals(10, g.x(), 1e-9);
            assertEquals(20, g.y(), 1e-9);

            Point2d origin = p.forward(0, 0);
            assertEquals(0, origin.x(), 1e-9);
            assertEquals(0, origin.y(), 1e-9);
        }
    }

    @Test
    void transformBetweenProjections() {
        try (CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:3857")) {
            assertFalse(t.isIdentity());
            Point2d m = t.forward(10, 20);
            assertEquals(MERC_X, m.x(), 1e-6);
            assertEquals(MERC_Y, m.y(), 1e-6);

            Point2d back = t.backward(m);
            assertEquals(10, back.x(), 1e-9);
            assertEquals(20, back.y(), 1e-9);
            assertEquals(m, t.forward(new Point2d(10, 20)));
        }
    }

    @Test
    void transformBetweenTwoProjectedSystems() {
        // UTM zone 33N has its central meridian at 15 degrees east: easting 500000 on the equator.
        try (CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:32633")) {
            Point2d p = t.forward(15, 0);
            assertEquals(500000, p.x(), 1e-3);
            assertEquals(0, p.y(), 1e-3);
        }
    }

    @Test
    void identityTransformLeavesPointsAlone() {
        try (CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:4326")) {
            assertTrue(t.isIdentity());
            assertEquals(new Point2d(3, 4), t.forward(3, 4));
        }
    }

    @Test
    void transformsABox() {
        try (CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:3857")) {
            Box2d m = t.forward(new Box2d(-10, -20, 10, 20));
            assertEquals(-MERC_X, m.minX(), 1e-6);
            assertEquals(MERC_X, m.maxX(), 1e-6);
            assertEquals(-MERC_Y, m.minY(), 1e-6);
            assertEquals(MERC_Y, m.maxY(), 1e-6);

            Box2d back = t.backward(m);
            assertEquals(-10, back.minX(), 1e-9);
            assertEquals(20, back.maxY(), 1e-9);
        }
    }

    @Test
    void denseBoxTransformCoversBulgingEdges() {
        // Geographic box to UTM: the top and bottom edges curve, so sampling edges widens the result.
        try (CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:32633")) {
            Box2d corners = t.forward(new Box2d(12, 40, 18, 50));
            Box2d dense = t.forward(new Box2d(12, 40, 18, 50), 20);
            assertTrue(dense.minY() <= corners.minY() + 1e-6, corners + " vs " + dense);
            assertTrue(dense.maxY() >= corners.maxY() - 1e-6, corners + " vs " + dense);
            assertTrue(dense.height() >= corners.height() - 1e-6);
        }
    }

    @Test
    void webMercatorClampsThePoles() {
        try (CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:3857")) {
            Point2d pole = t.forward(0, 90);
            assertTrue(Double.isFinite(pole.y()) && pole.y() > 2e7, pole.toString());
        }
    }

    @Test
    void unprojectablePointFails() {
        // The far side of the globe is not visible in an orthographic projection.
        try (CoordinateTransform t = CoordinateTransform.between("epsg:4326", "+proj=ortho +lat_0=0 +lon_0=0 +datum=WGS84")) {
            assertEquals(0, t.forward(0, 0).x(), 1e-6);
            MapnikException e = assertThrows(MapnikException.class, () -> t.forward(180, 0));
            assertFalse(e.getMessage().isEmpty());
        }
    }

    @Test
    void transformCopiesItsProjections() {
        CoordinateTransform t;
        try (Projection a = Projection.of("epsg:4326"); Projection b = Projection.of("epsg:3857")) {
            t = CoordinateTransform.between(a, b);
        }
        try (CoordinateTransform tt = t) {
            assertEquals(MERC_X, tt.forward(10, 20).x(), 1e-6);
        }
    }

    @Test
    void closedObjectsRejectUse() {
        Projection p = Projection.of("epsg:4326");
        p.close();
        p.close();
        assertThrows(IllegalStateException.class, () -> p.forward(0, 0));

        CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:3857");
        t.close();
        t.close();
        assertThrows(IllegalStateException.class, () -> t.forward(0, 0));
    }

    @Test
    void mapProjectionMatchesTheTransformUsedForRendering() {
        // The extent you zoom to is in the map's projection; a transform gets you there from lon/lat.
        try (MapnikMap map = new MapnikMap(100, 100);
             CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:3857")) {
            map.setSrs("epsg:3857");
            map.setAspectFixMode(AspectFixMode.RESPECT);
            Box2d box = t.forward(new Box2d(-10, -10, 10, 10));
            map.zoomToBox(box);
            assertEquals(box, map.extent());
        }
    }
}
