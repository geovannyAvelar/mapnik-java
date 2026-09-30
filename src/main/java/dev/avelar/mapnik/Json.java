package dev.avelar.mapnik;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader for GeoJSON. Objects become {@link LinkedHashMap}, arrays {@link ArrayList},
 * numbers {@link Double}, strings {@link String}, true and false {@link Boolean}, null {@code null}.
 * Throws {@link IllegalArgumentException} with the position of the first problem.
 */
final class Json {
    private final String s;
    private int pos;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("JSON text is null");
        }
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.pos != text.length()) {
            throw p.error("unexpected text after the JSON value");
        }
        return v;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("invalid JSON at position " + pos + ": " + what);
    }

    private void ws() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private Object value() {
        if (pos >= s.length()) {
            throw error("unexpected end");
        }
        char c = s.charAt(pos);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw error("unexpected character '" + c + "'");
        }
    }

    private Object literal(String word, Object value) {
        if (!s.startsWith(word, pos)) {
            throw error("expected " + word);
        }
        pos += word.length();
        return value;
    }

    private Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<>();
        pos++; // {
        ws();
        if (peek() == '}') {
            pos++;
            return out;
        }
        while (true) {
            ws();
            if (peek() != '"') {
                throw error("expected a string key");
            }
            String key = string();
            ws();
            if (peek() != ':') {
                throw error("expected ':'");
            }
            pos++;
            ws();
            out.put(key, value());
            ws();
            char c = peek();
            pos++;
            if (c == '}') {
                return out;
            }
            if (c != ',') {
                pos--;
                throw error("expected ',' or '}'");
            }
        }
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<>();
        pos++; // [
        ws();
        if (peek() == ']') {
            pos++;
            return out;
        }
        while (true) {
            ws();
            out.add(value());
            ws();
            char c = peek();
            pos++;
            if (c == ']') {
                return out;
            }
            if (c != ',') {
                pos--;
                throw error("expected ',' or ']'");
            }
        }
    }

    private char peek() {
        if (pos >= s.length()) {
            throw error("unexpected end");
        }
        return s.charAt(pos);
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        pos++; // opening quote
        while (true) {
            if (pos >= s.length()) {
                throw error("unterminated string");
            }
            char c = s.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= s.length()) {
                throw error("unterminated escape");
            }
            char e = s.charAt(pos++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (pos + 4 > s.length()) {
                        throw error("short \\u escape");
                    }
                    try {
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad \\u escape");
                    }
                    pos += 4;
                    break;
                default:
                    throw error("bad escape \\" + e);
            }
        }
    }

    private Double number() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
            pos++;
        }
        if (pos < s.length() && s.charAt(pos) == '.') {
            pos++;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                pos++;
            }
        }
        if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
            pos++;
            if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                pos++;
            }
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                pos++;
            }
        }
        try {
            return Double.valueOf(s.substring(start, pos));
        } catch (NumberFormatException e) {
            pos = start;
            throw error("bad number");
        }
    }
}
