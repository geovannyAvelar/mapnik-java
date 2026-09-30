package dev.avelar.mapnik;

import com.sun.jna.Callback;

/** The native side calls this when it is done with a {@link JavaDatasource}. Not for use outside this package. */
public interface JavaReleaseHandler extends Callback {
    void invoke(long id);
}
