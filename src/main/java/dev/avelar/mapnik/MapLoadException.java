package dev.avelar.mapnik;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A style could not be loaded. It is a {@link MapnikException}, with what Mapnik said picked apart:
 * where in the XML ({@link #line()}), which style or layer it concerned, and the individual problems
 * when a strict load found several.
 */
public class MapLoadException extends MapnikException {
    private static final Pattern LINE = Pattern.compile("\\bline[: ]+(\\d+)");
    private static final Pattern COLUMN = Pattern.compile("\\bcolumn[: ]+(\\d+)");
    private static final Pattern STYLE = Pattern.compile("\\bstyle '([^']*)'");
    private static final Pattern LAYER = Pattern.compile("\\blayer '([^']*)'");
    private static final Pattern ELEMENT = Pattern.compile("\\bin (\\w+) at line");
    private static final Pattern FILE = Pattern.compile("\\bin file '([^']*)'");

    private final int line;
    private final int column;
    private final String style;
    private final String layer;
    private final String element;
    private final String file;
    private final List<String> problems;

    MapLoadException(String message) {
        super(message);
        this.line = number(LINE, message, true);
        this.column = number(COLUMN, message, false);
        this.style = group(STYLE, message);
        this.layer = group(LAYER, message);
        this.element = group(ELEMENT, message);
        this.file = group(FILE, message);
        List<String> found = new ArrayList<>();
        for (String l : message.split("\n")) {
            if (l.startsWith("* ")) {
                found.add(l.substring(2).trim());
            }
        }
        this.problems = Collections.unmodifiableList(found.isEmpty() ? Collections.singletonList(message) : found);
    }

    private static int number(Pattern p, String s, boolean last) {
        Matcher m = p.matcher(s);
        int out = -1;
        while (m.find()) {
            out = Integer.parseInt(m.group(1));
            if (!last) {
                break;
            }
        }
        return out;
    }

    private static String group(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /** The line of the XML Mapnik pointed at, counting from 1, or -1 if it gave none. */
    public int line() { return line; }

    /** The column, counting from 1, or -1 if Mapnik gave none (it usually does not). */
    public int column() { return column; }

    /** The name of the style the problem was in, or null. */
    public String style() { return style; }

    /** The name of the layer the problem was in, or null. */
    public String layer() { return layer; }

    /** The XML element the problem was in, such as {@code PolygonSymbolizer}, or null. */
    public String element() { return element; }

    /** The XML file, when Mapnik named it, or null. */
    public String file() { return file; }

    /**
     * The separate problems. A strict load reports every unknown attribute it met; otherwise this is
     * the one message.
     */
    public List<String> problems() { return problems; }
}
