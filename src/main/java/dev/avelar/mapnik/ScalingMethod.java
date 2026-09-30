package dev.avelar.mapnik;

/**
 * The resampling method for resizing an image. {@link #NEAR} is fastest and keeps hard pixels;
 * {@link #BILINEAR} and {@link #BICUBIC} are smooth; {@link #LANCZOS} is sharp and good for shrinking.
 */
public enum ScalingMethod {
    NEAR("near"),
    BILINEAR("bilinear"),
    BICUBIC("bicubic"),
    SPLINE16("spline16"),
    SPLINE36("spline36"),
    HANNING("hanning"),
    HAMMING("hamming"),
    HERMITE("hermite"),
    KAISER("kaiser"),
    QUADRIC("quadric"),
    CATROM("catrom"),
    GAUSSIAN("gaussian"),
    BESSEL("bessel"),
    MITCHELL("mitchell"),
    SINC("sinc"),
    LANCZOS("lanczos"),
    BLACKMAN("blackman");

    private final String xmlName;

    ScalingMethod(String xmlName) {
        this.xmlName = xmlName;
    }

    /** The name as written in style files, for example {@code bilinear}. */
    public String xmlName() {
        return xmlName;
    }
}
