package dev.avelar.mapnik;

import java.util.ArrayList;
import java.util.List;

/** One rule inside a group symbolizer: what to draw for the features of the group that match a filter. */
public final class GroupRule {
    private String filter;
    private final List<Symbolizer> symbolizers = new ArrayList<>();

    private GroupRule() {}

    public static GroupRule create() {
        return new GroupRule();
    }

    /** Only members of the group matching this expression, in Mapnik's expression language. */
    public GroupRule filter(String expression) {
        this.filter = expression;
        return this;
    }

    public GroupRule add(Symbolizer... symbolizers) {
        for (Symbolizer s : symbolizers) {
            if (s == null) {
                throw new IllegalArgumentException("symbolizer is null");
            }
            this.symbolizers.add(s);
        }
        return this;
    }

    public String toXml() {
        StringBuilder sb = new StringBuilder("<GroupRule>");
        if (filter != null) {
            sb.append("<Filter>").append(Xml.escapeText(filter)).append("</Filter>");
        }
        for (Symbolizer s : symbolizers) {
            sb.append(s.toXml());
        }
        return sb.append("</GroupRule>").toString();
    }

    @Override
    public String toString() {
        return toXml();
    }
}
