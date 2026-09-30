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
import java.util.Map;
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
        return load(styleXml, false);
    }

    /** As {@link #load(Path)}. With {@code strict}, Mapnik reports problems it would otherwise skip, such as unknown attributes. */
    public MapnikMap load(Path styleXml, boolean strict) {
        layerVersion++;
        Mapnik.check(N.mapnik_map_load(ptr(), styleXml.toString(), strict ? 1 : 0));
        return this;
    }

    /** Load a Mapnik XML style from a string. Relative paths in it resolve against {@code basePath}. */
    public MapnikMap loadString(String xml, Path basePath) {
        return loadString(xml, basePath, false);
    }

    /** As {@link #loadString(String, Path)}, optionally strict as in {@link #load(Path, boolean)}. */
    public MapnikMap loadString(String xml, Path basePath, boolean strict) {
        layerVersion++;
        Mapnik.check(N.mapnik_map_load_string(ptr(), xml, basePath == null ? null : basePath.toString(), strict ? 1 : 0));
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

    public MapnikMap setBackground(Color color) {
        return setBackground(color.toStyleString());
    }

    public Optional<String> backgroundImage() {
        return Optional.ofNullable(N.mapnik_map_get_background_image(ptr()));
    }

    public MapnikMap setBackgroundImage(Path image) {
        N.mapnik_map_set_background_image(ptr(), image.toString());
        return this;
    }

    /** How the background image is blended onto the background colour, by name, such as {@code multiply}. Empty if unset. */
    public Optional<String> backgroundImageCompOp() {
        return Optional.ofNullable(N.mapnik_map_get_background_image_comp_op(ptr()));
    }

    public MapnikMap setBackgroundImageCompOp(String name) {
        Mapnik.check(N.mapnik_map_set_background_image_comp_op(ptr(), name));
        return this;
    }

    public MapnikMap setBackgroundImageCompOp(BlendMode mode) {
        return setBackgroundImageCompOp(mode.xmlName());
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

    /** The directory set for fonts on this map, if any. */
    public Optional<String> fontDirectory() {
        return Optional.ofNullable(N.mapnik_map_get_font_directory(ptr()));
    }

    public MapnikMap setFontDirectory(Path dir) {
        N.mapnik_map_set_font_directory(ptr(), dir.toString());
        return this;
    }

    // ------------------------------------------------------------------ extra parameters

    /**
     * Arbitrary key/value pairs stored on the map and written as {@code <Parameters>} in its XML,
     * for your own metadata. In key order. Values are {@link String}, {@link Boolean}, {@link Long},
     * {@link Double} or null.
     */
    public Map<String, Object> parameters() {
        Pointer p = ptr();
        return ParamValues.read(N.mapnik_map_param_count(p),
            i -> N.mapnik_map_param_name(p, i),
            i -> N.mapnik_map_param_type(p, i),
            i -> N.mapnik_map_param_bool(p, i) == 1,
            i -> N.mapnik_map_param_int(p, i),
            i -> N.mapnik_map_param_double(p, i),
            i -> N.mapnik_map_param_string(p, i));
    }

    /** Set a parameter: a String, Boolean, Integer, Long, Double or Float. Replaces an existing one. */
    public MapnikMap setParameter(String key, Object value) {
        if (!ParamValues.supported(value)) {
            throw new IllegalArgumentException("unsupported parameter type for '" + key + "': "
                + (value == null ? "null" : value.getClass()));
        }
        if (value instanceof String) {
            N.mapnik_map_set_param_string(ptr(), key, (String) value);
        } else if (value instanceof Boolean) {
            N.mapnik_map_set_param_bool(ptr(), key, (Boolean) value ? 1 : 0);
        } else if (value instanceof Double || value instanceof Float) {
            N.mapnik_map_set_param_double(ptr(), key, ((Number) value).doubleValue());
        } else {
            N.mapnik_map_set_param_int(ptr(), key, ((Number) value).longValue());
        }
        return this;
    }

    public MapnikMap removeParameter(String key) {
        N.mapnik_map_remove_param(ptr(), key);
        return this;
    }

    // ------------------------------------------------------------------ pixels and map coordinates

    /**
     * Where a point in map coordinates (the map's projection) lands in the rendered image: x to the
     * right, y down from the top left. Needs an extent, for example from {@link #zoomToBox}.
     */
    public Point2d toPixel(double x, double y) {
        double[] px = {x};
        double[] py = {y};
        N.mapnik_map_world_to_pixel(ptr(), px, py);
        return new Point2d(px[0], py[0]);
    }

    public Point2d toPixel(Point2d world) {
        return toPixel(world.x(), world.y());
    }

    /** The map coordinates under a pixel of the rendered image. */
    public Point2d toWorld(double pixelX, double pixelY) {
        double[] px = {pixelX};
        double[] py = {pixelY};
        N.mapnik_map_pixel_to_world(ptr(), px, py);
        return new Point2d(px[0], py[0]);
    }

    public Point2d toWorld(Point2d pixel) {
        return toWorld(pixel.x(), pixel.y());
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

    // ------------------------------------------------------------------ styles in code

    /**
     * Add a style built in code, checked by Mapnik's strict XML loader. Throws
     * {@link IllegalArgumentException} if the map already has a style of that name (Mapnik would
     * silently ignore the new one), and {@link MapnikException} if Mapnik rejects the style. Strict
     * loading catches what plain loading skips: a misspelled attribute, an attribute that does not
     * belong on that kind of symbolizer, an unknown font face or a missing image file. The message
     * names the problem. A rejected style is not kept.
     */
    public MapnikMap addStyle(Style style) {
        return addStyle(style, true);
    }

    /**
     * As {@link #addStyle(Style)}. With {@code strict} false, Mapnik ignores attributes it does not
     * know and keeps going if a font or file is missing, so the style loads but may not draw.
     */
    public MapnikMap addStyle(Style style, boolean strict) {
        if (styleNames().contains(style.name())) {
            throw new IllegalArgumentException("the map already has a style named '" + style.name()
                + "'; use replaceStyle to change it");
        }
        try {
            loadString("<Map>" + style.toXml() + "</Map>", null, strict);
        } catch (MapnikException e) {
            // Mapnik can reject the style after it has already inserted it.
            if (styleNames().contains(style.name())) {
                removeStyle(style.name());
            }
            throw e;
        }
        return this;
    }

    /** Add a style, replacing one of the same name if there is one. */
    public MapnikMap replaceStyle(Style style) {
        if (styleNames().contains(style.name())) {
            removeStyle(style.name());
        }
        return addStyle(style);
    }

    public boolean hasStyle(String name) {
        return styleNames().contains(name);
    }

    /**
     * Add a font set for text symbolizers to use with {@code fontset-name}. The fonts must already
     * be registered (see {@link Mapnik#registerFonts}), or Mapnik throws {@link MapnikException}.
     */
    public MapnikMap addFontSet(FontSet fontSet) {
        loadString("<Map>" + fontSet.toXml() + "</Map>", null);
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

    // ------------------------------------------------------------------ tiles and layer subsets

    /**
     * Render one tile of the standard web map grid (see {@link Tiles}) as an image the size of the map,
     * which must be square: its width is the tile size, usually 256 or 512. The map is put in Web
     * Mercator for the render and then restored, including its projection, extent, size, aspect mode
     * and buffer size, so you can keep using it. Close the result.
     *
     * <p>{@code buffer} is a metatile margin in pixels: Mapnik draws a bigger picture that extends that
     * far past the tile on every side and the margin is then cropped off, so that labels and wide
     * lines that cross a tile edge match in the neighbouring tile. 0 draws the tile alone.
     */
    public Image renderTile(Tiles.Tile tile, int buffer) {
        if (buffer < 0) {
            throw new IllegalArgumentException("buffer must not be negative: " + buffer);
        }
        int size = width();
        if (size != height()) {
            throw new IllegalStateException("a tile map must be square, but this one is " + size + "x" + height());
        }
        String oldSrs = srs();
        AspectFixMode oldAspect = aspectFixMode();
        int oldBuffer = bufferSize();
        Box2d oldExtent = extent();
        try {
            Box2d b = tile.bounds();
            double metresPerPixel = b.width() / size;
            double grow = buffer * metresPerPixel;
            setSrs("epsg:3857").setAspectFixMode(AspectFixMode.RESPECT).setBufferSize(0);
            resize(size + 2 * buffer, size + 2 * buffer);
            zoomToBox(b.minX() - grow, b.minY() - grow, b.maxX() + grow, b.maxY() + grow);
            try (Image whole = renderToImage()) {
                return buffer == 0 ? whole.copy() : whole.crop(buffer, buffer, size, size);
            }
        } finally {
            resize(size, size);
            setSrs(oldSrs).setAspectFixMode(oldAspect).setBufferSize(oldBuffer);
            try {
                if (oldExtent.width() > 0 && oldExtent.height() > 0) {
                    setAspectFixMode(AspectFixMode.RESPECT).zoomToBox(oldExtent).setAspectFixMode(oldAspect);
                }
            } catch (MapnikException ignored) {
                // the map had no usable extent to restore
            }
        }
    }

    public Image renderTile(int z, int x, int y, int buffer) {
        return renderTile(new Tiles.Tile(z, x, y), buffer);
    }

    /** A tile encoded as {@code png}, {@code jpeg} and so on, as for {@link #renderToBytes}. */
    public byte[] renderTileToBytes(Tiles.Tile tile, String format, int buffer) {
        try (Image img = renderTile(tile, buffer)) {
            return img.toBytes(format);
        }
    }

    /**
     * Render only the named layers, then put every layer's active flag back as it was. Drawing order
     * is the style's, not the order of {@code names}. Throws {@link IllegalArgumentException} for an
     * unknown name. Close the result.
     */
    public Image renderLayers(Collection<String> names) {
        List<Layer> all = layers();
        List<Boolean> before = new ArrayList<>();
        for (Layer l : all) {
            before.add(l.isActive());
        }
        setActiveLayers(names);
        try {
            return renderToImage();
        } finally {
            List<Layer> fresh = layers();
            for (int i = 0; i < fresh.size(); i++) {
                fresh.get(i).setActive(before.get(i));
            }
        }
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
