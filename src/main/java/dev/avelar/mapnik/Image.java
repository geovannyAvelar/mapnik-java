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
