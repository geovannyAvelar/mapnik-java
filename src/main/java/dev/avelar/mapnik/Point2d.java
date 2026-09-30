package dev.avelar.mapnik;

/** A 2D coordinate. What x and y mean depends on the projection it is used with. */
public final class Point2d {
    private final double x;
    private final double y;

    public Point2d(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public double x() { return x; }
    public double y() { return y; }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Point2d)) {
            return false;
        }
        Point2d p = (Point2d) o;
        return Double.compare(x, p.x) == 0 && Double.compare(y, p.y) == 0;
    }

    @Override
    public int hashCode() {
        return 31 * Double.hashCode(x) + Double.hashCode(y);
    }

    @Override
    public String toString() {
        return "Point2d[" + x + ", " + y + "]";
    }
}
