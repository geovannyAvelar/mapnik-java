package dev.avelar.mapnik;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Notices native handles that were garbage collected without being closed, and reports them. It does
 * not free them: an object can become unreachable while a native call that uses its handle is still
 * running, so freeing from the garbage collector could crash the JVM. Close what you create, ideally
 * with try-with-resources.
 *
 * <p>Set the system property {@code mapnik.leakTrace=true} to record where each handle was created and
 * include that in the warning. It is off by default because it costs a stack trace per handle.
 */
final class HandleTracker extends PhantomReference<Object> {
    private static final Logger LOG = Logger.getLogger("dev.avelar.mapnik");
    private static final ReferenceQueue<Object> QUEUE = new ReferenceQueue<>();
    private static final Set<HandleTracker> LIVE = Collections.newSetFromMap(new ConcurrentHashMap<HandleTracker, Boolean>());
    private static final AtomicLong LEAKED = new AtomicLong();
    private static final boolean TRACE = Boolean.getBoolean("mapnik.leakTrace");
    private static volatile boolean started;

    private final String kind;
    private final Throwable site;
    private volatile boolean closed;

    private HandleTracker(Object owner, String kind) {
        super(owner, QUEUE);
        this.kind = kind;
        this.site = TRACE ? new Throwable("created here") : null;
    }

    /** Start watching {@code owner}. Call {@link #closed()} from its {@code close()}. */
    static HandleTracker track(Object owner, String kind) {
        start();
        HandleTracker t = new HandleTracker(owner, kind);
        LIVE.add(t);
        return t;
    }

    void closed() {
        closed = true;
        LIVE.remove(this);
    }

    /** How many handles have been found collected without being closed since the JVM started. */
    static long leaked() {
        return LEAKED.get();
    }

    private static synchronized void start() {
        if (started) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        HandleTracker r = (HandleTracker) QUEUE.remove();
                        LIVE.remove(r);
                        if (!r.closed) {
                            long n = LEAKED.incrementAndGet();
                            if (n <= 10) {
                                LOG.log(Level.WARNING, "a " + r.kind + " was garbage collected without close(); its native memory is not freed."
                                    + " Use try-with-resources." + (TRACE ? "" : " Set -Dmapnik.leakTrace=true to see where it was created."), r.site);
                            }
                        }
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "mapnik-java-leak-detector");
        t.setDaemon(true);
        t.start();
        started = true;
    }
}
