package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MapPoolIntegrationTest {
    private static final String STYLE = "<Map background-color=\"#336699\"><Style name=\"s\"><Rule/></Style></Map>";

    @Test
    void manyThreadsShareAFewMaps() throws Exception {
        AtomicInteger inUse = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ExecutorService threads = Executors.newFixedThreadPool(12);
        try (MapPool pool = new MapPool(3, 64, 64, m -> m.loadString(STYLE, null))) {
            List<Future<byte[]>> results = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                results.add(threads.submit(() -> pool.withMap(m -> {
                    int now = inUse.incrementAndGet();
                    peak.accumulateAndGet(now, Math::max);
                    try {
                        m.zoomToBox(-10, -10, 10, 10);
                        return m.renderToPng();
                    } finally {
                        inUse.decrementAndGet();
                    }
                })));
            }
            for (Future<byte[]> f : results) {
                byte[] png = f.get(60, TimeUnit.SECONDS);
                assertEquals((byte) 0x89, png[0]);
            }
            assertTrue(peak.get() <= 3, "never more maps in use than the pool holds: " + peak);
            assertEquals(3, pool.available());
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void borrowWaitsAndTimesOut() throws Exception {
        try (MapPool pool = new MapPool(1, 8, 8, m -> m.loadString(STYLE, null))) {
            try (MapPool.Lease first = pool.borrow()) {
                assertNotNull(first.map());
                assertEquals(0, pool.available());
                assertThrows(TimeoutException.class, () -> pool.borrow(100, TimeUnit.MILLISECONDS));
            }
            assertEquals(1, pool.available());
            try (MapPool.Lease again = pool.borrow(1, TimeUnit.SECONDS)) {
                assertNotNull(again.map());
            }
        }
    }

    @Test
    void aMapIsGivenBackWhenTheWorkThrows() {
        try (MapPool pool = new MapPool(1, 8, 8, m -> m.loadString(STYLE, null))) {
            assertThrows(IllegalArgumentException.class, () -> pool.withMap(m -> {
                throw new IllegalArgumentException("boom");
            }));
            assertEquals(1, pool.available());
        }
    }

    @Test
    void aFailingSetupClosesWhatWasMadeAndThrows() {
        AtomicInteger made = new AtomicInteger();
        assertThrows(MapLoadException.class, () -> new MapPool(3, 8, 8, m -> {
            if (made.incrementAndGet() == 2) {
                m.loadString("<Map", null);
            }
        }));
        assertEquals(2, made.get());
    }

    @Test
    void aLeaseCannotBeUsedAfterItIsGivenBack() {
        try (MapPool pool = new MapPool(1, 8, 8, m -> m.loadString(STYLE, null))) {
            MapPool.Lease lease = pool.borrow();
            lease.close();
            lease.close(); // harmless
            assertThrows(IllegalStateException.class, lease::map);
        }
    }

    @Test
    void aClosedPoolRefusesAndClosesBorrowedMapsOnReturn() {
        MapPool pool = new MapPool(2, 8, 8, m -> m.loadString(STYLE, null));
        MapPool.Lease lease = pool.borrow();
        pool.close();
        assertThrows(IllegalStateException.class, pool::borrow);
        lease.close();
        assertThrows(IllegalArgumentException.class, () -> new MapPool(0, 8, 8, m -> { }));
    }

    @Test
    void statsCountBorrowsWaitsAndTimeouts() throws Exception {
        try (MapPool pool = new MapPool(2, 8, 8, m -> m.loadString(STYLE, null))) {
            assertEquals(0, pool.stats().borrows());
            try (MapPool.Lease a = pool.borrow(); MapPool.Lease b = pool.borrow()) {
                MapPool.Stats busy = pool.stats();
                assertEquals(2, busy.size());
                assertEquals(2, busy.borrowed());
                assertEquals(0, busy.available());
                assertEquals(2, busy.borrows());
                assertThrows(TimeoutException.class, () -> pool.borrow(50, TimeUnit.MILLISECONDS));
            }
            MapPool.Stats after = pool.stats();
            assertEquals(1, after.timeouts());
            assertEquals(0, after.borrowed());
            assertEquals(0, after.waiting());
            assertTrue(after.toString().contains("2 borrows"), after.toString());
        }
    }

    @Test
    void aWaitingCallerIsCountedAndTheWaitIsMeasured() throws Exception {
        try (MapPool pool = new MapPool(1, 8, 8, m -> m.loadString(STYLE, null))) {
            ExecutorService one = Executors.newSingleThreadExecutor();
            try {
                MapPool.Lease held = pool.borrow();
                Future<?> waiter = one.submit(() -> pool.borrow().close());
                for (int i = 0; i < 200 && pool.stats().waiting() == 0; i++) {
                    Thread.sleep(10);
                }
                assertEquals(1, pool.stats().waiting());
                Thread.sleep(100);
                held.close();
                waiter.get(5, TimeUnit.SECONDS);
                MapPool.Stats s = pool.stats();
                assertEquals(2, s.borrows());
                assertTrue(s.longestWaitNanos() >= TimeUnit.MILLISECONDS.toNanos(100), "waited: " + s);
            } finally {
                one.shutdownNow();
            }
        }
    }

    @Test
    void aMaximumWaitMakesBorrowFailInsteadOfBlocking() {
        try (MapPool pool = MapPool.builder(1, 8, 8).setup(m -> m.loadString(STYLE, null)).maxWait(80, TimeUnit.MILLISECONDS).build()) {
            try (MapPool.Lease held = pool.borrow()) {
                long start = System.nanoTime();
                MapPool.ExhaustedException e = assertThrows(MapPool.ExhaustedException.class, () -> pool.withMap(m -> "x"));
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                assertTrue(ms >= 70 && ms < 2000, "waited about the limit: " + ms);
                assertTrue(e.getMessage().contains("busy"), e.getMessage());
                assertNotNull(held.map());
            }
            assertEquals("ok", pool.withMap(m -> "ok"));
            assertEquals(1, pool.stats().timeouts());
        }
    }

    @Test
    void warmupRunsOnEveryMapAfterSetupAndFailureStopsTheBuild() {
        List<String> order = java.util.Collections.synchronizedList(new ArrayList<String>());
        try (MapPool pool = MapPool.builder(3, 8, 8)
            .setup(m -> { m.loadString(STYLE, null); order.add("setup"); })
            .warmup(m -> { m.zoomToBox(-1, -1, 1, 1); m.renderToPng().getClass(); order.add("warmup"); })
            .build()) {
            assertEquals(3, pool.size());
        }
        assertEquals(java.util.Arrays.asList("setup", "warmup", "setup", "warmup", "setup", "warmup"), order);
        assertThrows(IllegalStateException.class, () -> MapPool.builder(2, 8, 8)
            .setup(m -> m.loadString(STYLE, null)).warmup(m -> { throw new IllegalStateException("cold"); }).build());
    }
}
