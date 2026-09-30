package dev.avelar.mapnik;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** What to ask a {@link Datasource} for: an area, optionally a resolution, scale and attribute list. */
public final class FeatureQuery {
    private final Box2d bbox;
    private final double resolutionX;
    private final double resolutionY;
    private final double scaleDenominator;
    private final List<String> properties;

    private FeatureQuery(Box2d bbox, double rx, double ry, double scale, List<String> properties) {
        this.bbox = bbox;
        this.resolutionX = rx;
        this.resolutionY = ry;
        this.scaleDenominator = scale;
        this.properties = properties;
    }

    /** Features whose bounding box touches {@code bbox}, in the datasource's projection. */
    public static FeatureQuery within(Box2d bbox) {
        return new FeatureQuery(bbox, 1.0, 1.0, 1.0, Collections.<String>emptyList());
    }

    /** Pixels per map unit on each axis. Some datasources use it to simplify geometry. */
    public FeatureQuery resolution(double x, double y) {
        return new FeatureQuery(bbox, x, y, scaleDenominator, properties);
    }

    public FeatureQuery scaleDenominator(double scaleDenominator) {
        return new FeatureQuery(bbox, resolutionX, resolutionY, scaleDenominator, properties);
    }

    /** Only read these attributes. By default a datasource may return none or all, depending on the plugin. */
    public FeatureQuery properties(String... names) {
        return properties(Arrays.asList(names));
    }

    public FeatureQuery properties(Collection<String> names) {
        return new FeatureQuery(bbox, resolutionX, resolutionY, scaleDenominator,
            Collections.unmodifiableList(new ArrayList<>(names)));
    }

    Box2d bbox() { return bbox; }
    double resolutionX() { return resolutionX; }
    double resolutionY() { return resolutionY; }
    double scaleDenominator() { return scaleDenominator; }
    String[] propertyArray() { return properties.toArray(new String[0]); }
    int propertyCount() { return properties.size(); }
}
