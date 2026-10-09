package dev.avelar.mapnik;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * An image format with its options, built in code instead of written as a string. Mapnik takes a format as text,
 * such as {@code png8:c=64:m=h}; this builds that text and checks every value in the range Mapnik accepts, so a
 * typo is an {@link IllegalArgumentException} at once instead of an error from deep inside the encoder.
 *
 * <pre>{@code
 * byte[] png = map.renderToBytes(ImageFormat.png8().colors(64).quantizer(ImageFormat.Quantizer.HEXTREE));
 * byte[] jpg = map.renderToBytes(ImageFormat.jpeg(85));
 * byte[] webp = map.renderToBytes(ImageFormat.webp().lossless());
 * }</pre>
 *
 * <p>The plain strings still work wherever a format is taken. {@link #option} sets any option Mapnik has that this
 * class has no method for.
 */
public final class ImageFormat {
    /** How PNG8 chooses its palette. */
    public enum Quantizer {
        /** The octree algorithm, Mapnik's older one. */
        OCTREE("o"),
        /** The hextree algorithm, Mapnik's default: usually better colours for the same palette size. */
        HEXTREE("h");

        private final String code;

        Quantizer(String code) {
            this.code = code;
        }
    }

    /** What PNG8 does about the alpha channel. */
    public enum Transparency {
        /** No transparency: alpha is dropped. */
        NONE(0),
        /** Pixels are either fully transparent or opaque: smaller, and works where partial transparency does not. */
        BINARY(1),
        /** Keep several levels of alpha in the palette (Mapnik's default). */
        ALPHA_LEVELS(2);

        private final int code;

        Transparency(int code) {
            this.code = code;
        }
    }

    /** The zlib strategy of a PNG. */
    public enum PngStrategy {
        DEFAULT("default"), FILTERED("filtered"), HUFFMAN_ONLY("huff"), RLE("rle"), FIXED("fixed");

        private final String code;

        PngStrategy(String code) {
            this.code = code;
        }
    }

    /** How TIFF data is compressed. */
    public enum TiffCompression {
        NONE("none"), LZW("lzw"), DEFLATE("deflate"), ADOBE_DEFLATE("adobedeflate");

        private final String code;

        TiffCompression(String code) {
            this.code = code;
        }
    }

    /** How a TIFF is laid out in the file. */
    public enum TiffLayout {
        SCANLINE("scanline"), STRIPPED("stripped"), TILED("tiled");

        private final String code;

        TiffLayout(String code) {
            this.code = code;
        }
    }

    private final String name;
    private final Map<String, String> options = new LinkedHashMap<>();

    private ImageFormat(String name) {
        this.name = name;
    }

    // ------------------------------------------------------------------ PNG

    /** True-colour PNG with an alpha channel: exact, and larger than {@link #png8()}. */
    public static ImageFormat png() {
        return new ImageFormat("png");
    }

    /** PNG with a palette of at most 256 colours: much smaller, and fine for maps with flat colours. */
    public static ImageFormat png8() {
        return new ImageFormat("png8");
    }

    /** PNG8: the number of colours in the palette, from 1 to 256. */
    public ImageFormat colors(int count) {
        requirePalette("colors");
        if (count < 1 || count > 256) {
            throw new IllegalArgumentException("a PNG8 palette has 1 to 256 colours: " + count);
        }
        return option("c", Integer.toString(count));
    }

    /** PNG8: the algorithm that picks the palette. */
    public ImageFormat quantizer(Quantizer quantizer) {
        requirePalette("quantizer");
        return option("m", quantizer.code);
    }

    /** PNG8: what to do with transparency. */
    public ImageFormat transparency(Transparency mode) {
        requirePalette("transparency");
        return option("t", Integer.toString(mode.code));
    }

    /** PNG8: the gamma the colours are matched in, 0 or more. Left out, no gamma correction is applied. */
    public ImageFormat gamma(double gamma) {
        requirePalette("gamma");
        if (!(gamma >= 0)) {
            throw new IllegalArgumentException("gamma must be 0 or more: " + gamma);
        }
        return option("g", Xml.number(gamma));
    }

    /** PNG and PNG8: zlib compression level, from -1 (zlib's default) or 0 (none) to 9 (smallest, slowest). */
    public ImageFormat compression(int level) {
        if (!name.startsWith("png")) {
            throw new IllegalStateException("compression levels are for PNG; use zlevel for TIFF");
        }
        if (level < -1 || level > 9) {
            throw new IllegalArgumentException("a zlib compression level is -1 to 9: " + level);
        }
        return option("z", Integer.toString(level));
    }

    /** PNG and PNG8: the zlib strategy. */
    public ImageFormat strategy(PngStrategy strategy) {
        if (!name.startsWith("png")) {
            throw new IllegalStateException("a strategy is for PNG");
        }
        return option("s", strategy.code);
    }

    private void requirePalette(String what) {
        if (!name.equals("png8")) {
            throw new IllegalStateException(what + " is for PNG8 (a palette); this is " + name);
        }
    }

    // ------------------------------------------------------------------ JPEG

    /** JPEG at a quality from 0 (smallest) to 100 (best). JPEG has no transparency. */
    public static ImageFormat jpeg(int quality) {
        if (quality < 0 || quality > 100) {
            throw new IllegalArgumentException("JPEG quality is 0 to 100: " + quality);
        }
        return new ImageFormat("jpeg").option("quality", Integer.toString(quality));
    }

    // ------------------------------------------------------------------ WebP

    /** WebP, lossy at quality 90 unless you say otherwise. */
    public static ImageFormat webp() {
        return new ImageFormat("webp");
    }

    /** WebP: quality from 0 to 100. Lossy unless {@link #lossless()}. */
    public ImageFormat quality(double quality) {
        requireName("webp", "quality (for JPEG give it to jpeg())");
        if (!(quality >= 0 && quality <= 100)) {
            throw new IllegalArgumentException("WebP quality is 0 to 100: " + quality);
        }
        return option("quality", Xml.number(quality));
    }

    /** WebP: store the pixels exactly. */
    public ImageFormat lossless() {
        requireName("webp", "lossless");
        return option("lossless", "1");
    }

    /** WebP: how hard the encoder works, from 0 (fast) to 6 (smallest). */
    public ImageFormat effort(int method) {
        requireName("webp", "effort");
        if (method < 0 || method > 6) {
            throw new IllegalArgumentException("WebP effort is 0 to 6: " + method);
        }
        return option("method", Integer.toString(method));
    }

    /** WebP: keep (true) or drop (false) the alpha channel. */
    public ImageFormat alpha(boolean keep) {
        requireName("webp", "alpha");
        return option("alpha", keep ? "true" : "false");
    }

    // ------------------------------------------------------------------ TIFF

    /** TIFF, uncompressed unless you say otherwise. */
    public static ImageFormat tiff() {
        return new ImageFormat("tiff");
    }

    public ImageFormat compression(TiffCompression compression) {
        requireName("tiff", "a compression");
        return option("compression", compression.code);
    }

    public ImageFormat layout(TiffLayout layout) {
        requireName("tiff", "a layout");
        return option("method", layout.code);
    }

    /** TIFF with deflate compression: the zlib level, from 0 to 9. */
    public ImageFormat zlevel(int level) {
        requireName("tiff", "zlevel");
        if (level < 0 || level > 9) {
            throw new IllegalArgumentException("a TIFF zlevel is 0 to 9: " + level);
        }
        return option("zlevel", Integer.toString(level));
    }

    /** TIFF, stripped layout: rows in each strip. */
    public ImageFormat rowsPerStrip(int rows) {
        requireName("tiff", "rowsPerStrip");
        if (rows < 0) {
            throw new IllegalArgumentException("rows per strip must not be negative: " + rows);
        }
        return option("rows_per_strip", Integer.toString(rows));
    }

    private void requireName(String required, String what) {
        if (!name.equals(required)) {
            throw new IllegalStateException(what + " is for " + required.toUpperCase(Locale.ROOT) + "; this is " + name);
        }
    }

    // ------------------------------------------------------------------ any other option

    /**
     * Set an option this class has no method for, by Mapnik's own name, such as {@code image_hint} for WebP. The value
     * is passed as it is, and Mapnik rejects what it does not accept when the image is encoded.
     */
    public ImageFormat option(String key, String value) {
        if (!key.matches("[A-Za-z_][A-Za-z0-9_]*") || !value.matches("[A-Za-z0-9_.+-]+")) {
            throw new IllegalArgumentException("not a valid format option: " + key + "=" + value);
        }
        options.put(key, value);
        return this;
    }

    /** The format as Mapnik writes it, such as {@code png8:c=64:m=h}. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(name);
        for (Map.Entry<String, String> e : options.entrySet()) {
            sb.append(':').append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
