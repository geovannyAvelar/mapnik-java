package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Vector tiles over HTTP and HTTPS. The HTTPS server has a self-signed certificate, so it is refused until it is
 * trusted: that is what shows the connection is authenticated and not only encrypted.
 */
class HttpsTilesIntegrationTest {
    private static byte[] tile;
    private HttpServer server;

    @BeforeAll
    static void setUp() throws Exception {
        Fixtures.dir();
        assumeTrue(Mapnik.isBundled(), "needs a Mapnik built with TLS support for its tiles plugin: the bundled one");
        assumeTrue(Mapnik.isDatasourceRegistered("tiles"), "no tiles input plugin");
        // The plugin sends no Accept-Encoding and does not unzip a tile fetched over HTTP, so servers send it plain.
        try (java.io.InputStream in = new java.util.zip.GZIPInputStream(Files.newInputStream(Paths.get("src/test/resources/tiles/square.pbf")))) {
            tile = readAll(in);
        }
    }

    @AfterEach
    void stop() {
        // setTrustedCertificates changes SSL_CERT_FILE for the whole process: put the bundled list back
        Path bundled = Mapnik.bundledDirectory().resolve("certs").resolve("cacert.pem");
        if (Files.exists(bundled)) {
            Mapnik.setTrustedCertificates(bundled);
        }
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpServer serve(HttpServer s, AtomicInteger hits) throws Exception {
        s.createContext("/", (HttpExchange x) -> {
            hits.incrementAndGet();
            if (x.getRequestURI().getPath().equals("/0/0/0.pbf")) {
                x.getResponseHeaders().add("Content-Type", "application/x-protobuf");
                x.sendResponseHeaders(200, tile.length);
                try (OutputStream os = x.getResponseBody()) {
                    os.write(tile);
                }
            } else {
                x.sendResponseHeaders(404, -1);
            }
            x.close();
        });
        s.start();
        server = s;
        return s;
    }

    private static Image draw(String url) {
        try (Datasource ds = Datasource.tilesFromUrl(url, "places");
             MapnikMap map = new MapnikMap(64, 64).setSrs("epsg:3857").setBackground("white");
             Layer layer = Layer.create("tiles", "epsg:3857")) {
            map.addStyle(Style.create("s").add(Rule.create().add(Symbolizer.polygon().fill("#ff0000"))));
            layer.addStyle("s").setDatasource(ds);
            map.addLayer(layer).zoomToBox(Tiles.bounds(0, 0, 0));
            return map.renderToImage();
        }
    }

    /** A self-signed certificate for localhost, made with the JDK's own keytool. Returns the keystore, with the PEM next to it. */
    private static Path[] certificate(Path dir) throws Exception {
        Path keystore = dir.resolve("server.p12");
        Path pem = dir.resolve("server.pem");
        String keytool = Paths.get(System.getProperty("java.home"), "bin", "keytool").toString();
        run(keytool, "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost",
            "-ext", "san=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass", "changeit");
        run(keytool, "-exportcert", "-rfc", "-alias", "t", "-file", pem.toString(), "-keystore", keystore.toString(), "-storepass", "changeit");
        return new Path[] {keystore, pem};
    }

    private static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(readAll(p.getInputStream()));
        assertEquals(0, p.waitFor(), String.join(" ", cmd) + "\n" + out);
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[4096];
        for (int n; (n = in.read(b)) > 0; ) {
            o.write(b, 0, n);
        }
        return o.toByteArray();
    }

    @Test
    void plainHttpTilesAreDrawn() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer s = serve(HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0), hits);
        try (Image img = draw("http://localhost:" + s.getAddress().getPort() + "/{z}/{x}/{y}.pbf")) {
            assertEquals(0xFFFF0000, img.getArgb(32, 32));
            assertEquals(0xFFFFFFFF, img.getArgb(2, 2));
        }
        assertTrue(hits.get() > 0);
    }

    @Test
    void anUntrustedHttpsServerIsRefusedAndATrustedOneIsDrawn(@TempDir Path dir) throws Exception {
        Path[] cert = certificate(dir);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (java.io.InputStream in = Files.newInputStream(cert[0])) {
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);
        HttpsServer https = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(ssl));
        AtomicInteger hits = new AtomicInteger();
        serve(https, hits);
        String url = "https://localhost:" + https.getAddress().getPort() + "/{z}/{x}/{y}.pbf";

        // not trusted: the handshake fails, so no tile arrives and nothing is drawn
        try (Image img = draw(url)) {
            assertEquals(0xFFFFFFFF, img.getArgb(32, 32), "a server with an unknown certificate must not be believed");
        } catch (MapnikException refused) {
            // also fine: the failure may surface as an error
        }
        assertEquals(0, hits.get(), "no request may reach a server whose certificate is not trusted");

        // trusted: drawn
        Mapnik.setTrustedCertificates(cert[1]);
        try (Image img = draw(url)) {
            assertEquals(0xFFFF0000, img.getArgb(32, 32));
            assertEquals(0xFFFFFFFF, img.getArgb(2, 2));
        }
        assertTrue(hits.get() > 0, "the trusted server was asked for its tile");
    }

    @Test
    void vectorTilesNeedALayerName() {
        assertThrows(IllegalArgumentException.class, () -> Datasource.tilesFromUrl("http://localhost:1/{z}/{x}/{y}.pbf", null));
    }
}
