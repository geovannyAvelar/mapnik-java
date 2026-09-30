package dev.avelar.mapnik;

import java.util.ArrayList;
import java.util.List;

/**
 * Colours a single-band raster, such as an elevation grid or a grey image, by its values. Add stops
 * from low to high, then pass it to {@link Symbolizer#colorizer(RasterColorizer)}.
 *
 * <pre>{@code
 * RasterColorizer.create().defaultMode(RasterColorizer.Mode.LINEAR)
 *     .stop(0, "blue").stop(500, "green").stop(3000, "white");
 * }</pre>
 */
public final class RasterColorizer {
    /** How values between stops are coloured. */
    public enum Mode {
        /** Each value takes the colour of the stop at or below it. */
        DISCRETE("discrete"),
        /** Colours are blended between the stops on either side. */
        LINEAR("linear"),
        /** Only values equal to a stop are coloured. */
        EXACT("exact");

        private final String xml;

        Mode(String xml) {
            this.xml = xml;
        }
    }

    private Mode defaultMode;
    private String defaultColor;
    private Double epsilon;
    private final List<String> stops = new ArrayList<>();

    private RasterColorizer() {}

    public static RasterColorizer create() {
        return new RasterColorizer();
    }

    /** The mode for stops that do not set their own. Mapnik's default is {@link Mode#LINEAR}. */
    public RasterColorizer defaultMode(Mode mode) {
        this.defaultMode = mode;
        return this;
    }

    /** The colour for values below the first stop and for missing data. Mapnik's default is transparent. */
    public RasterColorizer defaultColor(String color) {
        this.defaultColor = color;
        return this;
    }

    public RasterColorizer defaultColor(Color color) {
        return defaultColor(color.toStyleString());
    }

    /** How close a value must be to a stop to count as equal, in {@link Mode#EXACT} mode. */
    public RasterColorizer epsilon(double epsilon) {
        this.epsilon = epsilon;
        return this;
    }

    /** Add a stop at {@code value}, which must be above the previous stop's. Colour as in a style file. */
    public RasterColorizer stop(double value, String color) {
        return stop(value, color, null, null);
    }

    public RasterColorizer stop(double value, Color color) {
        return stop(value, color.toStyleString(), null, null);
    }

    /** As {@link #stop(double, String)} with its own mode and an optional label, which may be null. */
    public RasterColorizer stop(double value, String color, Mode mode, String label) {
        StringBuilder sb = new StringBuilder("<stop value=\"").append(Xml.number(value)).append("\" color=\"")
            .append(Xml.escape(color)).append('"');
        if (mode != null) {
            sb.append(" mode=\"").append(mode.xml).append('"');
        }
        if (label != null) {
            sb.append(" label=\"").append(Xml.escape(label)).append('"');
        }
        stops.add(sb.append("/>").toString());
        return this;
    }

    public int stopCount() {
        return stops.size();
    }

    public String toXml() {
        if (stops.isEmpty()) {
            throw new IllegalStateException("a raster colorizer needs at least one stop");
        }
        StringBuilder sb = new StringBuilder("<RasterColorizer");
        if (defaultMode != null) {
            sb.append(" default-mode=\"").append(defaultMode.xml).append('"');
        }
        if (defaultColor != null) {
            sb.append(" default-color=\"").append(Xml.escape(defaultColor)).append('"');
        }
        if (epsilon != null) {
            sb.append(" epsilon=\"").append(Xml.number(epsilon)).append('"');
        }
        sb.append('>');
        for (String s : stops) {
            sb.append(s);
        }
        return sb.append("</RasterColorizer>").toString();
    }

    @Override
    public String toString() {
        return toXml();
    }
}
