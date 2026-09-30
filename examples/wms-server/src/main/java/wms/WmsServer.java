package wms;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.avelar.mapnik.Mapnik;
import dev.avelar.mapnik.MapnikMap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A minimal WMS server (1.1.1 and 1.3.0) backed by mapnik-java.
 *
 * <p>Serves the layers of the bundled style (found with {@link MapnikMap#layerNames()}) in EPSG:4326,
 * CRS:84 and EPSG:3857. Supports GetCapabilities and GetMap. {@code LAYERS} selects layers with
 * {@link MapnikMap#setActiveLayers}; {@code CRS} is applied with {@link MapnikMap#setSrs}.
 * Each request renders with its own {@link MapnikMap}, since maps are not thread-safe.
 */
public final class WmsServer implements AutoCloseable {
    private static final int MAX_SIZE = 4096;

    private final HttpServer http;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Path style;
    private final List<String> layers;

    private WmsServer(int port, Path style, List<String> layers) throws IOException {
        this.style = style;
        this.layers = layers;
        this.http = HttpServer.create(new InetSocketAddress(port), 0);
        this.http.createContext("/wms", this::handle);
        this.http.setExecutor(pool);
        this.http.start();
    }

    /** Start on {@code port}; 0 picks a free port. */
    public static WmsServer start(int port) throws IOException {
        Mapnik.registerDatasources(inputPluginsDir());
        Path dir = Files.createTempDirectory("wms-server");
        for (String f : new String[] {"world.xml", "world.geojson", "route.geojson"}) {
            try (InputStream in = WmsServer.class.getResourceAsStream("/" + f)) {
                if (in == null) {
                    throw new IOException("missing resource " + f);
                }
                Files.copy(in, dir.resolve(f), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path style = dir.resolve("world.xml");
        List<String> layers;
        try (MapnikMap map = new MapnikMap(1, 1)) {
            layers = map.load(style).layerNames();
        }
        return new WmsServer(port, style, layers);
    }

    public int port() {
        return http.getAddress().getPort();
    }

    @Override
    public void close() {
        http.stop(0);
        pool.shutdownNow();
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        WmsServer server = start(port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        String base = "http://localhost:" + server.port() + "/wms";
        System.out.println("Mapnik " + Mapnik.version() + ", WMS listening on " + base);
        System.out.println("  " + base + "?SERVICE=WMS&REQUEST=GetCapabilities");
        System.out.println("  " + base + "?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS="
            + String.join(",", server.layers) + "&CRS=EPSG:4326&BBOX=-90,-180,90,180&WIDTH=800&HEIGHT=400&FORMAT=image/png");
    }

    // ---------------------------------------------------------------- request handling

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equals(ex.getRequestMethod())) {
                send(ex, 405, "text/plain", "GET only".getBytes(StandardCharsets.UTF_8));
                return;
            }
            Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
            String request = q.getOrDefault("request", "");
            if (request.equalsIgnoreCase("GetCapabilities")) {
                String host = ex.getRequestHeaders().getFirst("Host");
                String url = "http://" + (host != null ? host : "localhost:" + port()) + "/wms";
                send(ex, 200, "text/xml", capabilities(url, layers).getBytes(StandardCharsets.UTF_8));
            } else if (request.equalsIgnoreCase("GetMap")) {
                getMap(ex, q);
            } else {
                throw new WmsException("OperationNotSupported", "Unsupported REQUEST: " + request);
            }
        } catch (WmsException e) {
            send(ex, 400, "text/xml", exceptionXml(e).getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            WmsException w = new WmsException("NoApplicableCode", String.valueOf(e.getMessage()));
            send(ex, 500, "text/xml", exceptionXml(w).getBytes(StandardCharsets.UTF_8));
        }
    }

    private void getMap(HttpExchange ex, Map<String, String> q) throws IOException {
        String requested = q.getOrDefault("layers", "");
        if (requested.isEmpty()) {
            throw new WmsException("MissingParameterValue", "LAYERS is required");
        }
        List<String> wanted = Arrays.asList(requested.split(","));
        for (String l : wanted) {
            if (!layers.contains(l)) {
                throw new WmsException("LayerNotDefined", "Unknown layer: '" + l + "'");
            }
        }

        boolean v130 = !"1.1.1".equals(q.get("version"));
        String crs = (v130 ? q.get("crs") : q.get("srs"));
        if (crs == null) {
            throw new WmsException("MissingParameterValue", v130 ? "CRS is required" : "SRS is required");
        }
        crs = crs.toUpperCase(Locale.ROOT);
        if (!crs.equals("EPSG:4326") && !crs.equals("CRS:84") && !crs.equals("EPSG:3857")) {
            throw new WmsException("InvalidCRS", "Unsupported CRS: " + crs);
        }

        double[] b = parseBbox(q.get("bbox"));
        // WMS 1.3.0 + EPSG:4326 uses lat,lon axis order: miny,minx,maxy,maxx.
        if (v130 && crs.equals("EPSG:4326")) {
            b = new double[] {b[1], b[0], b[3], b[2]};
        }

        int width = parseSize(q.get("width"), "WIDTH");
        int height = parseSize(q.get("height"), "HEIGHT");

        String fmt = q.getOrDefault("format", "").toLowerCase(Locale.ROOT);
        String mapnikFormat;
        if (fmt.equals("image/png")) {
            mapnikFormat = "png";
        } else if (fmt.equals("image/jpeg")) {
            mapnikFormat = "jpeg";
        } else {
            throw new WmsException("InvalidFormat", "Unsupported FORMAT: " + fmt);
        }

        byte[] image;
        try (MapnikMap map = new MapnikMap(width, height)) {
            map.load(style);
            // Layers stay in their own projection; Mapnik reprojects them to the map's.
            map.setSrs(crs.equals("EPSG:3857") ? "epsg:3857" : "epsg:4326");
            map.setActiveLayers(wanted);
            map.zoomToBox(b[0], b[1], b[2], b[3]);
            image = map.renderToBytes(mapnikFormat);
        }
        send(ex, 200, fmt, image);
    }

    private static double[] parseBbox(String s) {
        if (s == null) {
            throw new WmsException("MissingParameterValue", "BBOX is required");
        }
        String[] p = s.split(",");
        if (p.length != 4) {
            throw new WmsException("InvalidParameterValue", "BBOX needs 4 values");
        }
        double[] b = new double[4];
        try {
            for (int i = 0; i < 4; i++) {
                b[i] = Double.parseDouble(p[i].trim());
            }
        } catch (NumberFormatException e) {
            throw new WmsException("InvalidParameterValue", "BBOX is not numeric: " + s);
        }
        if (!(b[0] < b[2]) || !(b[1] < b[3])) {
            throw new WmsException("InvalidParameterValue", "BBOX min must be below max");
        }
        return b;
    }

    private static int parseSize(String s, String name) {
        if (s == null) {
            throw new WmsException("MissingParameterValue", name + " is required");
        }
        try {
            int v = Integer.parseInt(s.trim());
            if (v < 1 || v > MAX_SIZE) {
                throw new WmsException("InvalidParameterValue", name + " must be 1.." + MAX_SIZE);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new WmsException("InvalidParameterValue", name + " is not an integer: " + s);
        }
    }

    // ---------------------------------------------------------------- documents

    static String capabilities(String url, List<String> layers) {
        StringBuilder sb = new StringBuilder();
        for (String name : layers) {
            sb.append("      <Layer queryable=\"0\">\n")
              .append("        <Name>").append(escape(name)).append("</Name>\n")
              .append("        <Title>").append(escape(name)).append("</Title>\n")
              .append("        <BoundingBox CRS=\"EPSG:4326\" minx=\"-90\" miny=\"-180\" maxx=\"90\" maxy=\"180\"/>\n")
              .append("        <BoundingBox CRS=\"CRS:84\" minx=\"-180\" miny=\"-90\" maxx=\"180\" maxy=\"90\"/>\n")
              .append("        <BoundingBox CRS=\"EPSG:3857\" minx=\"-20037508\" miny=\"-20037508\" maxx=\"20037508\" maxy=\"20037508\"/>\n")
              .append("      </Layer>\n");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<WMS_Capabilities version=\"1.3.0\" xmlns=\"http://www.opengis.net/wms\" "
            + "xmlns:xlink=\"http://www.w3.org/1999/xlink\">\n"
            + "  <Service>\n"
            + "    <Name>WMS</Name>\n"
            + "    <Title>mapnik-java demo WMS</Title>\n"
            + "  </Service>\n"
            + "  <Capability>\n"
            + "    <Request>\n"
            + "      <GetCapabilities>\n"
            + "        <Format>text/xml</Format>\n"
            + dcp(url)
            + "      </GetCapabilities>\n"
            + "      <GetMap>\n"
            + "        <Format>image/png</Format>\n"
            + "        <Format>image/jpeg</Format>\n"
            + dcp(url)
            + "      </GetMap>\n"
            + "    </Request>\n"
            + "    <Exception><Format>XML</Format></Exception>\n"
            + "    <Layer>\n"
            + "      <Title>Demo</Title>\n"
            + "      <CRS>EPSG:4326</CRS>\n"
            + "      <CRS>CRS:84</CRS>\n"
            + "      <CRS>EPSG:3857</CRS>\n"
            + "      <EX_GeographicBoundingBox>\n"
            + "        <westBoundLongitude>-180</westBoundLongitude>\n"
            + "        <eastBoundLongitude>180</eastBoundLongitude>\n"
            + "        <southBoundLatitude>-90</southBoundLatitude>\n"
            + "        <northBoundLatitude>90</northBoundLatitude>\n"
            + "      </EX_GeographicBoundingBox>\n"
            + sb
            + "    </Layer>\n"
            + "  </Capability>\n"
            + "</WMS_Capabilities>\n";
    }

    private static String dcp(String url) {
        return "        <DCPType><HTTP><Get>"
            + "<OnlineResource xlink:type=\"simple\" xlink:href=\"" + url + "?\"/>"
            + "</Get></HTTP></DCPType>\n";
    }

    private static String exceptionXml(WmsException e) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<ServiceExceptionReport version=\"1.3.0\" xmlns=\"http://www.opengis.net/ogc\">\n"
            + "  <ServiceException code=\"" + e.code + "\">" + escape(e.getMessage()) + "</ServiceException>\n"
            + "</ServiceExceptionReport>\n";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ---------------------------------------------------------------- plumbing

    /** Parameter names are case-insensitive in WMS; keys are lower-cased. */
    static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return m;
        }
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            try {
                m.put(URLDecoder.decode(k, "UTF-8").toLowerCase(Locale.ROOT), URLDecoder.decode(v, "UTF-8"));
            } catch (java.io.UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }
        return m;
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static String inputPluginsDir() throws IOException {
        String env = System.getenv("MAPNIK_INPUT_PLUGINS");
        if (env != null && !env.isEmpty()) {
            return env;
        }
        Process p = new ProcessBuilder("mapnik-config", "--input-plugins").redirectErrorStream(true).start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line = r.readLine();
            if (line == null || line.trim().isEmpty()) {
                throw new IOException("set MAPNIK_INPUT_PLUGINS or put mapnik-config on PATH");
            }
            return line.trim();
        }
    }

    /** An OGC service exception: code plus message. */
    private static final class WmsException extends RuntimeException {
        final String code;

        WmsException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
