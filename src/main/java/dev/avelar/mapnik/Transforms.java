package dev.avelar.mapnik;

/**
 * Checks for the SVG-style transforms used by the {@code geometry-transform} and
 * {@code image-transform} attributes, such as {@code translate(10,20) rotate(45) scale(2)}.
 */
public final class Transforms {
    private Transforms() {}

    /** Throws {@link MapnikException} with Mapnik's message if {@code transform} does not parse. */
    public static void check(String transform) {
        Mapnik.check(NativeApi.INSTANCE.mapnik_transform_check(transform));
    }

    /** True if {@code transform} parses. */
    public static boolean isValid(String transform) {
        return NativeApi.INSTANCE.mapnik_transform_check(transform) == 0;
    }
}
