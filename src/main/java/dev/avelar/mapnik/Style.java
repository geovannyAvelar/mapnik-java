package dev.avelar.mapnik;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A named set of {@link Rule}s. Add it to a map with {@link MapnikMap#addStyle}, then refer to it
 * from a layer with {@link Layer#addStyle}.
 */
public final class Style {
    /** Whether every matching rule draws, or only the first one that matches. */
    public enum FilterMode {
        ALL("all"), FIRST("first");

        private final String xml;

        FilterMode(String xml) {
            this.xml = xml;
        }
    }

    private final String name;
    private final Map<String, String> attributes = new LinkedHashMap<>();
    private final List<Rule> rules = new ArrayList<>();

    private Style(String name) {
        this.name = name;
    }

    public static Style create(String name) {
        Xml.checkName("style", name);
        return new Style(name);
    }

    /** A style as read from XML. */
    static Style fromParts(String name, Map<String, String> attributes, List<Rule> rules) {
        Style s = new Style(name);
        s.attributes.putAll(attributes);
        s.rules.addAll(rules);
        return s;
    }

    /**
     * Read a style from its XML, such as one {@link #toXml()} wrote or one taken from a Mapnik map file.
     * Throws {@link IllegalArgumentException} if the text is not a well-formed {@code <Style>} element.
     * DTDs are refused.
     */
    public static Style fromXml(String xml) {
        return StyleReader.parseStyle(xml);
    }

    /** The style's own attributes, such as {@code opacity} and {@code comp-op}, as written. */
    public Map<String, String> attributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public String name() { return name; }

    /** Opacity of everything drawn by this style, from 0 to 1. */
    public Style opacity(double v) {
        attributes.put("opacity", Xml.number(v));
        return this;
    }

    /** Compositing mode used when the style's layer is blended onto the map, such as {@code multiply}. */
    public Style compOp(String v) {
        attributes.put("comp-op", v);
        return this;
    }

    public Style compOp(BlendMode mode) {
        return compOp(mode.xmlName());
    }

    public Style filterMode(FilterMode mode) {
        attributes.put("filter-mode", mode.xml);
        return this;
    }

    /** Image filters applied to the layer after it is drawn, for example {@code "blur(10)"}. */
    public Style imageFilters(String filters) {
        attributes.put("image-filters", filters);
        return this;
    }

    public Style add(Rule... rules) {
        for (Rule r : rules) {
            if (r == null) {
                throw new IllegalArgumentException("rule is null");
            }
            this.rules.add(r);
        }
        return this;
    }

    public List<Rule> rules() {
        return Collections.unmodifiableList(rules);
    }

    public String toXml() {
        StringBuilder sb = new StringBuilder("<Style name=\"").append(Xml.escape(name)).append('"');
        for (Map.Entry<String, String> e : attributes.entrySet()) {
            sb.append(' ').append(e.getKey()).append("=\"").append(Xml.escape(e.getValue())).append('"');
        }
        sb.append('>');
        for (Rule r : rules) {
            sb.append(r.toXml());
        }
        return sb.append("</Style>").toString();
    }

    @Override
    public String toString() {
        return toXml();
    }
}
