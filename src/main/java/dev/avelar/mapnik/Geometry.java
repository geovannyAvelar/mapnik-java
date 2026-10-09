package dev.avelar.mapnik;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A 2D geometry: a point, line, polygon, a multi version of those, or a collection. Geometries are
 * immutable and hold no native memory. Build them with the factories ({@link #point}, {@link #polygon}
 * and so on) or read them from WKT, WKB or GeoJSON, and write them back out in any of the three.
 *
 * <p>Coordinates are plain numbers in whatever projection your data uses. Only 2D is supported: a
 * third value in GeoJSON is ignored, and WKT or WKB with Z or M is rejected.
 */
public abstract class Geometry {
    Geometry() {}

    /** What kind of geometry this is. */
    public abstract GeometryKind kind();

    /** True if it has no coordinates, for example an empty collection. */
    public abstract boolean isEmpty();

    /** The bounding box, or null if the geometry is empty. */
    public Box2d envelope() {
        double[] b = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        extend(b);
        return b[0] > b[2] ? null : new Box2d(b[0], b[1], b[2], b[3]);
    }

    abstract void extend(double[] box);

    abstract void wkt(StringBuilder sb);

    abstract void geoJson(StringBuilder sb);

    abstract void wkb(Out out);

    /** As WKT, for example {@code POINT(1 2)}. */
    public String toWkt() {
        StringBuilder sb = new StringBuilder();
        wkt(sb);
        return sb.toString();
    }

    /** As a GeoJSON geometry object, for example {@code {"type":"Point","coordinates":[1,2]}}. */
    public String toGeoJson() {
        StringBuilder sb = new StringBuilder();
        geoJson(sb);
        return sb.toString();
    }

    /** As little-endian 2D WKB. */
    public byte[] toWkb() {
        Out out = new Out();
        wkb(out);
        return out.bytes();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Geometry && Arrays.equals(toWkb(), ((Geometry) o).toWkb());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(toWkb());
    }

    @Override
    public String toString() {
        return toWkt();
    }

    // ================================================================== operations

    /** The closest point on a geometry to some location, and how far away it is. */
    public static final class Nearest {
        private final Point2d point;
        private final double distance;

        Nearest(Point2d point, double distance) {
            this.point = point;
            this.distance = distance;
        }

        public Point2d point() { return point; }

        /** The distance from the location to {@link #point()}, in the geometry's units. 0 if it is on the geometry. */
        public double distance() { return distance; }

        @Override
        public String toString() {
            return "Nearest[" + point + ", distance=" + distance + "]";
        }
    }

    /** The centre of mass. Throws {@link MapnikException} for an empty geometry. */
    public Point2d centroid() {
        byte[] w = toWkb();
        double[] xy = new double[2];
        Mapnik.check(NativeApi.INSTANCE.mapnik_geometry_centroid(w, w.length, xy));
        return new Point2d(xy[0], xy[1]);
    }

    /**
     * A point that lies inside a polygon, as far from its edges as reasonably possible (a good place
     * for a label, unlike the centroid of a C shape). For a multi polygon it uses the polygon with the
     * largest bounding box. Throws {@link IllegalStateException} for other kinds.
     */
    public Point2d interiorPoint() {
        Polygon target;
        if (this instanceof Polygon) {
            target = (Polygon) this;
        } else if (this instanceof MultiPolygon && !((MultiPolygon) this).polygons().isEmpty()) {
            target = null;
            double best = -1;
            for (Polygon p : ((MultiPolygon) this).polygons()) {
                Box2d e = p.envelope();
                double area = e.width() * e.height();
                if (area > best) {
                    best = area;
                    target = p;
                }
            }
        } else {
            throw new IllegalStateException("an interior point needs a polygon or a multi polygon, not " + kind());
        }
        byte[] w = target.toWkb();
        double[] xy = new double[2];
        Mapnik.check(NativeApi.INSTANCE.mapnik_geometry_interior_point(w, w.length, 1.0, xy));
        return new Point2d(xy[0], xy[1]);
    }

    /** The point of this geometry nearest to (x, y), with its distance. Throws {@link MapnikException} if empty. */
    public Nearest closestPoint(double x, double y) {
        byte[] w = toWkb();
        double[] out = new double[3];
        Mapnik.check(NativeApi.INSTANCE.mapnik_geometry_closest_point(w, w.length, x, y, out));
        return new Nearest(new Point2d(out[0], out[1]), out[2]);
    }

    /**
     * True if the geometry is valid: rings do not cross themselves, holes lie inside the exterior and
     * do not overlap each other, and so on. For a ring that crosses itself Mapnik reports the problem
     * as a wrong orientation, because a crossing ring has no single winding.
     */
    public boolean isValid() {
        byte[] w = toWkb();
        int r = NativeApi.INSTANCE.mapnik_geometry_is_valid(w, w.length);
        if (r < 0) {
            throw new MapnikException(NativeApi.INSTANCE.mapnik_last_error());
        }
        return r == 1;
    }

    /** Why the geometry is invalid, in Mapnik's words, or a sentence saying it is valid. */
    public String validityReason() {
        byte[] w = toWkb();
        String s = NativeApi.INSTANCE.mapnik_geometry_validity_reason(w, w.length);
        if (s == null) {
            throw new MapnikException(NativeApi.INSTANCE.mapnik_last_error());
        }
        return s;
    }

    /** True if the geometry has no self-intersections or repeated points that make it complex. */
    public boolean isSimple() {
        byte[] w = toWkb();
        int r = NativeApi.INSTANCE.mapnik_geometry_is_simple(w, w.length);
        if (r < 0) {
            throw new MapnikException(NativeApi.INSTANCE.mapnik_last_error());
        }
        return r == 1;
    }

    /**
     * A copy with polygon rings wound consistently: exteriors counter-clockwise, holes clockwise.
     * Either winding counts as valid for an ordinary polygon, but a consistent one is what GeoJSON and
     * many other tools expect. It does not repair a ring that crosses itself.
     */
    public Geometry corrected() {
        byte[] w = toWkb();
        return fromNativeWkt(NativeApi.INSTANCE.mapnik_geometry_correct(w, w.length));
    }

    /**
     * A copy with fewer points, keeping the overall shape. {@code tolerance} is in the geometry's units
     * and means a different distance for each algorithm. Polygon rings are never collapsed to fewer than
     * four points, and points and multi points are unchanged.
     */
    public Geometry simplify(SimplifyAlgorithm algorithm, double tolerance) {
        byte[] w = toWkb();
        return fromNativeWkt(NativeApi.INSTANCE.mapnik_geometry_simplify(w, w.length, algorithm.xmlName(), tolerance));
    }

    /**
     * A parallel copy of a line, {@code distance} to its left (positive) or right (negative). For line
     * strings, multi line strings and collections of them. Throws {@link MapnikException} for points and polygons.
     */
    public Geometry offset(double distance) {
        byte[] w = toWkb();
        return fromNativeWkt(NativeApi.INSTANCE.mapnik_geometry_offset(w, w.length, distance));
    }

    /**
     * A copy with every coordinate moved from the transform's source projection to its destination.
     * Throws {@link MapnikException} if any point cannot be transformed.
     */
    public Geometry reproject(CoordinateTransform transform) {
        byte[] w = toWkb();
        return fromNativeWkt(NativeApi.INSTANCE.mapnik_geometry_reproject(w, w.length, transform.ptr()));
    }

    private static Geometry fromNativeWkt(String wkt) {
        if (wkt == null) {
            throw new MapnikException(NativeApi.INSTANCE.mapnik_last_error());
        }
        return fromWkt(wkt);
    }

    // ================================================================== factories

    public static Point point(double x, double y) {
        return new Point(x, y);
    }

    /** A line through the points given as x0, y0, x1, y1, ... At least two points. */
    public static LineString lineString(double... xy) {
        return new LineString(xy);
    }

    public static LineString lineString(List<Point2d> points) {
        return new LineString(flatten(points));
    }

    /**
     * A polygon from an exterior ring and any holes, each given as x0, y0, x1, y1, ... The rings must
     * be closed (the last point repeats the first) and have at least four points.
     */
    public static Polygon polygon(double[] exterior, double[]... holes) {
        List<double[]> rings = new ArrayList<>();
        rings.add(exterior);
        rings.addAll(Arrays.asList(holes));
        return new Polygon(rings);
    }

    /** A rectangle covering {@code box}. */
    public static Polygon rectangle(Box2d box) {
        return polygon(new double[] {
            box.minX(), box.minY(), box.maxX(), box.minY(), box.maxX(), box.maxY(), box.minX(), box.maxY(),
            box.minX(), box.minY()});
    }

    public static MultiPoint multiPoint(double... xy) {
        return new MultiPoint(xy);
    }

    public static MultiLineString multiLineString(LineString... lines) {
        return new MultiLineString(Arrays.asList(lines));
    }

    public static MultiPolygon multiPolygon(Polygon... polygons) {
        return new MultiPolygon(Arrays.asList(polygons));
    }

    public static Collection collection(Geometry... geometries) {
        return new Collection(Arrays.asList(geometries));
    }

    /** A geometry with nothing in it: an empty collection. */
    public static Collection empty() {
        return new Collection(Collections.<Geometry>emptyList());
    }

    private static double[] flatten(List<Point2d> points) {
        double[] xy = new double[points.size() * 2];
        for (int i = 0; i < points.size(); i++) {
            xy[2 * i] = points.get(i).x();
            xy[2 * i + 1] = points.get(i).y();
        }
        return xy;
    }

    // ================================================================== validation

    private static double[] checkCoordinates(double[] xy, int minPoints, String what) {
        if (xy == null) {
            throw new IllegalArgumentException(what + " has no coordinates");
        }
        if (xy.length % 2 != 0) {
            throw new IllegalArgumentException(what + " needs x and y for every point, got " + xy.length + " numbers");
        }
        if (xy.length / 2 < minPoints) {
            throw new IllegalArgumentException(what + " needs at least " + minPoints + " points, got " + xy.length / 2);
        }
        for (double v : xy) {
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                throw new IllegalArgumentException(what + " has a coordinate that is not a finite number");
            }
        }
        return xy.clone();
    }

    private static double[] checkRing(double[] xy, String what) {
        double[] ring = checkCoordinates(xy, 4, what);
        int n = ring.length;
        if (ring[0] != ring[n - 2] || ring[1] != ring[n - 1]) {
            throw new IllegalArgumentException(what + " is not closed: its last point must equal its first");
        }
        return ring;
    }

    // ================================================================== the types

    /** A single location. */
    public static final class Point extends Geometry {
        private final double x;
        private final double y;

        Point(double x, double y) {
            checkCoordinates(new double[] {x, y}, 1, "a point");
            this.x = x;
            this.y = y;
        }

        public double x() { return x; }
        public double y() { return y; }

        public Point2d toPoint2d() { return new Point2d(x, y); }

        @Override public GeometryKind kind() { return GeometryKind.POINT; }
        @Override public boolean isEmpty() { return false; }

        @Override
        void extend(double[] b) {
            grow(b, x, y);
        }

        @Override
        void wkt(StringBuilder sb) {
            sb.append("POINT(");
            xy(sb, x, y);
            sb.append(')');
        }

        @Override
        void geoJson(StringBuilder sb) {
            sb.append("{\"type\":\"Point\",\"coordinates\":");
            jsonXy(sb, x, y);
            sb.append('}');
        }

        @Override
        void wkb(Out out) {
            out.header(1);
            out.f64(x);
            out.f64(y);
        }
    }

    /** A connected series of points. */
    public static final class LineString extends Geometry {
        private final double[] xy;

        LineString(double[] xy) {
            this.xy = checkCoordinates(xy, 2, "a line string");
        }

        /** The points as x0, y0, x1, y1, ... A copy. */
        public double[] coordinates() { return xy.clone(); }

        public int numPoints() { return xy.length / 2; }

        public Point2d point(int index) {
            return new Point2d(xy[2 * index], xy[2 * index + 1]);
        }

        @Override public GeometryKind kind() { return GeometryKind.LINE_STRING; }
        @Override public boolean isEmpty() { return false; }

        @Override
        void extend(double[] b) {
            growAll(b, xy);
        }

        @Override
        void wkt(StringBuilder sb) {
            sb.append("LINESTRING(");
            coords(sb, xy);
            sb.append(')');
        }

        @Override
        void geoJson(StringBuilder sb) {
            sb.append("{\"type\":\"LineString\",\"coordinates\":");
            jsonCoords(sb, xy);
            sb.append('}');
        }

        @Override
        void wkb(Out out) {
            out.header(2);
            out.points(xy);
        }
    }

    /** An area: an exterior ring and any number of holes. */
    public static final class Polygon extends Geometry {
        private final List<double[]> rings;

        Polygon(List<double[]> rings) {
            if (rings.isEmpty()) {
                throw new IllegalArgumentException("a polygon needs an exterior ring");
            }
            List<double[]> copy = new ArrayList<>();
            for (int i = 0; i < rings.size(); i++) {
                copy.add(checkRing(rings.get(i), i == 0 ? "the exterior ring" : "hole " + i));
            }
            this.rings = Collections.unmodifiableList(copy);
        }

        /** The exterior ring as x0, y0, x1, y1, ... A copy. */
        public double[] exterior() { return rings.get(0).clone(); }

        /** The holes, each as x0, y0, x1, y1, ... Copies. */
        public List<double[]> holes() {
            List<double[]> out = new ArrayList<>();
            for (int i = 1; i < rings.size(); i++) {
                out.add(rings.get(i).clone());
            }
            return out;
        }

        /** The exterior ring followed by the holes. Copies. */
        public List<double[]> rings() {
            List<double[]> out = new ArrayList<>();
            for (double[] r : rings) {
                out.add(r.clone());
            }
            return out;
        }

        @Override public GeometryKind kind() { return GeometryKind.POLYGON; }
        @Override public boolean isEmpty() { return false; }

        @Override
        void extend(double[] b) {
            for (double[] r : rings) {
                growAll(b, r);
            }
        }

        @Override
        void wkt(StringBuilder sb) {
            sb.append("POLYGON");
            ringsWkt(sb);
        }

        void ringsWkt(StringBuilder sb) {
            sb.append('(');
            for (int i = 0; i < rings.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('(');
                coords(sb, rings.get(i));
                sb.append(')');
            }
            sb.append(')');
        }

        @Override
        void geoJson(StringBuilder sb) {
            sb.append("{\"type\":\"Polygon\",\"coordinates\":");
            ringsJson(sb);
            sb.append('}');
        }

        void ringsJson(StringBuilder sb) {
            sb.append('[');
            for (int i = 0; i < rings.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                jsonCoords(sb, rings.get(i));
            }
            sb.append(']');
        }

        @Override
        void wkb(Out out) {
            out.header(3);
            out.u32(rings.size());
            for (double[] r : rings) {
                out.points(r);
            }
        }
    }

    /** Several separate points. May be empty. */
    public static final class MultiPoint extends Geometry {
        private final double[] xy;

        MultiPoint(double[] xy) {
            this.xy = checkCoordinates(xy, 0, "a multi point");
        }

        public double[] coordinates() { return xy.clone(); }

        public int numPoints() { return xy.length / 2; }

        @Override public GeometryKind kind() { return GeometryKind.MULTI_POINT; }
        @Override public boolean isEmpty() { return xy.length == 0; }

        @Override
        void extend(double[] b) {
            growAll(b, xy);
        }

        @Override
        void wkt(StringBuilder sb) {
            if (xy.length == 0) {
                sb.append("MULTIPOINT EMPTY");
                return;
            }
            sb.append("MULTIPOINT(");
            for (int i = 0; i < xy.length; i += 2) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('(');
                xy(sb, xy[i], xy[i + 1]);
                sb.append(')');
            }
            sb.append(')');
        }

        @Override
        void geoJson(StringBuilder sb) {
            sb.append("{\"type\":\"MultiPoint\",\"coordinates\":");
            jsonCoords(sb, xy);
            sb.append('}');
        }

        @Override
        void wkb(Out out) {
            out.header(4);
            out.u32(xy.length / 2);
            for (int i = 0; i < xy.length; i += 2) {
                out.header(1);
                out.f64(xy[i]);
                out.f64(xy[i + 1]);
            }
        }
    }

    /** Several lines. May be empty. */
    public static final class MultiLineString extends Geometry {
        private final List<LineString> lines;

        MultiLineString(List<LineString> lines) {
            this.lines = Collections.unmodifiableList(new ArrayList<>(nonNull(lines)));
        }

        public List<LineString> lines() { return lines; }

        @Override public GeometryKind kind() { return GeometryKind.MULTI_LINE_STRING; }
        @Override public boolean isEmpty() { return lines.isEmpty(); }

        @Override
        void extend(double[] b) {
            for (LineString l : lines) {
                l.extend(b);
            }
        }

        @Override
        void wkt(StringBuilder sb) {
            if (lines.isEmpty()) {
                sb.append("MULTILINESTRING EMPTY");
                return;
            }
            sb.append("MULTILINESTRING(");
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('(');
                coords(sb, lines.get(i).xy);
                sb.append(')');
            }
            sb.append(')');
        }

        @Override
        void geoJson(StringBuilder sb) {
            sb.append("{\"type\":\"MultiLineString\",\"coordinates\":[");
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                jsonCoords(sb, lines.get(i).xy);
            }
            sb.append("]}");
        }

        @Override
        void wkb(Out out) {
            out.header(5);
            out.u32(lines.size());
            for (LineString l : lines) {
                l.wkb(out);
            }
        }
    }

    /** Several polygons. May be empty. */
    public static final class MultiPolygon extends Geometry {
        private final List<Polygon> polygons;

        MultiPolygon(List<Polygon> polygons) {
            this.polygons = Collections.unmodifiableList(new ArrayList<>(nonNull(polygons)));
        }

        public List<Polygon> polygons() { return polygons; }

        @Override public GeometryKind kind() { return GeometryKind.MULTI_POLYGON; }
        @Override public boolean isEmpty() { return polygons.isEmpty(); }

        @Override
        void extend(double[] b) {
            for (Polygon p : polygons) {
                p.extend(b);
            }
        }

        @Override
        void wkt(StringBuilder sb) {
            if (polygons.isEmpty()) {
                sb.append("MULTIPOLYGON EMPTY");
                return;
            }
            sb.append("MULTIPOLYGON(");
            for (int i = 0; i < polygons.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                polygons.get(i).ringsWkt(sb);
            }
            sb.append(')');
        }

        @Override
        void geoJson(StringBuilder sb) {
            sb.append("{\"type\":\"MultiPolygon\",\"coordinates\":[");
            for (int i = 0; i < polygons.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                polygons.get(i).ringsJson(sb);
            }
            sb.append("]}");
        }

        @Override
        void wkb(Out out) {
            out.header(6);
            out.u32(polygons.size());
            for (Polygon p : polygons) {
                p.wkb(out);
            }
        }
    }

    /** A mix of geometries. May be empty, which is how "no geometry" is represented. */
    public static final class Collection extends Geometry {
        private final List<Geometry> geometries;

        Collection(List<Geometry> geometries) {
            this.geometries = Collections.unmodifiableList(new ArrayList<>(nonNull(geometries)));
        }

        public List<Geometry> geometries() { return geometries; }

        @Override public GeometryKind kind() { return GeometryKind.GEOMETRY_COLLECTION; }

        @Override
        public boolean isEmpty() {
            for (Geometry g : geometries) {
                if (!g.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        void extend(double[] b) {
            for (Geometry g : geometries) {
                g.extend(b);
            }
        }

        @Override
        void wkt(StringBuilder sb) {
            if (geometries.isEmpty()) {
                sb.append("GEOMETRYCOLLECTION EMPTY");
                return;
            }
            sb.append("GEOMETRYCOLLECTION(");
            for (int i = 0; i < geometries.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                geometries.get(i).wkt(sb);
            }
            sb.append(')');
        }

        @Override
        void geoJson(StringBuilder sb) {
            sb.append("{\"type\":\"GeometryCollection\",\"geometries\":[");
            for (int i = 0; i < geometries.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                geometries.get(i).geoJson(sb);
            }
            sb.append("]}");
        }

        @Override
        void wkb(Out out) {
            out.header(7);
            out.u32(geometries.size());
            for (Geometry g : geometries) {
                g.wkb(out);
            }
        }
    }

    private static <T> List<T> nonNull(List<T> list) {
        for (T t : list) {
            if (t == null) {
                throw new IllegalArgumentException("a geometry list contains null");
            }
        }
        return list;
    }

    // ================================================================== text helpers

    private static void grow(double[] b, double x, double y) {
        b[0] = Math.min(b[0], x);
        b[1] = Math.min(b[1], y);
        b[2] = Math.max(b[2], x);
        b[3] = Math.max(b[3], y);
    }

    private static void growAll(double[] b, double[] xy) {
        for (int i = 0; i < xy.length; i += 2) {
            grow(b, xy[i], xy[i + 1]);
        }
    }

    private static void xy(StringBuilder sb, double x, double y) {
        sb.append(Xml.number(x)).append(' ').append(Xml.number(y));
    }

    private static void coords(StringBuilder sb, double[] xy) {
        for (int i = 0; i < xy.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            xy(sb, xy[i], xy[i + 1]);
        }
    }

    private static void jsonXy(StringBuilder sb, double x, double y) {
        sb.append('[').append(Xml.number(x)).append(',').append(Xml.number(y)).append(']');
    }

    private static void jsonCoords(StringBuilder sb, double[] xy) {
        sb.append('[');
        for (int i = 0; i < xy.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            jsonXy(sb, xy[i], xy[i + 1]);
        }
        sb.append(']');
    }

    // ================================================================== WKB out

    /** A growing little-endian byte buffer. */
    static final class Out {
        private byte[] buf = new byte[64];
        private int n;

        private void ensure(int more) {
            if (n + more > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(buf.length * 2, n + more));
            }
        }

        void header(int type) {
            ensure(5);
            buf[n++] = 1; // little endian
            u32(type);
        }

        void u32(long v) {
            ensure(4);
            for (int i = 0; i < 4; i++) {
                buf[n++] = (byte) (v >>> (8 * i));
            }
        }

        void f64(double d) {
            ensure(8);
            long v = Double.doubleToLongBits(d);
            for (int i = 0; i < 8; i++) {
                buf[n++] = (byte) (v >>> (8 * i));
            }
        }

        void points(double[] xy) {
            u32(xy.length / 2);
            for (double v : xy) {
                f64(v);
            }
        }

        byte[] bytes() {
            return Arrays.copyOf(buf, n);
        }
    }

    // ================================================================== WKB in

    /** Read 2D WKB of either byte order. Throws {@link IllegalArgumentException} if it is malformed or has Z or M. */
    public static Geometry fromWkb(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("WKB data is null");
        }
        WkbReader r = new WkbReader(data);
        Geometry g = r.geometry();
        if (r.pos != data.length) {
            throw new IllegalArgumentException("unexpected " + (data.length - r.pos) + " bytes after the geometry");
        }
        return g;
    }

    private static final class WkbReader {
        private final byte[] d;
        private int pos;
        private boolean little;
        private int depth;

        WkbReader(byte[] d) {
            this.d = d;
        }

        private void need(int n) {
            if (pos + n > d.length) {
                throw new IllegalArgumentException("WKB is truncated at byte " + pos);
            }
        }

        private long u32() {
            need(4);
            long v = 0;
            for (int i = 0; i < 4; i++) {
                int b = d[pos + (little ? i : 3 - i)] & 0xFF;
                v |= (long) b << (8 * i);
            }
            pos += 4;
            return v;
        }

        private double f64() {
            need(8);
            long v = 0;
            for (int i = 0; i < 8; i++) {
                long b = d[pos + (little ? i : 7 - i)] & 0xFF;
                v |= b << (8 * i);
            }
            pos += 8;
            return Double.longBitsToDouble(v);
        }

        private int count() {
            long n = u32();
            // Every element takes at least a byte, so a count beyond the data is corrupt.
            if (n > d.length - pos) {
                throw new IllegalArgumentException("WKB count " + n + " is larger than the remaining data");
            }
            return (int) n;
        }

        private double[] points() {
            int n = count();
            if ((long) n * 16 > d.length - pos) {
                throw new IllegalArgumentException("WKB is truncated at byte " + pos);
            }
            double[] xy = new double[n * 2];
            for (int i = 0; i < xy.length; i++) {
                xy[i] = f64();
            }
            return xy;
        }

        Geometry geometry() {
            need(5);
            byte order = d[pos++];
            if (order != 0 && order != 1) {
                throw new IllegalArgumentException("bad WKB byte order " + order);
            }
            little = order == 1;
            long type = u32();
            if ((type & 0x80000000L) != 0 || (type & 0x40000000L) != 0 || (type >= 1000 && type < 4000)) {
                throw new IllegalArgumentException("only 2D WKB is supported, not Z or M");
            }
            if ((type & 0x20000000L) != 0) { // EWKB SRID
                u32();
                type &= ~0x20000000L;
            }
            switch ((int) type) {
                case 1: {
                    double x = f64();
                    double y = f64();
                    return new Point(x, y);
                }
                case 2:
                    return new LineString(points());
                case 3: {
                    int rings = count();
                    List<double[]> list = new ArrayList<>();
                    for (int i = 0; i < rings; i++) {
                        list.add(points());
                    }
                    return new Polygon(list);
                }
                case 4: {
                    int n = count();
                    double[] xy = new double[n * 2];
                    for (int i = 0; i < n; i++) {
                        Geometry g = geometry();
                        if (!(g instanceof Point)) {
                            throw new IllegalArgumentException("a multi point can only hold points");
                        }
                        xy[2 * i] = ((Point) g).x;
                        xy[2 * i + 1] = ((Point) g).y;
                    }
                    return new MultiPoint(xy);
                }
                case 5: {
                    int n = count();
                    List<LineString> lines = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        Geometry g = geometry();
                        if (!(g instanceof LineString)) {
                            throw new IllegalArgumentException("a multi line string can only hold line strings");
                        }
                        lines.add((LineString) g);
                    }
                    return new MultiLineString(lines);
                }
                case 6: {
                    int n = count();
                    List<Polygon> polys = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        Geometry g = geometry();
                        if (!(g instanceof Polygon)) {
                            throw new IllegalArgumentException("a multi polygon can only hold polygons");
                        }
                        polys.add((Polygon) g);
                    }
                    return new MultiPolygon(polys);
                }
                case 7: {
                    if (++depth > Json.MAX_DEPTH) {
                        throw new IllegalArgumentException("geometry collections nested more than " + Json.MAX_DEPTH + " levels deep");
                    }
                    int n = count();
                    List<Geometry> parts = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        parts.add(geometry());
                    }
                    depth--;
                    return new Collection(parts);
                }
                default:
                    throw new IllegalArgumentException("unknown WKB geometry type " + type);
            }
        }
    }

    // ================================================================== WKT in

    /**
     * Read WKT, such as {@code POINT(1 2)} or {@code POLYGON((0 0,1 0,1 1,0 0))}. Type names are
     * case-insensitive. {@code EMPTY} is accepted for the multi types, collections and lines.
     * Throws {@link IllegalArgumentException} with the position of the first problem.
     */
    public static Geometry fromWkt(String wkt) {
        if (wkt == null) {
            throw new IllegalArgumentException("WKT is null");
        }
        WktReader r = new WktReader(wkt);
        Geometry g = r.geometry();
        r.ws();
        if (r.pos != wkt.length()) {
            throw r.error("unexpected text after the geometry");
        }
        return g;
    }

    private static final class WktReader {
        private final String s;
        private int pos;
        private int depth;

        WktReader(String s) {
            this.s = s;
        }

        IllegalArgumentException error(String what) {
            return new IllegalArgumentException("invalid WKT at position " + pos + ": " + what);
        }

        void ws() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        private boolean peekIs(char c) {
            ws();
            return pos < s.length() && s.charAt(pos) == c;
        }

        private void expect(char c) {
            ws();
            if (pos >= s.length() || s.charAt(pos) != c) {
                throw error("expected '" + c + "'");
            }
            pos++;
        }

        private String word() {
            ws();
            int start = pos;
            while (pos < s.length() && Character.isLetter(s.charAt(pos))) {
                pos++;
            }
            if (start == pos) {
                throw error("expected a geometry type");
            }
            return s.substring(start, pos).toUpperCase(Locale.ROOT);
        }

        private double number() {
            ws();
            int start = pos;
            if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) {
                pos++;
            }
            boolean digits = false;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                pos++;
                digits = true;
            }
            if (pos < s.length() && s.charAt(pos) == '.') {
                pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                    digits = true;
                }
            }
            if (!digits) {
                pos = start;
                throw error("expected a number");
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            try {
                return Double.parseDouble(s.substring(start, pos));
            } catch (NumberFormatException e) {
                pos = start;
                throw error("bad number");
            }
        }

        /** One "x y" pair, appended to out. */
        private void coordinate(List<Double> out) {
            out.add(number());
            out.add(number());
            ws();
            if (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '-' || s.charAt(pos) == '+'
                || s.charAt(pos) == '.')) {
                throw error("only 2D geometries are supported, not Z or M");
            }
        }

        /** "x y, x y, ..." */
        private double[] coordinateList() {
            List<Double> list = new ArrayList<>();
            coordinate(list);
            while (peekIs(',')) {
                pos++;
                coordinate(list);
            }
            return toArray(list);
        }

        /** "( x y, x y )" */
        private double[] parenthesisedCoordinates() {
            expect('(');
            double[] xy = coordinateList();
            expect(')');
            return xy;
        }

        private boolean emptyKeyword() {
            ws();
            if (s.regionMatches(true, pos, "EMPTY", 0, 5)) {
                pos += 5;
                return true;
            }
            return false;
        }

        private void rejectZm() {
            ws();
            if (pos < s.length() && Character.isLetter(s.charAt(pos)) && !s.regionMatches(true, pos, "EMPTY", 0, 5)) {
                throw error("only 2D geometries are supported, not Z or M");
            }
        }

        private Polygon polygonBody() {
            expect('(');
            List<double[]> rings = new ArrayList<>();
            rings.add(parenthesisedCoordinates());
            while (peekIs(',')) {
                pos++;
                rings.add(parenthesisedCoordinates());
            }
            expect(')');
            return wrap(() -> new Polygon(rings));
        }

        Geometry geometry() {
            String type = word();
            rejectZm();
            switch (type) {
                case "POINT": {
                    if (emptyKeyword()) {
                        throw error("an empty point is not supported");
                    }
                    double[] xy = parenthesisedCoordinates();
                    if (xy.length != 2) {
                        throw error("a point has exactly one coordinate");
                    }
                    return wrap(() -> new Point(xy[0], xy[1]));
                }
                case "LINESTRING": {
                    if (emptyKeyword()) {
                        throw error("an empty line string is not supported");
                    }
                    double[] xy = parenthesisedCoordinates();
                    return wrap(() -> new LineString(xy));
                }
                case "POLYGON": {
                    if (emptyKeyword()) {
                        throw error("an empty polygon is not supported");
                    }
                    return polygonBody();
                }
                case "MULTIPOINT": {
                    if (emptyKeyword()) {
                        return new MultiPoint(new double[0]);
                    }
                    expect('(');
                    List<Double> list = new ArrayList<>();
                    while (true) {
                        if (peekIs('(')) {
                            pos++;
                            coordinate(list);
                            expect(')');
                        } else {
                            coordinate(list);
                        }
                        if (peekIs(',')) {
                            pos++;
                        } else {
                            break;
                        }
                    }
                    expect(')');
                    double[] xy = toArray(list);
                    return wrap(() -> new MultiPoint(xy));
                }
                case "MULTILINESTRING": {
                    if (emptyKeyword()) {
                        return new MultiLineString(Collections.<LineString>emptyList());
                    }
                    expect('(');
                    List<LineString> lines = new ArrayList<>();
                    do {
                        if (lines.size() > 0) {
                            pos++;
                        }
                        double[] xy = parenthesisedCoordinates();
                        lines.add(wrap(() -> new LineString(xy)));
                    } while (peekIs(','));
                    expect(')');
                    return new MultiLineString(lines);
                }
                case "MULTIPOLYGON": {
                    if (emptyKeyword()) {
                        return new MultiPolygon(Collections.<Polygon>emptyList());
                    }
                    expect('(');
                    List<Polygon> polys = new ArrayList<>();
                    do {
                        if (polys.size() > 0) {
                            pos++;
                        }
                        polys.add(polygonBody());
                    } while (peekIs(','));
                    expect(')');
                    return new MultiPolygon(polys);
                }
                case "GEOMETRYCOLLECTION": {
                    if (emptyKeyword()) {
                        return new Collection(Collections.<Geometry>emptyList());
                    }
                    if (++depth > Json.MAX_DEPTH) {
                        throw error("geometry collections nested more than " + Json.MAX_DEPTH + " levels deep");
                    }
                    expect('(');
                    List<Geometry> parts = new ArrayList<>();
                    do {
                        if (parts.size() > 0) {
                            pos++;
                        }
                        parts.add(geometry());
                    } while (peekIs(','));
                    expect(')');
                    depth--;
                    return new Collection(parts);
                }
                default:
                    if (type.matches("(POINT|LINESTRING|POLYGON|MULTIPOINT|MULTILINESTRING|MULTIPOLYGON|GEOMETRYCOLLECTION)(Z|M|ZM)")) {
                        throw error("only 2D geometries are supported, not Z or M");
                    }
                    throw error("unknown geometry type " + type);
            }
        }

        private <T> T wrap(java.util.function.Supplier<T> make) {
            try {
                return make.get();
            } catch (IllegalArgumentException e) {
                throw error(e.getMessage());
            }
        }
    }

    private static double[] toArray(List<Double> list) {
        double[] a = new double[list.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = list.get(i);
        }
        return a;
    }

    // ================================================================== GeoJSON in

    /**
     * Read a GeoJSON geometry object. A third (elevation) value in a position is ignored. A
     * {@code null} geometry reads as {@link #empty()}.
     */
    public static Geometry fromGeoJson(String json) {
        return fromGeoJsonValue(Json.parse(json));
    }

    @SuppressWarnings("unchecked")
    static Geometry fromGeoJsonValue(Object value) {
        if (value == null) {
            return empty();
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("a GeoJSON geometry must be an object");
        }
        Map<String, Object> o = (Map<String, Object>) value;
        Object type = o.get("type");
        if (!(type instanceof String)) {
            throw new IllegalArgumentException("GeoJSON geometry has no \"type\"");
        }
        switch ((String) type) {
            case "Point": {
                double[] p = position(o.get("coordinates"));
                return new Point(p[0], p[1]);
            }
            case "LineString":
                return new LineString(positions(o.get("coordinates")));
            case "Polygon":
                return geoJsonPolygon(o.get("coordinates"));
            case "MultiPoint":
                return new MultiPoint(positions(o.get("coordinates")));
            case "MultiLineString": {
                List<LineString> lines = new ArrayList<>();
                for (Object l : list(o.get("coordinates"), "coordinates")) {
                    lines.add(new LineString(positions(l)));
                }
                return new MultiLineString(lines);
            }
            case "MultiPolygon": {
                List<Polygon> polys = new ArrayList<>();
                for (Object p : list(o.get("coordinates"), "coordinates")) {
                    polys.add(geoJsonPolygon(p));
                }
                return new MultiPolygon(polys);
            }
            case "GeometryCollection": {
                List<Geometry> parts = new ArrayList<>();
                for (Object g : list(o.get("geometries"), "geometries")) {
                    parts.add(fromGeoJsonValue(g));
                }
                return new Collection(parts);
            }
            default:
                throw new IllegalArgumentException("unknown GeoJSON geometry type \"" + type + "\"");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o, String what) {
        if (!(o instanceof List)) {
            throw new IllegalArgumentException("GeoJSON \"" + what + "\" must be an array");
        }
        return (List<Object>) o;
    }

    private static double[] position(Object o) {
        List<Object> l = list(o, "position");
        if (l.size() < 2) {
            throw new IllegalArgumentException("a GeoJSON position needs at least x and y");
        }
        double[] p = new double[2];
        for (int i = 0; i < 2; i++) {
            if (!(l.get(i) instanceof Double)) {
                throw new IllegalArgumentException("a GeoJSON position must hold numbers");
            }
            p[i] = (Double) l.get(i);
        }
        return p;
    }

    private static double[] positions(Object o) {
        List<Object> l = list(o, "coordinates");
        double[] xy = new double[l.size() * 2];
        for (int i = 0; i < l.size(); i++) {
            double[] p = position(l.get(i));
            xy[2 * i] = p[0];
            xy[2 * i + 1] = p[1];
        }
        return xy;
    }

    private static Polygon geoJsonPolygon(Object o) {
        List<double[]> rings = new ArrayList<>();
        for (Object ring : list(o, "coordinates")) {
            rings.add(positions(ring));
        }
        return new Polygon(rings);
    }
}
