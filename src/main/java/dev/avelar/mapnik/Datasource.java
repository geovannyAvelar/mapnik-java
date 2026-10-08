package dev.avelar.mapnik;

import com.sun.jna.Pointer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A Mapnik datasource, such as a shapefile or a GeoJSON file. Create one with {@link #create} and
 * attach it to a {@link Layer}. Needs the matching input plugin registered with
 * {@link Mapnik#registerDatasources}.
 */
public class Datasource implements AutoCloseable {
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

        /** A field to declare on a {@link JavaDatasource}. */
        public static Field of(String name, FieldType type) {
            if (name == null || name.isEmpty() || type == null) {
                throw new IllegalArgumentException("a field needs a name and a type");
            }
            return new Field(name, type);
        }

        public String name() { return name; }
        public FieldType type() { return type; }

        @Override
        public String toString() { return name + ":" + type; }
    }

    private Pointer handle;
    private final HandleTracker tracker = HandleTracker.track(this, "Datasource");

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

    /**
     * Vector tiles from an MBTiles file (Mapnik Vector Tile data in a SQLite database). {@code layer}
     * is the tile layer to draw, such as {@code "places"}. It is required for vector tiles: with
     * null, Mapnik reads the file as raster tiles (PNG or JPEG images), which is right only for an MBTiles file of pictures. Use it in a layer whose
     * projection is {@code epsg:3857}: tiles are Web Mercator. Needs Mapnik's {@code tiles} input plugin,
     * which the prebuilt natives include.
     */
    public static Datasource mbtiles(Path file, String layer) {
        return tiles(file, layer, ".mbtiles");
    }

    /** As {@link #mbtiles}, for a PMTiles archive. */
    public static Datasource pmtiles(Path file, String layer) {
        return tiles(file, layer, ".pmtiles");
    }

    private static Datasource tiles(Path file, String layer, String extension) {
        // Mapnik tells the two formats apart by the file name, so a wrong name would be read as the wrong format
        if (!file.getFileName().toString().endsWith(extension)) {
            throw new IllegalArgumentException("expected a " + extension + " file: " + file);
        }
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("type", "tiles");
        params.put("file", file.toAbsolutePath().toString());
        if (layer != null) {
            params.put("layer", layer);
        }
        return create(params);
    }

    /**
     * Tiles fetched over HTTP: {@code url} is a TileJSON document ({@code .json}) or a template such as
     * {@code http://host/{z}/{x}/{y}.pbf} (or {@code .mvt}; other endings are read as raster tiles). The
     * connection is encrypted and the server's certificate is checked against the bundled list of trusted authorities (or
     * the file named by {@code SSL_CERT_FILE}, or set with {@link Mapnik#setTrustedCertificates}); an untrusted server's
     * tiles are not drawn. {@code layer} as in {@link #mbtiles}: required for vector tiles.
     */
    public static Datasource tilesFromUrl(String url, String layer) {
        if (layer == null && (url.endsWith(".pbf") || url.endsWith(".mvt"))) {
            throw new IllegalArgumentException("vector tiles need the name of the tile layer to draw; without one Mapnik reads the tiles as pictures");
        }
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("type", "tiles");
        params.put("url", url);
        if (layer != null) {
            params.put("layer", layer);
        }
        return create(params);
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

    /** The name the datasource gives its data, such as a table or layer name. May be empty. */
    public String layerName() { return N.mapnik_datasource_layer_name(ptr()); }

    /** The text encoding of attribute data, for example {@code utf-8}. */
    public String encoding() { return N.mapnik_datasource_encoding(ptr()); }

    /**
     * The parameters the datasource holds, in key order: the ones you passed, plus any the plugin
     * filled in. Values are {@link String}, {@link Boolean}, {@link Long}, {@link Double} or null.
     */
    public Map<String, Object> parameters() {
        Pointer p = ptr();
        return ParamValues.read(N.mapnik_datasource_param_count(p),
            i -> N.mapnik_datasource_param_name(p, i),
            i -> N.mapnik_datasource_param_type(p, i),
            i -> N.mapnik_datasource_param_bool(p, i) == 1,
            i -> N.mapnik_datasource_param_int(p, i),
            i -> N.mapnik_datasource_param_double(p, i),
            i -> N.mapnik_datasource_param_string(p, i));
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
        tracker.closed();
        if (handle != null) {
            N.mapnik_datasource_free(handle);
            handle = null;
        }
    }
}
