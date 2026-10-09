package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class BufferedImageIntegrationTest {
    @Test
    void pixelsSurviveTheTripToABufferedImageAndBack() {
        try (Image img = Image.create(5, 4)) {
            img.setArgb(0, 0, 0xFFFF0000).setArgb(4, 3, 0x8000FF00).setArgb(2, 1, 0x000000FF);
            BufferedImage bi = img.toBufferedImage();
            assertEquals(BufferedImage.TYPE_INT_ARGB, bi.getType());
            assertEquals(5, bi.getWidth());
            assertEquals(4, bi.getHeight());
            assertEquals(0xFFFF0000, bi.getRGB(0, 0));
            assertEquals(0x8000FF00, bi.getRGB(4, 3));
            try (Image back = Image.fromBufferedImage(bi)) {
                assertArrayEquals(img.toArgb(), back.toArgb());
            }
        }
    }

    @Test
    void anImageWithoutAlphaBecomesOpaque() {
        BufferedImage rgb = new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB);
        rgb.setRGB(1, 1, 0x123456);
        try (Image img = Image.fromBufferedImage(rgb)) {
            assertEquals(0xFF123456, img.getArgb(1, 1));
        }
    }
}
