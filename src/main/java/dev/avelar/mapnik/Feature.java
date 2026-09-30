package dev.avelar.mapnik;

import com.sun.jna.Pointer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One feature: an id, named attributes and a geometry. A feature is a plain snapshot. It holds no
 * native memory, so there is nothing to close and it stays valid after the {@link Featureset} that
 * produced it is closed. Features come out of queries, or you build them with {@link #create} and
 * add them to a {@link MemoryDatasource}.
 */
public final class Feature {
    private final long id;
    private final Map<String, Object> attributes;
    private final GeometryKind geometryKind;
    private final Box2d envelope;
    private final String wkt;
    private final String geometryGeoJson;
    private final String geoJson;
    private Geometry geometry; // given when built in Java, parsed from the WKT on first use otherwise

    private Feature(long id, Map<String, Object> attributes, GeometryKind kind, Box2d envelope,
                    String wkt, String geometryGeoJson, String geoJson, Geometry geometry) {
        this.id = id;
        this.attributes = attributes;
        this.geometryKind = kind;
        this.envelope = envelope;
        this.wkt = wkt;
        this.geometryGeoJson = geometryGeoJson;
        this.geoJson = geoJson;
        this.geometry = geometry;
    }

    /** Copy everything out of a native feature. */
    static Feature read(Pointer f) {
        NativeApi n = NativeApi.INSTANCE;
        Map<String, Object> attrs = new LinkedHashMap<>();
        int count = n.mapnik_feature_attribute_count(f);
        for (int i = 0; i < count; i++) {
            String name = n.mapnik_feature_attribute_name(f, i);
            Object value;
            switch (n.mapnik_feature_attribute_type(f, i)) {
                case 0:
                    value = null;
                    break;
                case 1:
                    value = n.mapnik_feature_attribute_bool(f, i) == 1;
                    break;
                case 2:
                    value = n.mapnik_feature_attribute_int(f, i);
                    break;
                case 3:
                    value = n.mapnik_feature_attribute_double(f, i);
                    break;
                default:
                    value = n.mapnik_feature_attribute_string(f, i);
            }
            attrs.put(name, value);
        }
        int k = n.mapnik_feature_geometry_type(f);
        GeometryKind[] kinds = GeometryKind.values();
        GeometryKind kind = k >= 0 && k < kinds.length ? kinds[k] : GeometryKind.UNKNOWN;

        Box2d box = null;
        double[] b = new double[4];
        if (n.mapnik_feature_envelope(f, b) == 0) {
            box = Box2d.of(b);
        }
        return new Feature(n.mapnik_feature_id(f), Collections.unmodifiableMap(attrs), kind, box,
            text(n.mapnik_feature_geometry_wkt(f)), text(n.mapnik_feature_geometry_geojson(f)),
            text(n.mapnik_feature_to_geojson(f)), null);
    }

    private static String text(String s) {
        if (s == null) {
            throw new MapnikException(NativeApi.INSTANCE.mapnik_last_error());
        }
        return s;
    }

    // ================================================================== building

    /**
     * Build a feature. Attribute values may be {@code null}, {@link String}, {@link Boolean},
     * {@link Integer}, {@link Long}, {@link Short}, {@link Byte}, {@link Double} or {@link Float};
     * whole numbers become {@link Long} and floats become {@link Double}. Attributes are kept in name
     * order, as features read from a datasource are.
     */
    public static Feature create(long id, Geometry geometry, Map<String, ?> attributes) {
        if (geometry == null) {
            throw new IllegalArgumentException("geometry is null; use Geometry.empty() for none");
        }
        Map<String, Object> attrs = new TreeMap<>();
        for (Map.Entry<String, ?> e : attributes.entrySet()) {
            attrs.put(e.getKey(), normalize(e.getKey(), e.getValue()));
        }
        boolean empty = geometry.isEmpty();
        String geometryJson = empty ? "null" : geometry.toGeoJson();
        StringBuilder sb = new StringBuilder("{\"type\":\"Feature\",\"id\":").append(id)
            .append(",\"geometry\":").append(geometryJson).append(",\"properties\":{");
        boolean first = true;
        for (Map.Entry<String, Object> e : attrs.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(Json.escape(e.getKey())).append(':').append(jsonValue(e.getValue()));
        }
        sb.append("}}");
        return new Feature(id, Collections.unmodifiableMap(attrs), empty ? GeometryKind.UNKNOWN : geometry.kind(),
            geometry.envelope(), geometry.toWkt(), geometryJson, sb.toString(), geometry);
    }

    public static Feature create(long id, Geometry geometry) {
        return create(id, geometry, Collections.<String, Object>emptyMap());
    }

    private static Object normalize(String name, Object v) {
        if (v == null || v instanceof String || v instanceof Boolean) {
            return v;
        }
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return ((Number) v).longValue();
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("attribute '" + name + "' is not a finite number");
            }
            return d;
        }
        throw new IllegalArgumentException("unsupported type for attribute '" + name + "': " + v.getClass().getName());
    }

    private static String jsonValue(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String) {
            return Json.escape((String) v);
        }
        if (v instanceof Double) {
            return Xml.number((Double) v);
        }
        return v.toString();
    }

    // ================================================================== GeoJSON in

    /**
     * Read a GeoJSON Feature. Its {@code properties} become attributes: strings, booleans and null as
     * they are, numbers as {@link Long} if they are whole and {@link Double} otherwise, and nested
     * objects or arrays as their JSON text. The {@code id} is used if it is a whole number, else
     * {@code defaultId}.
     */
    public static Feature fromGeoJson(String json, long defaultId) {
        return feature(Json.parse(json), defaultId);
    }

    /** As {@link #fromGeoJson(String, long)}, with id 1 if the feature has none. */
    public static Feature fromGeoJson(String json) {
        return fromGeoJson(json, 1);
    }

    /**
     * Read every feature of a GeoJSON FeatureCollection, or the single feature of a Feature. Features
     * without a whole-number {@code id} are numbered from 1 by their position.
     */
    @SuppressWarnings("unchecked")
    public static List<Feature> listFromGeoJson(String json) {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) {
            throw new IllegalArgumentException("GeoJSON must be an object");
        }
        Map<String, Object> o = (Map<String, Object>) root;
        if ("Feature".equals(o.get("type"))) {
            return Collections.singletonList(feature(o, 1));
        }
        if (!"FeatureCollection".equals(o.get("type"))) {
            throw new IllegalArgumentException("expected a GeoJSON FeatureCollection or Feature, found \"" + o.get("type") + "\"");
        }
        if (!(o.get("features") instanceof List)) {
            throw new IllegalArgumentException("a FeatureCollection needs a \"features\" array");
        }
        List<Feature> out = new ArrayList<>();
        long n = 1;
        for (Object f : (List<Object>) o.get("features")) {
            out.add(feature(f, n++));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Feature feature(Object value, long defaultId) {
        if (!(value instanceof Map) || !"Feature".equals(((Map<String, Object>) value).get("type"))) {
            throw new IllegalArgumentException("expected a GeoJSON Feature object");
        }
        Map<String, Object> f = (Map<String, Object>) value;
        long id = defaultId;
        Object rawId = f.get("id");
        if (rawId instanceof Double && (Double) rawId == Math.rint((Double) rawId) && Math.abs((Double) rawId) < 1e15) {
            id = ((Double) rawId).longValue();
        }
        Map<String, Object> props = new TreeMap<>();
        Object p = f.get("properties");
        if (p instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) p).entrySet()) {
                Object v = e.getValue();
                if (v instanceof Double) {
                    double d = (Double) v;
                    v = d == Math.rint(d) && Math.abs(d) < 1e15 ? (Object) Long.valueOf((long) d) : (Object) Double.valueOf(d);
                } else if (v instanceof Map || v instanceof List) {
                    v = Json.stringify(v);
                }
                props.put(e.getKey(), v);
            }
        } else if (p != null) {
            throw new IllegalArgumentException("a Feature's \"properties\" must be an object or null");
        }
        return create(id, Geometry.fromGeoJsonValue(f.get("geometry")), props);
    }

    // ================================================================== reading

    public long id() { return id; }

    /**
     * Attributes by name, in name order. Values are {@code null}, {@link Boolean}, {@link Long},
     * {@link Double} or {@link String}.
     */
    public Map<String, Object> attributes() { return attributes; }

    /** One attribute, or null if it is missing or null. */
    public Object attribute(String name) { return attributes.get(name); }

    /** {@link GeometryKind#UNKNOWN} if the feature has no geometry. */
    public GeometryKind geometryKind() { return geometryKind; }

    /** Bounding box of the geometry, in the datasource's projection. Null for an empty geometry. */
    public Box2d envelope() { return envelope; }

    /** The geometry as an object you can inspect, reproject and so on. Empty if the feature has none. */
    public synchronized Geometry geometry() {
        if (geometry == null) {
            geometry = Geometry.fromWkt(wkt);
        }
        return geometry;
    }

    /** The geometry as WKT, for example {@code POINT(1 2)}. Mapnik normalises polygon ring orientation. */
    public String geometryWkt() { return wkt; }

    /** The geometry as a GeoJSON geometry object. The text {@code null} for an empty geometry. */
    public String geometryGeoJson() { return geometryGeoJson; }

    /** The whole feature as a GeoJSON Feature. */
    public String toGeoJson() { return geoJson; }

    @Override
    public String toString() {
        return "Feature[id=" + id + ", " + geometryKind + ", " + attributes + "]";
    }
}
