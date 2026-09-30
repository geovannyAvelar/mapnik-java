package dev.avelar.mapnik;

import java.util.ArrayList;
import java.util.List;

/**
 * Maths for the standard "slippy map" tile grid used by web maps (XYZ or Google scheme): zoom 0 is one
 * tile covering the world in Web Mercator (EPSG:3857), and each zoom splits every tile in four. Tile
 * (0, 0) is at the top left, so y grows downwards. Pure Java: no native library needed.
 */
public final class Tiles {
    /** Half the width of the Web Mercator world, in metres. */
    public static final double ORIGIN_SHIFT = 20037508.342789244;

    /** The highest zoom this class accepts. */
    public static final int MAX_ZOOM = 30;

    /** The latitude Web Mercator cuts off at. */
    public static final double MAX_LATITUDE = 85.0511287798066;

    private Tiles() {}

    /** One tile of the grid. */
    public static final class Tile {
        private final int z;
        private final int x;
        private final int y;

        /** Throws {@link IllegalArgumentException} if the tile is not on the grid. */
        public Tile(int z, int x, int y) {
            if (z < 0 || z > MAX_ZOOM) {
                throw new IllegalArgumentException("zoom must be 0 to " + MAX_ZOOM + ": " + z);
            }
            long n = 1L << z;
            if (x < 0 || x >= n || y < 0 || y >= n) {
                throw new IllegalArgumentException("tile " + z + "/" + x + "/" + y + " is off the grid: x and y must be 0 to " + (n - 1));
            }
            this.z = z;
            this.x = x;
            this.y = y;
        }

        public int z() { return z; }
        public int x() { return x; }
        public int y() { return y; }

        /** The tile's area in Web Mercator metres. */
        public Box2d bounds() {
            return Tiles.bounds(z, x, y);
        }

        /** The tile's area in degrees of longitude and latitude. */
        public Box2d lonLatBounds() {
            return Tiles.lonLatBounds(z, x, y);
        }

        /** The y of this tile in the TMS scheme, which counts from the bottom. */
        public int tmsY() {
            return (int) ((1L << z) - 1 - y);
        }

        /** The tile one zoom out that contains this one. Throws {@link IllegalStateException} at zoom 0. */
        public Tile parent() {
            if (z == 0) {
                throw new IllegalStateException("zoom 0 has no parent");
            }
            return new Tile(z - 1, x >> 1, y >> 1);
        }

        /** The four tiles one zoom in, top left, top right, bottom left, bottom right. */
        public List<Tile> children() {
            if (z == MAX_ZOOM) {
                throw new IllegalStateException("zoom " + MAX_ZOOM + " has no children");
            }
            List<Tile> out = new ArrayList<>(4);
            for (int dy = 0; dy < 2; dy++) {
                for (int dx = 0; dx < 2; dx++) {
                    out.add(new Tile(z + 1, x * 2 + dx, y * 2 + dy));
                }
            }
            return out;
        }

        /** Bing-style quadkey: one digit per zoom, 0 to 3. Empty at zoom 0. */
        public String quadKey() {
            StringBuilder sb = new StringBuilder();
            for (int i = z; i > 0; i--) {
                int mask = 1 << (i - 1);
                int digit = 0;
                if ((x & mask) != 0) {
                    digit += 1;
                }
                if ((y & mask) != 0) {
                    digit += 2;
                }
                sb.append(digit);
            }
            return sb.toString();
        }

        /** The tile a quadkey names. */
        public static Tile fromQuadKey(String key) {
            int z = key.length();
            int x = 0;
            int y = 0;
            for (int i = 0; i < z; i++) {
                int mask = 1 << (z - 1 - i);
                char c = key.charAt(i);
                if (c < '0' || c > '3') {
                    throw new IllegalArgumentException("a quadkey has digits 0 to 3, got '" + c + "' in \"" + key + "\"");
                }
                int d = c - '0';
                if ((d & 1) != 0) {
                    x |= mask;
                }
                if ((d & 2) != 0) {
                    y |= mask;
                }
            }
            return new Tile(z, x, y);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Tile)) {
                return false;
            }
            Tile t = (Tile) o;
            return z == t.z && x == t.x && y == t.y;
        }

        @Override
        public int hashCode() {
            return (z * 31 + x) * 31 + y;
        }

        @Override
        public String toString() {
            return z + "/" + x + "/" + y;
        }
    }

    /** The area of tile (z, x, y) in Web Mercator metres. */
    public static Box2d bounds(int z, int x, int y) {
        new Tile(z, x, y); // validates
        double size = 2 * ORIGIN_SHIFT / (1L << z);
        double minX = -ORIGIN_SHIFT + x * size;
        double maxY = ORIGIN_SHIFT - y * size;
        return new Box2d(minX, maxY - size, minX + size, maxY);
    }

    /** The area of tile (z, x, y) in degrees. */
    public static Box2d lonLatBounds(int z, int x, int y) {
        Box2d m = bounds(z, x, y);
        return new Box2d(lon(m.minX()), lat(m.minY()), lon(m.maxX()), lat(m.maxY()));
    }

    private static double lon(double metresX) {
        return metresX / ORIGIN_SHIFT * 180.0;
    }

    private static double lat(double metresY) {
        double lat = metresY / ORIGIN_SHIFT * 180.0;
        return 180.0 / Math.PI * (2 * Math.atan(Math.exp(lat * Math.PI / 180.0)) - Math.PI / 2);
    }

    /**
     * The tile containing a location at a zoom. Latitudes beyond {@link #MAX_LATITUDE} count as at the
     * edge, and longitudes are clamped to the world.
     */
    public static Tile tileAt(double lon, double lat, int z) {
        if (Double.isNaN(lon) || Double.isNaN(lat)) {
            throw new IllegalArgumentException("longitude and latitude must be numbers");
        }
        if (z < 0 || z > MAX_ZOOM) {
            throw new IllegalArgumentException("zoom must be 0 to " + MAX_ZOOM + ": " + z);
        }
        long n = 1L << z;
        double clampedLat = Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, lat));
        double latRad = Math.toRadians(clampedLat);
        double xf = (lon + 180.0) / 360.0 * n;
        double yf = (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n;
        int x = (int) Math.max(0, Math.min(n - 1, Math.floor(xf)));
        int y = (int) Math.max(0, Math.min(n - 1, Math.floor(yf)));
        return new Tile(z, x, y);
    }

    /** Every tile at a zoom that touches a box given in degrees, row by row from the top left. */
    public static List<Tile> covering(Box2d lonLatBox, int z) {
        Tile topLeft = tileAt(lonLatBox.minX(), lonLatBox.maxY(), z);
        Tile bottomRight = tileAt(lonLatBox.maxX(), lonLatBox.minY(), z);
        long count = (long) (bottomRight.x() - topLeft.x() + 1) * (bottomRight.y() - topLeft.y() + 1);
        if (count > 1_000_000) {
            throw new IllegalArgumentException("that box covers " + count + " tiles at zoom " + z + "; use a lower zoom or a smaller box");
        }
        List<Tile> out = new ArrayList<>((int) count);
        for (int y = topLeft.y(); y <= bottomRight.y(); y++) {
            for (int x = topLeft.x(); x <= bottomRight.x(); x++) {
                out.add(new Tile(z, x, y));
            }
        }
        return out;
    }

    /** The size of one pixel, in metres, for tiles {@code tileSize} pixels wide at a zoom. */
    public static double resolution(int z, int tileSize) {
        if (z < 0 || z > MAX_ZOOM) {
            throw new IllegalArgumentException("zoom must be 0 to " + MAX_ZOOM + ": " + z);
        }
        if (tileSize <= 0) {
            throw new IllegalArgumentException("tile size must be positive: " + tileSize);
        }
        return 2 * ORIGIN_SHIFT / (tileSize * (double) (1L << z));
    }
}
