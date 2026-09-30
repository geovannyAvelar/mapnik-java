package dev.avelar.mapnik;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One drawing instruction inside a {@link Rule}: how to draw the geometry of a matching feature.
 * Build one with a factory such as {@link #polygon()}, then set attributes, using the typed methods
 * for common ones or {@link #attr(String, Object)} for any other. Attribute names are the ones in
 * Mapnik's XML reference, for example {@code stroke-dasharray} or {@code halo-radius}.
 *
 * <p>Mapnik silently ignores attributes it does not know. {@link MapnikMap#addStyle} therefore loads
 * the style in Mapnik's strict mode, which rejects a misspelled attribute, or one that does not
 * belong on that kind of symbolizer, and says which.
 */
public final class Symbolizer {
    private final String element;
    private final Map<String, String> attributes = new LinkedHashMap<>();
    private String body;

    private Symbolizer(String element) {
        this.element = element;
    }

    // ---------------------------------------------------------------- factories

    /** Fill polygons: {@code fill}, {@code fill-opacity}, {@code gamma}, {@code smooth}, ... */
    public static Symbolizer polygon() { return new Symbolizer("PolygonSymbolizer"); }

    /** Stroke lines and polygon outlines: {@code stroke}, {@code stroke-width}, {@code stroke-dasharray}, ... */
    public static Symbolizer line() { return new Symbolizer("LineSymbolizer"); }

    /** Draw markers at points, along lines or inside polygons: {@code marker-type}, {@code width}, {@code fill}, ... */
    public static Symbolizer markers() { return new Symbolizer("MarkersSymbolizer"); }

    /** Draw an image file at each point. */
    public static Symbolizer point(String file) { return new Symbolizer("PointSymbolizer").attr("file", file); }

    /** Fill polygons with a repeating image. */
    public static Symbolizer polygonPattern(String file) {
        return new Symbolizer("PolygonPatternSymbolizer").attr("file", file);
    }

    /** Stroke lines with a repeating image. */
    public static Symbolizer linePattern(String file) {
        return new Symbolizer("LinePatternSymbolizer").attr("file", file);
    }

    /** Draw raster data. */
    public static Symbolizer raster() { return new Symbolizer("RasterSymbolizer"); }

    /** Extrude polygons into simple 3D buildings. */
    public static Symbolizer building() { return new Symbolizer("BuildingSymbolizer"); }

    /** Draw a dot at each point. */
    public static Symbolizer dot() { return new Symbolizer("DotSymbolizer"); }

    /** Draw the collision boxes Mapnik computes, for debugging label placement. */
    public static Symbolizer debug() { return new Symbolizer("DebugSymbolizer"); }

    /**
     * Draw text. {@code expression} says what to write, in Mapnik's expression language, for example
     * {@code "[name]"} or {@code "[name] + ' (' + [pop] + ')'"}. Needs a font: set {@code face-name}
     * and register the fonts with {@link Mapnik#registerFonts}.
     */
    public static Symbolizer text(String expression) {
        Symbolizer s = new Symbolizer("TextSymbolizer");
        s.body = expression;
        return s;
    }

    /** A text label drawn on top of an image, such as a road shield. */
    public static Symbolizer shield(String expression, String file) {
        Symbolizer s = new Symbolizer("ShieldSymbolizer").attr("file", file);
        s.body = expression;
        return s;
    }

    // ---------------------------------------------------------------- attributes

    /**
     * Set any attribute. The value is written with {@code toString()}, except that numbers have no
     * trailing {@code .0} and booleans are {@code true} or {@code false}.
     */
    public Symbolizer attr(String name, Object value) {
        Xml.checkName("attribute", name);
        if (value == null) {
            throw new IllegalArgumentException("attribute '" + name + "' has no value");
        }
        String v;
        if (value instanceof Double || value instanceof Float) {
            v = Xml.number(((Number) value).doubleValue());
        } else {
            v = value.toString();
        }
        attributes.put(name, v);
        return this;
    }

    /** Fill colour: a name, {@code #rrggbb} or {@code rgba(r,g,b,a)}. */
    public Symbolizer fill(String color) { return attr("fill", color); }

    public Symbolizer fillOpacity(double v) { return attr("fill-opacity", v); }

    public Symbolizer stroke(String color) { return attr("stroke", color); }

    public Symbolizer strokeWidth(double v) { return attr("stroke-width", v); }

    public Symbolizer strokeOpacity(double v) { return attr("stroke-opacity", v); }

    /** For example {@code "5,3"}: 5 units on, 3 off. */
    public Symbolizer strokeDasharray(String v) { return attr("stroke-dasharray", v); }

    /** {@code miter}, {@code round} or {@code bevel}. */
    public Symbolizer strokeLinejoin(String v) { return attr("stroke-linejoin", v); }

    /** {@code butt}, {@code round} or {@code square}. */
    public Symbolizer strokeLinecap(String v) { return attr("stroke-linecap", v); }

    /**
     * Opacity, for symbolizers that have it: markers, point, dot, raster, text and the pattern
     * symbolizers. Polygons and lines have none; use {@link #fillOpacity} and {@link #strokeOpacity}.
     */
    public Symbolizer opacity(double v) { return attr("opacity", v); }

    /** Compositing mode, such as {@code multiply} or {@code screen}. */
    public Symbolizer compOp(String v) { return attr("comp-op", v); }

    /** Markers and dots: width and height in pixels. */
    public Symbolizer size(double width, double height) { return attr("width", width).attr("height", height); }

    public Symbolizer allowOverlap(boolean v) { return attr("allow-overlap", v); }

    /** Text: the font face, for example {@code "DejaVu Sans Book"}. */
    public Symbolizer faceName(String v) { return attr("face-name", v); }

    /** Text: the font size in pixels. */
    public Symbolizer fontSize(double v) { return attr("size", v); }

    /** Text: outline colour and width. */
    public Symbolizer halo(String color, double radius) { return attr("halo-fill", color).attr("halo-radius", radius); }

    // ---------------------------------------------------------------- output

    /** The element name, for example {@code PolygonSymbolizer}. */
    public String element() { return element; }

    /** The attributes set so far, in the order they were set. */
    public Map<String, String> attributes() { return Collections.unmodifiableMap(attributes); }

    public String toXml() {
        StringBuilder sb = new StringBuilder("<").append(element);
        for (Map.Entry<String, String> e : attributes.entrySet()) {
            sb.append(' ').append(e.getKey()).append("=\"").append(Xml.escape(e.getValue())).append('"');
        }
        if (body == null) {
            return sb.append("/>").toString();
        }
        return sb.append('>').append(Xml.escapeText(body)).append("</").append(element).append('>').toString();
    }

    @Override
    public String toString() {
        return toXml();
    }
}
