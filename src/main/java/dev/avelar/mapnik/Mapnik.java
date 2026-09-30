package dev.avelar.mapnik;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Logger;

/** Global Mapnik setup. */
public final class Mapnik {
    private static final Logger LOG = Logger.getLogger(Mapnik.class.getName());
    private static final String EXPECTED = loadExpectedVersion();
    private static volatile boolean verified;

    private Mapnik() {}

    /** Version of the Mapnik library the native shim is loaded against. */
    public static String version() {
        return NativeApi.INSTANCE.mapnik_version();
    }

    /** Numeric Mapnik version: major * 100000 + minor * 100 + patch, for example 400100 for 4.1.0. */
    public static int versionNumber() {
        return NativeApi.INSTANCE.mapnik_version_number();
    }

    /** Names of the registered input plugins, for example {@code geojson} and {@code shape}. */
    public static java.util.List<String> datasourcePlugins() {
        String names = NativeApi.INSTANCE.mapnik_datasource_plugin_names();
        return names == null || names.isEmpty()
            ? java.util.Collections.<String>emptyList()
            : java.util.Collections.unmodifiableList(java.util.Arrays.asList(names.split("\n")));
    }

    /**
     * The scale denominator for a map scale in projection units per pixel. Pass {@code geographic}
     * true for degrees. This is the number layers and rules compare against their scale ranges.
     */
    public static double scaleDenominator(double mapUnitsPerPixel, boolean geographic) {
        return NativeApi.INSTANCE.mapnik_scale_denominator(mapUnitsPerPixel, geographic ? 1 : 0);
    }

    /** True if Mapnik was built with Cairo, which PDF, SVG and PostScript output needs. */
    public static boolean hasCairo() {
        return NativeApi.INSTANCE.mapnik_cairo_available() == 1;
    }

    /** Version of Mapnik this wrapper release was built and tested against. */
    public static String expectedVersion() {
        return EXPECTED;
    }

    /** True when the loaded Mapnik has the same major.minor version as {@link #expectedVersion()}. */
    public static boolean isCompatible() {
        return sameMinor(version(), EXPECTED);
    }

    /** Load input plugins (postgis, shape, gdal...) from dir. Call once at startup. */
    public static void registerDatasources(String dir) {
        check(NativeApi.INSTANCE.mapnik_register_datasources(dir));
    }

    /** Register fonts from dir. Call once at startup. */
    public static void registerFonts(String dir) {
        check(NativeApi.INSTANCE.mapnik_register_fonts(dir));
    }

    /** Log a warning, once, if the loaded Mapnik differs in major.minor from the expected one. */
    static void verifyVersion() {
        if (verified) {
            return;
        }
        verified = true;
        String actual = version();
        if (!sameMinor(actual, EXPECTED)) {
            LOG.warning("mapnik-java was built for Mapnik " + EXPECTED
                + " but libmapnik_c is linked against Mapnik " + actual
                + ". The API may differ; rebuild the native shim and use a matching mapnik-java release.");
        }
    }

    static boolean sameMinor(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        return x.length >= 2 && y.length >= 2 && x[0].equals(y[0]) && x[1].equals(y[1]);
    }

    static void check(int rc) {
        if (rc != 0) {
            throw new MapnikException(NativeApi.INSTANCE.mapnik_last_error());
        }
    }

    private static String loadExpectedVersion() {
        Properties p = new Properties();
        try (InputStream in = Mapnik.class.getResourceAsStream("/mapnik-java.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            // fall through to "unknown"
        }
        return p.getProperty("mapnik.version", "unknown");
    }
}
