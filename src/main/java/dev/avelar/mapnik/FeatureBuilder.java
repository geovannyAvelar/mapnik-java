package dev.avelar.mapnik;

import com.sun.jna.Pointer;

import java.util.Collections;
import java.util.Map;

/**
 * A native copy of a {@link Feature} and some variables, for Mapnik to read while it evaluates an
 * expression. Package-private: {@link Expression} and {@link PathExpression} use it.
 */
final class FeatureBuilder implements AutoCloseable {
    private static final NativeApi N = NativeApi.INSTANCE;

    private final Pointer handle;

    FeatureBuilder(Feature feature, Map<String, ?> variables) {
        handle = N.mapnik_feature_builder_create(feature.id());
        if (handle == null) {
            throw new MapnikException(N.mapnik_last_error());
        }
        try {
            for (Map.Entry<String, Object> e : feature.attributes().entrySet()) {
                put(e.getKey(), e.getValue(), false);
            }
            for (Map.Entry<String, ?> e : variables.entrySet()) {
                if (!ParamValues.supported(e.getValue()) && e.getValue() != null) {
                    throw new IllegalArgumentException("unsupported type for variable '" + e.getKey() + "': "
                        + e.getValue().getClass().getName());
                }
                put(e.getKey(), e.getValue(), true);
            }
            Geometry g = feature.geometry();
            if (!g.isEmpty()) {
                byte[] wkb = g.toWkb();
                Mapnik.check(N.mapnik_feature_builder_set_geometry_wkb(handle, wkb, wkb.length));
            }
        } catch (RuntimeException e) {
            N.mapnik_feature_builder_free(handle);
            throw e;
        }
    }

    static FeatureBuilder of(Feature feature) {
        return new FeatureBuilder(feature, Collections.<String, Object>emptyMap());
    }

    private void put(String key, Object v, boolean variable) {
        if (v == null) {
            if (variable) {
                N.mapnik_feature_builder_put_var_null(handle, key);
            } else {
                N.mapnik_feature_builder_put_null(handle, key);
            }
        } else if (v instanceof String) {
            if (variable) {
                N.mapnik_feature_builder_put_var_string(handle, key, (String) v);
            } else {
                N.mapnik_feature_builder_put_string(handle, key, (String) v);
            }
        } else if (v instanceof Boolean) {
            int b = (Boolean) v ? 1 : 0;
            if (variable) {
                N.mapnik_feature_builder_put_var_bool(handle, key, b);
            } else {
                N.mapnik_feature_builder_put_bool(handle, key, b);
            }
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (variable) {
                N.mapnik_feature_builder_put_var_double(handle, key, d);
            } else {
                N.mapnik_feature_builder_put_double(handle, key, d);
            }
        } else {
            long l = ((Number) v).longValue();
            if (variable) {
                N.mapnik_feature_builder_put_var_int(handle, key, l);
            } else {
                N.mapnik_feature_builder_put_int(handle, key, l);
            }
        }
    }

    Pointer ptr() {
        return handle;
    }

    @Override
    public void close() {
        N.mapnik_feature_builder_free(handle);
    }
}
