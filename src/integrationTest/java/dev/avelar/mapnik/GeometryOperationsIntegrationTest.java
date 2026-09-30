package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Collections;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Geometry algorithms and reprojection against a real Mapnik. */
class GeometryOperationsIntegrationTest {
    private static final double[] SQUARE = {0, 0, 10, 0, 10, 10, 0, 10, 0, 0};

    @BeforeAll
    static void setUp() throws IOException {
        Fixtures.dir();
    }

    /** Even-odd test of a point against the rings of a polygon. */
    private static boolean inside(Geometry.Polygon poly, double px, double py) {
        boolean in = false;
        for (double[] ring : poly.rings()) {
            for (int i = 0, j = ring.length - 2; i < ring.length; j = i, i += 2) {
                double xi = ring[i], yi = ring[i + 1], xj = ring[j], yj = ring[j + 1];
                if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) {
                    in = !in;
                }
            }
        }
        return in;
    }

    // ---------------------------------------------------------------- centroid

    @Test
    void centroids() {
        assertEquals(new Point2d(5, 5), Geometry.polygon(SQUARE).centroid());
        Point2d tri = Geometry.polygon(new double[] {0, 0, 6, 0, 0, 6, 0, 0}).centroid();
        assertEquals(2, tri.x(), 1e-9);
        assertEquals(2, tri.y(), 1e-9);
        assertEquals(new Point2d(3, 4), Geometry.point(3, 4).centroid());
        Point2d line = Geometry.lineString(0, 0, 10, 0).centroid();
        assertEquals(5, line.x(), 1e-9);
        assertEquals(0, line.y(), 1e-9);
        Point2d pts = Geometry.multiPoint(0, 0, 10, 0, 10, 10, 0, 10).centroid();
        assertEquals(5, pts.x(), 1e-9);
        assertEquals(5, pts.y(), 1e-9);
    }

    @Test
    void theCentroidOfAnEmptyGeometryFails() {
        assertThrows(MapnikException.class, () -> Geometry.empty().centroid());
    }

    // ---------------------------------------------------------------- interior point

    @Test
    void theInteriorPointOfAPolygonIsInsideIt() {
        Geometry.Polygon p = Geometry.polygon(SQUARE);
        Point2d ip = p.interiorPoint();
        assertTrue(inside(p, ip.x(), ip.y()), ip.toString());
        assertEquals(5, ip.x(), 0.5);
        assertEquals(5, ip.y(), 0.5);
    }

    @Test
    void theInteriorPointAvoidsHolesAndNotches() {
        // A big square with a big hole in the middle: the centroid is in the hole, the interior point is not.
        Geometry.Polygon donut = Geometry.polygon(new double[] {0, 0, 100, 0, 100, 100, 0, 100, 0, 0},
            new double[] {20, 20, 20, 80, 80, 80, 80, 20, 20, 20});
        Point2d c = donut.centroid();
        assertFalse(inside(donut, c.x(), c.y()), "the centroid falls in the hole");
        Point2d ip = donut.interiorPoint();
        assertTrue(inside(donut, ip.x(), ip.y()), ip.toString());

        // A C shape: the centroid sits in the notch.
        Geometry.Polygon c2 = Geometry.polygon(new double[] {0, 0, 10, 0, 10, 2, 2, 2, 2, 8, 10, 8, 10, 10, 0, 10, 0, 0});
        Point2d ip2 = c2.interiorPoint();
        assertTrue(inside(c2, ip2.x(), ip2.y()), ip2.toString());
    }

    @Test
    void aMultiPolygonUsesItsLargestPart() {
        Geometry.Polygon small = Geometry.polygon(new double[] {100, 100, 101, 100, 101, 101, 100, 101, 100, 100});
        Geometry.Polygon big = Geometry.polygon(SQUARE);
        Point2d ip = Geometry.multiPolygon(small, big).interiorPoint();
        assertTrue(inside(big, ip.x(), ip.y()), ip.toString());
    }

    @Test
    void otherKindsHaveNoInteriorPoint() {
        assertThrows(IllegalStateException.class, () -> Geometry.point(1, 1).interiorPoint());
        assertThrows(IllegalStateException.class, () -> Geometry.lineString(0, 0, 1, 1).interiorPoint());
        assertThrows(IllegalStateException.class, () -> Geometry.multiPolygon().interiorPoint());
    }

    // ---------------------------------------------------------------- closest point

    @Test
    void theClosestPointOnALine() {
        Geometry.Nearest n = Geometry.lineString(0, 0, 10, 0).closestPoint(5, 3);
        assertEquals(5, n.point().x(), 1e-9);
        assertEquals(0, n.point().y(), 1e-9);
        assertEquals(3, n.distance(), 1e-9);
        assertTrue(n.toString().contains("distance=3"), n.toString());
    }

    @Test
    void theClosestPointBeyondTheEndOfALineIsItsEnd() {
        Geometry.Nearest n = Geometry.lineString(0, 0, 10, 0).closestPoint(14, 3);
        assertEquals(10, n.point().x(), 1e-9);
        assertEquals(0, n.point().y(), 1e-9);
        assertEquals(5, n.distance(), 1e-9);
    }

    @Test
    void theClosestPointOfAPolygon() {
        Geometry.Polygon p = Geometry.polygon(SQUARE);
        Geometry.Nearest outside = p.closestPoint(15, 5);
        assertEquals(10, outside.point().x(), 1e-9);
        assertEquals(5, outside.point().y(), 1e-9);
        assertEquals(5, outside.distance(), 1e-9);
        assertEquals(0, p.closestPoint(5, 5).distance(), 1e-9, "a point inside is at distance 0");
    }

    @Test
    void theClosestPointPicksTheNearestPart() {
        Geometry g = Geometry.multiPoint(0, 0, 100, 100, 10, 10);
        Geometry.Nearest n = g.closestPoint(9, 9);
        assertEquals(new Point2d(10, 10), n.point());
        assertEquals(Math.sqrt(2), n.distance(), 1e-9);
        assertEquals(new Point2d(3, 4), Geometry.point(3, 4).closestPoint(0, 0).point());
        assertEquals(5, Geometry.point(3, 4).closestPoint(0, 0).distance(), 1e-9);
    }

    @Test
    void theClosestPointOfAnEmptyGeometryFails() {
        assertThrows(MapnikException.class, () -> Geometry.empty().closestPoint(0, 0));
    }

    // ---------------------------------------------------------------- validity

    @Test
    void ordinaryShapesAreValidAndSimple() {
        for (Geometry g : new Geometry[] {Geometry.point(1, 2), Geometry.lineString(0, 0, 5, 5), Geometry.polygon(SQUARE),
            Geometry.multiPoint(1, 1, 2, 2)}) {
            assertTrue(g.isValid(), g.toWkt());
            assertTrue(g.isSimple(), g.toWkt());
        }
        assertEquals("Geometry is valid", Geometry.polygon(SQUARE).validityReason());
    }

    @Test
    void aBowTiePolygonIsInvalidAndSaysWhy() {
        Geometry bowTie = Geometry.polygon(new double[] {0, 0, 10, 10, 10, 0, 0, 10, 0, 0});
        assertFalse(bowTie.isValid());
        assertFalse(bowTie.validityReason().isEmpty());
        assertNotEquals("Geometry is valid", bowTie.validityReason());
    }

    @Test
    void eitherWindingIsValidButCorrectedIsAlwaysCounterClockwise() {
        Geometry.Polygon ccw = Geometry.polygon(SQUARE);
        Geometry.Polygon cw = Geometry.polygon(new double[] {0, 0, 0, 10, 10, 10, 10, 0, 0, 0});
        assertTrue(ccw.isValid());
        assertTrue(cw.isValid());
        assertTrue(signedArea(((Geometry.Polygon) ccw.corrected()).exterior()) > 0);
        assertTrue(signedArea(((Geometry.Polygon) cw.corrected()).exterior()) > 0, "a clockwise ring is reversed");
        assertEquals(ccw.corrected(), cw.corrected());
    }

    @Test
    void aHoleOutsideThePolygonIsInvalid() {
        Geometry bad = Geometry.polygon(SQUARE, new double[] {20, 20, 21, 20, 21, 21, 20, 21, 20, 20});
        assertFalse(bad.isValid());
        assertFalse(bad.validityReason().isEmpty());
        assertNotEquals("Geometry is valid", bad.validityReason());
    }

    @Test
    void aSelfCrossingLineIsNotSimple() {
        Geometry cross = Geometry.lineString(0, 0, 10, 10, 10, 0, 0, 10);
        assertFalse(cross.isSimple());
        assertTrue(Geometry.lineString(0, 0, 10, 0, 10, 10).isSimple());
    }

    // ---------------------------------------------------------------- correct

    /** Signed area by the shoelace formula: positive when the ring runs counter-clockwise. */
    private static double signedArea(double[] ring) {
        double a = 0;
        for (int i = 0; i + 3 < ring.length; i += 2) {
            a += ring[i] * ring[i + 3] - ring[i + 2] * ring[i + 1];
        }
        return a / 2;
    }

    @Test
    void correctedPolygonsWindConsistently() {
        Geometry.Polygon cw = Geometry.polygon(new double[] {0, 0, 0, 10, 10, 10, 10, 0, 0, 0});
        Geometry.Polygon ccw = Geometry.polygon(SQUARE);
        double a = signedArea(((Geometry.Polygon) cw.corrected()).exterior());
        double b = signedArea(((Geometry.Polygon) ccw.corrected()).exterior());
        assertEquals(Math.signum(a), Math.signum(b), "both orientations end up the same way round");
        assertEquals(100, Math.abs(a), 1e-9, "same area");
    }

    @Test
    void holesAreWoundTheOppositeWayToTheExterior() {
        Geometry.Polygon p = Geometry.polygon(SQUARE, new double[] {2, 2, 4, 2, 4, 4, 2, 4, 2, 2});
        Geometry.Polygon fixed = (Geometry.Polygon) p.corrected();
        assertNotEquals(Math.signum(signedArea(fixed.exterior())), Math.signum(signedArea(fixed.holes().get(0))));
    }

    @Test
    void correctingTwiceChangesNothing() {
        Geometry.Polygon p = Geometry.polygon(new double[] {0, 0, 0, 10, 10, 10, 10, 0, 0, 0});
        Geometry once = p.corrected();
        assertEquals(once, once.corrected());
        assertEquals(Geometry.point(1, 2), Geometry.point(1, 2).corrected());
    }

    // ---------------------------------------------------------------- simplify

    /** A noisy line: points every unit along x with a small zig-zag. */
    private static Geometry.LineString noisyLine(int n, double amplitude) {
        double[] xy = new double[n * 2];
        for (int i = 0; i < n; i++) {
            xy[2 * i] = i;
            xy[2 * i + 1] = (i % 2 == 0 ? 1 : -1) * amplitude;
        }
        return Geometry.lineString(xy);
    }

    @Test
    void simplifyingRemovesPoints() {
        Geometry.LineString line = noisyLine(101, 0.2);
        for (SimplifyAlgorithm a : SimplifyAlgorithm.values()) {
            Geometry.LineString out = (Geometry.LineString) line.simplify(a, 3.0);
            assertTrue(out.numPoints() < line.numPoints(), a + " kept " + out.numPoints());
            assertTrue(out.numPoints() >= 2, a.toString());
            assertEquals(0, out.point(0).x(), 1e-9, a + " keeps the start");
        }
    }

    @Test
    void douglasPeuckerFlattensNoiseBelowTheToleranceAndKeepsRealBends() {
        Geometry.LineString noise = noisyLine(101, 0.2);
        Geometry.LineString flat = (Geometry.LineString) noise.simplify(SimplifyAlgorithm.DOUGLAS_PEUCKER, 1.0);
        assertTrue(flat.numPoints() <= 4, "noise under the tolerance collapses: " + flat.numPoints());

        Geometry.LineString bent = Geometry.lineString(0, 0, 5, 0, 5, 5, 10, 5);
        Geometry.LineString kept = (Geometry.LineString) bent.simplify(SimplifyAlgorithm.DOUGLAS_PEUCKER, 1.0);
        assertEquals(4, kept.numPoints(), "a real corner survives");
    }

    @Test
    void aSmallerToleranceKeepsMorePoints() {
        Geometry.LineString line = noisyLine(101, 0.5);
        int coarse = ((Geometry.LineString) line.simplify(SimplifyAlgorithm.DOUGLAS_PEUCKER, 2.0)).numPoints();
        int fine = ((Geometry.LineString) line.simplify(SimplifyAlgorithm.DOUGLAS_PEUCKER, 0.1)).numPoints();
        assertTrue(fine > coarse, fine + " vs " + coarse);
    }

    @Test
    void simplifyingKeepsPolygonsAsPolygons() {
        double[] ring = new double[2 * 41];
        for (int i = 0; i < 40; i++) {
            double t = 2 * Math.PI * i / 40;
            ring[2 * i] = 50 * Math.cos(t);
            ring[2 * i + 1] = 50 * Math.sin(t);
        }
        ring[80] = ring[0];
        ring[81] = ring[1];
        Geometry.Polygon circle = Geometry.polygon(ring);
        Geometry.Polygon out = (Geometry.Polygon) circle.simplify(SimplifyAlgorithm.DOUGLAS_PEUCKER, 5.0);
        assertTrue(out.exterior().length / 2 < 41, "fewer points");
        assertTrue(out.exterior().length / 2 >= 4, "still a ring");
        assertTrue(out.isValid());
    }

    @Test
    void aRingIsNeverCollapsed() {
        Geometry.Polygon tiny = Geometry.polygon(new double[] {0, 0, 1, 0, 1, 1, 0, 1, 0, 0});
        Geometry.Polygon out = (Geometry.Polygon) tiny.simplify(SimplifyAlgorithm.DOUGLAS_PEUCKER, 1000);
        assertTrue(out.exterior().length / 2 >= 4);
    }

    @Test
    void simplifyHandlesMultipartAndPointGeometries() {
        Geometry.MultiLineString ml = Geometry.multiLineString(noisyLine(51, 0.1), noisyLine(51, 0.1));
        Geometry.MultiLineString out = (Geometry.MultiLineString) ml.simplify(SimplifyAlgorithm.DOUGLAS_PEUCKER, 1.0);
        assertEquals(2, out.lines().size());
        assertTrue(out.lines().get(0).numPoints() < 51);

        assertEquals(Geometry.point(1, 2), Geometry.point(1, 2).simplify(SimplifyAlgorithm.DOUGLAS_PEUCKER, 1));
        assertEquals(Geometry.multiPoint(1, 1, 2, 2), Geometry.multiPoint(1, 1, 2, 2).simplify(SimplifyAlgorithm.RADIAL_DISTANCE, 1));

        Geometry.Collection c = Geometry.collection(noisyLine(51, 0.1), Geometry.point(0, 0));
        Geometry.Collection co = (Geometry.Collection) c.simplify(SimplifyAlgorithm.DOUGLAS_PEUCKER, 1.0);
        assertEquals(2, co.geometries().size());
    }

    @Test
    void aNegativeToleranceIsRejected() {
        assertThrows(MapnikException.class, () -> noisyLine(10, 1).simplify(SimplifyAlgorithm.DOUGLAS_PEUCKER, -1));
    }

    // ---------------------------------------------------------------- offset

    @Test
    void anOffsetLineRunsParallel() {
        Geometry.LineString out = (Geometry.LineString) Geometry.lineString(0, 0, 10, 0).offset(2);
        assertEquals(2, out.numPoints());
        assertEquals(Math.abs(out.point(0).y()), 2, 1e-9, "two units away");
        assertEquals(out.point(0).y(), out.point(1).y(), 1e-9, "still horizontal");
        assertEquals(10, Math.abs(out.point(1).x() - out.point(0).x()), 1e-9, "same length");
    }

    @Test
    void oppositeOffsetsAreOnOppositeSides() {
        double a = ((Geometry.LineString) Geometry.lineString(0, 0, 10, 0).offset(2)).point(0).y();
        double b = ((Geometry.LineString) Geometry.lineString(0, 0, 10, 0).offset(-2)).point(0).y();
        assertEquals(-a, b, 1e-9);
        assertNotEquals(a, b, 0);
    }

    @Test
    void offsettingACornerKeepsTheDistance() {
        Geometry.LineString out = (Geometry.LineString) Geometry.lineString(0, 0, 10, 0, 10, 10).offset(1);
        assertTrue(out.numPoints() >= 3);
        for (int i = 0; i < out.numPoints(); i++) {
            assertTrue(Geometry.lineString(0, 0, 10, 0, 10, 10).closestPoint(out.point(i).x(), out.point(i).y()).distance() > 0.9,
                out.point(i).toString());
        }
    }

    @Test
    void multiLinesAreOffsetPartByPart() {
        Geometry out = Geometry.multiLineString(Geometry.lineString(0, 0, 10, 0), Geometry.lineString(0, 5, 10, 5)).offset(1);
        assertEquals(2, ((Geometry.MultiLineString) out).lines().size());
    }

    @Test
    void aZeroOffsetKeepsTheLine() {
        Geometry.LineString out = (Geometry.LineString) Geometry.lineString(0, 0, 10, 0).offset(0);
        assertEquals(0, out.point(0).y(), 1e-9);
    }

    @Test
    void onlyLinesCanBeOffset() {
        assertThrows(MapnikException.class, () -> Geometry.point(1, 1).offset(1));
        assertThrows(MapnikException.class, () -> Geometry.polygon(SQUARE).offset(1));
    }

    // ---------------------------------------------------------------- reproject

    @Test
    void reprojectingAPointMatchesTheTransform() {
        try (CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:3857")) {
            Point2d expected = t.forward(10, 20);
            Geometry.Point p = (Geometry.Point) Geometry.point(10, 20).reproject(t);
            assertEquals(expected.x(), p.x(), 1e-6);
            assertEquals(expected.y(), p.y(), 1e-6);
        }
    }

    @Test
    void reprojectingKeepsTheShape() {
        try (CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:3857")) {
            Geometry.Polygon poly = (Geometry.Polygon) Geometry.rectangle(new Box2d(-10, -20, 10, 20)).reproject(t);
            Box2d e = poly.envelope();
            Box2d expected = t.forward(new Box2d(-10, -20, 10, 20));
            assertEquals(expected.minX(), e.minX(), 1e-6);
            assertEquals(expected.maxY(), e.maxY(), 1e-6);
            assertEquals(5, poly.exterior().length / 2);
        }
    }

    @Test
    void reprojectingEveryKindWorks() {
        try (CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:3857")) {
            Geometry[] samples = {
                Geometry.point(1, 2), Geometry.lineString(0, 0, 1, 1),
                Geometry.polygon(new double[] {0, 0, 1, 0, 1, 1, 0, 0}), Geometry.multiPoint(1, 1, 2, 2),
                Geometry.multiLineString(Geometry.lineString(0, 0, 1, 1)),
                Geometry.multiPolygon(Geometry.polygon(new double[] {0, 0, 1, 0, 1, 1, 0, 0})),
                Geometry.collection(Geometry.point(1, 2), Geometry.lineString(0, 0, 1, 1))};
            for (Geometry g : samples) {
                Geometry out = g.reproject(t);
                assertEquals(g.kind(), out.kind(), g.toWkt());
                assertNotEquals(g, out, g.toWkt());
            }
        }
    }

    @Test
    void reprojectingThereAndBackGetsTheSameGeometry() {
        try (CoordinateTransform there = CoordinateTransform.between("epsg:4326", "epsg:3857");
             CoordinateTransform back = CoordinateTransform.between("epsg:3857", "epsg:4326")) {
            Geometry g = Geometry.lineString(-10.5, -20.25, 30.125, 40.5);
            Geometry.LineString r = (Geometry.LineString) g.reproject(there).reproject(back);
            for (int i = 0; i < 2; i++) {
                assertEquals(((Geometry.LineString) g).point(i).x(), r.point(i).x(), 1e-9);
                assertEquals(((Geometry.LineString) g).point(i).y(), r.point(i).y(), 1e-9);
            }
        }
    }

    @Test
    void reprojectingFailsIfAPointCannotBeTransformed() {
        try (CoordinateTransform t = CoordinateTransform.between("epsg:4326", "+proj=ortho +lat_0=0 +lon_0=0 +datum=WGS84")) {
            Geometry.lineString(0, 0, 10, 10).reproject(t); // the near side works
            MapnikException e = assertThrows(MapnikException.class, () -> Geometry.lineString(0, 0, 180, 0).reproject(t));
            assertTrue(e.getMessage().contains("could not be transformed"), e.getMessage());
        }
    }

    @Test
    void featuresCanBeReprojected() {
        try (CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:3857")) {
            Feature f = Feature.create(7, Geometry.point(10, 20), Collections.singletonMap("name", "x"));
            Feature g = f.reproject(t);
            assertEquals(7, g.id());
            assertEquals("x", g.attribute("name"));
            assertEquals(t.forward(10, 20).x(), ((Geometry.Point) g.geometry()).x(), 1e-6);
            assertEquals(GeometryKind.POINT, g.geometryKind());
            assertEquals(Geometry.point(10, 20), f.geometry(), "the original is untouched");
        }
    }

    @Test
    void closedTransformsAreRejected() {
        CoordinateTransform t = CoordinateTransform.between("epsg:4326", "epsg:3857");
        t.close();
        assertThrows(IllegalStateException.class, () -> Geometry.point(1, 2).reproject(t));
    }
}
