package dev.avelar.mapnik;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.nio.file.Path;

/**
 * A raster image in 8-bit RGBA, such as a rendered map or a picture loaded from a file. Pixels are
 * exposed as ARGB ints, {@code 0xAARRGGBB}, the same as {@link java.awt.image.BufferedImage#TYPE_INT_ARGB},
 * with straight (not premultiplied) alpha. Holds native memory: close it.
 */
public final class Image implements AutoCloseable {
    private static final NativeApi N = NativeApi.INSTANCE;

    private Pointer handle;

    Image(Pointer handle) {
        this.handle = handle;
    }

    /** A new fully transparent image. */
    public static Image create(int width, int height) {
        return check(N.mapnik_image_create(width, height));
    }

    /** Load a PNG, JPEG, TIFF or WebP file, whichever Mapnik was built to read. */
    public static Image load(Path file) {
        return check(N.mapnik_image_load_file(file.toString()));
    }

    /** Decode image data held in memory. */
    public static Image load(byte[] data) {
        return check(N.mapnik_image_load_bytes(data, data.length));
    }

    private static Image check(Pointer p) {
        if (p == null) {
            throw new MapnikException(N.mapnik_last_error());
        }
        return new Image(p);
    }

    public int width() { return N.mapnik_image_width(ptr()); }

    public int height() { return N.mapnik_image_height(ptr()); }

    /** The pixel at (x, y) as {@code 0xAARRGGBB}. Throws {@link IndexOutOfBoundsException} if outside the image. */
    public int getArgb(int x, int y) {
        checkBounds(x, y);
        return fromNative(N.mapnik_image_get_pixel(ptr(), x, y));
    }

    public Image setArgb(int x, int y, int argb) {
        checkBounds(x, y);
        Mapnik.check(N.mapnik_image_set_pixel(ptr(), x, y, toNative(argb)));
        return this;
    }

    /** Set every pixel: a colour name, {@code "#rrggbb"} or {@code "rgba(r,g,b,a)"}. */
    public Image fill(String color) {
        Mapnik.check(N.mapnik_image_fill(ptr(), color));
        return this;
    }

    public Image fill(Color color) {
        return fill(color.toStyleString());
    }

    // ------------------------------------------------------------------ operations

    /**
     * Apply Mapnik image filters in place, in order. Build them with {@link ImageFilters} or write
     * the {@code image-filters} syntax yourself, for example {@code "agg-stack-blur(5,5) invert"}.
     * Throws {@link MapnikException} for a filter Mapnik cannot parse; the image is then unchanged.
     */
    public Image filter(String... filters) {
        return filter(1.0, filters);
    }

    /** As {@link #filter(String...)}, with a scale factor for sizes inside the filters, as in {@link RenderOptions}. */
    public Image filter(double scaleFactor, String... filters) {
        if (filters.length == 0) {
            throw new IllegalArgumentException("no filters given");
        }
        Mapnik.check(N.mapnik_image_filter(ptr(), String.join(" ", filters), scaleFactor));
        return this;
    }

    /** Draw {@code source} onto this image with normal blending, its top left corner at (dx, dy). Parts outside are clipped. */
    public Image composite(Image source, int dx, int dy) {
        return composite(source, BlendMode.SRC_OVER, 1.0, dx, dy);
    }

    /**
     * Draw {@code source} onto this image at (dx, dy) with a blend mode and an opacity from 0 to 1.
     * The source may be any size and is not changed. Parts outside this image are clipped.
     */
    public Image composite(Image source, BlendMode mode, double opacity, int dx, int dy) {
        Mapnik.check(N.mapnik_image_composite(ptr(), source.ptr(), mode.xmlName(), opacity, dx, dy));
        return this;
    }

    /** A resized copy, using bilinear resampling. */
    public Image scaled(int width, int height) {
        return scaled(width, height, ScalingMethod.BILINEAR);
    }

    /** A resized copy. This image is not changed. Close the result. */
    public Image scaled(int width, int height, ScalingMethod method) {
        return check(N.mapnik_image_scale(ptr(), width, height, method.xmlName()));
    }

    /**
     * Reproject this image, which covers {@code sourceExtent} in {@code sourceSrs}, into a new image of
     * {@code width} x {@code height} pixels that covers {@code targetExtent} in {@code targetSrs}. Areas
     * of the new image that fall outside the source are transparent. Use a small target extent to
     * zoom in, and {@link ScalingMethod#BILINEAR} unless you need hard pixels. Close the result.
     */
    public Image warp(String sourceSrs, Box2d sourceExtent, String targetSrs, Box2d targetExtent,
                      int width, int height, ScalingMethod method) {
        return warp(sourceSrs, sourceExtent, targetSrs, targetExtent, width, height, method, 16);
    }

    /** As above, with the size in pixels of the grid the warp is approximated on (Mapnik's default is 16). */
    public Image warp(String sourceSrs, Box2d sourceExtent, String targetSrs, Box2d targetExtent,
                      int width, int height, ScalingMethod method, int meshSize) {
        return check(N.mapnik_image_warp(ptr(), sourceSrs, box(sourceExtent), targetSrs, box(targetExtent),
            width, height, meshSize, method.xmlName()));
    }

    private static double[] box(Box2d b) {
        return new double[] {b.minX(), b.minY(), b.maxX(), b.maxY()};
    }

    /** A copy of part of this image. Throws {@link MapnikException} if the area is not inside the image. */
    public Image crop(int x, int y, int width, int height) {
        return check(N.mapnik_image_crop(ptr(), x, y, width, height));
    }

    /** An independent copy. */
    public Image copy() {
        return check(N.mapnik_image_copy(ptr()));
    }

    /** Multiply every pixel's alpha by {@code opacity}, from 0 to 1. */
    public Image applyOpacity(double opacity) {
        if (!(opacity >= 0.0 && opacity <= 1.0)) {
            throw new IllegalArgumentException("opacity must be from 0 to 1: " + opacity);
        }
        Mapnik.check(N.mapnik_image_apply_opacity(ptr(), opacity));
        return this;
    }

    /** Make pixels of this colour transparent. Pixels that only partly match become partly transparent. */
    public Image colorToAlpha(Color color) {
        Mapnik.check(N.mapnik_image_color_to_alpha(ptr(), color.toStyleString()));
        return this;
    }

    // ------------------------------------------------------------------ probing

    /** The size and format of an encoded image, read without decoding the pixels. */
    public static final class Info {
        private final int width;
        private final int height;
        private final String format;

        Info(int width, int height, String format) {
            this.width = width;
            this.height = height;
            this.format = format;
        }

        public int width() { return width; }
        public int height() { return height; }

        /** {@code png}, {@code jpeg}, {@code tiff}, {@code webp}, or {@code unknown}. */
        public String format() { return format; }

        @Override
        public String toString() {
            return "Info[" + width + "x" + height + " " + format + "]";
        }
    }

    /** Read the size and format of an image file without decoding it. */
    public static Info probe(Path file) {
        int[] w = new int[1];
        int[] h = new int[1];
        Mapnik.check(N.mapnik_image_probe_file(file.toString(), w, h));
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            byte[] head = new byte[16];
            int n = in.read(head);
            return new Info(w[0], h[0], format(head, Math.max(n, 0)));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Read the size and format of encoded image data without decoding it. */
    public static Info probe(byte[] data) {
        int[] w = new int[1];
        int[] h = new int[1];
        Mapnik.check(N.mapnik_image_probe_bytes(data, data.length, w, h));
        return new Info(w[0], h[0], format(data, Math.min(data.length, 16)));
    }

    private static String format(byte[] b, int n) {
        if (n >= 4 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "png";
        }
        if (n >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        if (n >= 4 && ((b[0] == 'I' && b[1] == 'I' && b[2] == '*' && b[3] == 0)
            || (b[0] == 'M' && b[1] == 'M' && b[2] == 0 && b[3] == '*'))) {
            return "tiff";
        }
        if (n >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
            && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return "unknown";
    }

    /** True if every pixel has the same value. */
    public boolean isSolid() { return N.mapnik_image_is_solid(ptr()) == 1; }

    /** All pixels as {@code 0xAARRGGBB}, row by row from the top left. */
    public int[] toArgb() {
        int w = width();
        int h = height();
        byte[] rgba = new byte[Math.multiplyExact(Math.multiplyExact(w, h), 4)];
        N.mapnik_image_copy_rgba(ptr(), rgba);
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            int r = rgba[4 * i] & 0xFF;
            int g = rgba[4 * i + 1] & 0xFF;
            int b = rgba[4 * i + 2] & 0xFF;
            int a = rgba[4 * i + 3] & 0xFF;
            out[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return out;
    }

    /**
     * Encode the image. {@code format} is {@code png}, {@code jpeg}, {@code webp} or {@code tiff},
     * optionally with options, such as {@code png8}, {@code png256}, {@code jpeg90}, {@code png:z=9}.
     */
    public byte[] toBytes(String format) {
        PointerByReference out = new PointerByReference();
        IntByReference len = new IntByReference();
        Mapnik.check(N.mapnik_image_save_to_buffer(ptr(), format, out, len));
        Pointer buf = out.getValue();
        try {
            return buf.getByteArray(0, len.getValue());
        } finally {
            N.mapnik_buffer_free(buf);
        }
    }

    public byte[] toPng() {
        return toBytes("png");
    }

    public void save(Path file, String format) {
        Mapnik.check(N.mapnik_image_save(ptr(), file.toString(), format));
    }

    // ARGB in Java, RGBA packed little-endian (red in the low byte) in Mapnik.
    private static int toNative(int argb) {
        int a = argb >>> 24;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private static int fromNative(int rgba) {
        int a = rgba >>> 24;
        int b = (rgba >> 16) & 0xFF;
        int g = (rgba >> 8) & 0xFF;
        int r = rgba & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private void checkBounds(int x, int y) {
        if (x < 0 || y < 0 || x >= width() || y >= height()) {
            throw new IndexOutOfBoundsException("pixel (" + x + ", " + y + ") outside " + width() + "x" + height());
        }
    }

    Pointer ptr() {
        if (handle == null) {
            throw new IllegalStateException("image closed");
        }
        return handle;
    }

    @Override
    public void close() {
        if (handle != null) {
            N.mapnik_image_free(handle);
            handle = null;
        }
    }
}
