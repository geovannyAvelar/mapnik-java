package dev.avelar.mapnik;

import com.sun.jna.Pointer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A Mapnik datasource, such as a shapefile or a GeoJSON file. Create one with {@link #create} and
 * attach it to a {@link Layer}. Needs the matching input plugin registered with
 * {@link Mapnik#registerDatasources}.
 */
public final class Datasource implements AutoCloseable {
    private static final NativeApi N = NativeApi.INSTANCE;

    public enum Type { VECTOR, RASTER }

    public enum GeometryType { UNKNOWN, POINT, LINESTRING, POLYGON, COLLECTION }

    public enum FieldType { INTEGER, FLOAT, DOUBLE, STRING, BOOLEAN, GEOMETRY, OBJECT }

    /** A named attribute column. */
    public static final class Field {
        private final String name;
        private final FieldType type;

        Field(String name, FieldType type) {
            this.name = name;
            this.type = type;
        }

        public String name() { return name; }
        public FieldType type() { return type; }

        @Override
        public String toString() { return name + ":" + type; }
    }

    private Pointer handle;

    Datasource(Pointer handle) {
        this.handle = handle;
    }

    /**
     * Create a datasource. {@code params} must contain {@code type}, the plugin name (for example
     * {@code "geojson"} or {@code "shape"}), plus that plugin's options. Values may be String,
     * Integer/Long, Double/Float or Boolean.
     */
    public static Datasource create(Map<String, ?> params) {
        Pointer p = N.mapnik_params_create();
        try {
            for (Map.Entry<String, ?> e : params.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String) {
                    N.mapnik_params_set_string(p, e.getKey(), (String) v);
                } else if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
                    N.mapnik_params_set_int(p, e.getKey(), ((Number) v).longValue());
                } else if (v instanceof Double || v instanceof Float) {
                    N.mapnik_params_set_double(p, e.getKey(), ((Number) v).doubleValue());
                } else if (v instanceof Boolean) {
                    N.mapnik_params_set_bool(p, e.getKey(), (Boolean) v ? 1 : 0);
                } else {
                    throw new IllegalArgumentException(
                        "unsupported parameter type for '" + e.getKey() + "': " + (v == null ? "null" : v.getClass()));
                }
            }
            Pointer ds = N.mapnik_datasource_create(p);
            if (ds == null) {
                throw new MapnikException(N.mapnik_last_error());
            }
            return new Datasource(ds);
        } finally {
            N.mapnik_params_free(p);
        }
    }

    public Type type() {
        return N.mapnik_datasource_type(ptr()) == 1 ? Type.RASTER : Type.VECTOR;
    }

    public GeometryType geometryType() {
        int t = N.mapnik_datasource_geometry_type(ptr());
        GeometryType[] all = GeometryType.values();
        return t >= 0 && t < all.length ? all[t] : GeometryType.UNKNOWN;
    }

    /** The extent of the data, in the datasource's own projection. */
    public Box2d envelope() {
        double[] out = new double[4];
        Mapnik.check(N.mapnik_datasource_envelope(ptr(), out));
        return Box2d.of(out);
    }

    /** Attribute columns. */
    public List<Field> fields() {
        int n = N.mapnik_datasource_field_count(ptr());
        List<Field> fields = new ArrayList<>(n);
        FieldType[] types = FieldType.values();
        for (int i = 0; i < n; i++) {
            int t = N.mapnik_datasource_field_type(ptr(), i) - 1;
            fields.add(new Field(N.mapnik_datasource_field_name(ptr(), i),
                t >= 0 && t < types.length ? types[t] : FieldType.OBJECT));
        }
        return Collections.unmodifiableList(fields);
    }

    /** Features matching the query. Close the result. */
    public Featureset features(FeatureQuery query) {
        Box2d b = query.bbox();
        return Featureset.check(N.mapnik_datasource_features(ptr(), b.minX(), b.minY(), b.maxX(), b.maxY(),
            query.resolutionX(), query.resolutionY(), query.scaleDenominator(),
            query.propertyCount() == 0 ? null : query.propertyArray(), query.propertyCount()));
    }

    /** Features whose bounding box touches {@code box}. Close the result. */
    public Featureset features(Box2d box) {
        return features(FeatureQuery.within(box));
    }

    /** Features within {@code tolerance} of a point, in the datasource's projection. Close the result. */
    public Featureset featuresAtPoint(double x, double y, double tolerance) {
        return Featureset.check(N.mapnik_datasource_features_at_point(ptr(), x, y, tolerance));
    }

    Pointer ptr() {
        if (handle == null) {
            throw new IllegalStateException("datasource closed");
        }
        return handle;
    }

    /** Release this handle. A layer that already uses the datasource keeps its own reference. */
    @Override
    public void close() {
        if (handle != null) {
            N.mapnik_datasource_free(handle);
            handle = null;
        }
    }
}
