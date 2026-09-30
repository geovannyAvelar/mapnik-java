package dev.avelar.mapnik;

import com.sun.jna.Pointer;

import java.util.Collections;
import java.util.Map;

/**
 * A parsed expression in Mapnik's expression language, the one used in rule filters and text labels:
 * {@code [population] > 1000 and [kind] = 'city'}, {@code [name] + ' (' + [pop] + ')'},
 * {@code [elevation] * 3.28084}. Attribute names go in brackets, text in single quotes, and
 * {@code @name} refers to a variable you pass when you evaluate.
 *
 * <p>Use it to check a filter before putting it in a style, or to filter features in Java the same
 * way Mapnik will. Holds native memory: close it.
 */
public final class Expression implements AutoCloseable {
    private static final NativeApi N = NativeApi.INSTANCE;

    private final String text;
    private Pointer handle;

    private Expression(String text, Pointer handle) {
        this.text = text;
        this.handle = handle;
    }

    /**
     * Parse an expression. Throws {@link MapnikException} with Mapnik's message if it is not valid, so
     * this doubles as a syntax check.
     */
    public static Expression parse(String text) {
        Pointer p = N.mapnik_expression_parse(text);
        if (p == null) {
            throw new MapnikException(N.mapnik_last_error());
        }
        return new Expression(text, p);
    }

    /** True if {@code text} parses. */
    public static boolean isValid(String text) {
        try (Expression e = parse(text)) {
            return e != null;
        } catch (MapnikException ex) {
            return false;
        }
    }

    /** The text this expression was parsed from. */
    public String text() { return text; }

    /**
     * Evaluate against a feature. The result is {@code null}, {@link Boolean}, {@link Long},
     * {@link Double} or {@link String}. An attribute the feature does not have reads as null.
     */
    public Object evaluate(Feature feature) {
        return evaluate(feature, Collections.<String, Object>emptyMap());
    }

    /** As {@link #evaluate(Feature)}, with values for the {@code @name} variables the expression uses. */
    public Object evaluate(Feature feature, Map<String, ?> variables) {
        try (FeatureBuilder b = new FeatureBuilder(feature, variables)) {
            Pointer v = N.mapnik_expression_evaluate(ptr(), b.ptr());
            if (v == null) {
                throw new MapnikException(N.mapnik_last_error());
            }
            try {
                switch (N.mapnik_value_type(v)) {
                    case 0:
                        return null;
                    case 1:
                        return N.mapnik_value_bool(v) == 1;
                    case 2:
                        return N.mapnik_value_int(v);
                    case 3:
                        return N.mapnik_value_double(v);
                    default:
                        return N.mapnik_value_string(v);
                }
            } finally {
                N.mapnik_value_free(v);
            }
        }
    }

    /** Whether a feature passes this expression used as a filter: true, non-zero or non-empty counts as a match. */
    public boolean matches(Feature feature) {
        return matches(feature, Collections.<String, Object>emptyMap());
    }

    public boolean matches(Feature feature, Map<String, ?> variables) {
        try (FeatureBuilder b = new FeatureBuilder(feature, variables)) {
            int r = N.mapnik_expression_test(ptr(), b.ptr());
            if (r < 0) {
                throw new MapnikException(N.mapnik_last_error());
            }
            return r == 1;
        }
    }

    private Pointer ptr() {
        if (handle == null) {
            throw new IllegalStateException("expression closed");
        }
        return handle;
    }

    @Override
    public void close() {
        if (handle != null) {
            N.mapnik_expression_free(handle);
            handle = null;
        }
    }

    @Override
    public String toString() {
        return text;
    }
}
