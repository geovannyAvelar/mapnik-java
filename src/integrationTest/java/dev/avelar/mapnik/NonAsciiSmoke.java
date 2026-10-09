package dev.avelar.mapnik;

/** Run in a child JVM by {@link NonAsciiPathIntegrationTest}: loads the natives and draws one pixel. */
public final class NonAsciiSmoke {
    private NonAsciiSmoke() {}

    public static void main(String[] args) {
        try (MapnikMap map = new MapnikMap(8, 8).setBackground("#ff0000");
             Image img = map.renderToImage()) {
            if (!Mapnik.isBundled()) {
                System.out.println("NOT-BUNDLED");
                System.exit(3);
            }
            if (img.getArgb(4, 4) != 0xFFFF0000) {
                System.out.println("WRONG-PIXEL " + Integer.toHexString(img.getArgb(4, 4)));
                System.exit(4);
            }
            System.out.println("OK " + Mapnik.version() + " " + Mapnik.bundledDirectory());
        }
    }
}
