package dev.avelar.mapnik;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A fixed number of identical maps shared by many threads. A {@link MapnikMap} must not be used by two
 * threads at once, so a server that renders on request threads borrows one map, draws, and gives it
 * back. Every map is set up the same way by the function you pass in, usually by loading the style.
 *
 * <pre>{@code
 * MapPool pool = new MapPool(8, 256, 256, map -> map.load(Paths.get("style.xml")));
 * byte[] png = pool.withMap(map -> {
 *     map.zoomToBox(bounds);
 *     return map.renderToPng();
 * });
 * pool.close();
 * }</pre>
 *
 * <p>A map returns to the pool as you left it. Set size and bounds for each use, and do not change the
 * styles or layers inside {@link #withMap}, unless you do the same on every use.
 */
public final class MapPool implements AutoCloseable {
    private final List<MapnikMap> all = new ArrayList<>();
    private final BlockingQueue<MapnikMap> idle;
    private volatile boolean closed;

    /**
     * Create {@code size} maps of {@code width} by {@code height} pixels and run {@code setup} on each.
     * If setup fails for one, the maps made so far are closed and the exception is thrown.
     */
    public MapPool(int size, int width, int height, Consumer<MapnikMap> setup) {
        if (size < 1) {
            throw new IllegalArgumentException("a pool needs at least one map: " + size);
        }
        idle = new ArrayBlockingQueue<>(size);
        try {
            for (int i = 0; i < size; i++) {
                MapnikMap m = new MapnikMap(width, height);
                all.add(m);
                setup.accept(m);
                idle.add(m);
            }
        } catch (RuntimeException | Error e) {
            closeAll();
            throw e;
        }
    }

    /** A map borrowed from the pool. Close it to give the map back. */
    public final class Lease implements AutoCloseable {
        private MapnikMap map;

        private Lease(MapnikMap map) {
            this.map = map;
        }

        public MapnikMap map() {
            if (map == null) {
                throw new IllegalStateException("this map was given back");
            }
            return map;
        }

        @Override
        public void close() {
            if (map != null) {
                MapnikMap m = map;
                map = null;
                giveBack(m);
            }
        }
    }

    /** Wait for a free map. Close the lease when done, ideally with try-with-resources. */
    public Lease borrow() {
        try {
            return borrow(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException(e); // not reachable: no real timeout
        }
    }

    /** Wait up to {@code timeout} for a free map; throws {@link java.util.concurrent.TimeoutException} if none comes free. */
    public Lease borrow(long timeout, TimeUnit unit) throws java.util.concurrent.TimeoutException {
        if (closed) {
            throw new IllegalStateException("the pool is closed");
        }
        try {
            MapnikMap m = idle.poll(timeout, unit);
            if (m == null) {
                throw new java.util.concurrent.TimeoutException("no map came free in " + timeout + " " + unit);
            }
            if (closed) {
                giveBack(m);
                throw new IllegalStateException("the pool is closed");
            }
            return new Lease(m);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for a map", e);
        }
    }

    /** Borrow a map, run {@code work} with it and give it back, also when {@code work} throws. */
    public <T> T withMap(Function<MapnikMap, T> work) {
        try (Lease lease = borrow()) {
            return work.apply(lease.map());
        }
    }

    /** The number of maps in the pool, borrowed or not. */
    public int size() {
        return all.size();
    }

    /** The number of maps free right now. */
    public int available() {
        return idle.size();
    }

    private void giveBack(MapnikMap m) {
        if (closed) {
            m.close();
        } else {
            idle.add(m);
            if (closed && idle.remove(m)) {
                m.close(); // closed while we were adding it
            }
        }
    }

    /**
     * Close the free maps now and the borrowed ones as they come back. Do not close while threads
     * still need the pool: they get an {@link IllegalStateException}.
     */
    @Override
    public void close() {
        closed = true;
        closeAll();
    }

    private void closeAll() {
        MapnikMap m;
        while ((m = idle.poll()) != null) {
            m.close();
        }
        if (!closed) {
            // construction failed: nothing is borrowed
            for (MapnikMap each : all) {
                each.close();
            }
        }
    }
}
