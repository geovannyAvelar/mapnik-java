package org.mapnik;

/** Global Mapnik setup. */
public final class Mapnik {
    private Mapnik() {}

    public static String version() {
        return NativeApi.INSTANCE.mapnik_version();
    }

    /** Load input plugins (postgis, shape, gdal...) from dir. Call once at startup. */
    public static void registerDatasources(String dir) {
        check(NativeApi.INSTANCE.mapnik_register_datasources(dir));
    }

    /** Register fonts from dir. Call once at startup. */
    public static void registerFonts(String dir) {
        check(NativeApi.INSTANCE.mapnik_register_fonts(dir));
    }

    static void check(int rc) {
        if (rc != 0) {
            throw new MapnikException(NativeApi.INSTANCE.mapnik_last_error());
        }
    }
}
