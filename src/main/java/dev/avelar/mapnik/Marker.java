package dev.avelar.mapnik;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What Mapnik makes of an SVG or image file used as a marker or a point symbol: whether it can read it, and how big
 * it is. Use it to check a marker before putting it in a style, for example a file a user uploaded, and to size a
 * legend entry.
 *
 * <pre>{@code
 * Marker m = Marker.inspect(Paths.get("pin.svg"));
 * m.width();   // pixels (SVG user units) the symbol takes before any scaling
 * }</pre>
 */
public final class Marker {
    /** What kind of file it is. */
    public enum Kind { SVG, RASTER }

    private final Kind kind;
    private final Box2d bounds;
    private final double declaredWidth;
    private final double declaredHeight;

    private Marker(Kind kind, Box2d bounds, double declaredWidth, double declaredHeight) {
        this.kind = kind;
        this.bounds = bounds;
        this.declaredWidth = declaredWidth;
        this.declaredHeight = declaredHeight;
    }

    /**
     * Read the file. Throws {@link MapnikException} if it is missing or is not an SVG or image Mapnik can read. With
     * {@code strict}, an SVG with a colour, path, size, style or gradient Mapnik cannot read is refused as well, instead
     * of drawn without it. Elements it does not know at all are skipped either way. The exception does not say which part
     * was wrong; Mapnik's log does (see {@link Logging}).
     */
    public static Marker inspect(Path file, boolean strict) {
        int[] kind = new int[1];
        double[] out = new double[6];
        Mapnik.check(NativeApi.INSTANCE.mapnik_marker_inspect(file.toAbsolutePath().toString(), strict ? 1 : 0, kind, out));
        return new Marker(kind[0] == 1 ? Kind.SVG : Kind.RASTER, new Box2d(out[0], out[1], out[2], out[3]), out[4], out[5]);
    }

    /** As {@link #inspect(Path, boolean)}, not strict. */
    public static Marker inspect(Path file) {
        return inspect(file, false);
    }

    /** As {@link #inspect(Path, boolean)} for SVG text held in memory. */
    public static Marker inspectSvg(String svg, boolean strict) {
        try {
            Path tmp = Files.createTempFile("mapnik-java-marker", ".svg");
            try {
                Files.write(tmp, svg.getBytes(StandardCharsets.UTF_8));
                return inspect(tmp, strict);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public Kind kind() { return kind; }

    /** The area the drawing covers, in the file's own units. */
    public Box2d bounds() { return bounds; }

    /** The width of the drawing: of what is drawn for an SVG, of the picture for an image. */
    public double width() { return bounds.maxX() - bounds.minX(); }

    public double height() { return bounds.maxY() - bounds.minY(); }

    /** The width the file declares, such as the {@code width} attribute of an SVG. It can differ from {@link #width()}. */
    public double declaredWidth() { return declaredWidth; }

    public double declaredHeight() { return declaredHeight; }

    @Override
    public String toString() {
        return "Marker[" + kind + " " + width() + "x" + height() + ", declared " + declaredWidth + "x" + declaredHeight + "]";
    }
}
