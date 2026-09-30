package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Text formats, placements and group symbolizers as XML. Needs no native library. */
class RichStylingBuildersTest {

    @Test
    void aTextFormatWritesItsAttributesAndExpression() {
        assertEquals("<Format face-name=\"DejaVu Sans Bold\" size=\"14\" fill=\"red\">[name]</Format>",
            TextFormat.of("[name]").faceName("DejaVu Sans Bold").fontSize(14).fill("red").toXml());
        assertEquals("<Format>[a] + ' &amp; ' + [b] &lt; 3</Format>", TextFormat.of("[a] + ' & ' + [b] < 3").toXml());
    }

    @Test
    void textFormatHelpersUseTheRightAttributes() {
        String xml = TextFormat.of("[n]").halo("white", 2).textTransform("uppercase").characterSpacing(1.5)
            .lineSpacing(3).fill(Color.rgb(1, 2, 3)).toXml();
        assertTrue(xml.contains("halo-fill=\"white\""), xml);
        assertTrue(xml.contains("halo-radius=\"2\""), xml);
        assertTrue(xml.contains("text-transform=\"uppercase\""), xml);
        assertTrue(xml.contains("character-spacing=\"1.5\""), xml);
        assertTrue(xml.contains("line-spacing=\"3\""), xml);
        assertTrue(xml.contains("fill=\"rgba(1,2,3,1)\""), xml);
        assertTrue(TextFormat.of("[n]").halo(Color.WHITE, 1).toXml().contains("halo-fill=\"rgba(255,255,255,1)\""));
    }

    @Test
    void textFormatValidatesItsInput() {
        assertThrows(IllegalArgumentException.class, () -> TextFormat.of(null));
        assertThrows(IllegalArgumentException.class, () -> TextFormat.of("[n]").attr("bad name", 1));
        assertThrows(IllegalArgumentException.class, () -> TextFormat.of("[n]").attr("x", null));
        assertEquals("<Format></Format>", TextFormat.of("").toXml());
        assertEquals(TextFormat.of("[n]").toXml(), TextFormat.of("[n]").toString());
    }

    @Test
    void aTextPlacementWritesItsAttributes() {
        assertEquals("<Placement size=\"10\" dx=\"2\" dy=\"-3\"/>",
            TextPlacement.create().fontSize(10).offset(2, -3).toXml());
        assertEquals("<Placement wrap-width=\"40\"/>", TextPlacement.create().attr("wrap-width", 40).toXml());
        assertEquals("<Placement/>", TextPlacement.create().toXml());
        assertThrows(IllegalArgumentException.class, () -> TextPlacement.create().attr("a b", 1));
    }

    @Test
    void formattedTextPutsItsFormatsInside() {
        Symbolizer s = Symbolizer.formattedText(TextFormat.of("[name]").fill("red"), TextFormat.of("[pop]").fontSize(9))
            .faceName("DejaVu Sans Book").fontSize(12);
        assertEquals("<TextSymbolizer face-name=\"DejaVu Sans Book\" size=\"12\"><Format fill=\"red\">[name]</Format>"
            + "<Format size=\"9\">[pop]</Format></TextSymbolizer>", s.toXml());
        assertEquals(2, s.children().size());
        assertFalse(s.body().isPresent());
        assertThrows(IllegalArgumentException.class, Symbolizer::formattedText);
    }

    @Test
    void placementAlternativesFollowTheFormats() {
        Symbolizer s = Symbolizer.formattedText(TextFormat.of("[name]")).faceName("F")
            .placementList(TextPlacement.create().fontSize(10), TextPlacement.create().fontSize(8));
        assertEquals("<TextSymbolizer face-name=\"F\" placement-type=\"list\"><Format>[name]</Format>"
            + "<Placement size=\"10\"/><Placement size=\"8\"/></TextSymbolizer>", s.toXml());
    }

    @Test
    void placementAlternativesNeedFormattedText() {
        assertThrows(IllegalStateException.class, () -> Symbolizer.text("[n]").placementList(TextPlacement.create()));
        assertThrows(IllegalStateException.class, () -> Symbolizer.polygon().placementList(TextPlacement.create()));
    }

    @Test
    void placementPositions() {
        String xml = Symbolizer.text("[n]").placementPositions("E,NE,SE,W").toXml();
        assertTrue(xml.contains("placement-type=\"simple\""), xml);
        assertTrue(xml.contains("placements=\"E,NE,SE,W\""), xml);
        assertThrows(IllegalStateException.class, () -> Symbolizer.line().placementPositions("E"));
    }

    @Test
    void aGroupRuleWritesItsFilterAndSymbolizers() {
        assertEquals("<GroupRule><Filter>[n] &lt; 3</Filter><PointSymbolizer file=\"x.png\"/>"
                + "<TextSymbolizer face-name=\"F\">[name]</TextSymbolizer></GroupRule>",
            GroupRule.create().filter("[n] < 3").add(Symbolizer.point("x.png"), Symbolizer.text("[name]").faceName("F")).toXml());
        assertEquals("<GroupRule/>".replace("/>", "></GroupRule>"), GroupRule.create().toXml());
        assertThrows(IllegalArgumentException.class, () -> GroupRule.create().add((Symbolizer) null));
    }

    @Test
    void aGroupSymbolizerHoldsLayoutAndRules() {
        Symbolizer g = Symbolizer.group().attr("num-columns", 2).attr("repeat-key", "[id]")
            .simpleLayout(5).groupRule(GroupRule.create().add(Symbolizer.dot()));
        assertEquals("<GroupSymbolizer num-columns=\"2\" repeat-key=\"[id]\"><SimpleLayout item-margin=\"5\"/>"
            + "<GroupRule><DotSymbolizer/></GroupRule></GroupSymbolizer>", g.toXml());
        assertTrue(Symbolizer.group().pairLayout(2.5).toXml().contains("<PairLayout item-margin=\"2.5\"/>"));
        assertEquals("GroupSymbolizer", g.element());
    }

    @Test
    void groupColumnsSetTheThreeAttributesMapnikNeeds() {
        String xml = Symbolizer.group().groupColumns(1, 2, "[id]").toXml();
        assertEquals("<GroupSymbolizer start-column=\"1\" num-columns=\"2\" repeat-key=\"[id]\"/>", xml);
        assertThrows(IllegalStateException.class, () -> Symbolizer.polygon().groupColumns(1, 1, "[id]"));
    }

    @Test
    void groupPartsBelongOnlyToGroups() {
        assertThrows(IllegalStateException.class, () -> Symbolizer.polygon().simpleLayout(1));
        assertThrows(IllegalStateException.class, () -> Symbolizer.polygon().pairLayout(1));
        assertThrows(IllegalStateException.class, () -> Symbolizer.text("[n]").groupRule(GroupRule.create()));
    }
}
