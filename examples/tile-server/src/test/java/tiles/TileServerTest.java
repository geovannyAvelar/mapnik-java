package tiles;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TileServerTest {
    private static int status(TileServer s, String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://localhost:" + s.port() + path).openConnection();
        return c.getResponseCode();
    }

    private static BufferedImage tile(TileServer s, String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://localhost:" + s.port() + path).openConnection();
        assertEquals(200, c.getResponseCode(), path);
        assertEquals("image/png", c.getContentType());
        try (InputStream in = c.getInputStream()) {
            return ImageIO.read(new ByteArrayInputStream(in.readAllBytes()));
        }
    }

    @Test
    void aTileIsA256PixelPngAndItsMetatileNeighboursComeFromTheSameRender(@TempDir Path cache) throws Exception {
        try (TileServer s = TileServer.start(0, 4, cache)) {
            BufferedImage t = tile(s, "/3/2/3.png");
            assertEquals(256, t.getWidth());
            assertEquals(256, t.getHeight());
            assertEquals(1, s.renders());
            // 3/0..3/0..3 is one metatile (4x4): none of these renders again
            for (int x = 0; x < 4; x++) {
                for (int y = 0; y < 4; y++) {
                    tile(s, "/3/" + x + "/" + y + ".png");
                }
            }
            assertEquals(1, s.renders(), "one render served all sixteen tiles");
            tile(s, "/3/4/0.png");
            assertEquals(2, s.renders(), "a tile in the next metatile renders it");
            assertTrue(Files.isRegularFile(cache.resolve("3/2/3.png")), "tiles are written to the disk cache");
        }
    }

    @Test
    void aSecondServerFillsItsMemoryFromTheDiskCacheWithoutRendering(@TempDir Path cache) throws Exception {
        try (TileServer s = TileServer.start(0, 2, cache)) {
            tile(s, "/2/1/1.png");
        }
        try (TileServer s = TileServer.start(0, 2, cache)) {
            tile(s, "/2/1/1.png");
            assertEquals(0, s.renders());
        }
    }

    @Test
    void manyRequestsForOneMetatileRenderItOnce() throws Exception {
        try (TileServer s = TileServer.start(0, 4, null)) {
            ExecutorService clients = Executors.newFixedThreadPool(16);
            List<Future<BufferedImage>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                final String path = "/4/" + (i % 4) + "/" + (i / 4) + ".png";
                Callable<BufferedImage> c = () -> tile(s, path);
                results.add(clients.submit(c));
            }
            for (Future<BufferedImage> f : results) {
                assertEquals(256, f.get().getWidth());
            }
            clients.shutdown();
            assertEquals(1, s.renders());
        }
    }

    @Test
    void badPathsAreRefused() throws Exception {
        try (TileServer s = TileServer.start(0, 4, null)) {
            assertEquals(404, status(s, "/nonsense"));
            assertEquals(404, status(s, "/2/9/0.png"));
            assertEquals(404, status(s, "/30/0/0.png"));
        }
    }

    @Test
    void metatileSizeMustBeAPowerOfTwo() {
        assertThrows(IllegalArgumentException.class, () -> TileServer.start(0, 3, null));
    }
}
