package dev.avelar.mapnik;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Says which features get which symbolizers: an optional filter and scale range, then what to draw.
 * Rules in a {@link Style} are tried in order.
 */
public final class Rule {
    private String name;
    private String title;
    private String filter;
    private boolean elseFilter;
    private boolean alsoFilter;
    private Double minScaleDenominator;
    private Double maxScaleDenominator;
    private final List<Symbolizer> symbolizers = new ArrayList<>();

    private Rule() {}

    public static Rule create() {
        return new Rule();
    }

    public Rule name(String name) {
        this.name = name;
        return this;
    }

    public Rule title(String title) {
        this.title = title;
        return this;
    }

    /**
     * Only features matching this expression, in Mapnik's expression language, for example
     * {@code "[population] > 100000 and [kind] = 'city'"}. Attribute names go in brackets.
     */
    public Rule filter(String expression) {
        if (elseFilter || alsoFilter) {
            throw new IllegalStateException("a rule cannot have a filter and also be an else or also rule");
        }
        this.filter = expression;
        return this;
    }

    /** Matches features that no earlier rule in the style matched. */
    public Rule elseFilter() {
        if (filter != null || alsoFilter) {
            throw new IllegalStateException("a rule can have only one of filter, else and also");
        }
        this.elseFilter = true;
        return this;
    }

    /**
     * Matches only features that an earlier rule in the style already matched: the opposite of
     * {@link #elseFilter()}. Use it to add a detail to features another rule drew, for example a
     * centre dot. It does nothing in {@link Style.FilterMode#FIRST} mode, where a feature stops at its
     * first matching rule.
     */
    public Rule alsoFilter() {
        if (filter != null || elseFilter) {
            throw new IllegalStateException("a rule can have only one of filter, else and also");
        }
        this.alsoFilter = true;
        return this;
    }

    /** The rule applies only when the map's scale denominator is at least this (zoomed out less than this). */
    public Rule minScaleDenominator(double v) {
        this.minScaleDenominator = v;
        return this;
    }

    /** The rule applies only when the map's scale denominator is below this. */
    public Rule maxScaleDenominator(double v) {
        this.maxScaleDenominator = v;
        return this;
    }

    public Rule add(Symbolizer... symbolizers) {
        for (Symbolizer s : symbolizers) {
            if (s == null) {
                throw new IllegalArgumentException("symbolizer is null");
            }
            this.symbolizers.add(s);
        }
        return this;
    }

    public List<Symbolizer> symbolizers() {
        return Collections.unmodifiableList(symbolizers);
    }

    public String toXml() {
        StringBuilder sb = new StringBuilder("<Rule>");
        if (name != null) {
            sb.append("<Name>").append(Xml.escapeText(name)).append("</Name>");
        }
        if (title != null) {
            sb.append("<Title>").append(Xml.escapeText(title)).append("</Title>");
        }
        if (filter != null) {
            sb.append("<Filter>").append(Xml.escapeText(filter)).append("</Filter>");
        }
        if (elseFilter) {
            sb.append("<ElseFilter/>");
        }
        if (alsoFilter) {
            sb.append("<AlsoFilter/>");
        }
        if (minScaleDenominator != null) {
            sb.append("<MinScaleDenominator>").append(Xml.number(minScaleDenominator)).append("</MinScaleDenominator>");
        }
        if (maxScaleDenominator != null) {
            sb.append("<MaxScaleDenominator>").append(Xml.number(maxScaleDenominator)).append("</MaxScaleDenominator>");
        }
        for (Symbolizer s : symbolizers) {
            sb.append(s.toXml());
        }
        return sb.append("</Rule>").toString();
    }

    @Override
    public String toString() {
        return toXml();
    }
}
