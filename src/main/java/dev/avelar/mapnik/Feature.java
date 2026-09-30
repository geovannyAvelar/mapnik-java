package dev.avelar.mapnik;

import com.sun.jna.Pointer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One feature: an id, named attributes and a geometry. A feature is a plain snapshot. It holds no
 * native memory, so there is nothing to close and it stays valid after the {@link Featureset} that
 * produced it is closed.
 */
public final class Feature {
    private final long id;
    private final Map<String, Object> attributes;
    private final GeometryKind geometryKind;
    private final Box2d envelope;
    private final String wkt;
    private final String geometryGeoJson;
    private final String geoJson;

    private Feature(long id, Map<String, Object> attributes, GeometryKind kind, Box2d envelope,
                    String wkt, String geometryGeoJson, String geoJson) {
        this.id = id;
        this.attributes = attributes;
        this.geometryKind = kind;
        this.envelope = envelope;
        this.wkt = wkt;
        this.geometryGeoJson = geometryGeoJson;
        this.geoJson = geoJson;
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
            text(n.mapnik_feature_to_geojson(f)));
    }

    private static String text(String s) {
        if (s == null) {
            throw new MapnikException(NativeApi.INSTANCE.mapnik_last_error());
        }
        return s;
    }

    public long id() { return id; }

    /**
     * Attributes by name, in name order. Values are {@code null}, {@link Boolean}, {@link Long},
     * {@link Double} or {@link String}.
     */
    public Map<String, Object> attributes() { return attributes; }

    /** One attribute, or null if it is missing or null. */
    public Object attribute(String name) { return attributes.get(name); }

    public GeometryKind geometryKind() { return geometryKind; }

    /** Bounding box of the geometry, in the datasource's projection. Null for an empty geometry. */
    public Box2d envelope() { return envelope; }

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
