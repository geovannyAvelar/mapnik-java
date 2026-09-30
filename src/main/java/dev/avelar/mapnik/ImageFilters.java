package dev.avelar.mapnik;

import java.util.Arrays;

/**
 * Builds the text for {@link Image#filter(String...)}, the same syntax as the {@code image-filters}
 * style attribute. Filters run in the order given: {@code Image.filter(ImageFilters.blur(), ImageFilters.gray())}.
 */
public final class ImageFilters {
    private ImageFilters() {}

    public static String blur() { return "blur"; }

    /** A smoother blur; radii are in pixels. */
    public static String stackBlur(int radiusX, int radiusY) {
        return "agg-stack-blur(" + radiusX + "," + radiusY + ")";
    }

    public static String sharpen() { return "sharpen"; }

    public static String emboss() { return "emboss"; }

    public static String edgeDetect() { return "edge-detect"; }

    public static String sobel() { return "sobel"; }

    public static String xGradient() { return "x-gradient"; }

    public static String yGradient() { return "y-gradient"; }

    /** Greyscale. */
    public static String gray() { return "gray"; }

    /** Invert the colours, keeping alpha. */
    public static String invert() { return "invert"; }

    public static String colorBlindProtanope() { return "color-blind-protanope"; }

    public static String colorBlindDeuteranope() { return "color-blind-deuteranope"; }

    public static String colorBlindTritanope() { return "color-blind-tritanope"; }

    /** Make this colour transparent. Accepts any colour string. */
    public static String colorToAlpha(String color) { return "color-to-alpha(" + color + ")"; }

    public static String colorToAlpha(Color color) { return colorToAlpha(color.toStyleString()); }

    /**
     * Shift hue, saturation, lightness and alpha. Each pair is the range the channel is mapped to, from 0 to 1,
     * for example {@code scaleHsla(0, 1, 0, 0.5, 0, 1, 0, 1)} halves the saturation.
     */
    public static String scaleHsla(double h0, double h1, double s0, double s1, double l0, double l1, double a0, double a1) {
        return "scale-hsla(" + String.join(",", Arrays.asList(
            Xml.number(h0), Xml.number(h1), Xml.number(s0), Xml.number(s1),
            Xml.number(l0), Xml.number(l1), Xml.number(a0), Xml.number(a1))) + ")";
    }
}
