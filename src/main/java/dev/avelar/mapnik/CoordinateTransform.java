package dev.avelar.mapnik;

import com.sun.jna.Pointer;

/** Converts points and boxes from a source projection to a destination projection, and back. */
public final class CoordinateTransform implements AutoCloseable {
    private static final NativeApi N = NativeApi.INSTANCE;

    private Pointer handle;

    private CoordinateTransform(Pointer handle) {
        this.handle = handle;
    }

    /** A transform between two projections. It copies them, so you may close yours afterwards. */
    public static CoordinateTransform between(Projection source, Projection dest) {
        Pointer p = N.mapnik_transform_create(source.ptr(), dest.ptr());
        if (p == null) {
            throw new MapnikException(N.mapnik_last_error());
        }
        return new CoordinateTransform(p);
    }

    /** A transform between two projections given as {@code epsg:<code>} or PROJ strings. */
    public static CoordinateTransform between(String source, String dest) {
        try (Projection s = Projection.of(source); Projection d = Projection.of(dest)) {
            return between(s, d);
        }
    }

    /** True if source and destination are the same, so coordinates do not change. */
    public boolean isIdentity() { return N.mapnik_transform_is_identity(ptr()) == 1; }

    /** A point from the source projection to the destination projection. */
    public Point2d forward(double x, double y) {
        double[] px = {x};
        double[] py = {y};
        Mapnik.check(N.mapnik_transform_forward_point(ptr(), px, py));
        return new Point2d(px[0], py[0]);
    }

    public Point2d forward(Point2d p) {
        return forward(p.x(), p.y());
    }

    /** A point from the destination projection back to the source projection. */
    public Point2d backward(double x, double y) {
        double[] px = {x};
        double[] py = {y};
        Mapnik.check(N.mapnik_transform_backward_point(ptr(), px, py));
        return new Point2d(px[0], py[0]);
    }

    public Point2d backward(Point2d p) {
        return backward(p.x(), p.y());
    }

    /** A box from the source to the destination projection: the bounds of its four transformed corners. */
    public Box2d forward(Box2d box) {
        return forward(box, 0);
    }

    /**
     * Like {@link #forward(Box2d)}, but also samples {@code pointsPerEdge} points along each edge, so
     * the result covers edges that bulge when projected. Use it for large boxes.
     */
    public Box2d forward(Box2d box, int pointsPerEdge) {
        double[] b = {box.minX(), box.minY(), box.maxX(), box.maxY()};
        Mapnik.check(N.mapnik_transform_forward_box(ptr(), b, pointsPerEdge));
        return Box2d.of(b);
    }

    /** A box from the destination back to the source projection. */
    public Box2d backward(Box2d box) {
        return backward(box, 0);
    }

    public Box2d backward(Box2d box, int pointsPerEdge) {
        double[] b = {box.minX(), box.minY(), box.maxX(), box.maxY()};
        Mapnik.check(N.mapnik_transform_backward_box(ptr(), b, pointsPerEdge));
        return Box2d.of(b);
    }

    private Pointer ptr() {
        if (handle == null) {
            throw new IllegalStateException("transform closed");
        }
        return handle;
    }

    @Override
    public void close() {
        if (handle != null) {
            N.mapnik_transform_free(handle);
            handle = null;
        }
    }
}
