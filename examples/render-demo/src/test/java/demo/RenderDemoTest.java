package demo;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RenderDemoTest {
    @Test
    void rendersBundledMap(@TempDir Path dir) throws IOException {
        Path out = dir.resolve("world.png");
        RenderDemo.render(out, null, 400, 200);

        BufferedImage img = ImageIO.read(out.toFile());
        assertNotNull(img);
        assertEquals(400, img.getWidth());
        assertEquals(200, img.getHeight());

        // Ocean background in a corner, green land inside the western polygon
        // (lon -35, lat 0 -> x = (−35+180)/360*400 = 161, y = 100).
        assertEquals(0xFFCFE8F7, img.getRGB(2, 2));
        assertEquals(0xFF8FBC6B, img.getRGB(161, 100));
    }
}
