package dev.avelar.mapnik;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

/** Writes small TIFF and GeoTIFF files for tests: every encoding {@link RasterGrid} reads. */
final class TiffBuilder {
    int width, height;
    int bits = 8;
    int format = 1;            // 1 unsigned, 2 signed, 3 float
    int compression = 1;       // 1 none, 5 LZW, 8 deflate, 32773 PackBits
    int predictor = 1;
    boolean tiled;
    int tileW = 16, tileH = 16, rowsPerStrip = 8;
    boolean bigEndian;
    int bands = 1;
    int planar = 1;
    double[] origin;           // x, y of the top left corner
    double[] pixelSize;        // sx, sy
    boolean pixelIsPoint;
    int epsg = -1;
    boolean geographic;
    String nodata;
    double[] values;

    private final List<int[]> entries = new ArrayList<>();      // tag, type, count, value-or-offset
    private final Map<Integer, byte[]> blobs = new HashMap<>();

    TiffBuilder(int width, int height, double[] values) {
        this.width = width;
        this.height = height;
        this.values = values;
    }

    byte[] build() {
        ByteOrder order = bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        int bps = bits / 8;
        // chunk geometry
        int cw = tiled ? tileW : width;
        int ch = tiled ? tileH : rowsPerStrip;
        int across = (width + cw - 1) / cw;
        int down = (height + ch - 1) / ch;
        int bandsInChunk = planar == 1 ? bands : 1;
        List<byte[]> chunks = new ArrayList<>();
        int passes = planar == 1 ? 1 : bands;
        for (int band = 0; band < passes; band++) {
            for (int ty = 0; ty < down; ty++) {
                for (int tx = 0; tx < across; tx++) {
                    int rows = tiled ? ch : Math.min(ch, height - ty * ch);
                    byte[] raw = new byte[rows * cw * bandsInChunk * bps];
                    ByteBuffer b = ByteBuffer.wrap(raw).order(order);
                    for (int r = 0; r < rows; r++) {
                        for (int x = 0; x < cw; x++) {
                            int gx = tx * cw + x;
                            int gy = ty * ch + r;
                            boolean inside = gx < width && gy < height;
                            for (int k = 0; k < bandsInChunk; k++) {
                                int bandIndex = planar == 1 ? k : band;
                                double v = inside ? values[gy * width + gx] + bandIndex * 7 : 0;
                                put(b, (r * cw * bandsInChunk + x * bandsInChunk + k) * bps, v);
                            }
                        }
                    }
                    predict(raw, rows, cw * bandsInChunk, bandsInChunk, bps, order);
                    chunks.add(compress(raw));
                }
            }
        }
        // layout: header, chunk data, then blobs, then the directory
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        long[] offsets = new long[chunks.size()];
        long[] counts = new long[chunks.size()];
        int pos = 8;
        for (int i = 0; i < chunks.size(); i++) {
            offsets[i] = pos;
            counts[i] = chunks.get(i).length;
            body.write(chunks.get(i), 0, chunks.get(i).length);
            pos += chunks.get(i).length;
            if ((pos & 1) == 1) {
                body.write(0);
                pos++;
            }
        }
        entries.clear();
        blobs.clear();
        add(256, 4, 1, width);
        add(257, 4, 1, height);
        addShorts(258, repeat(bits, bands));
        add(259, 3, 1, compression);
        add(262, 3, 1, 1);
        addLongs(tiled ? 324 : 273, offsets);
        add(277, 3, 1, bands);
        if (!tiled) {
            add(278, 4, 1, rowsPerStrip);
            addLongs(279, counts);
        } else {
            add(322, 4, 1, tileW);
            add(323, 4, 1, tileH);
            addLongs(325, counts);
        }
        add(284, 3, 1, planar);
        if (predictor != 1) {
            add(317, 3, 1, predictor);
        }
        addShorts(339, repeat(format, bands));
        if (origin != null) {
            addDoubles(33550, new double[] {pixelSize[0], pixelSize[1], 0});
            addDoubles(33922, new double[] {0, 0, 0, origin[0], origin[1], 0});
            List<Integer> keys = new ArrayList<>();
            int count = 0;
            if (pixelIsPoint) {
                keys.add(1025); keys.add(0); keys.add(1); keys.add(2); count++;
            }
            if (epsg > 0) {
                keys.add(geographic ? 2048 : 3072); keys.add(0); keys.add(1); keys.add(epsg); count++;
            }
            int[] dir = new int[4 + keys.size()];
            dir[0] = 1; dir[1] = 1; dir[2] = 0; dir[3] = count;
            for (int i = 0; i < keys.size(); i++) {
                dir[4 + i] = keys.get(i);
            }
            addShorts(34735, dir);
        }
        if (nodata != null) {
            byte[] t = (nodata + "\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            entries.add(new int[] {42113, 2, t.length, 0});
            blobs.put(42113, t);
        }
        entries.sort((x, y) -> Integer.compare(x[0], y[0]));
        // place blobs
        int blobPos = pos;
        Map<Integer, Integer> blobAt = new HashMap<>();
        ByteArrayOutputStream blobOut = new ByteArrayOutputStream();
        for (int[] e : entries) {
            byte[] bl = blobs.get(e[0]);
            if (bl != null && bl.length > 4) {
                blobAt.put(e[0], blobPos);
                blobOut.write(bl, 0, bl.length);
                blobPos += bl.length;
                if ((blobPos & 1) == 1) {
                    blobOut.write(0);
                    blobPos++;
                }
            }
        }
        int ifdPos = blobPos;
        ByteBuffer out = ByteBuffer.allocate(ifdPos + 2 + 12 * entries.size() + 4).order(order);
        out.put((byte) (bigEndian ? 'M' : 'I')).put((byte) (bigEndian ? 'M' : 'I'));
        out.putShort((short) 42);
        out.putInt(ifdPos);
        out.put(body.toByteArray());
        out.put(blobOut.toByteArray());
        out.position(ifdPos);
        out.putShort((short) entries.size());
        for (int[] e : entries) {
            out.putShort((short) e[0]).putShort((short) e[1]).putInt(e[2]);
            byte[] bl = blobs.get(e[0]);
            if (bl != null && bl.length > 4) {
                out.putInt(blobAt.get(e[0]));
            } else if (bl != null) {
                byte[] padded = new byte[4];
                System.arraycopy(bl, 0, padded, 0, bl.length);
                out.put(padded);
            } else {
                if (e[1] == 3) {
                    out.putShort((short) e[3]).putShort((short) 0);
                } else {
                    out.putInt(e[3]);
                }
            }
        }
        out.putInt(0);
        return out.array();
    }

    // ------------------------------------------------------------------ pieces

    private static int[] repeat(int v, int n) {
        int[] a = new int[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    private void add(int tag, int type, int count, int value) {
        entries.add(new int[] {tag, type, count, value});
    }

    private byte[] pack(int type, long[] vals) {
        int size = type == 3 ? 2 : 4;
        ByteBuffer b = ByteBuffer.allocate(vals.length * size).order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        for (long v : vals) {
            if (type == 3) {
                b.putShort((short) v);
            } else {
                b.putInt((int) v);
            }
        }
        return b.array();
    }

    private void addShorts(int tag, int[] vals) {
        long[] l = new long[vals.length];
        for (int i = 0; i < l.length; i++) {
            l[i] = vals[i];
        }
        entries.add(new int[] {tag, 3, vals.length, 0});
        blobs.put(tag, pack(3, l));
    }

    private void addLongs(int tag, long[] vals) {
        entries.add(new int[] {tag, 4, vals.length, 0});
        blobs.put(tag, pack(4, vals));
    }

    private void addDoubles(int tag, double[] vals) {
        ByteBuffer b = ByteBuffer.allocate(vals.length * 8).order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        for (double v : vals) {
            b.putDouble(v);
        }
        entries.add(new int[] {tag, 12, vals.length, 0});
        blobs.put(tag, b.array());
    }

    private void put(ByteBuffer b, int at, double v) {
        switch (bits) {
            case 8: b.put(at, (byte) (long) v); break;
            case 16: b.putShort(at, (short) (long) v); break;
            case 32:
                if (format == 3) {
                    b.putFloat(at, (float) v);
                } else {
                    b.putInt(at, (int) (long) v);
                }
                break;
            default: b.putDouble(at, v);
        }
    }

    /** The forward predictor: what the reader undoes. */
    private void predict(byte[] raw, int rows, int samplesPerRow, int stride, int bps, ByteOrder order) {
        if (predictor == 1) {
            return;
        }
        int rowBytes = samplesPerRow * bps;
        ByteBuffer b = ByteBuffer.wrap(raw).order(order);
        for (int r = 0; r < rows; r++) {
            int base = r * rowBytes;
            if (predictor == 2) {
                for (int i = samplesPerRow - 1; i >= stride; i--) {
                    int at = base + i * bps;
                    int prev = base + (i - stride) * bps;
                    switch (bps) {
                        case 1: raw[at] = (byte) (raw[at] - raw[prev]); break;
                        case 2: b.putShort(at, (short) (b.getShort(at) - b.getShort(prev))); break;
                        default: b.putInt(at, b.getInt(at) - b.getInt(prev));
                    }
                }
            } else {
                byte[] tmp = new byte[rowBytes];
                for (int s = 0; s < samplesPerRow; s++) {
                    for (int k = 0; k < bps; k++) {
                        int src = base + s * bps + (order == ByteOrder.BIG_ENDIAN ? k : bps - 1 - k);
                        tmp[k * samplesPerRow + s] = raw[src];
                    }
                }
                for (int i = rowBytes - 1; i >= stride; i--) {
                    tmp[i] = (byte) (tmp[i] - tmp[i - stride]);
                }
                System.arraycopy(tmp, 0, raw, base, rowBytes);
            }
        }
    }

    private byte[] compress(byte[] raw) {
        switch (compression) {
            case 1: return raw;
            case 8: {
                Deflater d = new Deflater();
                d.setInput(raw);
                d.finish();
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] tmp = new byte[4096];
                while (!d.finished()) {
                    o.write(tmp, 0, d.deflate(tmp));
                }
                d.end();
                return o.toByteArray();
            }
            case 32773: return packBits(raw);
            case 5: return lzw(raw);
            default: throw new IllegalStateException();
        }
    }

    private static byte[] packBits(byte[] in) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int i = 0;
        while (i < in.length) {
            int run = 1;
            while (i + run < in.length && run < 128 && in[i + run] == in[i]) {
                run++;
            }
            if (run >= 2) {
                o.write(257 - run);
                o.write(in[i]);
                i += run;
            } else {
                int start = i;
                int n = 0;
                while (i < in.length && n < 128 && !(i + 1 < in.length && in[i + 1] == in[i])) {
                    i++;
                    n++;
                }
                if (n == 0) {
                    n = 1;
                    i++;
                }
                o.write(n - 1);
                o.write(in, start, n);
            }
        }
        return o.toByteArray();
    }

    /** TIFF LZW, written the way libtiff does, so the reader is tested against the real width rule. */
    private static byte[] lzw(byte[] in) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        long[] bitBuf = {0};
        int[] bitCount = {0};
        int width = 9;
        Map<String, Integer> dict = new HashMap<>();
        int next = 258;
        put(o, bitBuf, bitCount, 256, width);
        StringBuilder w = new StringBuilder();
        for (byte value : in) {
            char c = (char) (value & 0xFF);
            String wc = w.toString() + c;
            if (wc.length() == 1 || dict.containsKey(wc)) {
                w.append(c);
            } else {
                int code = w.length() == 1 ? w.charAt(0) : dict.get(w.toString());
                put(o, bitBuf, bitCount, code, width);
                dict.put(wc, next++);
                if (next > (1 << width) - 1 && width < 12) {
                    width++;
                }
                if (next == 4094) {
                    put(o, bitBuf, bitCount, 256, width);
                    dict.clear();
                    next = 258;
                    width = 9;
                }
                w.setLength(0);
                w.append(c);
            }
        }
        if (w.length() > 0) {
            int code = w.length() == 1 ? w.charAt(0) : dict.get(w.toString());
            put(o, bitBuf, bitCount, code, width);
            if (next + 1 > (1 << width) - 1 && width < 12) {
                width++;
            }
        }
        put(o, bitBuf, bitCount, 257, width);
        if (bitCount[0] > 0) {
            o.write((int) ((bitBuf[0] << (8 - bitCount[0])) & 0xFF));
        }
        return o.toByteArray();
    }

    private static void put(ByteArrayOutputStream o, long[] bitBuf, int[] bitCount, int code, int width) {
        bitBuf[0] = (bitBuf[0] << width) | code;
        bitCount[0] += width;
        while (bitCount[0] >= 8) {
            o.write((int) ((bitBuf[0] >> (bitCount[0] - 8)) & 0xFF));
            bitCount[0] -= 8;
        }
        bitBuf[0] &= (1L << bitCount[0]) - 1;
    }
}
