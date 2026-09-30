package demo;

import dev.avelar.mapnik.Mapnik;
import dev.avelar.mapnik.MapnikMap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/** Renders a bundled demo map (or your own style) to a PNG using mapnik-java. */
public final class RenderDemo {
    private RenderDemo() {}

    /** Usage: RenderDemo [output.png] [style.xml] [width] [height] */
    public static void main(String[] args) throws Exception {
        Path out = Paths.get(args.length > 0 ? args[0] : "world.png");
        Path style = args.length > 1 ? Paths.get(args[1]) : null;
        int width = args.length > 2 ? Integer.parseInt(args[2]) : 800;
        int height = args.length > 3 ? Integer.parseInt(args[3]) : 400;

        System.out.println("Mapnik " + Mapnik.version() + " (wrapper built for " + Mapnik.expectedVersion() + ")");
        render(out, style, width, height);
        System.out.println("Wrote " + out.toAbsolutePath() + " (" + Files.size(out) + " bytes)");
    }

    /** Render to {@code out}. A null {@code style} uses the bundled demo map. */
    public static void render(Path out, Path style, int width, int height) throws IOException {
        Mapnik.registerDatasources(inputPluginsDir());

        if (style == null) {
            Path dir = Files.createTempDirectory("render-demo");
            style = copyResource("world.xml", dir);
            copyResource("world.geojson", dir);
            copyResource("route.geojson", dir);
        }

        try (MapnikMap map = new MapnikMap(width, height)) {
            map.load(style).zoomToBox(-180, -90, 180, 90);
            map.renderToFile(out, "png");
        }
    }

    private static Path copyResource(String name, Path dir) throws IOException {
        try (InputStream in = RenderDemo.class.getResourceAsStream("/" + name)) {
            if (in == null) {
                throw new IOException("missing resource " + name);
            }
            Path target = dir.resolve(name);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        }
    }

    /** MAPNIK_INPUT_PLUGINS, or the output of {@code mapnik-config --input-plugins}. */
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
}
