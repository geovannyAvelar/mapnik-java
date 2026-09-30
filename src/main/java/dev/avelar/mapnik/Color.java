package dev.avelar.mapnik;

/**
 * An RGBA colour with 8 bits per channel. Parse one from anything Mapnik's style files accept: a name
 * ({@code "rebeccapurple"}), {@code "#rrggbb"}, {@code "#rrggbbaa"} or {@code "rgba(r,g,b,a)"} with a
 * from 0 to 1. Colours are accepted wherever the API also takes a colour string.
 */
public final class Color {
    public static final Color WHITE = new Color(255, 255, 255, 255);
    public static final Color BLACK = new Color(0, 0, 0, 255);
    public static final Color TRANSPARENT = new Color(0, 0, 0, 0);

    private final int r;
    private final int g;
    private final int b;
    private final int a;

    /** Each channel from 0 to 255. */
    public Color(int red, int green, int blue, int alpha) {
        this.r = check(red, "red");
        this.g = check(green, "green");
        this.b = check(blue, "blue");
        this.a = check(alpha, "alpha");
    }

    private static int check(int v, String name) {
        if (v < 0 || v > 255) {
            throw new IllegalArgumentException(name + " must be 0 to 255: " + v);
        }
        return v;
    }

    public static Color rgb(int red, int green, int blue) {
        return new Color(red, green, blue, 255);
    }

    /** From {@code 0xAARRGGBB}, the layout of {@link Image#getArgb}. */
    public static Color fromArgb(int argb) {
        return new Color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, argb >>> 24);
    }

    /** Parse a colour string. Throws {@link MapnikException} if Mapnik does not understand it. */
    public static Color parse(String text) {
        byte[] out = new byte[4];
        Mapnik.check(NativeApi.INSTANCE.mapnik_color_parse(text, out));
        return new Color(out[0] & 0xFF, out[1] & 0xFF, out[2] & 0xFF, out[3] & 0xFF);
    }

    public int red() { return r; }
    public int green() { return g; }
    public int blue() { return b; }

    /** Alpha from 0 (transparent) to 255 (opaque). */
    public int alpha() { return a; }

    public boolean isOpaque() { return a == 255; }

    /** As {@code 0xAARRGGBB}. */
    public int toArgb() {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** {@code #rrggbb}, or {@code #rrggbbaa} when not opaque. */
    public String toHex() {
        return NativeApi.INSTANCE.mapnik_color_to_hex(r, g, b, a);
    }

    /** The string passed to Mapnik wherever a colour is expected: exact for every channel value. */
    String toStyleString() {
        return "rgba(" + r + "," + g + "," + b + "," + Xml.number(a / 255.0) + ")";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Color && ((Color) o).toArgb() == toArgb();
    }

    @Override
    public int hashCode() {
        return toArgb();
    }

    /** Mapnik's own spelling, for example {@code rgb(255,0,0)} or {@code rgba(255,0,0,0.5)}. */
    @Override
    public String toString() {
        return NativeApi.INSTANCE.mapnik_color_to_string(r, g, b, a);
    }
}
