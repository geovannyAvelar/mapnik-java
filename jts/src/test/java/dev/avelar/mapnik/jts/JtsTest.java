package dev.avelar.mapnik.jts;

import static org.junit.jupiter.api.Assertions.*;

import dev.avelar.mapnik.Geometry;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.io.WKTReader;

class JtsTest {
    private static final String[] SHAPES = {
        "POINT (1.5 -2)",
        "LINESTRING (0 0, 1 1, 2 0.5)",
        "POLYGON ((0 0, 10 0, 10 10, 0 10, 0 0), (2 2, 4 2, 4 4, 2 4, 2 2))",
        "MULTIPOINT ((1 2), (3 4))",
        "MULTILINESTRING ((0 0, 1 1), (5 5, 6 7, 8 8))",
        "MULTIPOLYGON (((0 0, 4 0, 4 4, 0 0)), ((10 10, 14 10, 14 14, 10 10)))",
        "GEOMETRYCOLLECTION (POINT (1 1), LINESTRING (0 0, 2 2))"
    };

    @Test
    void everyKindSurvivesTheRoundTripBothWays() throws Exception {
        WKTReader reader = new WKTReader();
        for (String wkt : SHAPES) {
            org.locationtech.jts.geom.Geometry jts = reader.read(wkt);
            Geometry ours = Jts.fromJts(jts);
            assertTrue(Jts.toJts(ours).equalsExact(jts), "back to JTS: " + wkt);
            assertEquals(jts.getEnvelopeInternal().getMaxX(), ours.envelope().maxX(), 1e-12, wkt);
        }
    }

    @Test
    void anOpenRingIsRefusedByMapnikJavaNotByTheConverter() {
        assertThrows(IllegalArgumentException.class, () -> Geometry.polygon(new double[] {0, 0, 4, 0, 4, 3}));
    }

    @Test
    void emptyGeometriesAndTheFactoryAreHonoured() throws Exception {
        assertTrue(Jts.fromJts(new WKTReader().read("POINT EMPTY")).isEmpty());
        assertTrue(Jts.toJts(Geometry.empty()).isEmpty());
        org.locationtech.jts.geom.GeometryFactory f = new org.locationtech.jts.geom.GeometryFactory(
            new org.locationtech.jts.geom.PrecisionModel(), 3857);
        assertEquals(3857, Jts.toJts(Geometry.point(1, 2), f).getSRID());
    }
}
