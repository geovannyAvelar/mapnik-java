package dev.avelar.mapnik;

import com.sun.jna.Pointer;

/**
 * An image with one number per pixel: an elevation grid, a temperature field, a count, a grey picture.
 * It is the data a raster layer would give you, held in memory, so that no GDAL is needed. Colour it
 * with a {@link RasterColorizer} to draw it, or read the values back as an array.
 *
 * <pre>{@code
 * try (GrayImage dem = GrayImage.create(GrayImage.Type.FLOAT32, 256, 256)) {
 *     dem.write(heights);                        // double[256 * 256], row by row from the top left
 *     Image picture = RasterColorizer.create().stop(0, "blue").stop(2000, "white").colorize(dem);
 * }
 * }</pre>
 *
 * <p>Values go in and out as {@code double}s. The integer types hold whole numbers in their range, and
 * a value that does not fit is refused with an exception instead of wrapping around. Not thread-safe.
 */
public final class GrayImage implements AutoCloseable {
    private static final NativeApi N = NativeApi.INSTANCE;

    /** What each pixel holds. */
    public enum Type {
        UINT8(1), INT8(2), UINT16(3), INT16(4), UINT32(5), INT32(6), FLOAT32(7), UINT64(8), INT64(9), FLOAT64(10);

        private final int code;

        Type(int code) {
            this.code = code;
        }

        static Type of(int code) {
            for (Type t : values()) {
                if (t.code == code) {
                    return t;
                }
            }
            throw new IllegalStateException("unknown pixel type " + code);
        }
    }

    private Pointer handle;
    private final HandleTracker tracker = HandleTracker.track(this, "GrayImage");

    private GrayImage(Pointer handle) {
        this.handle = handle;
    }

    /** A new image with every pixel 0. */
    public static GrayImage create(Type type, int width, int height) {
        return create(type, width, height, 0);
    }

    /** A new image with every pixel set to {@code initial}. */
    public static GrayImage create(Type type, int width, int height, double initial) {
        Pointer p = N.mapnik_gray_create(type.code, width, height, initial);
        if (p == null) {
            throw new MapnikException(N.mapnik_last_error());
        }
        return new GrayImage(p);
    }

    /** A new image of the given size with these values, row by row from the top left. */
    public static GrayImage of(Type type, int width, int height, double[] values) {
        GrayImage g = create(type, width, height);
        try {
            g.write(values);
        } catch (RuntimeException e) {
            g.close();
            throw e;
        }
        return g;
    }

    public int width() {
        return N.mapnik_gray_width(ptr());
    }

    public int height() {
        return N.mapnik_gray_height(ptr());
    }

    public Type type() {
        return Type.of(N.mapnik_gray_type(ptr()));
    }

    /** One pixel. Throws {@link MapnikException} if the position is outside the image. */
    public double get(int x, int y) {
        double[] out = new double[1];
        Mapnik.check(N.mapnik_gray_get(ptr(), x, y, out));
        return out[0];
    }

    /** Set one pixel. Throws {@link MapnikException} if the position is outside the image or the value does not fit the type. */
    public GrayImage set(int x, int y, double value) {
        Mapnik.check(N.mapnik_gray_set(ptr(), x, y, value));
        return this;
    }

    /** Every pixel, row by row from the top left. */
    public double[] read() {
        long count = (long) width() * height();
        if (count > Integer.MAX_VALUE - 8) {
            throw new IllegalStateException("too many pixels for one array: " + count);
        }
        double[] out = new double[(int) count];
        Mapnik.check(N.mapnik_gray_read(ptr(), out, count));
        return out;
    }

    /**
     * Replace every pixel. {@code values} must hold width times height numbers, row by row from the top
     * left. If any value does not fit the type, nothing changes.
     */
    public GrayImage write(double[] values) {
        Mapnik.check(N.mapnik_gray_write(ptr(), values, values.length));
        return this;
    }

    /** The smallest and largest value as {@code {min, max}}, ignoring NaN. Throws if there is none. */
    public double[] range() {
        double[] out = new double[2];
        Mapnik.check(N.mapnik_gray_range(ptr(), 0, 0, out));
        return out;
    }

    /** As {@link #range()}, also ignoring pixels equal to {@code nodata}. */
    public double[] range(double nodata) {
        double[] out = new double[2];
        Mapnik.check(N.mapnik_gray_range(ptr(), 1, nodata, out));
        return out;
    }

    /** Colour the image with the stops of {@code colorizer}. Same as {@link RasterColorizer#colorize}. */
    public Image colorize(RasterColorizer colorizer) {
        return colorize(colorizer, null);
    }

    /** As {@link #colorize(RasterColorizer)}; pixels equal to {@code nodata} get the colorizer's default colour. */
    public Image colorize(RasterColorizer colorizer, Double nodata) {
        if (colorizer.stopCount() == 0) {
            throw new IllegalStateException("a raster colorizer needs at least one stop");
        }
        int[] colors = colorizer.nativeColors();
        Pointer p = N.mapnik_gray_colorize(ptr(), colorizer.nativeValues(), colors, colorizer.nativeModes(), colors.length,
            colorizer.nativeDefaultMode(), colorizer.nativeDefaultColor(), colorizer.nativeEpsilon(),
            nodata == null ? 0 : 1, nodata == null ? 0 : nodata);
        if (p == null) {
            throw new MapnikException(N.mapnik_last_error());
        }
        return new Image(p);
    }

    private Pointer ptr() {
        if (handle == null) {
            throw new IllegalStateException("gray image closed");
        }
        return handle;
    }

    @Override
    public void close() {
        tracker.closed();
        if (handle != null) {
            N.mapnik_gray_free(handle);
            handle = null;
        }
    }
}
