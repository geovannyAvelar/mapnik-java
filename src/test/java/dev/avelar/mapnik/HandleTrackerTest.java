package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HandleTrackerTest {
    private static final class Owner {}

    private static void waitForLeaks(long atLeast) throws InterruptedException {
        for (int i = 0; i < 100 && HandleTracker.leaked() < atLeast; i++) {
            System.gc();
            Thread.sleep(50);
        }
    }

    @Test
    void anUnclosedHandleIsCounted() throws Exception {
        long before = HandleTracker.leaked();
        HandleTracker.track(new Owner(), "Test");
        waitForLeaks(before + 1);
        assertEquals(before + 1, HandleTracker.leaked());
    }

    @Test
    void aClosedHandleIsNotCounted() throws Exception {
        long before = HandleTracker.leaked();
        Owner o = new Owner();
        HandleTracker t = HandleTracker.track(o, "Test");
        t.closed();
        o = null;
        for (int i = 0; i < 10; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertEquals(before, HandleTracker.leaked());
    }
}
