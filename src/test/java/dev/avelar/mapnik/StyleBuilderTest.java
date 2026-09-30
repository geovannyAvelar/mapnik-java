package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The XML the style builders write. Needs no native library. */
class StyleBuilderTest {

    @Test
    void symbolizersWriteTheirAttributesInOrder() {
        assertEquals("<PolygonSymbolizer fill=\"red\" fill-opacity=\"0.5\"/>",
            Symbolizer.polygon().fill("red").fillOpacity(0.5).toXml());
        assertEquals("<LineSymbolizer stroke=\"#336699\" stroke-width=\"2\" stroke-dasharray=\"5,3\"/>",
            Symbolizer.line().stroke("#336699").strokeWidth(2).strokeDasharray("5,3").toXml());
        assertEquals("<MarkersSymbolizer marker-type=\"ellipse\" width=\"10\" height=\"20\" allow-overlap=\"true\"/>",
            Symbolizer.markers().attr("marker-type", "ellipse").size(10, 20).allowOverlap(true).toXml());
    }

    @Test
    void factoriesUseTheRightElementNames() {
        assertEquals("PolygonSymbolizer", Symbolizer.polygon().element());
        assertEquals("LineSymbolizer", Symbolizer.line().element());
        assertEquals("MarkersSymbolizer", Symbolizer.markers().element());
        assertEquals("PointSymbolizer", Symbolizer.point("a.png").element());
        assertEquals("PolygonPatternSymbolizer", Symbolizer.polygonPattern("a.png").element());
        assertEquals("LinePatternSymbolizer", Symbolizer.linePattern("a.png").element());
        assertEquals("RasterSymbolizer", Symbolizer.raster().element());
        assertEquals("BuildingSymbolizer", Symbolizer.building().element());
        assertEquals("DotSymbolizer", Symbolizer.dot().element());
        assertEquals("DebugSymbolizer", Symbolizer.debug().element());
        assertEquals("TextSymbolizer", Symbolizer.text("[name]").element());
        assertEquals("ShieldSymbolizer", Symbolizer.shield("[ref]", "s.png").element());
    }

    @Test
    void fileBasedSymbolizersSetTheirFile() {
        assertEquals("<PointSymbolizer file=\"pin.png\"/>", Symbolizer.point("pin.png").toXml());
        assertEquals("<PolygonPatternSymbolizer file=\"hatch.png\"/>", Symbolizer.polygonPattern("hatch.png").toXml());
    }

    @Test
    void textSymbolizerPutsTheExpressionInItsBodyEscaped() {
        assertEquals("<TextSymbolizer face-name=\"DejaVu Sans Book\" size=\"12\" halo-fill=\"white\" halo-radius=\"1\">"
                + "[name] + ' &amp; ' + [kind] &lt; 3</TextSymbolizer>",
            Symbolizer.text("[name] + ' & ' + [kind] < 3").faceName("DejaVu Sans Book").fontSize(12)
                .halo("white", 1).toXml());
    }

    @Test
    void shieldHasAFileAndABody() {
        assertEquals("<ShieldSymbolizer file=\"s.png\">[ref]</ShieldSymbolizer>",
            Symbolizer.shield("[ref]", "s.png").toXml());
    }

    @Test
    void attributeValuesAreEscaped() {
        assertEquals("<PointSymbolizer file=\"a&amp;b&quot;c&lt;.png\"/>", Symbolizer.point("a&b\"c<.png").toXml());
    }

    @Test
    void numbersHaveNoTrailingZerosOrExponents() {
        assertEquals("2", Xml.number(2.0));
        assertEquals("-3", Xml.number(-3));
        assertEquals("1.25", Xml.number(1.25));
        assertEquals("0.1", Xml.number(0.1));
        assertEquals("0.0000001", Xml.number(1e-7));
        assertEquals("100000000000000000000", Xml.number(1e20));
        assertThrows(IllegalArgumentException.class, () -> Xml.number(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Xml.number(Double.POSITIVE_INFINITY));
    }

    @Test
    void attrAcceptsAnyValueTypeAndRejectsBadNames() {
        assertEquals("true", Symbolizer.markers().attr("allow-overlap", Boolean.TRUE).attributes().get("allow-overlap"));
        assertEquals("3", Symbolizer.markers().attr("spacing", 3).attributes().get("spacing"));
        assertEquals("2.5", Symbolizer.markers().attr("spacing", 2.5f).attributes().get("spacing"));
        assertThrows(IllegalArgumentException.class, () -> Symbolizer.polygon().attr("fill=\"x\" evil", "red"));
        assertThrows(IllegalArgumentException.class, () -> Symbolizer.polygon().attr("", "red"));
        assertThrows(IllegalArgumentException.class, () -> Symbolizer.polygon().attr("1fill", "red"));
        assertThrows(IllegalArgumentException.class, () -> Symbolizer.polygon().attr("fill", null));
    }

    @Test
    void attributesAreReadOnly() {
        Symbolizer s = Symbolizer.polygon().fill("red");
        assertThrows(UnsupportedOperationException.class, () -> s.attributes().put("x", "y"));
    }

    @Test
    void ruleWritesItsPartsInAValidOrder() {
        Rule r = Rule.create().name("big").title("Big places").filter("[pop] > 1000")
            .minScaleDenominator(100).maxScaleDenominator(2500.5)
            .add(Symbolizer.polygon().fill("red"), Symbolizer.line().stroke("black"));
        assertEquals("<Rule><Name>big</Name><Title>Big places</Title><Filter>[pop] &gt; 1000</Filter>"
                + "<MinScaleDenominator>100</MinScaleDenominator><MaxScaleDenominator>2500.5</MaxScaleDenominator>"
                + "<PolygonSymbolizer fill=\"red\"/><LineSymbolizer stroke=\"black\"/></Rule>",
            r.toXml());
    }

    @Test
    void ruleFiltersAreEscaped() {
        assertEquals("<Rule><Filter>[count] &lt; 5 and [name] != 'a&amp;b'</Filter></Rule>",
            Rule.create().filter("[count] < 5 and [name] != 'a&b'").toXml());
    }

    @Test
    void ruleElseAndAlso() {
        assertEquals("<Rule><ElseFilter/></Rule>", Rule.create().elseFilter().toXml());
        assertEquals("<Rule><AlsoFilter/></Rule>", Rule.create().alsoFilter().toXml());
    }

    @Test
    void aRuleHasOnlyOneKindOfFilter() {
        assertThrows(IllegalStateException.class, () -> Rule.create().filter("[a]=1").elseFilter());
        assertThrows(IllegalStateException.class, () -> Rule.create().elseFilter().filter("[a]=1"));
        assertThrows(IllegalStateException.class, () -> Rule.create().alsoFilter().elseFilter());
        assertThrows(IllegalStateException.class, () -> Rule.create().filter("[a]=1").alsoFilter());
    }

    @Test
    void ruleRejectsNullSymbolizers() {
        assertThrows(IllegalArgumentException.class, () -> Rule.create().add((Symbolizer) null));
    }

    @Test
    void styleWritesAttributesAndRules() {
        Style s = Style.create("roads").opacity(0.75).compOp("multiply").filterMode(Style.FilterMode.FIRST)
            .imageFilters("blur(2)")
            .add(Rule.create().add(Symbolizer.line().stroke("gray")));
        assertEquals("<Style name=\"roads\" opacity=\"0.75\" comp-op=\"multiply\" filter-mode=\"first\" "
                + "image-filters=\"blur(2)\"><Rule><LineSymbolizer stroke=\"gray\"/></Rule></Style>",
            s.toXml());
        assertEquals("roads", s.name());
        assertEquals(1, s.rules().size());
    }

    @Test
    void styleNamesAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> Style.create(""));
        assertThrows(IllegalArgumentException.class, () -> Style.create("a b"));
        assertThrows(IllegalArgumentException.class, () -> Style.create("x\"><Evil/>"));
        assertEquals("ok_name-1", Style.create("ok_name-1").name());
    }

    @Test
    void fontSetWritesItsFaces() {
        assertEquals("<FontSet name=\"text\"><Font face-name=\"DejaVu Sans Book\"/><Font face-name=\"Noto Sans\"/></FontSet>",
            FontSet.create("text", "DejaVu Sans Book", "Noto Sans").toXml());
        assertThrows(IllegalArgumentException.class, () -> FontSet.create("text"));
        assertThrows(IllegalArgumentException.class, () -> FontSet.create("bad name", "x"));
    }
}
