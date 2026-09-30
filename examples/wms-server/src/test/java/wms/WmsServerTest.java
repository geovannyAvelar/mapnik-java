package wms;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WmsServerTest {
    private static final int OCEAN = 0xFFCFE8F7;
    private static final int LAND = 0xFF8FBC6B;

    private static WmsServer server;

    @BeforeAll
    static void start() throws IOException {
        server = WmsServer.start(0);
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static final class Response {
        final int status;
        final String type;
        final byte[] body;

        Response(int status, String type, byte[] body) {
            this.status = status;
            this.type = type;
            this.body = body;
        }

        String text() {
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }

        BufferedImage image() throws IOException {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(body));
            assertNotNull(img, "not an image: " + text());
            return img;
        }
    }

    private static Response get(String query) throws IOException {
        URL url = new URL("http://localhost:" + server.port() + "/wms?" + query);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        int status = c.getResponseCode();
        try (InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            return new Response(status, c.getContentType(), out.toByteArray());
        }
    }

    private static final String MAP_130 =
        "SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=world&STYLES=&FORMAT=image/png&WIDTH=400&HEIGHT=200";

    @Test
    void capabilitiesListsTheLayer() throws IOException {
        Response r = get("SERVICE=WMS&REQUEST=GetCapabilities");
        assertEquals(200, r.status);
        assertTrue(r.type.startsWith("text/xml"));
        assertTrue(r.text().contains("<WMS_Capabilities version=\"1.3.0\""));
        assertTrue(r.text().contains("<Name>world</Name>"));
        assertTrue(r.text().contains("image/png"));
        assertTrue(r.text().contains("localhost:" + server.port() + "/wms?"));
    }

    @Test
    void getMap130UsesLatLonAxisOrderForEpsg4326() throws IOException {
        Response r = get(MAP_130 + "&CRS=EPSG:4326&BBOX=-90,-180,90,180");
        assertEquals(200, r.status);
        assertEquals("image/png", r.type);
        BufferedImage img = r.image();
        assertEquals(400, img.getWidth());
        assertEquals(200, img.getHeight());
        assertEquals(OCEAN, img.getRGB(2, 2));
        // lon -35, lat 0 is inside the western polygon: x = 145/360*400 = 161, y = 100
        assertEquals(LAND, img.getRGB(161, 100));
    }

    @Test
    void getMap130Crs84UsesLonLatAxisOrder() throws IOException {
        BufferedImage img = get(MAP_130 + "&CRS=CRS:84&BBOX=-180,-90,180,90").image();
        assertEquals(LAND, img.getRGB(161, 100));
    }

    @Test
    void getMap111UsesSrsAndLonLatAxisOrder() throws IOException {
        Response r = get("SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&LAYERS=world&FORMAT=image/png"
            + "&WIDTH=400&HEIGHT=200&SRS=EPSG:4326&BBOX=-180,-90,180,90");
        assertEquals(200, r.status);
        assertEquals(LAND, r.image().getRGB(161, 100));
    }

    @Test
    void parameterNamesAreCaseInsensitive() throws IOException {
        Response r = get("service=WMS&version=1.3.0&request=getmap&layers=world&format=image/png"
            + "&width=50&height=25&crs=epsg:4326&bbox=-90,-180,90,180");
        assertEquals(200, r.status);
        assertEquals(50, r.image().getWidth());
    }

    @Test
    void subregionZoomsIn() throws IOException {
        // Zoom on the western polygon (lon -60..-10, lat -30..30): entire frame is land.
        BufferedImage img = get(MAP_130.replace("WIDTH=400&HEIGHT=200", "WIDTH=100&HEIGHT=120")
            + "&CRS=EPSG:4326&BBOX=-20,-50,20,-20").image();
        assertEquals(LAND, img.getRGB(50, 60));
        assertEquals(LAND, img.getRGB(5, 5));
    }

    @Test
    void jpegFormat() throws IOException {
        Response r = get(MAP_130.replace("image/png", "image/jpeg") + "&CRS=EPSG:4326&BBOX=-90,-180,90,180");
        assertEquals(200, r.status);
        assertEquals("image/jpeg", r.type);
        assertEquals((byte) 0xFF, r.body[0]);
        assertEquals((byte) 0xD8, r.body[1]);
    }

    @Test
    void unknownLayerIsAServiceException() throws IOException {
        Response r = get(MAP_130.replace("LAYERS=world", "LAYERS=nope") + "&CRS=EPSG:4326&BBOX=-90,-180,90,180");
        assertEquals(400, r.status);
        assertTrue(r.text().contains("code=\"LayerNotDefined\""));
    }

    @Test
    void unsupportedCrsIsAServiceException() throws IOException {
        Response r = get(MAP_130 + "&CRS=EPSG:3857&BBOX=0,0,1,1");
        assertEquals(400, r.status);
        assertTrue(r.text().contains("code=\"InvalidCRS\""));
    }

    @Test
    void missingBboxIsAServiceException() throws IOException {
        Response r = get(MAP_130 + "&CRS=EPSG:4326");
        assertEquals(400, r.status);
        assertTrue(r.text().contains("code=\"MissingParameterValue\""));
    }

    @Test
    void badBboxAndSizeAreRejected() throws IOException {
        assertTrue(get(MAP_130 + "&CRS=EPSG:4326&BBOX=1,2,3").text().contains("InvalidParameterValue"));
        assertTrue(get(MAP_130 + "&CRS=EPSG:4326&BBOX=10,10,0,0").text().contains("InvalidParameterValue"));
        assertTrue(get(MAP_130.replace("WIDTH=400", "WIDTH=99999") + "&CRS=EPSG:4326&BBOX=-90,-180,90,180")
            .text().contains("InvalidParameterValue"));
    }

    @Test
    void unsupportedFormatAndRequest() throws IOException {
        assertTrue(get(MAP_130.replace("image/png", "image/gif") + "&CRS=EPSG:4326&BBOX=-90,-180,90,180")
            .text().contains("InvalidFormat"));
        assertTrue(get("SERVICE=WMS&REQUEST=GetFeatureInfo").text().contains("OperationNotSupported"));
    }

    @Test
    void concurrentRequestsAllSucceed() throws Exception {
        String q = MAP_130 + "&CRS=EPSG:4326&BBOX=-90,-180,90,180";
        byte[] expected = get(q).body;
        Thread[] threads = new Thread[8];
        final boolean[] ok = new boolean[threads.length];
        for (int i = 0; i < threads.length; i++) {
            final int n = i;
            threads[i] = new Thread(() -> {
                try {
                    ok[n] = true;
                    for (int j = 0; j < 5; j++) {
                        ok[n] &= java.util.Arrays.equals(expected, get(q).body);
                    }
                } catch (IOException e) {
                    ok[n] = false;
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }
        for (boolean b : ok) {
            assertTrue(b);
        }
    }
}
