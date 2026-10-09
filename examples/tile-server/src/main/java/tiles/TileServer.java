package tiles;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.avelar.mapnik.Box2d;
import dev.avelar.mapnik.Image;
import dev.avelar.mapnik.MapPool;
import dev.avelar.mapnik.Mapnik;
import dev.avelar.mapnik.MapnikMap;
import dev.avelar.mapnik.Tiles;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A slippy-map tile server ({@code /{z}/{x}/{y}.png}) on mapnik-java, with the three things a real one needs:
 *
 * <ul>
 *   <li><b>Metatiles.</b> A request renders a block of {@code metatile x metatile} tiles in one go and cuts it up.
 *       Labels and wide lines that cross a tile edge are then drawn once, so neighbouring tiles agree, and one
 *       render is cheaper than {@code metatile^2} small ones.
 *   <li><b>Caching.</b> Cut tiles go into an in-memory LRU cache and, if a directory is given, onto disk.
 *   <li><b>Async rendering.</b> HTTP threads never render. A request asks for its metatile; renders run on a small
 *       pool sized to the {@link MapPool}; requests for the same metatile wait for the one render under way.
 * </ul>
 *
 * It is a demo of the wrapper, not a production tile server: no authentication, no cache expiry, one fixed style.
 */
public final class TileServer implements AutoCloseable {
    static final int TILE = 256;
    private static final Pattern PATH = Pattern.compile("/(\\d{1,2})/(\\d{1,8})/(\\d{1,8})\\.png");

    private final HttpServer http;
    private final ExecutorService httpThreads = Executors.newFixedThreadPool(8);
    private final ExecutorService renderThreads;
    private final MapPool pool;
    private final int metatile;
    private final Path diskCache;
    private final Lru memory;
    private final Map<String, CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>();
    private volatile long renders;

    private TileServer(int port, Path style, int metatile, int renderers, Path diskCache, int memoryTiles) throws IOException {
        this.metatile = metatile;
        this.diskCache = diskCache;
        this.memory = new Lru(memoryTiles);
        this.renderThreads = Executors.newFixedThreadPool(renderers);
        // Every map is resized to the metatile it renders, so the size given here does not matter.
        this.pool = MapPool.builder(renderers, TILE, TILE)
            .setup(m -> m.load(style).setSrs("epsg:3857").setBufferSize(64))
            .maxWait(30, TimeUnit.SECONDS)
            .build();
        if (diskCache != null) {
            Files.createDirectories(diskCache);
        }
        this.http = HttpServer.create(new InetSocketAddress(port), 0);
        this.http.createContext("/", this::handle);
        this.http.setExecutor(httpThreads);
        this.http.start();
    }

    /**
     * Start on {@code port} (0 picks a free port). {@code metatile} is the side of a metatile in tiles, a power of
     * two; {@code diskCache} may be null.
     */
    public static TileServer start(int port, int metatile, Path diskCache) throws IOException {
        if (metatile < 1 || Integer.bitCount(metatile) != 1) {
            throw new IllegalArgumentException("the metatile size is a power of two: " + metatile);
        }
        Mapnik.registerDatasources(inputPluginsDir());
        Path dir = Files.createTempDirectory("tile-server");
        for (String f : new String[] {"world.xml", "world.geojson", "route.geojson"}) {
            try (InputStream in = TileServer.class.getResourceAsStream("/" + f)) {
                if (in == null) {
                    throw new IOException("missing resource " + f);
                }
                Files.copy(in, dir.resolve(f), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        int renderers = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        return new TileServer(port, dir.resolve("world.xml"), metatile, renderers, diskCache, 4096);
    }

    public int port() {
        return http.getAddress().getPort();
    }

    /** How many metatiles have been rendered. Cached and shared requests do not add to it. */
    public long renders() {
        return renders;
    }

    @Override
    public void close() {
        http.stop(0);
        httpThreads.shutdownNow();
        renderThreads.shutdownNow();
        pool.close();
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        int metatile = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        Path cache = args.length > 2 ? Path.of(args[2]) : null;
        TileServer server = start(port, metatile, cache);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        System.out.println("Mapnik " + Mapnik.version() + ", tiles on http://localhost:" + server.port() + "/{z}/{x}/{y}.png"
            + ", metatile " + metatile + (cache != null ? ", disk cache " + cache : ""));
        System.out.println("  http://localhost:" + server.port() + "/   (a Leaflet page)");
    }

    // ---------------------------------------------------------------- requests

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) {
            send(ex, 200, "text/html; charset=utf-8", LEAFLET.getBytes(StandardCharsets.UTF_8), false);
            return;
        }
        Matcher m = PATH.matcher(path);
        if (!"GET".equals(ex.getRequestMethod()) || !m.matches()) {
            send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8), false);
            return;
        }
        int z = Integer.parseInt(m.group(1));
        long x = Long.parseLong(m.group(2));
        long y = Long.parseLong(m.group(3));
        if (z > 22 || x >= (1L << z) || y >= (1L << z)) {
            send(ex, 404, "text/plain", "outside the world".getBytes(StandardCharsets.UTF_8), false);
            return;
        }
        String key = z + "/" + x + "/" + y;
        byte[] hit = lookup(key);
        if (hit != null) {
            send(ex, 200, "image/png", hit, true);
            return;
        }
        // HTTP threads are not held while the tile renders: the answer is sent from whichever thread finishes it.
        metatileFor(z, (int) x, (int) y).whenComplete((ignored, error) -> {
            try {
                byte[] tile = error == null ? lookup(key) : null;
                if (tile != null) {
                    send(ex, 200, "image/png", tile, true);
                } else {
                    send(ex, 503, "text/plain", "busy or failed, try again".getBytes(StandardCharsets.UTF_8), false);
                }
            } catch (IOException e) {
                ex.close();
            }
        });
    }

    private byte[] lookup(String key) {
        byte[] t = memory.get(key);
        if (t == null && diskCache != null) {
            Path f = diskCache.resolve(key + ".png");
            try {
                if (Files.isRegularFile(f)) {
                    t = Files.readAllBytes(f);
                    memory.put(key, t);
                }
            } catch (IOException e) {
                return null;
            }
        }
        return t;
    }

    /** A future that completes when every tile of the metatile holding (z, x, y) is cached. One render per metatile. */
    private CompletableFuture<Void> metatileFor(int z, int x, int y) {
        int side = Math.min(metatile, 1 << z);
        int mx = x - x % side;
        int my = y - y % side;
        String id = z + "/" + mx + "/" + my;
        CompletableFuture<Void> mine = new CompletableFuture<>();
        CompletableFuture<Void> existing = inFlight.putIfAbsent(id, mine);
        if (existing != null) {
            return existing;
        }
        renderThreads.execute(() -> {
            try {
                renderMetatile(z, mx, my, side);
                mine.complete(null);
            } catch (Throwable t) {
                mine.completeExceptionally(t);
            } finally {
                inFlight.remove(id);
            }
        });
        return mine;
    }

    private void renderMetatile(int z, int mx, int my, int side) throws IOException {
        Box2d first = Tiles.bounds(z, mx, my);
        Box2d last = Tiles.bounds(z, mx + side - 1, my + side - 1);
        Box2d area = new Box2d(first.minX(), last.minY(), last.maxX(), first.maxY());
        Image big;
        try (MapPool.Lease lease = pool.borrow()) {
            MapnikMap map = lease.map();
            map.resize(side * TILE, side * TILE);
            map.zoomToBox(area);
            big = map.renderToImage();
        }
        renders++;
        try (Image whole = big) {
            for (int dy = 0; dy < side; dy++) {
                for (int dx = 0; dx < side; dx++) {
                    byte[] png;
                    try (Image tile = whole.crop(dx * TILE, dy * TILE, TILE, TILE)) {
                        png = tile.toPng();
                    }
                    String key = z + "/" + (mx + dx) + "/" + (my + dy);
                    memory.put(key, png);
                    if (diskCache != null) {
                        Path f = diskCache.resolve(key + ".png");
                        Files.createDirectories(f.getParent());
                        Path tmp = f.resolveSibling(f.getFileName() + ".tmp" + Thread.currentThread().getId());
                        Files.write(tmp, png);
                        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);   // readers never see half a file
                    }
                }
            }
        }
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body, boolean cacheable) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        if (cacheable) {
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
        }
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    /** A least-recently-used cache of tiles, by count. */
    private static final class Lru {
        private final Map<String, byte[]> map;

        Lru(int max) {
            this.map = new LinkedHashMap<String, byte[]>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > max;
                }
            };
        }

        synchronized byte[] get(String key) {
            return map.get(key);
        }

        synchronized void put(String key, byte[] value) {
            map.put(key, value);
        }
    }

    private static String inputPluginsDir() throws IOException {
        String env = System.getenv("MAPNIK_INPUT_PLUGINS");
        if (env != null && !env.isEmpty()) {
            return env;
        }
        Process p;
        try {
            p = new ProcessBuilder("mapnik-config", "--input-plugins").redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IOException("set MAPNIK_INPUT_PLUGINS or put mapnik-config on PATH", e);
        }
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
            String line = r.readLine();
            if (line == null || line.trim().isEmpty()) {
                throw new IOException("set MAPNIK_INPUT_PLUGINS or put mapnik-config on PATH");
            }
            return line.trim();
        }
    }

    private static final String LEAFLET = "<!doctype html><meta charset=utf-8><title>tile-server</title>"
        + "<link rel=stylesheet href=https://unpkg.com/leaflet@1.9.4/dist/leaflet.css>"
        + "<style>html,body,#m{height:100%;margin:0}</style><div id=m></div>"
        + "<script src=https://unpkg.com/leaflet@1.9.4/dist/leaflet.js></script>"
        + "<script>var m=L.map('m').setView([20,0],2);L.tileLayer('/{z}/{x}/{y}.png',{maxZoom:12}).addTo(m)</script>";
}
