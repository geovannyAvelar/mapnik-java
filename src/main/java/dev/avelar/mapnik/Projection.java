package dev.avelar.mapnik;

import com.sun.jna.Pointer;

import java.util.Optional;

/**
 * A coordinate reference system, such as {@code epsg:3857} or a PROJ string. Converts between
 * geographic (longitude, latitude) and projected coordinates. To convert between two projections,
 * use {@link CoordinateTransform}.
 *
 * Safe to share between threads: calls on one object take turns. (For speed, give each thread its
 * own, which takes microseconds to make.)
 */
public final class Projection implements AutoCloseable {
    private static final NativeApi N = NativeApi.INSTANCE;

    private Pointer handle;
    private final HandleTracker tracker = HandleTracker.track(this, "Projection");

    private Projection(Pointer handle) {
        this.handle = handle;
    }

    /** Create from {@code epsg:<code>} or a PROJ string. Throws {@link MapnikException} if it is invalid. */
    public static Projection of(String params) {
        Pointer p = N.mapnik_projection_create(params);
        if (p == null) {
            throw new MapnikException(N.mapnik_last_error());
        }
        return new Projection(p);
    }

    /** The string this projection was created from. */
    public synchronized String params() { return N.mapnik_projection_params(ptr()); }

    /** The PROJ definition. */
    public synchronized String definition() { return N.mapnik_projection_definition(ptr()); }

    /** A human-readable name, for example {@code WGS 84}. */
    public synchronized String description() { return N.mapnik_projection_description(ptr()); }

    /** True for longitude/latitude systems. */
    public synchronized boolean isGeographic() { return N.mapnik_projection_is_geographic(ptr()) == 1; }

    /** The geographic area the projection is meant for, if known, as longitude/latitude degrees. */
    public synchronized Optional<Box2d> areaOfUse() {
        double[] out = new double[4];
        return N.mapnik_projection_area_of_use(ptr(), out) == 1 ? Optional.of(Box2d.of(out)) : Optional.<Box2d>empty();
    }

    /** Geographic (x = longitude, y = latitude) to this projection's coordinates. */
    public synchronized Point2d forward(double lon, double lat) {
        double[] x = {lon};
        double[] y = {lat};
        Mapnik.check(N.mapnik_projection_forward(ptr(), x, y));
        return new Point2d(x[0], y[0]);
    }

    /** This projection's coordinates to geographic (x = longitude, y = latitude). */
    public synchronized Point2d inverse(double x, double y) {
        double[] px = {x};
        double[] py = {y};
        Mapnik.check(N.mapnik_projection_inverse(ptr(), px, py));
        return new Point2d(px[0], py[0]);
    }

    Pointer ptr() {
        if (handle == null) {
            throw new IllegalStateException("projection closed");
        }
        return handle;
    }

    @Override
    public synchronized void close() {
        tracker.closed();
        if (handle != null) {
            N.mapnik_projection_free(handle);
            handle = null;
        }
    }
}
