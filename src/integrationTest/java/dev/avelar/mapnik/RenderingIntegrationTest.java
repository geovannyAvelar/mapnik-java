package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Render options, output formats, Image, and Cairo output against a real Mapnik. */
class RenderingIntegrationTest {
    private static final int RED = 0xFFFF0000;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLUE = 0xFF0000FF;
    private static final int TRANSPARENT = 0x00000000;

    private static Path dir;

    @BeforeAll
    static void setUp() throws IOException {
        dir = Fixtures.dir();
    }

    private static MapnikMap map(String style) {
        return new MapnikMap(200, 200).load(dir.resolve(style)).zoomToBox(-20, -20, 20, 20);
    }

    private static BufferedImage decode(byte[] data) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
        assertNotNull(img, "not a decodable image");
        return img;
    }

    private static int count(BufferedImage img, int rgb) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (img.getRGB(x, y) == rgb) {
                    n++;
                }
            }
        }
        return n;
    }

    private static String start(byte[] data, int n) {
        return new String(data, 0, Math.min(n, data.length), StandardCharsets.ISO_8859_1);
    }

    // ---------------------------------------------------------------- formats

    @Test
    void indexedPngFormats() throws IOException {
        try (MapnikMap m = map("square.xml")) {
            for (String format : new String[] {"png8", "png256"}) {
                byte[] data = m.renderToBytes(format);
                assertEquals("\u0089PNG", start(data, 4), format);
                BufferedImage img = decode(data);
                assertEquals(200, img.getWidth());
                assertEquals(RED, img.getRGB(100, 100), format);
                assertEquals(WHITE, img.getRGB(10, 10), format);
            }
        }
    }

    @Test
    void indexedPngIsSmallerThanTrueColourPng() {
        try (MapnikMap m = map("square.xml")) {
            assertTrue(m.renderToBytes("png8").length < m.renderToBytes("png").length);
        }
    }

    @Test
    void jpegQualityOption() throws IOException {
        try (MapnikMap m = map("outline.xml")) {
            byte[] low = m.renderToBytes("jpeg10");
            byte[] high = m.renderToBytes("jpeg95");
            assertEquals((byte) 0xFF, low[0]);
            assertEquals((byte) 0xD8, low[1]);
            assertTrue(high.length > low.length, low.length + " vs " + high.length);
            assertNotNull(decode(high));
        }
    }

    @Test
    void webpAndTiff() {
        try (MapnikMap m = map("square.xml")) {
            byte[] webp = m.renderToBytes("webp");
            assertEquals("RIFF", start(webp, 4));
            assertEquals("WEBP", new String(webp, 8, 4, StandardCharsets.ISO_8859_1));

            byte[] tiff = m.renderToBytes("tiff");
            String magic = start(tiff, 4);
            assertTrue(magic.equals("II*\0") || magic.equals("MM\0*"), "tiff magic");
        }
    }

    @Test
    void pngCompressionOption() {
        try (MapnikMap m = map("outline.xml")) {
            assertTrue(m.renderToBytes("png:z=9").length <= m.renderToBytes("png:z=1").length);
        }
    }

    @Test
    void renderToFileWritesTheSameBytes(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("map.png");
        try (MapnikMap m = map("square.xml")) {
            m.renderToFile(out, "png8");
            assertArrayEquals(m.renderToBytes("png8"), Files.readAllBytes(out));
        }
    }

    // ---------------------------------------------------------------- options

    @Test
    void scaleFactorScalesLineWidths() throws IOException {
        try (MapnikMap m = map("outline.xml")) {
            int thin = count(decode(m.renderToBytes("png")), 0xFF000000);
            int thick = count(decode(m.renderToBytes("png", RenderOptions.defaults().scaleFactor(2))), 0xFF000000);
            assertTrue(thin > 0);
            assertTrue(thick > thin * 1.5, "scale 2 should roughly double the line width: " + thin + " -> " + thick);
        }
    }

    @Test
    void offsetMakesTheImageAWindowOntoALargerMap() throws IOException {
        try (MapnikMap m = map("square.xml")) {
            // The square covers pixels 50..149 on both axes.
            BufferedImage plain = decode(m.renderToBytes("png"));
            assertEquals(RED, plain.getRGB(135, 100));
            assertEquals(WHITE, plain.getRGB(35, 100));
            assertEquals(RED, plain.getRGB(100, 145));

            // Offset (20, 10): the drawing moves left by 20 and up by 10, to x 30..129 and y 40..139.
            BufferedImage moved = decode(m.renderToBytes("png", RenderOptions.defaults().offset(20, 10)));
            assertEquals(WHITE, moved.getRGB(135, 100), "right edge moved left");
            assertEquals(RED, moved.getRGB(35, 100), "left edge moved left");
            assertEquals(WHITE, moved.getRGB(100, 145), "bottom edge moved up");
            assertEquals(RED, moved.getRGB(100, 45), "top edge moved up");
        }
    }

    @Test
    void offsetsLetYouRenderABigMapInTiles() throws IOException {
        // A window that starts at x = 100 shows the right half of the whole picture, at its left edge.
        try (MapnikMap whole = map("square.xml");
             MapnikMap tile = new MapnikMap(200, 200).load(dir.resolve("square.xml")).zoomToBox(-20, -20, 20, 20)) {
            BufferedImage full = decode(whole.renderToBytes("png"));
            BufferedImage right = decode(tile.renderToBytes("png", RenderOptions.defaults().offset(100, 0)));
            for (int y = 0; y < 200; y += 20) {
                for (int x = 0; x < 100; x += 10) {
                    assertEquals(full.getRGB(x + 100, y), right.getRGB(x, y), x + "," + y);
                }
            }
        }
    }

    @Test
    void optionsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> RenderOptions.defaults().scaleFactor(0));
        assertThrows(IllegalArgumentException.class, () -> RenderOptions.defaults().scaleFactor(-1));
        assertThrows(IllegalArgumentException.class, () -> RenderOptions.defaults().offset(-1, 0));
        RenderOptions o = RenderOptions.defaults().scaleFactor(2).offset(3, 4);
        assertEquals(2, o.scaleFactor(), 0);
        assertEquals(3, o.offsetX());
        assertEquals(4, o.offsetY());
        assertEquals(1, RenderOptions.defaults().scaleFactor(), 0, "defaults are not modified");
    }

    // ---------------------------------------------------------------- Image basics

    @Test
    void createdImageIsTransparentAndCanBeFilled() {
        try (Image img = Image.create(10, 5)) {
            assertEquals(10, img.width());
            assertEquals(5, img.height());
            assertEquals(TRANSPARENT, img.getArgb(3, 3));
            assertTrue(img.isSolid());

            img.fill("red");
            assertEquals(RED, img.getArgb(0, 0));
            assertEquals(RED, img.getArgb(9, 4));
            assertTrue(img.isSolid());

            img.fill("#0000ff");
            assertEquals(BLUE, img.getArgb(5, 2));
        }
    }

    @Test
    void pixelsRoundTripWithAlpha() {
        try (Image img = Image.create(4, 4)) {
            img.setArgb(1, 2, 0x80336699);
            assertEquals(0x80336699, img.getArgb(1, 2));
            assertEquals(TRANSPARENT, img.getArgb(0, 0));
            assertFalse(img.isSolid());
        }
    }

    @Test
    void toArgbListsPixelsRowByRow() {
        try (Image img = Image.create(3, 2)) {
            img.setArgb(0, 0, 0xFF010203).setArgb(2, 1, 0xFF0A0B0C);
            int[] px = img.toArgb();
            assertEquals(6, px.length);
            assertEquals(0xFF010203, px[0]);
            assertEquals(0xFF0A0B0C, px[5]);
            assertEquals(0, px[1]);
        }
    }

    @Test
    void outOfRangePixelsAreRejected() {
        try (Image img = Image.create(3, 3)) {
            assertThrows(IndexOutOfBoundsException.class, () -> img.getArgb(3, 0));
            assertThrows(IndexOutOfBoundsException.class, () -> img.getArgb(0, -1));
            assertThrows(IndexOutOfBoundsException.class, () -> img.setArgb(0, 3, 0));
        }
    }

    @Test
    void invalidSizesAndColoursAreRejected() {
        assertThrows(MapnikException.class, () -> Image.create(0, 5));
        assertThrows(MapnikException.class, () -> Image.create(5, -1));
        try (Image img = Image.create(2, 2)) {
            assertThrows(MapnikException.class, () -> img.fill("not-a-colour"));
        }
    }

    // ---------------------------------------------------------------- Image encode and decode

    @Test
    void imageSurvivesAPngRoundTripWithExactAlpha(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("pixels.png");
        try (Image img = Image.create(8, 8)) {
            img.fill("white").setArgb(2, 3, 0x80FF0000).setArgb(4, 4, 0xFF123456).setArgb(5, 5, 0x00000000);
            img.save(file, "png");

            try (Image viaFile = Image.load(file); Image viaBytes = Image.load(img.toPng())) {
                for (Image loaded : new Image[] {viaFile, viaBytes}) {
                    assertEquals(8, loaded.width());
                    assertEquals(0x80FF0000, loaded.getArgb(2, 3), "straight alpha must survive");
                    assertEquals(0xFF123456, loaded.getArgb(4, 4));
                    assertEquals(0x00000000, loaded.getArgb(5, 5));
                    assertEquals(WHITE, loaded.getArgb(0, 0));
                }
            }
        }
    }

    @Test
    void encodedImageMatchesWhatJavaDecodes() throws IOException {
        try (Image img = Image.create(6, 6)) {
            img.fill("#336699").setArgb(1, 1, 0xFFFF0000);
            BufferedImage b = decode(img.toPng());
            assertEquals(0xFFFF0000, b.getRGB(1, 1));
            assertEquals(0xFF336699, b.getRGB(0, 0));
        }
    }

    @Test
    void imageFormatsAreEncoded() {
        try (Image img = Image.create(16, 16)) {
            img.fill("red");
            assertEquals((byte) 0xFF, img.toBytes("jpeg")[0]);
            assertEquals("RIFF", start(img.toBytes("webp"), 4));
            assertEquals("\u0089PNG", start(img.toBytes("png8"), 4));
        }
    }

    @Test
    void loadErrors(@TempDir Path tmp) {
        assertThrows(MapnikException.class, () -> Image.load(tmp.resolve("missing.png")));
        assertThrows(MapnikException.class, () -> Image.load(new byte[] {1, 2, 3, 4, 5}));
        assertThrows(MapnikException.class, () -> Image.load(new byte[0]));
    }

    @Test
    void closedImageRejectsUse() {
        Image img = Image.create(2, 2);
        img.close();
        img.close();
        assertThrows(IllegalStateException.class, img::width);
    }

    // ---------------------------------------------------------------- rendering to images

    @Test
    void renderToImageMatchesRenderToPng() throws IOException {
        try (MapnikMap m = map("square.xml"); Image img = m.renderToImage()) {
            assertEquals(200, img.width());
            assertEquals(200, img.height());
            assertEquals(RED, img.getArgb(100, 100));
            assertEquals(WHITE, img.getArgb(10, 10));

            BufferedImage viaPng = decode(m.renderToPng());
            for (int[] p : new int[][] {{100, 100}, {10, 10}, {55, 55}, {49, 100}, {150, 100}}) {
                assertEquals(viaPng.getRGB(p[0], p[1]), img.getArgb(p[0], p[1]), p[0] + "," + p[1]);
            }
        }
    }

    @Test
    void renderingBlendsOverTheExistingImage() {
        try (MapnikMap m = map("transparent-square.xml"); Image img = Image.create(200, 200)) {
            img.fill("blue");
            m.render(img);
            assertEquals(RED, img.getArgb(100, 100), "opaque red covers blue");
            assertEquals(BLUE, img.getArgb(10, 10), "transparent background leaves blue alone");
        }
    }

    @Test
    void semiTransparentDrawingBlendsWithStraightAlphaResult() {
        try (MapnikMap m = map("half-red.xml"); Image img = Image.create(200, 200)) {
            img.fill("blue");
            m.render(img);
            int centre = img.getArgb(100, 100);
            assertEquals(0xFF, centre >>> 24, "still opaque");
            assertEquals(128, (centre >> 16) & 0xFF, 2, "half red over blue: red channel");
            assertEquals(0, (centre >> 8) & 0xFF, 2);
            assertEquals(127, centre & 0xFF, 2, "half red over blue: blue channel");
            assertEquals(BLUE, img.getArgb(10, 10));
        }
    }

    @Test
    void semiTransparentDrawingOnATransparentImageKeepsItsAlpha() {
        try (MapnikMap m = map("half-red.xml"); Image img = Image.create(200, 200)) {
            m.render(img);
            int centre = img.getArgb(100, 100);
            assertEquals(128, centre >>> 24, 2, "alpha is half");
            assertEquals(255, (centre >> 16) & 0xFF, 1, "colour is straight, not premultiplied");
            assertEquals(0, (centre >> 8) & 0xFF, 1);
            assertEquals(0, centre & 0xFF, 1);
            assertEquals(TRANSPARENT, img.getArgb(10, 10));
        }
    }

    @Test
    void renderingTwiceStacksTheAlpha() {
        try (MapnikMap m = map("half-red.xml"); Image img = Image.create(200, 200)) {
            m.render(img).render(img);
            int alpha = img.getArgb(100, 100) >>> 24;
            assertEquals(192, alpha, 3, "1 - 0.5 * 0.5 = 0.75");
        }
    }

    @Test
    void renderToImageWithOptions() {
        try (MapnikMap m = map("outline.xml"); Image thin = m.renderToImage();
             Image thick = m.renderToImage(RenderOptions.defaults().scaleFactor(3))) {
            assertTrue(countBlack(thick) > countBlack(thin) * 2);
        }
    }

    private static int countBlack(Image img) {
        int n = 0;
        for (int px : img.toArgb()) {
            if (px == 0xFF000000) {
                n++;
            }
        }
        return n;
    }

    @Test
    void renderToImageChecksTheSize() {
        try (MapnikMap m = map("square.xml"); Image wrong = Image.create(100, 100)) {
            MapnikException e = assertThrows(MapnikException.class, () -> m.render(wrong));
            assertTrue(e.getMessage().contains("size"), e.getMessage());
            assertEquals(TRANSPARENT, wrong.getArgb(50, 50), "nothing was drawn");
        }
    }

    @Test
    void imageCanBeReusedAcrossRenders() {
        try (Image img = Image.create(200, 200); MapnikMap a = map("transparent-square.xml")) {
            a.render(img);
            assertEquals(RED, img.getArgb(100, 100));
            img.fill("white");
            a.render(img);
            assertEquals(RED, img.getArgb(100, 100));
            assertEquals(WHITE, img.getArgb(5, 5));
        }
    }

    // ---------------------------------------------------------------- Cairo (vector) output

    @Test
    void pdfSvgAndPostScript(@TempDir Path tmp) throws IOException {
        assumeTrue(Mapnik.hasCairo(), "this Mapnik has no Cairo support");
        try (MapnikMap m = map("square.xml")) {
            Path pdf = tmp.resolve("map.pdf");
            m.renderToFile(pdf, "pdf");
            assertEquals("%PDF", start(Files.readAllBytes(pdf), 4));

            Path svg = tmp.resolve("map.svg");
            m.renderToFile(svg, "svg");
            assertTrue(new String(Files.readAllBytes(svg), StandardCharsets.UTF_8).contains("<svg"));

            Path ps = tmp.resolve("map.ps");
            m.renderToFile(ps, "ps");
            assertEquals("%!PS", start(Files.readAllBytes(ps), 4));
        }
    }

    @Test
    void vectorOutputToBytesAndFormatNamesAreCaseInsensitive() {
        assumeTrue(Mapnik.hasCairo(), "this Mapnik has no Cairo support");
        try (MapnikMap m = map("square.xml")) {
            assertEquals("%PDF", start(m.renderToBytes("PDF"), 4));
            assertTrue(start(m.renderToBytes("svg"), 400).contains("<svg"));
        }
    }

    @Test
    void vectorOutputHonoursTheScaleFactor(@TempDir Path tmp) throws IOException {
        assumeTrue(Mapnik.hasCairo(), "this Mapnik has no Cairo support");
        try (MapnikMap m = map("outline.xml")) { // line widths scale; a filled polygon has none
            Path one = tmp.resolve("one.svg");
            Path two = tmp.resolve("two.svg");
            m.renderToFile(one, "svg");
            m.renderToFile(two, "svg", RenderOptions.defaults().scaleFactor(2));
            assertNotEquals(new String(Files.readAllBytes(one), StandardCharsets.UTF_8),
                new String(Files.readAllBytes(two), StandardCharsets.UTF_8));
        }
    }

    @Test
    void unknownRasterFormatStillFails() {
        try (MapnikMap m = map("square.xml")) {
            assertThrows(MapnikException.class, () -> m.renderToBytes("not-a-format"));
        }
    }
}
