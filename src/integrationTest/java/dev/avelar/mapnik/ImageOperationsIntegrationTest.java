package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Image filters, blending, scaling, cropping and probing against a real Mapnik. */
class ImageOperationsIntegrationTest {
    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;

    @BeforeAll
    static void setUp() throws IOException {
        Fixtures.dir();
    }

    private static Image solid(int w, int h, int argb) {
        Image img = Image.create(w, h);
        img.fill(Color.fromArgb(argb));
        return img;
    }

    private static int a(int p) { return p >>> 24; }
    private static int r(int p) { return (p >> 16) & 0xFF; }
    private static int g(int p) { return (p >> 8) & 0xFF; }
    private static int b(int p) { return p & 0xFF; }

    private static void assertColour(int r, int g, int b, int a, int actual, int tol, String what) {
        assertEquals(r, r(actual), tol, what + " red of " + Integer.toHexString(actual));
        assertEquals(g, g(actual), tol, what + " green of " + Integer.toHexString(actual));
        assertEquals(b, b(actual), tol, what + " blue of " + Integer.toHexString(actual));
        assertEquals(a, a(actual), tol, what + " alpha of " + Integer.toHexString(actual));
    }

    // ---------------------------------------------------------------- filters

    @Test
    void invertFlipsTheColoursAndKeepsAlpha() {
        try (Image img = solid(3, 3, RED)) {
            img.filter(ImageFilters.invert());
            assertColour(0, 255, 255, 255, img.getArgb(1, 1), 1, "invert");
        }
    }

    @Test
    void invertKeepsPartialAlpha() {
        try (Image img = solid(3, 3, 0x80FF0000)) {
            img.filter(ImageFilters.invert());
            int p = img.getArgb(1, 1);
            assertEquals(0x80, a(p), 1, "alpha kept");
            assertColour(0, 255, 255, 0x80, p, 3, "invert");
        }
    }

    @Test
    void grayMakesTheChannelsEqual() {
        try (Image img = solid(3, 3, RED)) {
            img.filter(ImageFilters.gray());
            int p = img.getArgb(1, 1);
            assertEquals(r(p), g(p));
            assertEquals(g(p), b(p));
            assertTrue(r(p) > 40 && r(p) < 120, "luminance of red: " + r(p));
            assertEquals(255, a(p));
        }
    }

    @Test
    void blurSmearsAPixelOverItsThreeByThreeNeighbourhood() {
        try (Image img = solid(15, 15, WHITE)) {
            img.setArgb(7, 7, BLACK);
            img.filter(ImageFilters.blur());
            int centre = r(img.getArgb(7, 7));
            int neighbour = r(img.getArgb(8, 7));
            assertTrue(centre > 150 && centre < 250, "the black pixel is lighter now: " + centre);
            assertEquals(centre, neighbour, 5, "the smear is flat over the 3x3 block");
            assertTrue(r(img.getArgb(10, 7)) >= 250, "beyond the block it is barely touched");
        }
    }

    @Test
    void stackBlurIsSmoothAndItsReachFollowsTheRadius() {
        try (Image small = solid(15, 15, WHITE); Image wide = solid(15, 15, WHITE)) {
            small.setArgb(7, 7, BLACK);
            wide.setArgb(7, 7, BLACK);
            small.filter(ImageFilters.stackBlur(1, 1));
            wide.filter(ImageFilters.stackBlur(4, 4));

            assertEquals(255, r(small.getArgb(3, 7)), "radius 1 does not reach 4 pixels out");
            assertTrue(r(wide.getArgb(3, 7)) < 255, "radius 4 does");
            assertTrue(r(small.getArgb(7, 7)) < r(wide.getArgb(7, 7)),
                "a small radius keeps the centre darker than a large one");
            assertTrue(r(wide.getArgb(6, 7)) <= r(wide.getArgb(5, 7)), "smooth falloff away from the centre");
            assertEquals(r(wide.getArgb(5, 7)), r(wide.getArgb(9, 7)), 2, "symmetric");
        }
    }

    @Test
    void everyNamedFilterIsAccepted() {
        String[] all = {
            ImageFilters.blur(), ImageFilters.stackBlur(2, 2), ImageFilters.sharpen(), ImageFilters.emboss(),
            ImageFilters.edgeDetect(), ImageFilters.sobel(), ImageFilters.xGradient(), ImageFilters.yGradient(),
            ImageFilters.gray(), ImageFilters.invert(), ImageFilters.colorBlindProtanope(),
            ImageFilters.colorBlindDeuteranope(), ImageFilters.colorBlindTritanope(),
            ImageFilters.colorToAlpha("white"), ImageFilters.colorToAlpha(Color.BLACK),
            ImageFilters.scaleHsla(0, 1, 0, 1, 0, 1, 0, 1)};
        for (String f : all) {
            try (Image img = solid(8, 8, RED)) {
                img.setArgb(2, 2, BLUE);
                img.filter(f);
                assertEquals(8, img.width(), f);
            }
        }
    }

    @Test
    void filtersRunInOrder() {
        try (Image together = solid(3, 3, RED); Image apart = solid(3, 3, RED)) {
            together.filter(ImageFilters.invert(), ImageFilters.gray());
            apart.filter(ImageFilters.invert()).filter(ImageFilters.gray());
            assertEquals(apart.getArgb(1, 1), together.getArgb(1, 1));
        }
    }

    @Test
    void filterSyntaxCanBeWrittenByHand() {
        try (Image img = solid(3, 3, RED)) {
            img.filter("invert gray");
            int p = img.getArgb(1, 1);
            assertEquals(r(p), g(p));
        }
    }

    @Test
    void colorToAlphaFilterMakesThatColourTransparent() {
        try (Image img = solid(4, 4, WHITE)) {
            img.setArgb(1, 1, RED);
            img.filter(ImageFilters.colorToAlpha(Color.WHITE));
            assertEquals(0, a(img.getArgb(0, 0)), "white became transparent");
            assertEquals(255, a(img.getArgb(1, 1)), "red stays opaque");
        }
    }

    @Test
    void saturationScalingTurnsColourGray() {
        try (Image img = solid(3, 3, RED)) {
            img.filter(ImageFilters.scaleHsla(0, 1, 0, 0, 0, 1, 0, 1));
            int p = img.getArgb(1, 1);
            assertEquals(r(p), g(p), 2);
            assertEquals(g(p), b(p), 2);
        }
    }

    @Test
    void badFiltersAreRejectedAndLeaveTheImageAlone() {
        try (Image img = solid(3, 3, RED)) {
            assertThrows(MapnikException.class, () -> img.filter("not-a-filter"));
            assertThrows(MapnikException.class, () -> img.filter("blur(", "gray"));
            assertEquals(RED, img.getArgb(1, 1));
            assertThrows(IllegalArgumentException.class, () -> img.filter());
        }
    }

    // ---------------------------------------------------------------- blending

    @Test
    void srcOverPaintsTheSourceOnTop() {
        try (Image dst = solid(6, 6, BLUE); Image src = solid(3, 3, RED)) {
            dst.composite(src, 2, 2);
            assertEquals(BLUE, dst.getArgb(0, 0));
            assertEquals(BLUE, dst.getArgb(1, 2));
            assertEquals(RED, dst.getArgb(2, 2));
            assertEquals(RED, dst.getArgb(4, 4));
            assertEquals(BLUE, dst.getArgb(5, 5));
            assertEquals(RED, src.getArgb(1, 1), "the source is not changed");
        }
    }

    @Test
    void partsOutsideTheDestinationAreClipped() {
        try (Image dst = solid(4, 4, BLUE); Image src = solid(4, 4, RED)) {
            dst.composite(src, -2, -2);
            assertEquals(RED, dst.getArgb(0, 0));
            assertEquals(RED, dst.getArgb(1, 1));
            assertEquals(BLUE, dst.getArgb(2, 2));

            dst.composite(src, 50, 50);
            dst.composite(src, -50, -50);
            assertEquals(BLUE, dst.getArgb(3, 3), "fully outside changes nothing");
        }
    }

    @Test
    void theSourceCanBeADifferentSize() {
        try (Image dst = solid(10, 2, BLUE); Image src = solid(2, 6, RED)) {
            dst.composite(src, 4, 0);
            assertEquals(RED, dst.getArgb(4, 1));
            assertEquals(RED, dst.getArgb(5, 0));
            assertEquals(BLUE, dst.getArgb(6, 0));
        }
    }

    @Test
    void blendModesCombineTheColoursDifferently() {
        int[][] cases = {
            // mode index, then expected r, g, b of red drawn over blue
        };
        assertBlend(BlendMode.MULTIPLY, 0, 0, 0);
        assertBlend(BlendMode.SCREEN, 255, 0, 255);
        assertBlend(BlendMode.PLUS, 255, 0, 255);
        assertBlend(BlendMode.DIFFERENCE, 255, 0, 255);
        assertBlend(BlendMode.DARKEN, 0, 0, 0);
        assertBlend(BlendMode.LIGHTEN, 255, 0, 255);
        assertBlend(BlendMode.SRC, 255, 0, 0);
        assertBlend(BlendMode.DST, 0, 0, 255);
        assertBlend(BlendMode.SRC_OVER, 255, 0, 0);
        assertBlend(BlendMode.DST_OVER, 0, 0, 255);
    }

    private static void assertBlend(BlendMode mode, int r, int g, int b) {
        try (Image dst = solid(2, 2, BLUE); Image src = solid(2, 2, RED)) {
            dst.composite(src, mode, 1.0, 0, 0);
            assertColour(r, g, b, 255, dst.getArgb(0, 0), 1, mode.xmlName());
        }
    }

    @Test
    void clearEmptiesTheOverlap() {
        try (Image dst = solid(4, 4, BLUE); Image src = solid(2, 2, RED)) {
            dst.composite(src, BlendMode.CLEAR, 1.0, 0, 0);
            assertEquals(0, a(dst.getArgb(0, 0)));
            assertEquals(BLUE, dst.getArgb(3, 3));
        }
    }

    @Test
    void opacityMixesSourceAndDestination() {
        try (Image dst = solid(2, 2, BLUE); Image src = solid(2, 2, RED)) {
            dst.composite(src, BlendMode.SRC_OVER, 0.5, 0, 0);
            assertColour(128, 0, 127, 255, dst.getArgb(0, 0), 2, "half red over blue");
        }
        try (Image dst = solid(2, 2, BLUE); Image src = solid(2, 2, RED)) {
            dst.composite(src, BlendMode.SRC_OVER, 0.0, 0, 0);
            assertEquals(BLUE, dst.getArgb(0, 0), "opacity 0 changes nothing");
        }
    }

    @Test
    void aTransparentSourceChangesNothing() {
        try (Image dst = solid(2, 2, BLUE); Image src = Image.create(2, 2)) {
            dst.composite(src, 0, 0);
            assertEquals(BLUE, dst.getArgb(1, 1));
        }
    }

    @Test
    void semiTransparentSourceOnATransparentImageKeepsItsColour() {
        try (Image dst = Image.create(2, 2); Image src = solid(2, 2, 0x80FF0000)) {
            dst.composite(src, 0, 0);
            assertColour(255, 0, 0, 0x80, dst.getArgb(0, 0), 2, "straight alpha result");
        }
    }

    @Test
    void semiTransparentSourceOverAnOpaqueOne() {
        try (Image dst = solid(2, 2, BLUE); Image src = solid(2, 2, 0x80FF0000)) {
            dst.composite(src, 0, 0);
            assertColour(128, 0, 127, 255, dst.getArgb(0, 0), 2, "half red over blue");
        }
    }

    @Test
    void everyBlendModeIsAcceptedAndNamedAsMapnikNamesIt() {
        Set<String> names = new HashSet<>();
        for (BlendMode mode : BlendMode.values()) {
            assertTrue(names.add(mode.xmlName()), "duplicate " + mode);
            assertEquals(mode, BlendMode.fromXmlName(mode.xmlName()));
            try (Image dst = solid(2, 2, BLUE); Image src = solid(2, 2, RED)) {
                dst.composite(src, mode, 1.0, 0, 0);
            }
            try (Layer l = Layer.create("x")) {
                l.setCompOp(mode);
                assertEquals(mode.xmlName(), l.compOp().get(), "Mapnik's name for " + mode);
            }
        }
        assertEquals(37, names.size());
        assertThrows(IllegalArgumentException.class, () -> BlendMode.fromXmlName("nope"));
    }

    @Test
    void blendModeOverloadsReachStylesAndMaps() {
        try (MapnikMap map = new MapnikMap(10, 10)) {
            map.setBackgroundImageCompOp(BlendMode.OVERLAY);
            assertEquals("overlay", map.backgroundImageCompOp().get());
        }
        assertTrue(Symbolizer.polygon().compOp(BlendMode.MULTIPLY).toXml().contains("comp-op=\"multiply\""));
        assertTrue(Style.create("s").compOp(BlendMode.SCREEN).toXml().contains("comp-op=\"screen\""));
    }

    // ---------------------------------------------------------------- scaling

    @Test
    void scalingMakesANewImageOfTheRequestedSize() {
        try (Image src = solid(4, 4, RED); Image big = src.scaled(10, 6)) {
            assertEquals(10, big.width());
            assertEquals(6, big.height());
            assertEquals(4, src.width(), "the original is untouched");
            assertEquals(RED, big.getArgb(5, 3));
        }
    }

    @Test
    void aSolidColourStaysSolidWithEveryMethod() {
        for (ScalingMethod m : ScalingMethod.values()) {
            try (Image src = solid(8, 8, RED); Image up = src.scaled(16, 16, m); Image down = src.scaled(5, 5, m)) {
                assertColour(255, 0, 0, 255, up.getArgb(8, 8), 2, m + " up");
                assertColour(255, 0, 0, 255, down.getArgb(2, 2), 2, m + " down");
            }
        }
    }

    @Test
    void nearestNeighbourKeepsHardEdges() {
        try (Image src = Image.create(2, 2)) {
            src.setArgb(0, 0, RED).setArgb(1, 0, BLUE).setArgb(0, 1, BLUE).setArgb(1, 1, RED);
            try (Image big = src.scaled(4, 4, ScalingMethod.NEAR)) {
                assertEquals(RED, big.getArgb(0, 0));
                assertEquals(RED, big.getArgb(1, 1));
                assertEquals(BLUE, big.getArgb(2, 0));
                assertEquals(BLUE, big.getArgb(3, 1));
                assertEquals(BLUE, big.getArgb(0, 3));
                assertEquals(RED, big.getArgb(3, 3));
            }
        }
    }

    @Test
    void bilinearBlendsBetweenPixels() {
        try (Image src = Image.create(2, 1)) {
            src.setArgb(0, 0, BLACK).setArgb(1, 0, WHITE);
            try (Image big = src.scaled(8, 1, ScalingMethod.BILINEAR)) {
                int mid = r(big.getArgb(4, 0));
                assertTrue(mid > 30 && mid < 225, "an in-between grey, got " + mid);
                assertTrue(r(big.getArgb(1, 0)) < r(big.getArgb(6, 0)), "left darker than right");
            }
        }
    }

    @Test
    void scalingKeepsStraightAlpha() {
        try (Image src = solid(6, 6, 0x80FF0000); Image big = src.scaled(12, 12, ScalingMethod.BILINEAR)) {
            assertColour(255, 0, 0, 0x80, big.getArgb(6, 6), 3, "scaled half-transparent red");
        }
    }

    @Test
    void badScalingRequestsFail() {
        try (Image src = solid(4, 4, RED)) {
            assertThrows(MapnikException.class, () -> src.scaled(0, 5));
            assertThrows(MapnikException.class, () -> src.scaled(5, -1));
            assertEquals(RED, src.getArgb(0, 0));
        }
    }

    // ---------------------------------------------------------------- crop and copy

    private static Image gradient(int w, int h) {
        Image img = Image.create(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setArgb(x, y, 0xFF000000 | (x << 8) | y);
            }
        }
        return img;
    }

    @Test
    void cropCopiesTheRequestedArea() {
        try (Image src = gradient(10, 10); Image part = src.crop(3, 4, 2, 3)) {
            assertEquals(2, part.width());
            assertEquals(3, part.height());
            assertEquals(0xFF000000 | (3 << 8) | 4, part.getArgb(0, 0));
            assertEquals(0xFF000000 | (4 << 8) | 6, part.getArgb(1, 2));
            assertEquals(10, src.width(), "the original is untouched");
        }
    }

    @Test
    void cropMustBeInsideTheImage() {
        try (Image src = gradient(10, 10)) {
            assertThrows(MapnikException.class, () -> src.crop(9, 9, 2, 2));
            assertThrows(MapnikException.class, () -> src.crop(-1, 0, 2, 2));
            assertThrows(MapnikException.class, () -> src.crop(0, 0, 0, 2));
            try (Image whole = src.crop(0, 0, 10, 10)) {
                assertEquals(src.getArgb(9, 9), whole.getArgb(9, 9));
            }
        }
    }

    @Test
    void copiesAreIndependent() {
        try (Image src = solid(3, 3, RED); Image copy = src.copy()) {
            copy.fill("blue");
            assertEquals(RED, src.getArgb(1, 1));
            assertEquals(BLUE, copy.getArgb(1, 1));
        }
    }

    @Test
    void copyKeepsPartialAlphaExactly() {
        try (Image src = solid(3, 3, 0x80336699); Image copy = src.copy()) {
            assertEquals(0x80336699, copy.getArgb(1, 1));
        }
    }

    // ---------------------------------------------------------------- opacity and colour to alpha

    @Test
    void applyOpacityScalesAlpha() {
        try (Image img = solid(2, 2, RED)) {
            img.applyOpacity(0.5);
            assertColour(255, 0, 0, 128, img.getArgb(0, 0), 1, "half opacity");
            img.applyOpacity(0.5);
            assertEquals(64, a(img.getArgb(0, 0)), 1, "applied twice");
            img.applyOpacity(0);
            assertEquals(0, a(img.getArgb(0, 0)));
        }
    }

    @Test
    void applyOpacityLeavesTransparentPixelsTransparent() {
        try (Image img = Image.create(2, 2)) {
            img.applyOpacity(0.7);
            assertEquals(0, a(img.getArgb(0, 0)));
        }
    }

    @Test
    void applyOpacityIsRangeChecked() {
        try (Image img = solid(2, 2, RED)) {
            assertThrows(IllegalArgumentException.class, () -> img.applyOpacity(1.5));
            assertThrows(IllegalArgumentException.class, () -> img.applyOpacity(-0.1));
            assertThrows(IllegalArgumentException.class, () -> img.applyOpacity(Double.NaN));
            assertEquals(RED, img.getArgb(0, 0));
        }
    }

    @Test
    void colorToAlphaMakesAColourTransparent() {
        try (Image img = solid(4, 4, WHITE)) {
            img.setArgb(1, 1, RED);
            img.colorToAlpha(Color.WHITE);
            assertEquals(0, a(img.getArgb(0, 0)));
            assertEquals(255, a(img.getArgb(1, 1)));
            assertEquals(255, r(img.getArgb(1, 1)));
        }
    }

    // ---------------------------------------------------------------- probe

    private static Image sample() {
        Image img = Image.create(13, 7);
        img.fill("red");
        return img;
    }

    @Test
    void probeReadsSizeAndFormatFromBytes() {
        try (Image img = sample()) {
            String[][] formats = {{"png", "png"}, {"png8", "png"}, {"jpeg", "jpeg"}, {"webp", "webp"}, {"tiff", "tiff"}};
            for (String[] f : formats) {
                Image.Info info = Image.probe(img.toBytes(f[0]));
                assertEquals(13, info.width(), f[0]);
                assertEquals(7, info.height(), f[0]);
                assertEquals(f[1], info.format(), f[0]);
            }
        }
    }

    @Test
    void probeReadsAFile(@TempDir Path tmp) {
        Path file = tmp.resolve("x.png");
        try (Image img = sample()) {
            img.save(file, "png");
        }
        Image.Info info = Image.probe(file);
        assertEquals(13, info.width());
        assertEquals(7, info.height());
        assertEquals("png", info.format());
        assertTrue(info.toString().contains("13x7"), info.toString());
    }

    @Test
    void probeRejectsWhatIsNotAnImage(@TempDir Path tmp) {
        assertThrows(MapnikException.class, () -> Image.probe(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));
        assertThrows(MapnikException.class, () -> Image.probe(new byte[0]));
        assertThrows(MapnikException.class, () -> Image.probe(tmp.resolve("missing.png")));
    }
}
