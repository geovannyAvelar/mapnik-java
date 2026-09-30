package dev.avelar.mapnik;

/** Options for rendering a map. Immutable: each method returns a changed copy. */
public final class RenderOptions {
    private static final RenderOptions DEFAULTS = new RenderOptions(1.0, 0, 0);

    private final double scaleFactor;
    private final int offsetX;
    private final int offsetY;

    private RenderOptions(double scaleFactor, int offsetX, int offsetY) {
        this.scaleFactor = scaleFactor;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    /** Scale factor 1, no offset. */
    public static RenderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Scale line widths, symbols and text by this factor without changing the image size: 1.0 is
     * normal, 2.0 suits an image rendered at twice the pixel size for a high-dpi screen. Must be above 0.
     */
    public RenderOptions scaleFactor(double factor) {
        if (!(factor > 0)) {
            throw new IllegalArgumentException("scale factor must be above 0: " + factor);
        }
        return new RenderOptions(factor, offsetX, offsetY);
    }

    /**
     * Treat the image as a window that starts at pixel (x, y) of a larger map image, so the drawing
     * moves left by x and up by y. Useful for rendering a big map in pieces. Not used for PDF, SVG and
     * PS output.
     */
    public RenderOptions offset(int x, int y) {
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException("offset must not be negative: " + x + ", " + y);
        }
        return new RenderOptions(scaleFactor, x, y);
    }

    public double scaleFactor() { return scaleFactor; }
    public int offsetX() { return offsetX; }
    public int offsetY() { return offsetY; }

    @Override
    public String toString() {
        return "RenderOptions[scaleFactor=" + scaleFactor + ", offset=" + offsetX + "," + offsetY + "]";
    }
}
