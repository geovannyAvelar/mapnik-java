package dev.avelar.mapnik;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One run of text in a label, with its own font, size and colours, so a label can mix styles:
 * a bold name followed by a smaller grey population. Give a few to {@link Symbolizer#formattedText}.
 * The expression is in Mapnik's expression language, for example {@code "[name]"}.
 */
public final class TextFormat {
    private final String expression;
    private final Map<String, String> attributes = new LinkedHashMap<>();

    private TextFormat(String expression) {
        if (expression == null) {
            throw new IllegalArgumentException("text expression is null");
        }
        this.expression = expression;
    }

    public static TextFormat of(String expression) {
        return new TextFormat(expression);
    }

    /** Set any attribute of a {@code <Format>} element. The typed methods cover the common ones. */
    public TextFormat attr(String name, Object value) {
        Xml.checkName("attribute", name);
        if (value == null) {
            throw new IllegalArgumentException("attribute '" + name + "' has no value");
        }
        attributes.put(name, value instanceof Double || value instanceof Float
            ? Xml.number(((Number) value).doubleValue()) : value.toString());
        return this;
    }

    public TextFormat faceName(String face) { return attr("face-name", face); }

    public TextFormat fontSize(double size) { return attr("size", size); }

    public TextFormat fill(String color) { return attr("fill", color); }

    public TextFormat fill(Color color) { return fill(color.toStyleString()); }

    public TextFormat halo(String color, double radius) { return attr("halo-fill", color).attr("halo-radius", radius); }

    public TextFormat halo(Color color, double radius) { return halo(color.toStyleString(), radius); }

    /** {@code none}, {@code uppercase}, {@code lowercase} or {@code capitalize}. */
    public TextFormat textTransform(String transform) { return attr("text-transform", transform); }

    public TextFormat characterSpacing(double pixels) { return attr("character-spacing", pixels); }

    public TextFormat lineSpacing(double pixels) { return attr("line-spacing", pixels); }

    public String toXml() {
        StringBuilder sb = new StringBuilder("<Format");
        for (Map.Entry<String, String> e : attributes.entrySet()) {
            sb.append(' ').append(e.getKey()).append("=\"").append(Xml.escape(e.getValue())).append('"');
        }
        return sb.append('>').append(Xml.escapeText(expression)).append("</Format>").toString();
    }

    @Override
    public String toString() {
        return toXml();
    }
}
