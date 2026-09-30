package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Mapnik's expression language, path patterns and transforms against a real Mapnik. */
class ExpressionIntegrationTest {

    @BeforeAll
    static void setUp() throws IOException {
        Fixtures.dir();
    }

    private static Feature city() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("name", "Zürich");
        a.put("count", 7);
        a.put("ratio", 2.5);
        a.put("ok", true);
        a.put("none", null);
        return Feature.create(1, Geometry.point(1, 2), a);
    }

    private static Object eval(String expression) {
        try (Expression e = Expression.parse(expression)) {
            return e.evaluate(city());
        }
    }

    private static boolean matches(String expression) {
        try (Expression e = Expression.parse(expression)) {
            return e.matches(city());
        }
    }

    // ---------------------------------------------------------------- parsing

    @Test
    void validExpressionsParse() {
        for (String text : new String[] {"1 + 2", "[count] > 5", "[a] = 'x' and [b] != 2", "not [ok]", "length([name])",
            "[name].replace('a','b')", "@zoom >= 3", "([a] + [b]) * 2"}) {
            assertTrue(Expression.isValid(text), text);
            try (Expression e = Expression.parse(text)) {
                assertEquals(text, e.text());
                assertEquals(text, e.toString());
            }
        }
    }

    @Test
    void invalidExpressionsFailWithMapniksMessage() {
        for (String text : new String[] {"[broken", "1 +", "[a] ==", "'unterminated", "[name].length", "((1)"}) {
            MapnikException e = assertThrows(MapnikException.class, () -> Expression.parse(text), text);
            assertTrue(e.getMessage().contains(text), e.getMessage());
            assertFalse(Expression.isValid(text), text);
        }
    }

    @Test
    void theSameCheckUsedForStyles() {
        // A filter that Expression accepts is accepted by a style; one it rejects is rejected there too.
        String good = "[count] > 5 and [name] != 'x'";
        String bad = "[count] >";
        assertTrue(Expression.isValid(good));
        assertFalse(Expression.isValid(bad));
        try (MapnikMap map = new MapnikMap(10, 10)) {
            map.addStyle(Style.create("ok").add(Rule.create().filter(good).add(Symbolizer.polygon())));
            assertThrows(MapnikException.class,
                () -> map.addStyle(Style.create("no").add(Rule.create().filter(bad).add(Symbolizer.polygon()))));
        }
    }

    // ---------------------------------------------------------------- arithmetic and text

    @Test
    void arithmetic() {
        assertEquals(3L, eval("1 + 2"));
        assertEquals(-3L, eval("7 - 10"));
        assertEquals(3.0, eval("1.5 * 2"));
        assertEquals(1L, eval("10 % 3"));
        assertEquals(-7L, eval("-[count]"));
        assertEquals(17.5, eval("[count] * [ratio]"));
        assertEquals(14L, eval("([count] + 0) * 2"));
    }

    @Test
    void integerDivisionStaysAnInteger() {
        assertEquals(2L, eval("10 / 4"), "whole numbers divide to a whole number");
        assertEquals(2.5, eval("10.0 / 4"));
    }

    @Test
    void textConcatenationAndReplace() {
        assertEquals("ab", eval("'a' + 'b'"));
        assertEquals("Zürich (7)", eval("[name] + ' (' + [count] + ')'"));
        assertEquals("Zurich", eval("[name].replace('ü','u')"));
        assertEquals(6L, eval("length([name])"));
    }

    @Test
    void mathFunctions() {
        assertEquals(0.0, eval("sin(0)"));
        assertEquals(3.0, eval("abs(-3)"));
        assertEquals(5.0, eval("max(1, 5)"));
        assertEquals(1.0, eval("min(1, 5)"));
        assertEquals(1024.0, eval("pow(2, 10)"));
    }

    // ---------------------------------------------------------------- comparisons and logic

    @Test
    void comparisons() {
        assertEquals(Boolean.TRUE, eval("2 > 1"));
        assertEquals(Boolean.FALSE, eval("2 < 1"));
        assertEquals(Boolean.TRUE, eval("[count] = 7"));
        assertEquals(Boolean.FALSE, eval("[count] != 7"));
        assertEquals(Boolean.TRUE, eval("[name] = 'Zürich'"));
        assertEquals(Boolean.FALSE, eval("[name] = 'Zurich'"));
    }

    @Test
    void logic() {
        assertEquals(Boolean.TRUE, eval("[count] >= 7 and [ratio] < 3"));
        assertEquals(Boolean.TRUE, eval("[count] > 100 or [ok]"));
        assertEquals(Boolean.FALSE, eval("not [ok]"));
        assertEquals(Boolean.FALSE, eval("not([count] = 7)"));
        assertEquals(Boolean.TRUE, eval("([count] > 5) and ([name] = 'Zürich')"));
    }

    @Test
    void regularExpressionsMatchTheWholeValue() {
        assertEquals(Boolean.TRUE, eval("[name].match('Z.*')"));
        assertEquals(Boolean.FALSE, eval("[name].match('Z')"), "match is not a substring search");
        assertEquals(Boolean.FALSE, eval("[name].match('z.*')"));
    }

    // ---------------------------------------------------------------- missing values

    @Test
    void missingAndNullAttributesReadAsNull() {
        assertNull(eval("[nope]"));
        assertNull(eval("[none]"));
        assertEquals(Boolean.TRUE, eval("[nope] = null"));
        assertEquals(Boolean.TRUE, eval("[none] = null"));
        assertFalse(matches("[nope]"));
    }

    @Test
    void theGeometryTypeIsAnAttribute() {
        assertEquals(1L, eval("[mapnik::geometry_type]"), "1 is a point");
        Feature poly = Feature.create(1, Geometry.polygon(new double[] {0, 0, 1, 0, 1, 1, 0, 0}));
        try (Expression e = Expression.parse("[mapnik::geometry_type]")) {
            assertEquals(3L, e.evaluate(poly));
        }
    }

    // ---------------------------------------------------------------- matches

    @Test
    void matchesFollowsFilterTruthiness() {
        assertTrue(matches("[count] > 5"));
        assertFalse(matches("[count] > 50"));
        assertTrue(matches("1 + 2"));
        assertFalse(matches("sin(0)"), "zero is false");
        assertTrue(matches("[name]"), "non-empty text is true");
        assertFalse(matches("''"), "empty text is false");
    }

    @Test
    void expressionsFilterAListOfFeatures() {
        try (Expression big = Expression.parse("[count] >= 5")) {
            int kept = 0;
            for (int n = 0; n < 10; n++) {
                Feature f = Feature.create(n, Geometry.point(n, n), Collections.singletonMap("count", n));
                if (big.matches(f)) {
                    kept++;
                }
            }
            assertEquals(5, kept);
        }
    }

    @Test
    void oneExpressionCanBeUsedManyTimes() {
        try (Expression e = Expression.parse("[count] * 2")) {
            for (int n = 1; n <= 20; n++) {
                Feature f = Feature.create(n, Geometry.point(0, 0), Collections.singletonMap("count", n));
                assertEquals((long) n * 2, e.evaluate(f));
            }
        }
    }

    // ---------------------------------------------------------------- variables

    @Test
    void variablesFillInTheAtNames() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("zoom", 5);
        vars.put("label", "hi");
        vars.put("factor", 1.5);
        vars.put("on", true);
        try (Expression e = Expression.parse("@zoom + 1")) {
            assertEquals(6L, e.evaluate(city(), vars));
        }
        try (Expression e = Expression.parse("@label + '!'")) {
            assertEquals("hi!", e.evaluate(city(), vars));
        }
        try (Expression e = Expression.parse("@factor * 2")) {
            assertEquals(3.0, e.evaluate(city(), vars));
        }
        try (Expression e = Expression.parse("@on and [count] > 5")) {
            assertTrue(e.matches(city(), vars));
        }
    }

    @Test
    void anUnsetVariableIsNullAndVariablesAreNotAttributes() {
        try (Expression e = Expression.parse("@missing")) {
            assertNull(e.evaluate(city()));
        }
        Map<String, Object> vars = Collections.<String, Object>singletonMap("count", 99);
        try (Expression e = Expression.parse("[count]")) {
            assertEquals(7L, e.evaluate(city(), vars), "the feature's attribute wins; @count would read the variable");
        }
        try (Expression e = Expression.parse("@count")) {
            assertEquals(99L, e.evaluate(city(), vars));
        }
    }

    @Test
    void variablesMustBeSimpleValues() {
        try (Expression e = Expression.parse("@x")) {
            assertThrows(IllegalArgumentException.class,
                () -> e.evaluate(city(), Collections.<String, Object>singletonMap("x", new Object())));
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    void closedExpressionsRejectUse() {
        Expression e = Expression.parse("1 + 1");
        e.close();
        e.close();
        assertThrows(IllegalStateException.class, () -> e.evaluate(city()));
        assertThrows(IllegalStateException.class, () -> e.matches(city()));
    }

    @Test
    void aFeatureWithoutGeometryCanBeEvaluated() {
        Feature f = Feature.create(1, Geometry.empty(), Collections.singletonMap("count", 3));
        try (Expression e = Expression.parse("[count] + 1")) {
            assertEquals(4L, e.evaluate(f));
        }
    }

    // ---------------------------------------------------------------- path expressions

    @Test
    void pathPatternsFillInAttributes() {
        Feature f = city();
        try (PathExpression p = PathExpression.parse("icons/[name].png")) {
            assertEquals("icons/Zürich.png", p.evaluate(f));
            assertEquals("icons/[name].png", p.text());
            assertEquals("icons/[name].png", p.toString());
        }
        try (PathExpression p = PathExpression.parse("[count]")) {
            assertEquals("7", p.evaluate(f));
        }
        try (PathExpression p = PathExpression.parse("plain.png")) {
            assertEquals("plain.png", p.evaluate(f));
        }
    }

    @Test
    void aMissingAttributeBecomesEmpty() {
        try (PathExpression p = PathExpression.parse("icons/[nope]/x")) {
            assertEquals("icons//x", p.evaluate(city()));
        }
        try (PathExpression p = PathExpression.parse("icons/[none]/x")) {
            assertEquals("icons//x", p.evaluate(city()));
        }
    }

    @Test
    void oneFeatureAfterAnother() {
        try (PathExpression p = PathExpression.parse("tiles/[z]/[x].png")) {
            for (int n = 1; n <= 5; n++) {
                Map<String, Object> m = new HashMap<>();
                m.put("z", n);
                m.put("x", n * 10);
                assertEquals("tiles/" + n + "/" + n * 10 + ".png", p.evaluate(Feature.create(n, Geometry.empty(), m)));
            }
        }
    }

    @Test
    void badPathPatternsAreRejected() {
        MapnikException e = assertThrows(MapnikException.class, () -> PathExpression.parse("["));
        assertTrue(e.getMessage().contains("failed to parse path expression"), e.getMessage());
    }

    @Test
    void closedPathExpressionsRejectUse() {
        PathExpression p = PathExpression.parse("x");
        p.close();
        p.close();
        assertThrows(IllegalStateException.class, () -> p.evaluate(city()));
    }

    // ---------------------------------------------------------------- transforms

    @Test
    void validTransformsPass() {
        for (String t : new String[] {"translate(10,20)", "rotate(45) scale(2)", "matrix(1,0,0,1,5,5)", "skewX(10)",
            "translate([x], 5)"}) {
            assertTrue(Transforms.isValid(t), t);
            Transforms.check(t);
        }
    }

    @Test
    void invalidTransformsAreRejected() {
        for (String t : new String[] {"rotate(", "frobnicate(1)", "translate(", "scale(a b c d e f g)"}) {
            assertFalse(Transforms.isValid(t), t);
            assertThrows(MapnikException.class, () -> Transforms.check(t), t);
        }
    }

    @Test
    void transformsWorkInASymbolizerAttribute() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            map.addStyle(Style.create("t").add(Rule.create().add(
                Symbolizer.polygon().fill("red").attr("geometry-transform", "translate(5, 10) scale(2)"))));
            assertTrue(map.hasStyle("t"));
        }
    }
}
