package dev.avelar.mapnik.jts;

import dev.avelar.mapnik.Geometry;
import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;

/**
 * Converts between mapnik-java geometries and JTS ones. GeoTools, Hibernate Spatial, Spring Data and most Java GIS
 * code use JTS, so this joins them to Mapnik.
 *
 * <pre>{@code
 * org.locationtech.jts.geom.Geometry g = new WKTReader().read("POLYGON((0 0, 4 0, 4 3, 0 0))");
 * datasource.add(Jts.fromJts(g), attributes);
 * org.locationtech.jts.geom.Geometry back = Jts.toJts(feature.geometry());
 * }</pre>
 *
 * <p>Only the X and Y of each coordinate are kept: mapnik-java geometries are two-dimensional. An empty JTS geometry
 * becomes an empty collection, and the other way round.
 */
public final class Jts {
    private static final GeometryFactory DEFAULT = new GeometryFactory();

    private Jts() {}

    // ------------------------------------------------------------------ mapnik-java to JTS

    /** A JTS geometry with a default factory (no SRID). */
    public static org.locationtech.jts.geom.Geometry toJts(Geometry g) {
        return toJts(g, DEFAULT);
    }

    /** A JTS geometry made by {@code factory}, which sets the precision model and SRID of the result. */
    public static org.locationtech.jts.geom.Geometry toJts(Geometry g, GeometryFactory factory) {
        if (g instanceof Geometry.Point) {
            Geometry.Point p = (Geometry.Point) g;
            return factory.createPoint(new Coordinate(p.x(), p.y()));
        }
        if (g instanceof Geometry.LineString) {
            return factory.createLineString(coords(((Geometry.LineString) g).coordinates(), false));
        }
        if (g instanceof Geometry.Polygon) {
            return polygon((Geometry.Polygon) g, factory);
        }
        if (g instanceof Geometry.MultiPoint) {
            Coordinate[] c = coords(((Geometry.MultiPoint) g).coordinates(), false);
            org.locationtech.jts.geom.Point[] points = new org.locationtech.jts.geom.Point[c.length];
            for (int i = 0; i < c.length; i++) {
                points[i] = factory.createPoint(c[i]);
            }
            return factory.createMultiPoint(points);
        }
        if (g instanceof Geometry.MultiLineString) {
            List<Geometry.LineString> lines = ((Geometry.MultiLineString) g).lines();
            org.locationtech.jts.geom.LineString[] out = new org.locationtech.jts.geom.LineString[lines.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = factory.createLineString(coords(lines.get(i).coordinates(), false));
            }
            return factory.createMultiLineString(out);
        }
        if (g instanceof Geometry.MultiPolygon) {
            List<Geometry.Polygon> polygons = ((Geometry.MultiPolygon) g).polygons();
            org.locationtech.jts.geom.Polygon[] out = new org.locationtech.jts.geom.Polygon[polygons.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = polygon(polygons.get(i), factory);
            }
            return factory.createMultiPolygon(out);
        }
        if (g instanceof Geometry.Collection) {
            List<Geometry> parts = ((Geometry.Collection) g).geometries();
            org.locationtech.jts.geom.Geometry[] out = new org.locationtech.jts.geom.Geometry[parts.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = toJts(parts.get(i), factory);
            }
            return factory.createGeometryCollection(out);
        }
        throw new IllegalArgumentException("unknown geometry: " + g);
    }

    private static org.locationtech.jts.geom.Polygon polygon(Geometry.Polygon p, GeometryFactory factory) {
        List<double[]> rings = p.rings();
        LinearRing shell = factory.createLinearRing(coords(rings.get(0), true));
        LinearRing[] holes = new LinearRing[rings.size() - 1];
        for (int i = 0; i < holes.length; i++) {
            holes[i] = factory.createLinearRing(coords(rings.get(i + 1), true));
        }
        return factory.createPolygon(shell, holes);
    }

    /** Coordinates from x0, y0, x1, y1, ...; a ring is closed if it is not already. */
    private static Coordinate[] coords(double[] xy, boolean ring) {
        int n = xy.length / 2;
        boolean close = ring && n > 0 && (xy[0] != xy[2 * n - 2] || xy[1] != xy[2 * n - 1]);
        Coordinate[] out = new Coordinate[n + (close ? 1 : 0)];
        for (int i = 0; i < n; i++) {
            out[i] = new Coordinate(xy[2 * i], xy[2 * i + 1]);
        }
        if (close) {
            out[n] = new Coordinate(xy[0], xy[1]);
        }
        return out;
    }

    // ------------------------------------------------------------------ JTS to mapnik-java

    /** A mapnik-java geometry. The SRID and any Z or M values are dropped. */
    public static Geometry fromJts(org.locationtech.jts.geom.Geometry g) {
        if (g.isEmpty()) {
            return Geometry.empty();
        }
        if (g instanceof org.locationtech.jts.geom.Point) {
            org.locationtech.jts.geom.Point p = (org.locationtech.jts.geom.Point) g;
            return Geometry.point(p.getX(), p.getY());
        }
        if (g instanceof org.locationtech.jts.geom.LineString) {   // includes LinearRing
            return Geometry.lineString(flat(((org.locationtech.jts.geom.LineString) g).getCoordinates()));
        }
        if (g instanceof org.locationtech.jts.geom.Polygon) {
            return polygon((org.locationtech.jts.geom.Polygon) g);
        }
        if (g instanceof org.locationtech.jts.geom.MultiPoint) {
            return Geometry.multiPoint(flat(g.getCoordinates()));
        }
        if (g instanceof org.locationtech.jts.geom.MultiLineString) {
            Geometry.LineString[] lines = new Geometry.LineString[g.getNumGeometries()];
            for (int i = 0; i < lines.length; i++) {
                lines[i] = (Geometry.LineString) fromJts(g.getGeometryN(i));
            }
            return Geometry.multiLineString(lines);
        }
        if (g instanceof org.locationtech.jts.geom.MultiPolygon) {
            Geometry.Polygon[] polygons = new Geometry.Polygon[g.getNumGeometries()];
            for (int i = 0; i < polygons.length; i++) {
                polygons[i] = (Geometry.Polygon) fromJts(g.getGeometryN(i));
            }
            return Geometry.multiPolygon(polygons);
        }
        if (g instanceof org.locationtech.jts.geom.GeometryCollection) {
            List<Geometry> parts = new ArrayList<>();
            for (int i = 0; i < g.getNumGeometries(); i++) {
                parts.add(fromJts(g.getGeometryN(i)));
            }
            return Geometry.collection(parts.toArray(new Geometry[0]));
        }
        throw new IllegalArgumentException("unknown JTS geometry: " + g.getGeometryType());
    }

    private static Geometry.Polygon polygon(org.locationtech.jts.geom.Polygon p) {
        double[][] holes = new double[p.getNumInteriorRing()][];
        for (int i = 0; i < holes.length; i++) {
            holes[i] = flat(p.getInteriorRingN(i).getCoordinates());
        }
        return Geometry.polygon(flat(p.getExteriorRing().getCoordinates()), holes);
    }

    private static double[] flat(Coordinate[] c) {
        double[] xy = new double[2 * c.length];
        for (int i = 0; i < c.length; i++) {
            xy[2 * i] = c[i].x;
            xy[2 * i + 1] = c[i].y;
        }
        return xy;
    }
}
