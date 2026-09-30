package dev.avelar.mapnik;

import com.sun.jna.Callback;
import com.sun.jna.Pointer;

/** The native side calls this to ask a {@link JavaDatasource} for features. Not for use outside this package. */
public interface JavaFeaturesHandler extends Callback {
    /** Returns 0 on success and sets *out and *len, or non-zero with an error message in the buffer. */
    int invoke(long id, double minx, double miny, double maxx, double maxy, double resolutionX, double resolutionY,
               double scaleDenominator, Pointer out, Pointer len);
}
