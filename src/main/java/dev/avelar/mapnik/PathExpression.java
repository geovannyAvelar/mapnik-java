package dev.avelar.mapnik;

import com.sun.jna.Pointer;

/**
 * A text pattern with attribute names in brackets, such as {@code icons/[type].png}, which a feature
 * fills in: a feature with {@code type} set to {@code bus} gives {@code icons/bus.png}. Mapnik uses
 * these for the {@code file} of image symbolizers. Holds native memory: close it.
 */
public final class PathExpression implements AutoCloseable {
    private static final NativeApi N = NativeApi.INSTANCE;

    private final String text;
    private Pointer handle;
    private final HandleTracker tracker = HandleTracker.track(this, "PathExpression");

    private PathExpression(String text, Pointer handle) {
        this.text = text;
        this.handle = handle;
    }

    /** Parse a pattern. Throws {@link MapnikException} if Mapnik cannot. */
    public static PathExpression parse(String text) {
        Pointer p = N.mapnik_path_expression_parse(text);
        if (p == null) {
            throw new MapnikException(N.mapnik_last_error());
        }
        return new PathExpression(text, p);
    }

    public String text() { return text; }

    /** The pattern with the feature's attributes filled in. An attribute the feature lacks becomes empty text. */
    public String evaluate(Feature feature) {
        if (handle == null) {
            throw new IllegalStateException("path expression closed");
        }
        try (FeatureBuilder b = FeatureBuilder.of(feature)) {
            String s = N.mapnik_path_expression_evaluate(handle, b.ptr());
            if (s == null) {
                throw new MapnikException(N.mapnik_last_error());
            }
            return s;
        }
    }

    @Override
    public void close() {
        tracker.closed();
        if (handle != null) {
            N.mapnik_path_expression_free(handle);
            handle = null;
        }
    }

    @Override
    public String toString() {
        return text;
    }
}
