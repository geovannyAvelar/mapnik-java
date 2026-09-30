package dev.avelar.mapnik;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** A Mapnik map. Not thread-safe: use one instance per thread. */
public final class MapnikMap implements AutoCloseable {
    private static final NativeApi N = NativeApi.INSTANCE;

    private Pointer handle;
    /** Bumped whenever the native layer list may move, which invalidates {@link Layer} views. */
    private int layerVersion;

    public MapnikMap(int width, int height) {
        Mapnik.verifyVersion();
        handle = N.mapnik_map_create(width, height);
        if (handle == null) {
            throw new MapnikException(N.mapnik_last_error());
        }
    }

    // ------------------------------------------------------------------ load and save

    /** Load a Mapnik XML style from a file. Its layers are added to the map. */
    public MapnikMap load(Path styleXml) {
        layerVersion++;
        Mapnik.check(N.mapnik_map_load(ptr(), styleXml.toString()));
        return this;
    }

    /** Load a Mapnik XML style from a string. Relative paths in it resolve against {@code basePath}. */
    public MapnikMap loadString(String xml, Path basePath) {
        layerVersion++;
        Mapnik.check(N.mapnik_map_load_string(ptr(), xml, basePath == null ? null : basePath.toString()));
        return this;
    }

    /** The map as Mapnik XML. With {@code explicitDefaults}, settings at their default value are written too. */
    public String toXml(boolean explicitDefaults) {
        String xml = N.mapnik_map_save_to_string(ptr(), explicitDefaults ? 1 : 0);
        if (xml == null) {
            throw new MapnikException(N.mapnik_last_error());
        }
        return xml;
    }

    public String toXml() {
        return toXml(false);
    }

    public void save(Path xmlFile, boolean explicitDefaults) {
        Mapnik.check(N.mapnik_map_save(ptr(), xmlFile.toString(), explicitDefaults ? 1 : 0));
    }

    // ------------------------------------------------------------------ size and projection

    public int width() { return N.mapnik_map_width(ptr()); }

    public int height() { return N.mapnik_map_height(ptr()); }

    public MapnikMap resize(int width, int height) {
        N.mapnik_map_resize(ptr(), width, height);
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

    // ------------------------------------------------------------------ appearance

    /** The background colour as Mapnik prints it, for example {@code rgb(255,255,255)} or {@code rgba(0,0,255,0.5)}, if set. */
    public Optional<String> background() {
        return Optional.ofNullable(N.mapnik_map_get_background(ptr()));
    }

    /** Set the background colour: a name ({@code "white"}), {@code "#rrggbb"}, or {@code "rgba(r,g,b,a)"}. */
    public MapnikMap setBackground(String color) {
        Mapnik.check(N.mapnik_map_set_background(ptr(), color));
        return this;
    }

    public Optional<String> backgroundImage() {
        return Optional.ofNullable(N.mapnik_map_get_background_image(ptr()));
    }

    public MapnikMap setBackgroundImage(Path image) {
        N.mapnik_map_set_background_image(ptr(), image.toString());
        return this;
    }

    public double backgroundImageOpacity() { return N.mapnik_map_get_background_image_opacity(ptr()); }

    public MapnikMap setBackgroundImageOpacity(double opacity) {
        N.mapnik_map_set_background_image_opacity(ptr(), opacity);
        return this;
    }

    /** Extra pixels rendered around the map so labels and symbols at the edge are not cut off. */
    public int bufferSize() { return N.mapnik_map_get_buffer_size(ptr()); }

    public MapnikMap setBufferSize(int pixels) {
        N.mapnik_map_set_buffer_size(ptr(), pixels);
        return this;
    }

    /** Fonts directory for this map. */
    public MapnikMap registerFonts(Path dir, boolean recurse) {
        Mapnik.check(N.mapnik_map_register_fonts(ptr(), dir.toString(), recurse ? 1 : 0));
        return this;
    }

    public MapnikMap loadFonts() {
        Mapnik.check(N.mapnik_map_load_fonts(ptr()));
        return this;
    }

    public String basePath() { return N.mapnik_map_get_base_path(ptr()); }

    /** Directory that relative paths in the style are resolved against. */
    public MapnikMap setBasePath(Path dir) {
        N.mapnik_map_set_base_path(ptr(), dir.toString());
        return this;
    }

    // ------------------------------------------------------------------ extent and zoom

    public AspectFixMode aspectFixMode() {
        return AspectFixMode.values()[N.mapnik_map_get_aspect_fix_mode(ptr())];
    }

    /** How {@link #zoomToBox} treats a box whose aspect ratio differs from the map's. */
    public MapnikMap setAspectFixMode(AspectFixMode mode) {
        N.mapnik_map_set_aspect_fix_mode(ptr(), mode.ordinal());
        return this;
    }

    /** The extent currently shown, in the map's projection. */
    public Box2d extent() {
        double[] out = new double[4];
        N.mapnik_map_get_current_extent(ptr(), out);
        return Box2d.of(out);
    }

    /** {@link #extent()} grown by the buffer size. */
    public Box2d bufferedExtent() {
        double[] out = new double[4];
        N.mapnik_map_get_buffered_extent(ptr(), out);
        return Box2d.of(out);
    }

    /** The extent beyond which nothing is drawn, if set. */
    public Optional<Box2d> maximumExtent() {
        double[] out = new double[4];
        return N.mapnik_map_get_maximum_extent(ptr(), out) == 1 ? Optional.of(Box2d.of(out)) : Optional.<Box2d>empty();
    }

    public MapnikMap setMaximumExtent(Box2d box) {
        N.mapnik_map_set_maximum_extent(ptr(), box.minX(), box.minY(), box.maxX(), box.maxY());
        return this;
    }

    public MapnikMap resetMaximumExtent() {
        N.mapnik_map_reset_maximum_extent(ptr());
        return this;
    }

    public MapnikMap zoomToBox(double minx, double miny, double maxx, double maxy) {
        N.mapnik_map_zoom_to_box(ptr(), minx, miny, maxx, maxy);
        return this;
    }

    public MapnikMap zoomToBox(Box2d box) {
        return zoomToBox(box.minX(), box.minY(), box.maxX(), box.maxY());
    }

    /** Fit the extent of all layers' data. */
    public MapnikMap zoomAll() {
        N.mapnik_map_zoom_all(ptr());
        return this;
    }

    /**
     * Scale the extent about its centre by {@code factor}: above 1 zooms out (shows more), below 1
     * zooms in. {@code zoom(0.5)} shows half the width and height.
     */
    public MapnikMap zoom(double factor) {
        N.mapnik_map_zoom(ptr(), factor);
        return this;
    }

    /** Move the view by a pixel offset. */
    public MapnikMap pan(int x, int y) {
        N.mapnik_map_pan(ptr(), x, y);
        return this;
    }

    /** Centre the view on pixel (x, y), then scale the extent by {@code zoom} as {@link #zoom} does. */
    public MapnikMap panAndZoom(int x, int y, double zoom) {
        N.mapnik_map_pan_and_zoom(ptr(), x, y, zoom);
        return this;
    }

    /** Map units per pixel. */
    public double scale() { return N.mapnik_map_scale(ptr()); }

    /** Scale denominator, as used by layers' and rules' scale ranges. */
    public double scaleDenominator() { return N.mapnik_map_scale_denominator(ptr()); }

    // ------------------------------------------------------------------ layers

    public int layerCount() { return N.mapnik_map_layer_count(ptr()); }

    /** Layer names in drawing order: the first is drawn first, so it ends up at the bottom. */
    public List<String> layerNames() {
        List<String> names = new ArrayList<>();
        for (Layer l : layers()) {
            names.add(l.name());
        }
        return Collections.unmodifiableList(names);
    }

    /** Views onto all layers, in drawing order. See {@link Layer} for how long they stay valid. */
    public List<Layer> layers() {
        int n = layerCount();
        List<Layer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(layer(i));
        }
        return out;
    }

    /** A view onto the layer at {@code index}. */
    public Layer layer(int index) {
        Pointer p = N.mapnik_map_get_layer(ptr(), index);
        if (p == null) {
            throw new IndexOutOfBoundsException("layer index " + index + ", count " + layerCount());
        }
        return Layer.view(p, this);
    }

    /** A view onto the first layer with this name. Throws {@link IllegalArgumentException} if there is none. */
    public Layer layer(String name) {
        return layer(indexOf(name));
    }

    /** Add a copy of {@code layer} on top of the existing layers. Invalidates layer views from this map. */
    public MapnikMap addLayer(Layer layer) {
        layerVersion++;
        Mapnik.check(N.mapnik_map_add_layer(ptr(), layer.ptr()));
        return this;
    }

    public MapnikMap removeLayer(int index) {
        layerVersion++;
        if (N.mapnik_map_remove_layer(ptr(), index) != 0) {
            throw new IndexOutOfBoundsException("layer index " + index);
        }
        return this;
    }

    public MapnikMap removeLayer(String name) {
        return removeLayer(indexOf(name));
    }

    /** Remove all layers and styles. */
    public MapnikMap removeAll() {
        layerVersion++;
        N.mapnik_map_remove_all(ptr());
        return this;
    }

    /** Whether the named layer is drawn. Layers are active by default. */
    public boolean isLayerActive(String name) {
        return layer(name).isActive();
    }

    /** Turn one layer on or off. Throws {@link IllegalArgumentException} for an unknown name. */
    public MapnikMap setLayerActive(String name, boolean active) {
        layer(name).setActive(active);
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
        for (Layer l : layers()) {
            l.setActive(wanted.contains(l.name()));
        }
        return this;
    }

    /** Turn every layer back on. */
    public MapnikMap activateAllLayers() {
        for (Layer l : layers()) {
            l.setActive(true);
        }
        return this;
    }

    // ------------------------------------------------------------------ queries

    /**
     * Features of a layer at a point, given in the map's projection. The layer must be queryable
     * (see {@link Layer#setQueryable}) for some plugins. Close the result.
     */
    public Featureset queryPoint(String layer, double x, double y) {
        return Featureset.check(N.mapnik_map_query_point(ptr(), indexOf(layer), x, y));
    }

    /** Features of a layer under pixel (px, py) of the map image. Close the result. */
    public Featureset queryMapPoint(String layer, double px, double py) {
        return Featureset.check(N.mapnik_map_query_map_point(ptr(), indexOf(layer), px, py));
    }

    // ------------------------------------------------------------------ styles

    /** Names of the styles defined in the map, sorted. */
    public List<String> styleNames() {
        int n = N.mapnik_map_style_count(ptr());
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(N.mapnik_map_style_name(ptr(), i));
        }
        return Collections.unmodifiableList(out);
    }

    public MapnikMap removeStyle(String name) {
        N.mapnik_map_remove_style(ptr(), name);
        return this;
    }

    // ------------------------------------------------------------------ render

    /**
     * Render to a file. {@code format} is an image format ({@code png}, {@code jpeg}, {@code webp},
     * {@code tiff}, with options such as {@code png8}, {@code png256}, {@code jpeg90}) or a vector
     * format ({@code pdf}, {@code svg}, {@code ps}, which need Cairo support in Mapnik).
     */
    public void renderToFile(Path out, String format) {
        renderToFile(out, format, RenderOptions.defaults());
    }

    public void renderToFile(Path out, String format, RenderOptions options) {
        if (isVector(format)) {
            Mapnik.check(N.mapnik_map_render_to_cairo_file(ptr(), out.toString(), format.toLowerCase(Locale.ROOT),
                options.scaleFactor()));
        } else {
            Mapnik.check(N.mapnik_map_render_to_file(ptr(), out.toString(), format, options.scaleFactor(),
                options.offsetX(), options.offsetY()));
        }
    }

    /** Render to memory. Accepts the same formats as {@link #renderToFile}. */
    public byte[] renderToBytes(String format) {
        return renderToBytes(format, RenderOptions.defaults());
    }

    public byte[] renderToBytes(String format, RenderOptions options) {
        if (isVector(format)) {
            // Cairo can only write to a file.
            try {
                Path tmp = Files.createTempFile("mapnik-java", "." + format.toLowerCase(Locale.ROOT));
                try {
                    renderToFile(tmp, format, options);
                    return Files.readAllBytes(tmp);
                } finally {
                    Files.deleteIfExists(tmp);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        PointerByReference out = new PointerByReference();
        IntByReference len = new IntByReference();
        Mapnik.check(N.mapnik_map_render_to_buffer(ptr(), format, out, len, options.scaleFactor(),
            options.offsetX(), options.offsetY()));
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

    /** Render to a new {@link Image} the size of the map. Close it when done. */
    public Image renderToImage() {
        return renderToImage(RenderOptions.defaults());
    }

    public Image renderToImage(RenderOptions options) {
        Image image = Image.create(width(), height());
        try {
            render(image, options);
            return image;
        } catch (RuntimeException e) {
            image.close();
            throw e;
        }
    }

    /** Draw onto an existing image, blending over what is there. The image must be the same size as the map. */
    public MapnikMap render(Image image) {
        return render(image, RenderOptions.defaults());
    }

    public MapnikMap render(Image image, RenderOptions options) {
        Mapnik.check(N.mapnik_map_render_to_image(ptr(), image.ptr(), options.scaleFactor(),
            options.offsetX(), options.offsetY()));
        return this;
    }

    private static boolean isVector(String format) {
        String f = format.toLowerCase(Locale.ROOT);
        return f.equals("pdf") || f.equals("svg") || f.equals("ps");
    }

    // ------------------------------------------------------------------ plumbing

    private int indexOf(String name) {
        List<String> names = layerNames();
        int i = names.indexOf(name);
        if (i < 0) {
            throw new IllegalArgumentException("unknown layer: " + name);
        }
        return i;
    }

    int layerVersion() {
        return layerVersion;
    }

    boolean isLayerViewValid(int version) {
        return handle != null && version == layerVersion;
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
