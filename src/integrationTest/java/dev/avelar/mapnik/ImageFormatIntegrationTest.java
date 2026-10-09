package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The formats built by {@link ImageFormat} do what they say when Mapnik encodes with them. */
class ImageFormatIntegrationTest {
    @BeforeAll
    static void setUp() throws Exception {
        Fixtures.dir();
    }

    /** A picture with thousands of colours and a half transparent corner. */
    private static Image picture() {
        Image img = Image.create(96, 96);
        for (int y = 0; y < 96; y++) {
            for (int x = 0; x < 96; x++) {
                int alpha = (x < 24 && y < 24) ? 128 : 255;
                img.setArgb(x, y, (alpha << 24) | ((x * 2) << 16) | ((y * 2) << 8) | ((x + y) & 0xFF));
            }
        }
        return img;
    }

    private static BufferedImage decode(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    @Test
    void png8PaletteSizeFollowsColors() throws Exception {
        try (Image img = picture()) {
            int small = ((IndexColorModel) decode(img.toBytes(ImageFormat.png8().colors(8))).getColorModel()).getMapSize();
            int large = ((IndexColorModel) decode(img.toBytes(ImageFormat.png8().colors(256))).getColorModel()).getMapSize();
            // Mapnik rounds a palette of 16 colours or fewer up to 16 entries
            assertTrue(small <= 16, "a palette of 8 colours: " + small);
            assertTrue(large > small * 4, "a bigger palette when asked: " + small + " vs " + large);
            assertTrue(img.toBytes(ImageFormat.png8().colors(8)).length < img.toBytes(ImageFormat.png()).length,
                "a small palette is smaller than true colour");
        }
    }

    @Test
    void bothQuantizersWorkAndTheyDiffer() throws Exception {
        try (Image img = picture()) {
            byte[] octree = img.toBytes(ImageFormat.png8().colors(32).quantizer(ImageFormat.Quantizer.OCTREE));
            byte[] hextree = img.toBytes(ImageFormat.png8().colors(32).quantizer(ImageFormat.Quantizer.HEXTREE));
            assertNotNull(decode(octree));
            assertNotNull(decode(hextree));
            assertFalse(java.util.Arrays.equals(octree, hextree), "different algorithms, different pictures");
        }
    }

    @Test
    void transparencyModesChangeTheAlpha() throws Exception {
        try (Image img = picture()) {
            int levels = decode(img.toBytes(ImageFormat.png8().transparency(ImageFormat.Transparency.ALPHA_LEVELS))).getRGB(5, 5) >>> 24;
            int binary = decode(img.toBytes(ImageFormat.png8().transparency(ImageFormat.Transparency.BINARY))).getRGB(5, 5) >>> 24;
            int none = decode(img.toBytes(ImageFormat.png8().transparency(ImageFormat.Transparency.NONE))).getRGB(5, 5) >>> 24;
            assertTrue(levels > 0 && levels < 255, "the half transparent corner keeps a middle alpha: " + levels);
            assertTrue(binary == 0 || binary == 255, "binary transparency has no middle: " + binary);
            assertEquals(255, none, "no transparency: the corner is opaque");
        }
    }

    @Test
    void pngCompressionLevelAndStrategyChangeTheSize() throws Exception {
        try (Image img = picture()) {
            byte[] none = img.toBytes(ImageFormat.png().compression(0));
            byte[] best = img.toBytes(ImageFormat.png().compression(9));
            assertTrue(best.length < none.length, "level 9 is smaller than none: " + best.length + " vs " + none.length);
            assertNotNull(decode(img.toBytes(ImageFormat.png().strategy(ImageFormat.PngStrategy.RLE))));
            // lossless whatever the settings
            assertEquals(img.getArgb(40, 40), decode(best).getRGB(40, 40));
        }
    }

    @Test
    void jpegQualityChangesTheSize() {
        try (Image img = picture()) {
            int low = img.toBytes(ImageFormat.jpeg(10)).length;
            int high = img.toBytes(ImageFormat.jpeg(95)).length;
            assertTrue(low < high, "lower quality is smaller: " + low + " vs " + high);
        }
    }

    @Test
    void webpLosslessKeepsEveryPixelAndLossyDoesNot() {
        try (Image img = picture();
             Image exact = Image.load(img.toBytes(ImageFormat.webp().lossless()));
             Image lossy = Image.load(img.toBytes(ImageFormat.webp().quality(20)))) {
            int differs = 0;
            for (int y = 0; y < 96; y += 7) {
                for (int x = 0; x < 96; x += 7) {
                    assertEquals(img.getArgb(x, y), exact.getArgb(x, y), "lossless at " + x + "," + y);
                    if (img.getArgb(x, y) != lossy.getArgb(x, y)) {
                        differs++;
                    }
                }
            }
            assertTrue(differs > 0, "quality 20 loses detail");
        }
    }

    @Test
    void tiffCompressionShrinksFlatPictures() {
        try (Image flat = Image.create(128, 128)) {
            flat.fill("#3366cc");
            int none = flat.toBytes(ImageFormat.tiff().compression(ImageFormat.TiffCompression.NONE)).length;
            int lzw = flat.toBytes(ImageFormat.tiff().compression(ImageFormat.TiffCompression.LZW)).length;
            int deflate = flat.toBytes(ImageFormat.tiff().compression(ImageFormat.TiffCompression.DEFLATE).zlevel(9)).length;
            assertTrue(lzw < none / 4, "LZW: " + lzw + " vs none " + none);
            assertTrue(deflate < none / 4, "deflate: " + deflate + " vs none " + none);
        }
    }

    @Test
    void aMapRendersWithAFormatObjectAndAFreeFormOptionIsPassedThrough() throws Exception {
        try (MapnikMap map = new MapnikMap(64, 64).setBackground("#336699")) {
            byte[] png = map.renderToBytes(ImageFormat.png8().colors(16));
            assertEquals((byte) 0x89, png[0]);
            assertNotNull(decode(png));
            java.nio.file.Path file = java.nio.file.Files.createTempFile("fmt", ".png");
            try {
                map.renderToFile(file, ImageFormat.png8().colors(16));
                assertTrue(java.nio.file.Files.size(file) > 50);
            } finally {
                java.nio.file.Files.deleteIfExists(file);
            }
        }
        // Mapnik, not the builder, refuses an option it does not know the value of
        try (Image img = picture()) {
            assertThrows(MapnikException.class, () -> img.toBytes(ImageFormat.webp().option("image_hint", "9")));
        }
    }
}
