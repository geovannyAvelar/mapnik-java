package dev.avelar.mapnik;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One alternative way to place a label. Mapnik tries them in order and uses the first that fits without
 * overlapping other labels, for example a normal size and then a smaller one. See
 * {@link Symbolizer#placementList}.
 */
public final class TextPlacement {
    private final Map<String, String> attributes = new LinkedHashMap<>();

    private TextPlacement() {}

    public static TextPlacement create() {
        return new TextPlacement();
    }

    /** Set any attribute of a {@code <Placement>} element, such as {@code size}, {@code dx}, {@code dy} or {@code wrap-width}. */
    public TextPlacement attr(String name, Object value) {
        Xml.checkName("attribute", name);
        if (value == null) {
            throw new IllegalArgumentException("attribute '" + name + "' has no value");
        }
        attributes.put(name, value instanceof Double || value instanceof Float
            ? Xml.number(((Number) value).doubleValue()) : value.toString());
        return this;
    }

    /** This alternative uses a different font size. */
    public TextPlacement fontSize(double size) { return attr("size", size); }

    public TextPlacement offset(double dx, double dy) { return attr("dx", dx).attr("dy", dy); }

    public String toXml() {
        StringBuilder sb = new StringBuilder("<Placement");
        for (Map.Entry<String, String> e : attributes.entrySet()) {
            sb.append(' ').append(e.getKey()).append("=\"").append(Xml.escape(e.getValue())).append('"');
        }
        return sb.append("/>").toString();
    }

    @Override
    public String toString() {
        return toXml();
    }
}
