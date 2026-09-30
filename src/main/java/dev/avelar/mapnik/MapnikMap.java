package dev.avelar.mapnik;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A Mapnik map. Not thread-safe: use one instance per thread. */
public final class MapnikMap implements AutoCloseable {
    private static final NativeApi N = NativeApi.INSTANCE;

    private Pointer handle;

    public MapnikMap(int width, int height) {
        Mapnik.verifyVersion();
        handle = N.mapnik_map_create(width, height);
        if (handle == null) {
            throw new MapnikException(N.mapnik_last_error());
        }
    }

    public MapnikMap load(Path styleXml) {
        Mapnik.check(N.mapnik_map_load(ptr(), styleXml.toString()));
        return this;
    }

    public MapnikMap loadString(String xml, Path basePath) {
        Mapnik.check(N.mapnik_map_load_string(ptr(), xml, basePath == null ? null : basePath.toString()));
        return this;
    }

    public MapnikMap resize(int width, int height) {
        N.mapnik_map_resize(ptr(), width, height);
        return this;
    }

    public MapnikMap zoomToBox(double minx, double miny, double maxx, double maxy) {
        N.mapnik_map_zoom_to_box(ptr(), minx, miny, maxx, maxy);
        return this;
    }

    public MapnikMap zoomAll() {
        N.mapnik_map_zoom_all(ptr());
        return this;
    }

    /** The map projection, as given in the style or by {@link #setSrs}, e.g. {@code epsg:4326}. */
    public String srs() {
        return N.mapnik_map_get_srs(ptr());
    }

    /**
     * Set the map projection: anything Mapnik accepts, such as {@code epsg:3857} or a PROJ string.
     * Layers keep their own projection and are reprojected when rendered. The extent passed to
     * {@link #zoomToBox} is in this projection, so set it first. An invalid value fails at render time.
     */
    public MapnikMap setSrs(String srs) {
        Mapnik.check(N.mapnik_map_set_srs(ptr(), srs));
        return this;
    }

    /** Layer names in drawing order: the first is drawn first, so it ends up at the bottom. */
    public List<String> layerNames() {
        int n = N.mapnik_map_layer_count(ptr());
        List<String> names = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            names.add(N.mapnik_map_layer_name(ptr(), i));
        }
        return Collections.unmodifiableList(names);
    }

    /** Whether the named layer is drawn. Layers are active by default. */
    public boolean isLayerActive(String name) {
        return N.mapnik_map_layer_active(ptr(), indexOf(name)) == 1;
    }

    /** Turn one layer on or off. Throws {@link IllegalArgumentException} for an unknown name. */
    public MapnikMap setLayerActive(String name, boolean active) {
        Mapnik.check(N.mapnik_map_set_layer_active(ptr(), indexOf(name), active ? 1 : 0));
        return this;
    }

    /**
     * Draw only these layers; all others are turned off. Drawing order stays the style's order, not
     * the order of {@code names}. Throws {@link IllegalArgumentException} for an unknown name, and
     * changes nothing in that case.
     */
    public MapnikMap setActiveLayers(Collection<String> names) {
        List<String> all = layerNames();
        Set<String> wanted = new HashSet<>(names);
        for (String n : wanted) {
            if (!all.contains(n)) {
                throw new IllegalArgumentException("unknown layer: " + n);
            }
        }
        for (int i = 0; i < all.size(); i++) {
            Mapnik.check(N.mapnik_map_set_layer_active(ptr(), i, wanted.contains(all.get(i)) ? 1 : 0));
        }
        return this;
    }

    /** Turn every layer back on. */
    public MapnikMap activateAllLayers() {
        for (int i = 0; i < N.mapnik_map_layer_count(ptr()); i++) {
            Mapnik.check(N.mapnik_map_set_layer_active(ptr(), i, 1));
        }
        return this;
    }

    private int indexOf(String name) {
        int i = layerNames().indexOf(name);
        if (i < 0) {
            throw new IllegalArgumentException("unknown layer: " + name);
        }
        return i;
    }

    public void renderToFile(Path out, String format) {
        Mapnik.check(N.mapnik_map_render_to_file(ptr(), out.toString(), format));
    }

    public byte[] renderToBytes(String format) {
        PointerByReference out = new PointerByReference();
        IntByReference len = new IntByReference();
        Mapnik.check(N.mapnik_map_render_to_buffer(ptr(), format, out, len));
        Pointer buf = out.getValue();
        try {
            return buf.getByteArray(0, len.getValue());
        } finally {
            N.mapnik_buffer_free(buf);
        }
    }

    public byte[] renderToPng() {
        return renderToBytes("png");
    }

    private Pointer ptr() {
        if (handle == null) {
            throw new IllegalStateException("map closed");
        }
        return handle;
    }

    @Override
    public void close() {
        if (handle != null) {
            N.mapnik_map_free(handle);
            handle = null;
        }
    }
}
