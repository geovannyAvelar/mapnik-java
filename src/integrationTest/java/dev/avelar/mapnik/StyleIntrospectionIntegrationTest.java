package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Reading styles back from a real map, and using what comes back. */
class StyleIntrospectionIntegrationTest {
    private static Path dir;

    @BeforeAll
    static void setUp() throws IOException {
        dir = Fixtures.dir();
    }

    private static Style roads() {
        return Style.create("roads").opacity(0.9)
            .add(Rule.create().filter("[count] > 2").maxScaleDenominator(5000000)
                    .add(Symbolizer.markers().attr("marker-type", "ellipse").size(20, 20).fill("red").allowOverlap(true)),
                Rule.create().elseFilter()
                    .add(Symbolizer.markers().attr("marker-type", "ellipse").size(20, 20).fill("blue").allowOverlap(true)));
    }

    // ---------------------------------------------------------------- what comes back

    @Test
    void stylesLoadedFromAFileAreListed() {
        try (MapnikMap map = new MapnikMap(10, 10).load(dir.resolve("two-layers.xml"))) {
            List<Style> all = map.styles();
            assertEquals(Arrays.asList("blue", "red"), Arrays.asList(all.get(0).name(), all.get(1).name()));
            Symbolizer red = map.style("red").get().rules().get(0).symbolizers().get(0);
            assertEquals("PolygonSymbolizer", red.element());
            assertEquals("rgb(255,0,0)", red.attribute("fill").get());
        }
    }

    @Test
    void stylesAddedInCodeComeBackWithTheirRules() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            map.addStyle(roads());
            Style back = map.style("roads").get();
            assertEquals("roads", back.name());
            assertEquals(0.9, Double.parseDouble(back.attributes().get("opacity")), 1e-6, "Mapnik keeps opacity as a float");
            assertEquals(2, back.rules().size());
            assertTrue(back.rules().get(1).isElse());
            assertEquals(5000000.0, back.rules().get(0).maxScale().get(), 0);
            assertEquals("MarkersSymbolizer", back.rules().get(0).symbolizers().get(0).element());
            assertEquals("shape://ellipse", back.rules().get(0).symbolizers().get(0).attribute("file").get(),
                "Mapnik writes marker-type=ellipse as a shape:// file");
            assertEquals("20", back.rules().get(0).symbolizers().get(0).attribute("width").get());
        }
    }

    @Test
    void mapnikWritesFiltersInItsOwnSpelling() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            map.addStyle(roads());
            String filter = map.style("roads").get().rules().get(0).filterExpression().get();
            assertTrue(filter.contains("[count]") && filter.contains("2"), filter);
            assertTrue(Expression.isValid(filter), "what Mapnik writes is a valid expression: " + filter);
        }
    }

    @Test
    void unknownStylesAreEmpty() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            assertFalse(map.style("nope").isPresent());
            assertTrue(map.styles().isEmpty());
        }
    }

    @Test
    void theListIsASnapshot() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            map.addStyle(roads());
            List<Style> before = map.styles();
            map.removeStyle("roads");
            assertEquals(1, before.size());
            assertTrue(map.styles().isEmpty());
            assertThrows(UnsupportedOperationException.class, () -> before.add(roads()));
        }
    }

    // ---------------------------------------------------------------- using what comes back

    @Test
    void aStyleReadBackIsAcceptedByTheStrictParser() {
        try (MapnikMap a = new MapnikMap(10, 10); MapnikMap b = new MapnikMap(10, 10)) {
            a.addStyle(roads());
            b.addStyle(a.style("roads").get()); // strict: Mapnik accepts its own output
            assertTrue(b.hasStyle("roads"));
        }
    }

    @Test
    void aStyleReadBackDrawsTheSame() {
        Datasource points;
        try (MemoryDatasource ds = MemoryDatasource.create()
                .add(Geometry.point(1, 2), java.util.Collections.singletonMap("count", 3))
                .add(Geometry.point(-10, -10), java.util.Collections.singletonMap("count", 0))) {
            points = ds;
            byte[] original = render(ds, roads());

            Style read;
            try (MapnikMap scratch = new MapnikMap(10, 10)) {
                scratch.addStyle(roads());
                read = scratch.style("roads").get();
            }
            assertArrayEquals(original, render(ds, read), "the read-back style draws identically");
        }
    }

    private static byte[] render(MemoryDatasource ds, Style style) {
        try (MapnikMap map = new MapnikMap(200, 200).setSrs("epsg:4326").setBackground("white");
             Layer layer = Layer.create("d", "epsg:4326")) {
            map.addStyle(style);
            layer.addStyle(style.name()).setDatasource(ds);
            map.addLayer(layer).zoomToBox(-20, -20, 20, 20);
            return map.renderToPng();
        }
    }

    @Test
    void aReadBackStyleCanBeEditedAndReplaced() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            map.addStyle(roads());
            Style s = map.style("roads").get();
            s.add(Rule.create().add(Symbolizer.dot().fill("green").size(2, 2)));
            map.replaceStyle(s);
            assertEquals(3, map.style("roads").get().rules().size());
        }
    }

    @Test
    void theWholeMapRoundTripsThroughItsStyles() {
        String xml;
        try (MapnikMap a = new MapnikMap(10, 10).load(dir.resolve("two-layers.xml"))) {
            xml = a.toXml();
        }
        try (MapnikMap b = new MapnikMap(10, 10)) {
            for (Style s : StyleReader.stylesOf(xml)) {
                b.addStyle(s);
            }
            assertEquals(Arrays.asList("blue", "red"), b.styleNames());
            Optional<Style> red = b.style("red");
            assertTrue(red.isPresent());
        }
    }
}
