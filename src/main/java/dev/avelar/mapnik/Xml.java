package dev.avelar.mapnik;

/** Small XML helpers for the style builders. */
final class Xml {
    private Xml() {}

    /** For text between tags: only &, < and > need escaping. */
    static String escapeText(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** For attribute values: also quotes. */
    static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&apos;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    /** A number as Mapnik expects it: no exponent and no trailing ".0". */
    static String number(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            throw new IllegalArgumentException("not a finite number: " + v);
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    static void checkName(String kind, String name) {
        if (name == null || !name.matches("[a-zA-Z][a-zA-Z0-9_-]*")) {
            throw new IllegalArgumentException(kind + " name must be letters, digits, '-' or '_': " + name);
        }
    }
}
