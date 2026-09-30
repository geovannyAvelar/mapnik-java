package dev.avelar.mapnik;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Reads styles out of Mapnik XML into {@link Style}, {@link Rule} and {@link Symbolizer}. Uses SAX
 * rather than DOM because DOM does not keep attributes in the order they were written.
 */
final class StyleReader {
    private StyleReader() {}

    /** A tiny XML tree that keeps attribute and child order. */
    static final class Node {
        final String tag;
        final Map<String, String> attributes = new LinkedHashMap<>();
        /** Child {@link Node}s and text, in document order. */
        final List<Object> content = new ArrayList<>();

        Node(String tag) {
            this.tag = tag;
        }

        List<Node> elements() {
            List<Node> out = new ArrayList<>();
            for (Object o : content) {
                if (o instanceof Node) {
                    out.add((Node) o);
                }
            }
            return out;
        }

        String text() {
            StringBuilder sb = new StringBuilder();
            for (Object o : content) {
                if (o instanceof String) {
                    sb.append((String) o);
                }
            }
            return sb.toString();
        }
    }

    static Node parse(String xml) {
        if (xml == null) {
            throw new IllegalArgumentException("XML is null");
        }
        try {
            SAXParserFactory f = SAXParserFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setNamespaceAware(false);
            f.setXIncludeAware(false);
            SAXParser parser = f.newSAXParser();
            final List<Node> stack = new ArrayList<>();
            final Node[] root = new Node[1];
            parser.parse(new InputSource(new StringReader(xml)), new DefaultHandler() {
                @Override
                public void startElement(String uri, String local, String qName, Attributes atts) {
                    Node n = new Node(qName);
                    for (int i = 0; i < atts.getLength(); i++) {
                        n.attributes.put(atts.getQName(i), atts.getValue(i));
                    }
                    if (stack.isEmpty()) {
                        root[0] = n;
                    } else {
                        stack.get(stack.size() - 1).content.add(n);
                    }
                    stack.add(n);
                }

                @Override
                public void endElement(String uri, String local, String qName) {
                    stack.remove(stack.size() - 1);
                }

                @Override
                public void characters(char[] ch, int start, int length) {
                    if (!stack.isEmpty()) {
                        List<Object> c = stack.get(stack.size() - 1).content;
                        String piece = new String(ch, start, length);
                        if (!c.isEmpty() && c.get(c.size() - 1) instanceof String) {
                            c.set(c.size() - 1, c.get(c.size() - 1) + piece);
                        } else {
                            c.add(piece);
                        }
                    }
                }
            });
            if (root[0] == null) {
                throw new IllegalArgumentException("not well-formed XML: no element");
            }
            return root[0];
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalArgumentException("not well-formed XML: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read XML: " + e.getMessage(), e);
        }
    }

    static Style parseStyle(String xml) {
        Node root = parse(xml);
        if (!"Style".equals(root.tag)) {
            throw new IllegalArgumentException("expected a <Style> element, found <" + root.tag + ">");
        }
        return style(root);
    }

    /** Every style of a whole map document, in document order. */
    static List<Style> stylesOf(String mapXml) {
        List<Style> out = new ArrayList<>();
        for (Node e : parse(mapXml).elements()) {
            if ("Style".equals(e.tag)) {
                out.add(style(e));
            }
        }
        return out;
    }

    private static Style style(Node e) {
        String name = e.attributes.get("name");
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("a <Style> has no name");
        }
        Map<String, String> attrs = new LinkedHashMap<>(e.attributes);
        attrs.remove("name");
        List<Rule> rules = new ArrayList<>();
        for (Node r : e.elements()) {
            if ("Rule".equals(r.tag)) {
                rules.add(rule(r));
            }
        }
        return Style.fromParts(name, attrs, rules);
    }

    private static Rule rule(Node e) {
        String name = null;
        String title = null;
        String filter = null;
        boolean elseFilter = false;
        boolean alsoFilter = false;
        Double min = null;
        Double max = null;
        List<Symbolizer> symbolizers = new ArrayList<>();
        for (Node c : e.elements()) {
            switch (c.tag) {
                case "Name": name = c.text(); break;
                case "Title": title = c.text(); break;
                case "Filter": filter = c.text(); break;
                case "ElseFilter": elseFilter = true; break;
                case "AlsoFilter": alsoFilter = true; break;
                case "MinScaleDenominator": min = number(c); break;
                case "MaxScaleDenominator": max = number(c); break;
                default: symbolizers.add(symbolizer(c));
            }
        }
        return Rule.fromParts(name, title, filter, elseFilter, alsoFilter, min, max, symbolizers);
    }

    private static Double number(Node e) {
        try {
            return Double.valueOf(e.text().trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("<" + e.tag + "> is not a number: " + e.text());
        }
    }

    private static Symbolizer symbolizer(Node e) {
        List<Node> kids = e.elements();
        String body = null;
        List<String> childXml = new ArrayList<>();
        if (kids.isEmpty()) {
            String text = e.text();
            if (!text.trim().isEmpty()) {
                body = text;
            }
        } else {
            for (Node k : kids) {
                childXml.add(serialize(k));
            }
        }
        return Symbolizer.fromParts(e.tag, e.attributes, body, childXml);
    }

    /** An element and everything inside it as XML text. */
    static String serialize(Node e) {
        StringBuilder sb = new StringBuilder("<").append(e.tag);
        for (Map.Entry<String, String> a : e.attributes.entrySet()) {
            sb.append(' ').append(a.getKey()).append("=\"").append(Xml.escape(a.getValue())).append('"');
        }
        if (e.content.isEmpty()) {
            return sb.append("/>").toString();
        }
        sb.append('>');
        for (Object o : e.content) {
            sb.append(o instanceof Node ? serialize((Node) o) : Xml.escapeText((String) o));
        }
        return sb.append("</").append(e.tag).append('>').toString();
    }
}
