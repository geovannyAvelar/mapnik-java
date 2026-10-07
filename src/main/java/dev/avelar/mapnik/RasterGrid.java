package dev.avelar.mapnik;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * A grid of numbers over an area, such as an elevation model, read from a file without GDAL. Pure
 * Java: it needs no native library, so it works anywhere. Turn it into a {@link GrayImage} to colour
 * and draw it.
 *
 * <pre>{@code
 * RasterGrid dem = RasterGrid.read(Paths.get("dem.tif"));
 * try (GrayImage g = dem.toGrayImage()) {
 *     Image picture = ramp.colorize(g);
 * }
 * }</pre>
 *
 * <p>Supported files:
 * <ul>
 *   <li><b>Esri ASCII grid</b> ({@code .asc}): the header with {@code ncols}, {@code nrows}, the lower left
 *       corner or centre, {@code cellsize} and optional {@code NODATA_value}.</li>
 *   <li><b>GeoTIFF</b> ({@code .tif}, {@code .tiff}): classic TIFF (not BigTIFF), strips or tiles, 8, 16, 32
 *       and 64-bit unsigned, signed and floating point samples, compression none, deflate, LZW and
 *       PackBits, with the horizontal and floating-point predictors. The first band is read. The
 *       extent comes from the model tiepoint and pixel scale (or transformation) tags, and the
 *       projection from the GeoKey directory when it is an EPSG code.</li>
 * </ul>
 * Anything else (JPEG or other compression, BigTIFF) is refused with an {@link IllegalArgumentException}
 * that says so.
 */
public final class RasterGrid {
    /** The most pixels one grid may hold: more would need gigabytes of {@code double}s. */
    public static final long MAX_PIXELS = 100_000_000L;

    private final int width;
    private final int height;
    private final double[] values;
    private final Double noData;
    private final Box2d extent;
    private final String srs;
    private final GrayImage.Type suggestedType;

    RasterGrid(int width, int height, double[] values, Double noData, Box2d extent, String srs, GrayImage.Type type) {
        this.width = width;
        this.height = height;
        this.values = values;
        this.noData = noData;
        this.extent = extent;
        this.srs = srs;
        this.suggestedType = type;
    }

    public int width() { return width; }

    public int height() { return height; }

    /** Every value, row by row from the top left. This is the array itself, not a copy. */
    public double[] values() { return values; }

    /** The value that means "no data", if the file names one. */
    public OptionalDouble noData() { return noData == null ? OptionalDouble.empty() : OptionalDouble.of(noData); }

    /** The area the grid covers, in its projection, or null if the file does not say. */
    public Box2d extent() { return extent; }

    /** The projection as {@code epsg:<code>}, or null if the file has none or it is not an EPSG code. */
    public String srs() { return srs; }

    /** The pixel type that holds the file's values exactly. */
    public GrayImage.Type type() { return suggestedType; }

    /** Smallest and largest value as {@code {min, max}}, ignoring NaN and the no-data value. Throws if none. */
    public double[] range() {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            if (Double.isNaN(v) || (noData != null && v == noData)) {
                continue;
            }
            if (v < lo) {
                lo = v;
            }
            if (v > hi) {
                hi = v;
            }
        }
        if (lo > hi) {
            throw new IllegalStateException("the grid has no valid values");
        }
        return new double[] {lo, hi};
    }

    /** One value. Throws {@link IndexOutOfBoundsException} outside the grid. */
    public double get(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            throw new IndexOutOfBoundsException("(" + x + ", " + y + ") outside " + width + "x" + height);
        }
        return values[y * width + x];
    }

    /** The value under a map position, or NaN if it is outside the extent or the grid has none. */
    public double valueAt(double mapX, double mapY) {
        if (extent == null || !(mapX >= extent.minX() && mapX < extent.maxX() && mapY > extent.minY() && mapY <= extent.maxY())) {
            return Double.NaN;
        }
        int x = (int) ((mapX - extent.minX()) / (extent.maxX() - extent.minX()) * width);
        int y = (int) ((extent.maxY() - mapY) / (extent.maxY() - extent.minY()) * height);
        return values[Math.min(y, height - 1) * width + Math.min(x, width - 1)];
    }

    /** A {@link GrayImage} of the file's own pixel type. Close it. */
    public GrayImage toGrayImage() {
        return toGrayImage(suggestedType);
    }

    /**
     * A {@link GrayImage} of the given type. Values that do not fit the type (a float grid into an
     * integer image, say) are refused, not wrapped. Close it.
     */
    public GrayImage toGrayImage(GrayImage.Type type) {
        return GrayImage.of(type, width, height, values);
    }

    // ------------------------------------------------------------------ reading

    /** Read a file, choosing the format by its extension: {@code .asc}, {@code .tif} or {@code .tiff}. */
    public static RasterGrid read(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".asc")) {
            return readAscii(file);
        }
        if (name.endsWith(".tif") || name.endsWith(".tiff")) {
            return readGeoTiff(file);
        }
        throw new IllegalArgumentException("unknown raster file type (expected .asc, .tif or .tiff): " + file);
    }

    public static RasterGrid readGeoTiff(Path file) throws IOException {
        return readGeoTiff(Files.readAllBytes(file));
    }

    public static RasterGrid readGeoTiff(byte[] data) {
        return GeoTiffReader.read(data);
    }

    public static RasterGrid readAscii(Path file) throws IOException {
        return readAscii(new String(Files.readAllBytes(file), StandardCharsets.US_ASCII));
    }

    /** Read the text of an Esri ASCII grid. */
    public static RasterGrid readAscii(String text) {
        int pos = 0;
        int len = text.length();
        int ncols = -1;
        int nrows = -1;
        double xll = Double.NaN;
        double yll = Double.NaN;
        double cell = Double.NaN;
        boolean xCenter = false;
        boolean yCenter = false;
        Double nodata = null;
        // header: up to six "key value" lines, ended by the first line that starts with a number
        while (pos < len) {
            int eol = text.indexOf('\n', pos);
            if (eol < 0) {
                eol = len;
            }
            String line = text.substring(pos, eol).trim();
            if (line.isEmpty()) {
                pos = eol + 1;
                continue;
            }
            char c = line.charAt(0);
            if (!Character.isLetter(c)) {
                break;
            }
            String[] kv = line.split("\\s+");
            if (kv.length != 2) {
                throw new IllegalArgumentException("bad ASCII grid header line: " + line);
            }
            String key = kv[0].toLowerCase(Locale.ROOT);
            double val = number(kv[1], key);
            switch (key) {
                case "ncols": ncols = whole(val, key); break;
                case "nrows": nrows = whole(val, key); break;
                case "xllcorner": xll = val; break;
                case "yllcorner": yll = val; break;
                case "xllcenter": xll = val; xCenter = true; break;
                case "yllcenter": yll = val; yCenter = true; break;
                case "cellsize": cell = val; break;
                case "nodata_value": nodata = val; break;
                default: throw new IllegalArgumentException("unknown ASCII grid header key: " + kv[0]);
            }
            pos = eol + 1;
        }
        if (ncols <= 0 || nrows <= 0 || Double.isNaN(xll) || Double.isNaN(yll) || !(cell > 0)) {
            throw new IllegalArgumentException("the ASCII grid header needs ncols, nrows, xllcorner (or xllcenter), "
                + "yllcorner (or yllcenter) and a positive cellsize");
        }
        if ((long) ncols * nrows > MAX_PIXELS) {
            throw new IllegalArgumentException("the grid has " + (long) ncols * nrows + " cells; the limit is " + MAX_PIXELS);
        }
        double[] values = new double[ncols * nrows];
        int n = 0;
        while (pos < len && n < values.length) {
            while (pos < len && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
            int start = pos;
            while (pos < len && !Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
            if (start == pos) {
                break;
            }
            values[n++] = number(text.substring(start, pos), "a cell");
        }
        if (n != values.length) {
            throw new IllegalArgumentException("the ASCII grid has " + n + " values but " + values.length + " were expected");
        }
        double minX = xCenter ? xll - cell / 2 : xll;
        double minY = yCenter ? yll - cell / 2 : yll;
        Box2d extent = new Box2d(minX, minY, minX + ncols * cell, minY + nrows * cell);
        // whole numbers fit a 32-bit integer image; anything else is kept as a double so nothing is rounded
        GrayImage.Type type = GrayImage.Type.INT32;
        for (double v : values) {
            if (v != Math.rint(v) || Math.abs(v) > 2147483647.0) {
                type = GrayImage.Type.FLOAT64;
                break;
            }
        }
        return new RasterGrid(ncols, nrows, values, nodata, extent, null, type);
    }

    private static double number(String s, String what) {
        try {
            double v = Double.parseDouble(s);
            if (Double.isInfinite(v)) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number for " + what + ": " + s);
        }
    }

    private static int whole(double v, String what) {
        if (v != Math.rint(v) || v <= 0 || v > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(what + " must be a positive whole number: " + v);
        }
        return (int) v;
    }
}
