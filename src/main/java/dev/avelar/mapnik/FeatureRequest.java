package dev.avelar.mapnik;

/** What Mapnik is asking a {@link FeatureSource} for. */
public final class FeatureRequest {
    private final Box2d bbox;
    private final double resolutionX;
    private final double resolutionY;
    private final double scaleDenominator;

    FeatureRequest(Box2d bbox, double resolutionX, double resolutionY, double scaleDenominator) {
        this.bbox = bbox;
        this.resolutionX = resolutionX;
        this.resolutionY = resolutionY;
        this.scaleDenominator = scaleDenominator;
    }

    /** The area wanted, in the datasource's projection (the layer's). Return features that touch it. */
    public Box2d bbox() { return bbox; }

    /** Pixels per map unit across. */
    public double resolutionX() { return resolutionX; }

    /** Pixels per map unit down. */
    public double resolutionY() { return resolutionY; }

    /** The scale denominator of the map being drawn, for choosing how much detail to return. */
    public double scaleDenominator() { return scaleDenominator; }

    @Override
    public String toString() {
        return "FeatureRequest[" + bbox + ", scale 1:" + scaleDenominator + "]";
    }
}
