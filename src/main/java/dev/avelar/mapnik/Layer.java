package dev.avelar.mapnik;

import com.sun.jna.Pointer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A map layer. There are two kinds:
 * <ul>
 *   <li>A layer you build with {@link #create}. You own it and should {@link #close()} it. Adding it
 *       to a map with {@link MapnikMap#addLayer} copies it, so you can close yours afterwards.</li>
 *   <li>A view onto a layer inside a map, from {@link MapnikMap#layer(String)} and friends. Changes
 *       to it change the map. Closing it does nothing. It stops working, with an
 *       {@link IllegalStateException}, once the map's layer list changes (add, remove, load) or the
 *       map is closed.</li>
 * </ul>
 */
public final class Layer implements AutoCloseable {
    private static final NativeApi N = NativeApi.INSTANCE;

    private final Pointer handle;
    private final MapnikMap parent;   // null for an owned layer
    private final int parentVersion;
    private boolean closed;

    private Layer(Pointer handle, MapnikMap parent) {
        this.handle = handle;
        this.parent = parent;
        this.parentVersion = parent == null ? 0 : parent.layerVersion();
    }

    static Layer view(Pointer handle, MapnikMap parent) {
        return new Layer(handle, parent);
    }

    /** A new standalone layer in EPSG:4326. */
    public static Layer create(String name) {
        return create(name, null);
    }

    /** A new standalone layer. {@code srs} may be null for the default (WGS 84). */
    public static Layer create(String name, String srs) {
        Pointer p = N.mapnik_layer_create(name, srs);
        if (p == null) {
            throw new MapnikException(N.mapnik_last_error());
        }
        return new Layer(p, null);
    }

    // ------------------------------------------------------------------ identity

    public String name() { return N.mapnik_layer_get_name(ptr()); }

    public Layer setName(String name) {
        N.mapnik_layer_set_name(ptr(), name);
        return this;
    }

    /** The projection of this layer's data. */
    public String srs() { return N.mapnik_layer_get_srs(ptr()); }

    public Layer setSrs(String srs) {
        N.mapnik_layer_set_srs(ptr(), srs);
        return this;
    }

    // ------------------------------------------------------------------ styles

    /** Names of the styles this layer is drawn with, in order. */
    public List<String> styles() {
        int n = N.mapnik_layer_style_count(ptr());
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(N.mapnik_layer_style_name(ptr(), i));
        }
        return Collections.unmodifiableList(out);
    }

    public Layer addStyle(String styleName) {
        N.mapnik_layer_add_style(ptr(), styleName);
        return this;
    }

    // ------------------------------------------------------------------ visibility

    /** Whether the layer is drawn at all. On by default. */
    public boolean isActive() { return N.mapnik_layer_get_active(ptr()) == 1; }

    public Layer setActive(boolean active) {
        N.mapnik_layer_set_active(ptr(), active ? 1 : 0);
        return this;
    }

    public double minimumScaleDenominator() { return N.mapnik_layer_get_minimum_scale_denominator(ptr()); }

    public Layer setMinimumScaleDenominator(double v) {
        N.mapnik_layer_set_minimum_scale_denominator(ptr(), v);
        return this;
    }

    public double maximumScaleDenominator() { return N.mapnik_layer_get_maximum_scale_denominator(ptr()); }

    public Layer setMaximumScaleDenominator(double v) {
        N.mapnik_layer_set_maximum_scale_denominator(ptr(), v);
        return this;
    }

    /** True if the layer is active and {@code scaleDenominator} is inside its scale range. */
    public boolean isVisibleAt(double scaleDenominator) {
        return N.mapnik_layer_visible(ptr(), scaleDenominator) == 1;
    }

    public double opacity() { return N.mapnik_layer_get_opacity(ptr()); }

    public Layer setOpacity(double opacity) {
        N.mapnik_layer_set_opacity(ptr(), opacity);
        return this;
    }

    /**
     * How this layer is blended onto what is below it, by the name used in styles, such as
     * {@code multiply}, {@code screen} or {@code src-over}. Empty if never set.
     */
    public Optional<String> compOp() {
        return Optional.ofNullable(N.mapnik_layer_get_comp_op(ptr()));
    }

    /** Set the blend mode. Throws {@link MapnikException} for a name Mapnik does not know. */
    public Layer setCompOp(String name) {
        Mapnik.check(N.mapnik_layer_set_comp_op(ptr(), name));
        return this;
    }

    // ------------------------------------------------------------------ child layers

    /** Add a copy of {@code child} under this layer, to group layers. */
    public Layer addChild(Layer child) {
        N.mapnik_layer_add_child(ptr(), child.ptr());
        return this;
    }

    public int childCount() {
        return N.mapnik_layer_child_count(ptr());
    }

    /** A copy of the child at {@code index}, which you own and should close. Changes to it do not affect this layer. */
    public Layer childCopy(int index) {
        Pointer p = N.mapnik_layer_child_copy(ptr(), index);
        if (p == null) {
            throw new IndexOutOfBoundsException("child index " + index + ", count " + childCount());
        }
        return new Layer(p, null);
    }

    // ------------------------------------------------------------------ behaviour flags

    public boolean isQueryable() { return N.mapnik_layer_get_queryable(ptr()) == 1; }

    public Layer setQueryable(boolean v) {
        N.mapnik_layer_set_queryable(ptr(), v ? 1 : 0);
        return this;
    }

    public boolean clearsLabelCache() { return N.mapnik_layer_get_clear_label_cache(ptr()) == 1; }

    public Layer setClearLabelCache(boolean v) {
        N.mapnik_layer_set_clear_label_cache(ptr(), v ? 1 : 0);
        return this;
    }

    public boolean cachesFeatures() { return N.mapnik_layer_get_cache_features(ptr()) == 1; }

    public Layer setCacheFeatures(boolean v) {
        N.mapnik_layer_set_cache_features(ptr(), v ? 1 : 0);
        return this;
    }

    /** The column rendering is grouped by, or an empty string. */
    public String groupBy() { return N.mapnik_layer_get_group_by(ptr()); }

    public Layer setGroupBy(String column) {
        N.mapnik_layer_set_group_by(ptr(), column);
        return this;
    }

    // ------------------------------------------------------------------ buffer and extent

    /** Buffer around the layer's tiles in pixels, if set. Falls back to the map's buffer size. */
    public Optional<Integer> bufferSize() {
        int[] out = new int[1];
        return N.mapnik_layer_get_buffer_size(ptr(), out) == 1 ? Optional.of(out[0]) : Optional.<Integer>empty();
    }

    public Layer setBufferSize(int size) {
        N.mapnik_layer_set_buffer_size(ptr(), size);
        return this;
    }

    public Layer resetBufferSize() {
        N.mapnik_layer_reset_buffer_size(ptr());
        return this;
    }

    /** Extent beyond which this layer is not drawn, if set. */
    public Optional<Box2d> maximumExtent() {
        double[] out = new double[4];
        return N.mapnik_layer_get_maximum_extent(ptr(), out) == 1 ? Optional.of(Box2d.of(out)) : Optional.<Box2d>empty();
    }

    public Layer setMaximumExtent(Box2d box) {
        N.mapnik_layer_set_maximum_extent(ptr(), box.minX(), box.minY(), box.maxX(), box.maxY());
        return this;
    }

    public Layer resetMaximumExtent() {
        N.mapnik_layer_reset_maximum_extent(ptr());
        return this;
    }

    // ------------------------------------------------------------------ data

    /** Attach a datasource. The layer keeps its own reference, so you may close {@code ds} afterwards. */
    public Layer setDatasource(Datasource ds) {
        N.mapnik_layer_set_datasource(ptr(), ds.ptr());
        return this;
    }

    /** A new handle onto the layer's datasource, which you should close, or empty if there is none. */
    public Optional<Datasource> datasource() {
        Pointer p = N.mapnik_layer_get_datasource(ptr());
        return p == null ? Optional.<Datasource>empty() : Optional.of(new Datasource(p));
    }

    /** The extent of the layer's data, in the layer's projection. Throws if there is no datasource. */
    public Box2d envelope() {
        double[] out = new double[4];
        Mapnik.check(N.mapnik_layer_envelope(ptr(), out));
        return Box2d.of(out);
    }

    // ------------------------------------------------------------------ plumbing

    Pointer ptr() {
        if (closed) {
            throw new IllegalStateException("layer closed");
        }
        if (parent != null && !parent.isLayerViewValid(parentVersion)) {
            throw new IllegalStateException("layer is no longer valid: the map's layers changed or the map was closed");
        }
        return handle;
    }

    /** Frees a layer made with {@link #create}. Does nothing for a view onto a map's layer. */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (parent == null) {
                N.mapnik_layer_free(handle);
            }
        }
    }
}
