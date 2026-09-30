package dev.avelar.mapnik;

import com.sun.jna.Pointer;

import java.util.Collections;
import java.util.Map;

/**
 * A datasource that holds features in memory, so you can render data that is not in a file: results
 * of your own queries, shapes you compute, or GeoJSON you received. Use it like any
 * {@link Datasource}, for example with {@link Layer#setDatasource}.
 *
 * <pre>{@code
 * try (MemoryDatasource ds = MemoryDatasource.create()) {
 *     ds.add(Geometry.point(10, 20), Collections.singletonMap("name", "Depot"));
 *     layer.setDatasource(ds);
 * }
 * }</pre>
 *
 * <p>Coordinates must be in the projection of the layer you attach it to. Mapnik does not inspect
 * in-memory features, so {@link #fields()} is empty and {@link #geometryType()} is always
 * {@link Datasource.GeometryType#COLLECTION}. Filters and labels still see every attribute.
 */
public final class MemoryDatasource extends Datasource {
    private static final NativeApi N = NativeApi.INSTANCE;

    private long nextId = 1;

    private MemoryDatasource(Pointer handle) {
        super(handle);
    }

    /** An empty memory datasource. Close it when done. */
    public static MemoryDatasource create() {
        Pointer p = N.mapnik_memory_datasource_create();
        if (p == null) {
            throw new MapnikException(N.mapnik_last_error());
        }
        return new MemoryDatasource(p);
    }

    /** Add a feature. A layer that already uses this datasource sees it on the next render. */
    public MemoryDatasource add(Feature feature) {
        Pointer b = N.mapnik_feature_builder_create(feature.id());
        if (b == null) {
            throw new MapnikException(N.mapnik_last_error());
        }
        try {
            Geometry g = feature.geometry();
            if (!g.isEmpty()) {
                byte[] wkb = g.toWkb();
                Mapnik.check(N.mapnik_feature_builder_set_geometry_wkb(b, wkb, wkb.length));
            }
            for (Map.Entry<String, Object> e : feature.attributes().entrySet()) {
                put(b, e.getKey(), e.getValue());
            }
            Mapnik.check(N.mapnik_memory_datasource_push(ptr(), b));
        } finally {
            N.mapnik_feature_builder_free(b);
        }
        nextId = Math.max(nextId, feature.id() + 1);
        return this;
    }

    private static void put(Pointer b, String key, Object v) {
        if (v == null) {
            N.mapnik_feature_builder_put_null(b, key);
        } else if (v instanceof String) {
            N.mapnik_feature_builder_put_string(b, key, (String) v);
        } else if (v instanceof Boolean) {
            N.mapnik_feature_builder_put_bool(b, key, (Boolean) v ? 1 : 0);
        } else if (v instanceof Double) {
            N.mapnik_feature_builder_put_double(b, key, (Double) v);
        } else {
            N.mapnik_feature_builder_put_int(b, key, ((Number) v).longValue());
        }
    }

    /** Add a geometry with attributes. It gets the next free id. */
    public MemoryDatasource add(Geometry geometry, Map<String, ?> attributes) {
        return add(Feature.create(nextId, geometry, attributes));
    }

    /** Add a geometry with no attributes. */
    public MemoryDatasource add(Geometry geometry) {
        return add(geometry, Collections.<String, Object>emptyMap());
    }

    public MemoryDatasource addAll(Iterable<Feature> features) {
        for (Feature f : features) {
            add(f);
        }
        return this;
    }

    /**
     * Add every feature of a GeoJSON FeatureCollection or Feature, read as by
     * {@link Feature#listFromGeoJson(String)}. Coordinates are used as they are; GeoJSON is normally
     * in EPSG:4326, so attach the datasource to a layer whose projection is {@code epsg:4326}.
     */
    public MemoryDatasource addGeoJson(String json) {
        for (Feature f : Feature.listFromGeoJson(json)) {
            add(f);
        }
        return this;
    }

    /** The number of features held. */
    public int size() {
        return N.mapnik_memory_datasource_size(ptr());
    }

    /** Remove every feature. */
    public MemoryDatasource clear() {
        N.mapnik_memory_datasource_clear(ptr());
        nextId = 1;
        return this;
    }

    /** Override the extent {@link #envelope()} reports, which otherwise follows the features. */
    public MemoryDatasource setEnvelope(Box2d box) {
        N.mapnik_memory_datasource_set_envelope(ptr(), box.minX(), box.minY(), box.maxX(), box.maxY());
        return this;
    }
}
