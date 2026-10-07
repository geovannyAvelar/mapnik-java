package dev.avelar.mapnik;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
 * <p>For a server, use {@link #builder} to also set how long a request may wait for a map, and a warm-up
 * that draws once on each map before the pool is used. {@link #stats()} reports how busy the pool is.
 *
 * <p>A map returns to the pool as you left it. Set size and bounds for each use, and do not change the
 * styles or layers inside {@link #withMap}, unless you do the same on every use.
 */
public final class MapPool implements AutoCloseable {
    private final List<MapnikMap> all = new ArrayList<>();
    private final BlockingQueue<MapnikMap> idle;
    private final long maxWaitNanos;   // 0: wait for as long as it takes
    private volatile boolean closed;

    private final AtomicLong borrows = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();
    private final AtomicLong waitedNanos = new AtomicLong();
    private final AtomicLong longestWaitNanos = new AtomicLong();
    private final AtomicInteger waiting = new AtomicInteger();

    /** Thrown by {@link #borrow()} and {@link #withMap} when no map came free within the pool's maximum wait. */
    public static final class ExhaustedException extends MapnikException {
        ExhaustedException(String message) {
            super(message);
        }
    }

    /** How busy a pool has been since it was created. */
    public static final class Stats {
        private final int size, available, waiting;
        private final long borrows, timeouts, waitedNanos, longestWaitNanos;

        Stats(int size, int available, int waiting, long borrows, long timeouts, long waitedNanos, long longestWaitNanos) {
            this.size = size;
            this.available = available;
            this.waiting = waiting;
            this.borrows = borrows;
            this.timeouts = timeouts;
            this.waitedNanos = waitedNanos;
            this.longestWaitNanos = longestWaitNanos;
        }

        /** Maps in the pool. */
        public int size() { return size; }

        /** Maps free right now. */
        public int available() { return available; }

        /** Maps borrowed right now. */
        public int borrowed() { return size - available; }

        /** Callers waiting for a map right now. */
        public int waiting() { return waiting; }

        /** Maps handed out so far. */
        public long borrows() { return borrows; }

        /** Requests that gave up waiting. */
        public long timeouts() { return timeouts; }

        /** Total time callers spent waiting for a map, in nanoseconds. */
        public long waitedNanos() { return waitedNanos; }

        /** The longest a single caller waited, in nanoseconds. */
        public long longestWaitNanos() { return longestWaitNanos; }

        /** Average wait per borrow, in nanoseconds; 0 before any borrow. */
        public long averageWaitNanos() { return borrows == 0 ? 0 : waitedNanos / borrows; }

        @Override
        public String toString() {
            return "MapPool[" + borrowed() + "/" + size + " busy, " + waiting + " waiting, " + borrows + " borrows, "
                + timeouts + " timeouts, average wait " + averageWaitNanos() / 1000 + " us]";
        }
    }

    /** Builds a pool; see {@link MapPool#builder}. */
    public static final class Builder {
        private final int size, width, height;
        private Consumer<MapnikMap> setup = m -> { };
        private Consumer<MapnikMap> warmup;
        private long maxWaitNanos;

        private Builder(int size, int width, int height) {
            this.size = size;
            this.width = width;
            this.height = height;
        }

        /** Run on each map when the pool is created, typically to load the style. */
        public Builder setup(Consumer<MapnikMap> setup) {
            this.setup = setup;
            return this;
        }

        /**
         * Run on each map after setup, to make the first real request as fast as the rest: draw once, so that
         * fonts, symbols and datasources are loaded and cached. If it throws, the pool is not created.
         */
        public Builder warmup(Consumer<MapnikMap> warmup) {
            this.warmup = warmup;
            return this;
        }

        /**
         * The longest {@link MapPool#borrow()} and {@link MapPool#withMap} wait for a free map before they throw
         * {@link ExhaustedException}. Without it they wait as long as it takes, which under overload stacks up
         * requests; a limit lets a server answer "busy" instead.
         */
        public Builder maxWait(long timeout, TimeUnit unit) {
            if (timeout < 0) {
                throw new IllegalArgumentException("the wait must not be negative: " + timeout);
            }
            this.maxWaitNanos = unit.toNanos(timeout);
            return this;
        }

        public MapPool build() {
            return new MapPool(size, width, height, setup, warmup, maxWaitNanos);
        }
    }

    /** Start building a pool of {@code size} maps of {@code width} by {@code height} pixels. */
    public static Builder builder(int size, int width, int height) {
        return new Builder(size, width, height);
    }

    /**
     * Create {@code size} maps of {@code width} by {@code height} pixels and run {@code setup} on each.
     * If setup fails for one, the maps made so far are closed and the exception is thrown.
     */
    public MapPool(int size, int width, int height, Consumer<MapnikMap> setup) {
        this(size, width, height, setup, null, 0);
    }

    private MapPool(int size, int width, int height, Consumer<MapnikMap> setup, Consumer<MapnikMap> warmup, long maxWaitNanos) {
        this.maxWaitNanos = maxWaitNanos;
        if (size < 1) {
            throw new IllegalArgumentException("a pool needs at least one map: " + size);
        }
        idle = new ArrayBlockingQueue<>(size);
        try {
            for (int i = 0; i < size; i++) {
                MapnikMap m = new MapnikMap(width, height);
                all.add(m);
                setup.accept(m);
                if (warmup != null) {
                    warmup.accept(m);
                }
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

    /**
     * Wait for a free map. Close the lease when done, ideally with try-with-resources. Waits as long as
     * the pool's maximum wait if it has one (then throws {@link ExhaustedException}), else as long as it takes.
     */
    public Lease borrow() {
        try {
            return maxWaitNanos > 0 ? borrow(maxWaitNanos, TimeUnit.NANOSECONDS) : borrow(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new ExhaustedException("no map came free within " + TimeUnit.NANOSECONDS.toMillis(maxWaitNanos)
                + " ms: all " + all.size() + " maps are busy");
        }
    }

    /** Wait up to {@code timeout} for a free map; throws {@link java.util.concurrent.TimeoutException} if none comes free. */
    public Lease borrow(long timeout, TimeUnit unit) throws java.util.concurrent.TimeoutException {
        if (closed) {
            throw new IllegalStateException("the pool is closed");
        }
        long start = System.nanoTime();
        waiting.incrementAndGet();
        try {
            MapnikMap m = idle.poll(timeout, unit);
            long waited = System.nanoTime() - start;
            if (m == null) {
                timeouts.incrementAndGet();
                throw new java.util.concurrent.TimeoutException("no map came free in " + timeout + " " + unit);
            }
            borrows.incrementAndGet();
            waitedNanos.addAndGet(waited);
            longestWaitNanos.accumulateAndGet(waited, Math::max);
            if (closed) {
                giveBack(m);
                throw new IllegalStateException("the pool is closed");
            }
            return new Lease(m);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for a map", e);
        } finally {
            waiting.decrementAndGet();
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

    /** A snapshot of how busy the pool has been. */
    public Stats stats() {
        return new Stats(all.size(), idle.size(), waiting.get(), borrows.get(), timeouts.get(), waitedNanos.get(),
            longestWaitNanos.get());
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
