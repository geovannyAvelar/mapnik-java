package dev.avelar.mapnik;

/**
 * How {@link Geometry#simplify} decides which points to drop. The tolerance means something different
 * for each, so try a value and look at the result.
 */
public enum SimplifyAlgorithm {
    /** Drops points closer than the tolerance to the previous kept point. Fast and crude. */
    RADIAL_DISTANCE("radial-distance"),
    /** Keeps points that deviate from a straight run by more than the tolerance. The usual choice. */
    DOUGLAS_PEUCKER("douglas-peucker"),
    /** Drops the points that change the shape least, by triangle area. Smooth results. */
    VISVALINGAM_WHYATT("visvalingam-whyatt"),
    /** A variant of Visvalingam-Whyatt that keeps the line's overall direction. */
    ZHAO_SALFELD("zhao-saalfeld");

    private final String xmlName;

    SimplifyAlgorithm(String xmlName) {
        this.xmlName = xmlName;
    }

    /** The name as written in style files, for example {@code douglas-peucker}. */
    public String xmlName() {
        return xmlName;
    }
}
