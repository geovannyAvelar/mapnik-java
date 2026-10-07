package dev.avelar.mapnik;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * A stream of features from a query. Read it once, in order, then {@link #close()} it, ideally with
 * try-with-resources. Closing while features remain just discards them.
 */
public final class Featureset implements Iterable<Feature>, Iterator<Feature>, AutoCloseable {
    private static final NativeApi N = NativeApi.INSTANCE;

    private Pointer handle;
    private final HandleTracker tracker = HandleTracker.track(this, "Featureset");
    private Feature next;
    private boolean done;
    private boolean iterated;

    Featureset(Pointer handle) {
        this.handle = handle;
    }

    static Featureset check(Pointer p) {
        if (p == null) {
            throw new MapnikException(N.mapnik_last_error());
        }
        return new Featureset(p);
    }

    /** Read all remaining features into a list. */
    public List<Feature> toList() {
        List<Feature> out = new ArrayList<>();
        while (hasNext()) {
            out.add(next());
        }
        return out;
    }

    @Override
    public Iterator<Feature> iterator() {
        if (iterated) {
            throw new IllegalStateException("a Featureset can only be iterated once");
        }
        iterated = true;
        return this;
    }

    @Override
    public boolean hasNext() {
        if (next != null) {
            return true;
        }
        if (done) {
            return false;
        }
        if (handle == null) {
            throw new IllegalStateException("featureset closed");
        }
        PointerByReference out = new PointerByReference();
        int rc = N.mapnik_featureset_next(handle, out);
        if (rc < 0) {
            done = true;
            throw new MapnikException(N.mapnik_last_error());
        }
        if (rc == 0) {
            done = true;
            return false;
        }
        Pointer f = out.getValue();
        try {
            next = Feature.read(f);
        } finally {
            N.mapnik_feature_free(f);
        }
        return true;
    }

    @Override
    public Feature next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        Feature f = next;
        next = null;
        return f;
    }

    @Override
    public void close() {
        tracker.closed();
        if (handle != null) {
            N.mapnik_featureset_free(handle);
            handle = null;
        }
    }
}
