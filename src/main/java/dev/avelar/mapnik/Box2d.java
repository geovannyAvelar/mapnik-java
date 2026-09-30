package dev.avelar.mapnik;

/** An axis-aligned bounding box, in the units of whatever projection it is used with. */
public final class Box2d {
    private final double minX;
    private final double minY;
    private final double maxX;
    private final double maxY;

    public Box2d(double minX, double minY, double maxX, double maxY) {
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
    }

    static Box2d of(double[] a) {
        return new Box2d(a[0], a[1], a[2], a[3]);
    }

    public double minX() { return minX; }
    public double minY() { return minY; }
    public double maxX() { return maxX; }
    public double maxY() { return maxY; }
    public double width() { return maxX - minX; }
    public double height() { return maxY - minY; }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Box2d)) {
            return false;
        }
        Box2d b = (Box2d) o;
        return Double.compare(minX, b.minX) == 0 && Double.compare(minY, b.minY) == 0
            && Double.compare(maxX, b.maxX) == 0 && Double.compare(maxY, b.maxY) == 0;
    }

    @Override
    public int hashCode() {
        int h = Double.hashCode(minX);
        h = 31 * h + Double.hashCode(minY);
        h = 31 * h + Double.hashCode(maxX);
        return 31 * h + Double.hashCode(maxY);
    }

    @Override
    public String toString() {
        return "Box2d[" + minX + ", " + minY + ", " + maxX + ", " + maxY + "]";
    }
}
