package dev.avelar.mapnik;

/**
 * How one image or layer is blended onto another. The names are the ones used in style files
 * (the {@code comp-op} attribute), see {@link #xmlName()}. Most people want {@link #SRC_OVER}, the
 * normal "paint on top", or {@link #MULTIPLY} and {@link #SCREEN}.
 */
public enum BlendMode {
    CLEAR("clear"),
    SRC("src"),
    DST("dst"),
    SRC_OVER("src-over"),
    DST_OVER("dst-over"),
    SRC_IN("src-in"),
    DST_IN("dst-in"),
    SRC_OUT("src-out"),
    DST_OUT("dst-out"),
    SRC_ATOP("src-atop"),
    DST_ATOP("dst-atop"),
    XOR("xor"),
    PLUS("plus"),
    MINUS("minus"),
    MULTIPLY("multiply"),
    SCREEN("screen"),
    OVERLAY("overlay"),
    DARKEN("darken"),
    LIGHTEN("lighten"),
    COLOR_DODGE("color-dodge"),
    COLOR_BURN("color-burn"),
    HARD_LIGHT("hard-light"),
    SOFT_LIGHT("soft-light"),
    DIFFERENCE("difference"),
    EXCLUSION("exclusion"),
    CONTRAST("contrast"),
    INVERT("invert"),
    INVERT_RGB("invert-rgb"),
    GRAIN_MERGE("grain-merge"),
    GRAIN_EXTRACT("grain-extract"),
    HUE("hue"),
    SATURATION("saturation"),
    COLOR("color"),
    VALUE("value"),
    LINEAR_DODGE("linear-dodge"),
    LINEAR_BURN("linear-burn"),
    DIVIDE("divide");

    private final String xmlName;

    BlendMode(String xmlName) {
        this.xmlName = xmlName;
    }

    /** The name as written in style files, for example {@code src-over}. */
    public String xmlName() {
        return xmlName;
    }

    /** Find a mode by its style-file name. Throws {@link IllegalArgumentException} if there is none. */
    public static BlendMode fromXmlName(String name) {
        for (BlendMode m : values()) {
            if (m.xmlName.equals(name)) {
                return m;
            }
        }
        throw new IllegalArgumentException("unknown blend mode: " + name);
    }
}
