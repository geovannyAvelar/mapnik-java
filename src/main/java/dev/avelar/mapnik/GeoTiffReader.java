package dev.avelar.mapnik;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Reads the first band of a classic TIFF or GeoTIFF into a {@link RasterGrid}. See that class for what is supported. */
final class GeoTiffReader {
    private static final int T_WIDTH = 256, T_HEIGHT = 257, T_BITS = 258, T_COMPRESSION = 259, T_PHOTOMETRIC = 262,
        T_STRIP_OFFSETS = 273, T_SAMPLES = 277, T_ROWS_PER_STRIP = 278, T_STRIP_COUNTS = 279, T_PLANAR = 284,
        T_PREDICTOR = 317, T_TILE_WIDTH = 322, T_TILE_LENGTH = 323, T_TILE_OFFSETS = 324, T_TILE_COUNTS = 325,
        T_SAMPLE_FORMAT = 339, T_PIXEL_SCALE = 33550, T_TIEPOINT = 33922, T_TRANSFORM = 34264,
        T_GEOKEYS = 34735, T_GEODOUBLES = 34736, T_NODATA = 42113;

    private final ByteBuffer buf;
    private final Map<Integer, Entry> tags = new HashMap<>();

    private static final class Entry {
        final int type;
        final long count;
        final int valueOffset;   // where the values start in the file

        Entry(int type, long count, int valueOffset) {
            this.type = type;
            this.count = count;
            this.valueOffset = valueOffset;
        }
    }

    private GeoTiffReader(byte[] data) {
        buf = ByteBuffer.wrap(data);
    }

    static RasterGrid read(byte[] data) {
        try {
            return new GeoTiffReader(data).readGrid();
        } catch (java.nio.BufferUnderflowException | IndexOutOfBoundsException | NegativeArraySizeException e) {
            throw new IllegalArgumentException("the TIFF is truncated or damaged", e);
        }
    }

    private static IllegalArgumentException bad(String message) {
        return new IllegalArgumentException(message);
    }

    // ------------------------------------------------------------------ directory

    private int sizeOf(int type) {
        switch (type) {
            case 1: case 2: case 6: case 7: return 1;
            case 3: case 8: return 2;
            case 4: case 9: case 11: return 4;
            case 5: case 10: case 12: return 8;
            default: return 0;
        }
    }

    private void readDirectory() {
        if (buf.capacity() < 8) {
            throw bad("not a TIFF file: too short");
        }
        int b0 = buf.get(0);
        int b1 = buf.get(1);
        if (b0 == 'I' && b1 == 'I') {
            buf.order(ByteOrder.LITTLE_ENDIAN);
        } else if (b0 == 'M' && b1 == 'M') {
            buf.order(ByteOrder.BIG_ENDIAN);
        } else {
            throw bad("not a TIFF file: bad byte order mark");
        }
        int magic = buf.getShort(2) & 0xFFFF;
        if (magic == 43) {
            throw bad("BigTIFF is not supported; convert the file to a classic TIFF");
        }
        if (magic != 42) {
            throw bad("not a TIFF file: bad magic number " + magic);
        }
        long ifd = buf.getInt(4) & 0xFFFFFFFFL;
        if (ifd < 8 || ifd + 2 > buf.capacity()) {
            throw bad("the TIFF directory is outside the file");
        }
        int n = buf.getShort((int) ifd) & 0xFFFF;
        if (ifd + 2 + 12L * n > buf.capacity()) {
            throw bad("the TIFF directory is truncated");
        }
        for (int i = 0; i < n; i++) {
            int at = (int) ifd + 2 + 12 * i;
            int tag = buf.getShort(at) & 0xFFFF;
            int type = buf.getShort(at + 2) & 0xFFFF;
            long count = buf.getInt(at + 4) & 0xFFFFFFFFL;
            int size = sizeOf(type);
            if (size == 0) {
                continue;   // a type we do not know: ignore the tag
            }
            long bytes = size * count;
            long where = bytes <= 4 ? at + 8 : (buf.getInt(at + 8) & 0xFFFFFFFFL);
            if (where + bytes > buf.capacity()) {
                throw bad("TIFF tag " + tag + " points outside the file");
            }
            tags.put(tag, new Entry(type, count, (int) where));
        }
    }

    private boolean has(int tag) {
        return tags.containsKey(tag);
    }

    private long num(int tag, int index, long fallback) {
        Entry e = tags.get(tag);
        if (e == null || index >= e.count) {
            return fallback;
        }
        int at = e.valueOffset + index * sizeOf(e.type);
        switch (e.type) {
            case 1: case 7: return buf.get(at) & 0xFF;
            case 6: return buf.get(at);
            case 3: return buf.getShort(at) & 0xFFFF;
            case 8: return buf.getShort(at);
            case 4: return buf.getInt(at) & 0xFFFFFFFFL;
            case 9: return buf.getInt(at);
            default: throw bad("TIFF tag " + tag + " has an unexpected type " + e.type);
        }
    }

    private double dbl(int tag, int index) {
        Entry e = tags.get(tag);
        int at = e.valueOffset + index * sizeOf(e.type);
        switch (e.type) {
            case 12: return buf.getDouble(at);
            case 11: return buf.getFloat(at);
            default: return num(tag, index, 0);
        }
    }

    private String ascii(int tag) {
        Entry e = tags.get(tag);
        if (e == null || e.type != 2) {
            return null;
        }
        byte[] b = new byte[(int) e.count];
        for (int i = 0; i < b.length; i++) {
            b[i] = buf.get(e.valueOffset + i);
        }
        int end = b.length;
        while (end > 0 && b[end - 1] == 0) {
            end--;
        }
        return new String(b, 0, end, java.nio.charset.StandardCharsets.US_ASCII).trim();
    }

    // ------------------------------------------------------------------ pixels

    private RasterGrid readGrid() {
        readDirectory();
        long w = num(T_WIDTH, 0, 0);
        long h = num(T_HEIGHT, 0, 0);
        if (w <= 0 || h <= 0) {
            throw bad("the TIFF has no image size");
        }
        if (w * h > RasterGrid.MAX_PIXELS) {
            throw bad("the TIFF has " + w * h + " pixels; the limit is " + RasterGrid.MAX_PIXELS);
        }
        int width = (int) w;
        int height = (int) h;
        int spp = (int) num(T_SAMPLES, 0, 1);
        int bits = (int) num(T_BITS, 0, 1);
        int format = (int) num(T_SAMPLE_FORMAT, 0, 1);
        int compression = (int) num(T_COMPRESSION, 0, 1);
        int predictor = (int) num(T_PREDICTOR, 0, 1);
        int planar = (int) num(T_PLANAR, 0, 1);
        if (spp < 1 || spp > 64) {
            throw bad("unsupported number of samples per pixel: " + spp);
        }
        for (int i = 1; i < spp; i++) {
            if (num(T_BITS, i, bits) != bits) {
                throw bad("bands with different bit depths are not supported");
            }
        }
        if (bits != 8 && bits != 16 && bits != 32 && bits != 64) {
            throw bad("unsupported bits per sample: " + bits + " (8, 16, 32 and 64 are read)");
        }
        if (format != 1 && format != 2 && format != 3) {
            throw bad("unsupported sample format: " + format);
        }
        if (format == 3 && bits != 32 && bits != 64) {
            throw bad("floating point samples must be 32 or 64 bits");
        }
        if (bits == 64 && format != 3) {
            throw bad("64-bit integer samples are not supported");
        }
        if (planar != 1 && planar != 2) {
            throw bad("unsupported planar configuration: " + planar);
        }
        if (predictor != 1 && predictor != 2 && predictor != 3) {
            throw bad("unsupported predictor: " + predictor);
        }
        if (predictor == 3 && format != 3) {
            throw bad("the floating point predictor is only for floating point samples");
        }
        if (compression != 1 && compression != 5 && compression != 8 && compression != 32946 && compression != 32773) {
            throw bad("unsupported TIFF compression " + compression + " (none, LZW, deflate and PackBits are read)");
        }
        int bps = bits / 8;
        boolean tiled = has(T_TILE_OFFSETS);
        int tileW;
        int tileH;
        long[] offsets;
        long[] counts;
        if (tiled) {
            tileW = (int) num(T_TILE_WIDTH, 0, 0);
            tileH = (int) num(T_TILE_LENGTH, 0, 0);
            if (tileW <= 0 || tileH <= 0) {
                throw bad("the TIFF has tiles without a size");
            }
            offsets = array(T_TILE_OFFSETS);
            counts = array(T_TILE_COUNTS);
        } else {
            tileW = width;
            tileH = (int) Math.min(num(T_ROWS_PER_STRIP, 0, height), height);
            if (tileH <= 0) {
                tileH = height;
            }
            offsets = array(T_STRIP_OFFSETS);
            counts = array(T_STRIP_COUNTS);
        }
        if (offsets.length == 0 || offsets.length != counts.length) {
            throw bad("the TIFF has no usable image data");
        }
        int across = (width + tileW - 1) / tileW;
        int down = (height + tileH - 1) / tileH;
        long perBand = (long) across * down;
        if (planar == 1 ? offsets.length < perBand : offsets.length < perBand * spp) {
            throw bad("the TIFF lists fewer image chunks than it needs");
        }
        int samplesInPixel = planar == 1 ? spp : 1;

        double[] out = new double[width * height];
        for (int ty = 0; ty < down; ty++) {
            for (int tx = 0; tx < across; tx++) {
                int index = ty * across + tx;           // band 0 is the first run of chunks in both layouts
                int rows = tiled ? tileH : Math.min(tileH, height - ty * tileH);
                int rowBytes = tileW * samplesInPixel * bps;
                long expected = (long) rows * rowBytes;
                if (expected > (1L << 28)) {
                    throw bad("a TIFF chunk is too large");
                }
                byte[] raw = chunk(offsets[index], counts[index], compression, (int) expected);
                undoPredictor(raw, predictor, rows, tileW * samplesInPixel, samplesInPixel, bps);
                int x0 = tx * tileW;
                int y0 = ty * tileH;
                int usableW = Math.min(tileW, width - x0);
                int usableRows = Math.min(rows, height - y0);
                ByteBuffer c = ByteBuffer.wrap(raw).order(buf.order());
                for (int r = 0; r < usableRows; r++) {
                    for (int x = 0; x < usableW; x++) {
                        int at = r * rowBytes + x * samplesInPixel * bps;   // first band
                        out[(y0 + r) * width + x0 + x] = sample(c, at, bits, format);
                    }
                }
            }
        }

        Double nodata = null;
        String nd = ascii(T_NODATA);
        if (nd != null && !nd.isEmpty()) {
            try {
                nodata = Double.parseDouble(nd);
            } catch (NumberFormatException e) {
                // a nodata tag we cannot read: treat the grid as having none
            }
        }
        return new RasterGrid(width, height, out, nodata, extent(width, height), srs(), type(bits, format));
    }

    private long[] array(int tag) {
        Entry e = tags.get(tag);
        if (e == null) {
            return new long[0];
        }
        if (e.count > 50_000_000L) {
            throw bad("the TIFF lists too many chunks");
        }
        long[] out = new long[(int) e.count];
        for (int i = 0; i < out.length; i++) {
            out[i] = num(tag, i, 0);
        }
        return out;
    }

    private static GrayImage.Type type(int bits, int format) {
        if (format == 3) {
            return bits == 32 ? GrayImage.Type.FLOAT32 : GrayImage.Type.FLOAT64;
        }
        if (format == 2) {
            return bits == 8 ? GrayImage.Type.INT8 : bits == 16 ? GrayImage.Type.INT16 : GrayImage.Type.INT32;
        }
        return bits == 8 ? GrayImage.Type.UINT8 : bits == 16 ? GrayImage.Type.UINT16 : GrayImage.Type.UINT32;
    }

    private static double sample(ByteBuffer b, int at, int bits, int format) {
        switch (bits) {
            case 8: return format == 2 ? b.get(at) : b.get(at) & 0xFF;
            case 16: return format == 2 ? b.getShort(at) : b.getShort(at) & 0xFFFF;
            case 32:
                if (format == 3) {
                    return b.getFloat(at);
                }
                return format == 2 ? b.getInt(at) : b.getInt(at) & 0xFFFFFFFFL;
            default: return b.getDouble(at);
        }
    }

    private byte[] chunk(long offset, long count, int compression, int expected) {
        if (offset < 0 || count < 0 || offset + count > buf.capacity()) {
            throw bad("a TIFF chunk lies outside the file");
        }
        byte[] in = new byte[(int) count];
        for (int i = 0; i < in.length; i++) {
            in[i] = buf.get((int) offset + i);
        }
        byte[] out;
        switch (compression) {
            case 1: out = in; break;
            case 5: out = Lzw.decode(in, expected); break;
            case 8: case 32946: out = inflate(in, expected); break;
            default: out = packBits(in, expected); break;
        }
        if (out.length < expected) {
            // the last chunk of a file is sometimes cut short: pad rather than fail
            if (out.length == 0) {
                throw bad("a TIFF chunk is empty or damaged");
            }
            out = java.util.Arrays.copyOf(out, expected);
        }
        return out;
    }

    private static byte[] inflate(byte[] in, int expected) {
        Inflater inflater = new Inflater();
        inflater.setInput(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream(expected);
        byte[] tmp = new byte[8192];
        try {
            while (!inflater.finished() && out.size() < expected) {
                int n = inflater.inflate(tmp);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                out.write(tmp, 0, Math.min(n, expected - out.size()));
            }
        } catch (DataFormatException e) {
            throw bad("a TIFF chunk is not valid deflate data: " + e.getMessage());
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    private static byte[] packBits(byte[] in, int expected) {
        byte[] out = new byte[expected];
        int o = 0;
        int i = 0;
        while (i < in.length && o < expected) {
            int n = in[i++];
            if (n >= 0) {
                int len = n + 1;
                if (i + len > in.length) {
                    len = in.length - i;
                }
                len = Math.min(len, expected - o);
                System.arraycopy(in, i, out, o, len);
                i += n + 1;
                o += len;
            } else if (n != -128) {
                if (i >= in.length) {
                    break;
                }
                int len = Math.min(1 - n, expected - o);
                java.util.Arrays.fill(out, o, o + len, in[i++]);
                o += len;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ predictors

    private void undoPredictor(byte[] data, int predictor, int rows, int samplesPerRow, int stride, int bps) {
        if (predictor == 1) {
            return;
        }
        int rowBytes = samplesPerRow * bps;
        if (predictor == 2) {
            ByteBuffer b = ByteBuffer.wrap(data).order(buf.order());
            for (int r = 0; r < rows; r++) {
                int base = r * rowBytes;
                for (int i = stride; i < samplesPerRow; i++) {
                    int at = base + i * bps;
                    int prev = base + (i - stride) * bps;
                    switch (bps) {
                        case 1: data[at] = (byte) (data[at] + data[prev]); break;
                        case 2: b.putShort(at, (short) (b.getShort(at) + b.getShort(prev))); break;
                        case 4: b.putInt(at, b.getInt(at) + b.getInt(prev)); break;
                        default: throw bad("the horizontal predictor is not supported for " + bps * 8 + "-bit samples");
                    }
                }
            }
            return;
        }
        // predictor 3: bytes of each row were split into planes (most significant first) and differenced
        byte[] tmp = new byte[rowBytes];
        for (int r = 0; r < rows; r++) {
            int base = r * rowBytes;
            for (int i = stride; i < rowBytes; i++) {
                data[base + i] = (byte) (data[base + i] + data[base + i - stride]);
            }
            System.arraycopy(data, base, tmp, 0, rowBytes);
            for (int s = 0; s < samplesPerRow; s++) {
                for (int k = 0; k < bps; k++) {
                    int source = tmp[k * samplesPerRow + s] & 0xFF;
                    int target = base + s * bps + (buf.order() == ByteOrder.BIG_ENDIAN ? k : bps - 1 - k);
                    data[target] = (byte) source;
                }
            }
        }
    }

    // ------------------------------------------------------------------ georeferencing

    private Box2d extent(int width, int height) {
        double x0;
        double y0;
        double sx;
        double sy;
        if (has(T_TRANSFORM) && tags.get(T_TRANSFORM).count >= 16) {
            // a north-up affine transform: x = a*i + c, y = e*j + f
            if (dbl(T_TRANSFORM, 1) != 0 || dbl(T_TRANSFORM, 4) != 0) {
                return null;   // rotated: no simple extent
            }
            sx = dbl(T_TRANSFORM, 0);
            sy = -dbl(T_TRANSFORM, 5);
            x0 = dbl(T_TRANSFORM, 3);
            y0 = dbl(T_TRANSFORM, 7);
        } else if (has(T_PIXEL_SCALE) && has(T_TIEPOINT) && tags.get(T_PIXEL_SCALE).count >= 2
            && tags.get(T_TIEPOINT).count >= 6) {
            sx = dbl(T_PIXEL_SCALE, 0);
            sy = dbl(T_PIXEL_SCALE, 1);
            x0 = dbl(T_TIEPOINT, 3) - dbl(T_TIEPOINT, 0) * sx;
            y0 = dbl(T_TIEPOINT, 4) + dbl(T_TIEPOINT, 1) * sy;
        } else {
            return null;
        }
        if (!(sx > 0) || !(sy > 0)) {
            return null;
        }
        if (geoKey(1025) == 2) {   // GTRasterTypeGeoKey: pixel is point; the tiepoint is the pixel centre
            x0 -= sx / 2;
            y0 += sy / 2;
        }
        return new Box2d(x0, y0 - height * sy, x0 + width * sx, y0);
    }

    private String srs() {
        long projected = geoKey(3072);
        if (projected > 0 && projected != 32767) {
            return "epsg:" + projected;
        }
        long geographic = geoKey(2048);
        if (geographic > 0 && geographic != 32767) {
            return "epsg:" + geographic;
        }
        return null;
    }

    /** The value of a short-valued GeoKey, or -1 if absent or not stored inline. */
    private long geoKey(int key) {
        Entry e = tags.get(T_GEOKEYS);
        if (e == null || e.count < 4) {
            return -1;
        }
        int n = (int) num(T_GEOKEYS, 3, 0);
        for (int i = 0; i < n && 4 + 4 * i + 3 < e.count; i++) {
            if (num(T_GEOKEYS, 4 + 4 * i, -1) == key) {
                return num(T_GEOKEYS, 4 + 4 * i + 1, 0) == 0 ? num(T_GEOKEYS, 4 + 4 * i + 3, -1) : -1;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ LZW

    /** TIFF's LZW: codes of 9 to 12 bits, most significant bit first, with the "early change" width rule. */
    static final class Lzw {
        private Lzw() {}

        static byte[] decode(byte[] in, int expected) {
            byte[] out = new byte[expected];
            int o = 0;
            int[] prefix = new int[4096];
            byte[] suffix = new byte[4096];
            int[] length = new int[4096];
            for (int i = 0; i < 256; i++) {
                suffix[i] = (byte) i;
                length[i] = 1;
                prefix[i] = -1;
            }
            int next = 258;
            int width = 9;
            int old = -1;
            long bitBuf = 0;
            int bitCount = 0;
            int pos = 0;
            while (o < expected) {
                while (bitCount < width && pos < in.length) {
                    bitBuf = (bitBuf << 8) | (in[pos++] & 0xFF);
                    bitCount += 8;
                }
                if (bitCount < width) {
                    break;
                }
                int code = (int) ((bitBuf >> (bitCount - width)) & ((1 << width) - 1));
                bitCount -= width;
                if (code == 257) {
                    break;                   // end of information
                }
                if (code == 256) {           // clear
                    next = 258;
                    width = 9;
                    old = -1;
                    continue;
                }
                if (code > next || (code == next && old < 0)) {
                    throw new IllegalArgumentException("a TIFF chunk is not valid LZW data");
                }
                // code == next is the KwKwK case: the old string plus its own first byte
                int len = code < next ? length[code] : length[old] + 1;
                int first;
                if (code < next) {
                    first = writeString(out, o, code, prefix, suffix, length);
                } else {
                    writeString(out, o, old, prefix, suffix, length);
                    first = out[o] & 0xFF;
                    if (o + len - 1 < expected) {
                        out[o + len - 1] = (byte) first;
                    }
                }
                if (old >= 0 && next < 4096) {
                    prefix[next] = old;
                    suffix[next] = (byte) first;
                    length[next] = length[old] + 1;
                    next++;
                }
                o += Math.min(len, expected - o);
                old = code;
                if (next + 1 >= (1 << width) && width < 12) {
                    width++;
                }
            }
            return java.util.Arrays.copyOf(out, o);
        }

        /** Write the string for {@code code} at {@code at}; returns its first byte. Cut off at the buffer end. */
        private static int writeString(byte[] out, int at, int code, int[] prefix, byte[] suffix, int[] length) {
            int len = length[code];
            int c = code;
            for (int i = len - 1; i >= 0; i--) {
                if (at + i < out.length) {
                    out[at + i] = suffix[c];
                }
                c = prefix[c];
            }
            return out[at] & 0xFF;
        }
    }
}
