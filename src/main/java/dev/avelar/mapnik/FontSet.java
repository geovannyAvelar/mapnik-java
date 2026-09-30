package dev.avelar.mapnik;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A list of font faces to try in order, so text falls back to the next one for characters a font
 * lacks. Use it in a text symbolizer with {@code fontset-name}.
 */
public final class FontSet {
    private final String name;
    private final List<String> faces;

    private FontSet(String name, List<String> faces) {
        this.name = name;
        this.faces = faces;
    }

    public static FontSet create(String name, String... faceNames) {
        Xml.checkName("fontset", name);
        if (faceNames.length == 0) {
            throw new IllegalArgumentException("a font set needs at least one face");
        }
        return new FontSet(name, Collections.unmodifiableList(new ArrayList<>(Arrays.asList(faceNames))));
    }

    public String name() { return name; }

    public List<String> faces() { return faces; }

    public String toXml() {
        StringBuilder sb = new StringBuilder("<FontSet name=\"").append(Xml.escape(name)).append("\">");
        for (String f : faces) {
            sb.append("<Font face-name=\"").append(Xml.escape(f)).append("\"/>");
        }
        return sb.append("</FontSet>").toString();
    }

    @Override
    public String toString() {
        return toXml();
    }
}
