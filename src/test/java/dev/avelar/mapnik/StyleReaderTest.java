package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Reading styles from XML. Needs no native library. */
class StyleReaderTest {

    private static Style sample() {
        return Style.create("roads").opacity(0.75).compOp("multiply").filterMode(Style.FilterMode.FIRST).imageFilters("blur(2)")
            .add(Rule.create().name("main").title("Main roads").filter("[kind] = 'motorway' and [lanes] < 4")
                    .minScaleDenominator(100).maxScaleDenominator(250000.5)
                    .add(Symbolizer.line().stroke("#d1322b").strokeWidth(3).strokeDasharray("5,3"),
                         Symbolizer.markers().attr("marker-type", "ellipse").size(6, 6).fill("red")),
                Rule.create().elseFilter().add(Symbolizer.polygon().fill("rgba(1,2,3,0.5)")),
                Rule.create().alsoFilter().add(Symbolizer.text("[name] + ' & ' + [ref]").faceName("DejaVu Sans Book").fontSize(11)));
    }

    @Test
    void aBuiltStyleSurvivesAWriteAndRead() {
        Style original = sample();
        Style back = Style.fromXml(original.toXml());
        assertEquals(original.toXml(), back.toXml());
    }

    @Test
    void readsTheStyleAttributes() {
        Style s = Style.fromXml(sample().toXml());
        assertEquals("roads", s.name());
        assertEquals("0.75", s.attributes().get("opacity"));
        assertEquals("multiply", s.attributes().get("comp-op"));
        assertEquals("first", s.attributes().get("filter-mode"));
        assertEquals("blur(2)", s.attributes().get("image-filters"));
        assertFalse(s.attributes().containsKey("name"), "the name is not an ordinary attribute");
        assertThrows(UnsupportedOperationException.class, () -> s.attributes().put("x", "y"));
    }

    @Test
    void readsRuleDetails() {
        Style s = Style.fromXml(sample().toXml());
        assertEquals(3, s.rules().size());
        Rule main = s.rules().get(0);
        assertEquals("main", main.name().get());
        assertEquals("Main roads", main.title().get());
        assertEquals("[kind] = 'motorway' and [lanes] < 4", main.filterExpression().get());
        assertEquals(100.0, main.minScale().get(), 0);
        assertEquals(250000.5, main.maxScale().get(), 0);
        assertFalse(main.isElse());
        assertTrue(s.rules().get(1).isElse());
        assertTrue(s.rules().get(2).isAlso());
        assertFalse(s.rules().get(1).filterExpression().isPresent());
        assertFalse(s.rules().get(1).name().isPresent());
        assertFalse(s.rules().get(1).minScale().isPresent());
    }

    @Test
    void readsSymbolizers() {
        Style s = Style.fromXml(sample().toXml());
        Symbolizer line = s.rules().get(0).symbolizers().get(0);
        assertEquals("LineSymbolizer", line.element());
        assertEquals("#d1322b", line.attribute("stroke").get());
        assertEquals("3", line.attribute("stroke-width").get());
        assertEquals("5,3", line.attribute("stroke-dasharray").get());
        assertFalse(line.attribute("nope").isPresent());
        assertFalse(line.body().isPresent());
        assertEquals("MarkersSymbolizer", s.rules().get(0).symbolizers().get(1).element());
        assertEquals("PolygonSymbolizer", s.rules().get(1).symbolizers().get(0).element());
    }

    @Test
    void textBodiesKeepTheirSpecialCharacters() {
        Symbolizer text = Style.fromXml(sample().toXml()).rules().get(2).symbolizers().get(0);
        assertEquals("TextSymbolizer", text.element());
        assertEquals("[name] + ' & ' + [ref]", text.body().get());
        assertEquals("DejaVu Sans Book", text.attribute("face-name").get());
    }

    @Test
    void nestedElementsAreKeptAsXml() {
        String xml = "<Style name=\"s\"><Rule><RasterSymbolizer opacity=\"0.5\"><RasterColorizer default-mode=\"linear\">"
            + "<stop value=\"0\" color=\"blue\"/><stop value=\"9\" color=\"red\" label=\"a &amp; b\"/></RasterColorizer>"
            + "</RasterSymbolizer></Rule></Style>";
        Symbolizer r = Style.fromXml(xml).rules().get(0).symbolizers().get(0);
        assertEquals(1, r.children().size());
        assertEquals("<RasterColorizer default-mode=\"linear\"><stop value=\"0\" color=\"blue\"/>"
            + "<stop value=\"9\" color=\"red\" label=\"a &amp; b\"/></RasterColorizer>", r.children().get(0));
        assertEquals(xml, Style.fromXml(xml).toXml());
    }

    @Test
    void anEmptyStyleAndRuleAreFine() {
        Style s = Style.fromXml("<Style name=\"empty\"/>");
        assertEquals("empty", s.name());
        assertTrue(s.rules().isEmpty());
        Style r = Style.fromXml("<Style name=\"r\"><Rule/></Style>");
        assertEquals(1, r.rules().size());
        assertTrue(r.rules().get(0).symbolizers().isEmpty());
    }

    @Test
    void unicodeSurvives() {
        Style s = Style.create("Ärger-ü").add(Rule.create().filter("[name] = 'Zürich ☃'").add(Symbolizer.polygon()));
        assertEquals(s.toXml(), Style.fromXml(s.toXml()).toXml());
        assertEquals("[name] = 'Zürich ☃'", Style.fromXml(s.toXml()).rules().get(0).filterExpression().get());
    }

    @Test
    void readsFromAWholeMapDocument() {
        String map = "<?xml version=\"1.0\" encoding=\"utf-8\"?><Map srs=\"epsg:4326\">"
            + "<Style name=\"a\"><Rule><PolygonSymbolizer fill=\"red\"/></Rule></Style>"
            + "<Style name=\"b\" opacity=\"0.5\"><Rule><LineSymbolizer/></Rule></Style>"
            + "<Layer name=\"l\"><StyleName>a</StyleName></Layer></Map>";
        java.util.List<Style> all = StyleReader.stylesOf(map);
        assertEquals(Arrays.asList("a", "b"), Arrays.asList(all.get(0).name(), all.get(1).name()));
        assertEquals(2, all.size());
        assertEquals("0.5", all.get(1).attributes().get("opacity"));
    }

    @Test
    void badInputIsRejected() {
        String[] bad = {"", "not xml", "<Style", "<Style name=\"x\">", "<Rule/>", "<Style/>", "<Style name=\"\"/>",
            "<Style name=\"a\"><Rule><MinScaleDenominator>abc</MinScaleDenominator></Rule></Style>"};
        for (String x : bad) {
            assertThrows(IllegalArgumentException.class, () -> Style.fromXml(x), x);
        }
        assertThrows(IllegalArgumentException.class, () -> Style.fromXml(null));
    }

    @Test
    void documentTypeDeclarationsAreRefused() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE s [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
            + "<Style name=\"&x;\"/>";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Style.fromXml(xxe));
        assertTrue(e.getMessage().contains("not well-formed XML"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Style.fromXml("<!DOCTYPE Style><Style name=\"a\"/>"));
    }

    @Test
    void theStyleReadBackCanBeChangedAndUsed() {
        Style s = Style.fromXml(sample().toXml());
        s.add(Rule.create().add(Symbolizer.dot().fill("blue")));
        assertEquals(4, s.rules().size());
        assertTrue(s.toXml().contains("DotSymbolizer"));
    }
}
