package dev.avelar.mapnik;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A datasource whose features come from your own code, called when Mapnik renders or queries: a
 * database with your own access rules, a web service, generated data, a cache. Unlike a
 * {@link MemoryDatasource}, nothing is held up front and each render asks for just the area it needs.
 *
 * <pre>{@code
 * FeatureSource source = request -> myIndex.query(request.bbox());   // a list of Feature
 * try (JavaDatasource ds = JavaDatasource.create(source, worldExtent)) {
 *     layer.setDatasource(ds);
 * }
 * }</pre>
 *
 * <p>Features' coordinates must be in the projection of the layer. The source is kept alive for as long
 * as Mapnik still uses the datasource, even after you close this handle, for example while a map
 * whose layer uses it is open.
 */
public final class JavaDatasource extends Datasource {
    private static final NativeApi N = NativeApi.INSTANCE;

    /** id to source. An entry stays until Mapnik releases the native datasource. */
    private static final Map<Long, FeatureSource> SOURCES = new ConcurrentHashMap<>();
    private static final AtomicLong IDS = new AtomicLong(1);

    /** The buffer the last call on this thread returned. Native code has copied from it before the next call. */
    private static final ThreadLocal<Memory> BUFFER = new ThreadLocal<>();

    // Held in static fields so they are never garbage collected while native code may call them.
    private static final JavaFeaturesHandler FEATURES = JavaDatasource::onFeatures;
    private static final JavaReleaseHandler RELEASE = id -> SOURCES.remove(id);

    static {
        N.mapnik_java_set_handlers(FEATURES, RELEASE);
    }

    private JavaDatasource(Pointer handle) {
        super(handle);
    }

    /** Create one. {@code envelope} is the extent of all the data, in the layer's projection. */
    public static JavaDatasource create(FeatureSource source, Box2d envelope) {
        return create(source, envelope, Collections.<Field>emptyList());
    }

    /**
     * As {@link #create(FeatureSource, Box2d)}, also declaring the attribute columns, which show up in
     * {@link #fields()}. Mapnik does not check that features have them.
     */
    public static JavaDatasource create(FeatureSource source, Box2d envelope, List<Field> fields) {
        if (source == null || envelope == null) {
            throw new IllegalArgumentException("a source and an envelope are required");
        }
        long id = IDS.getAndIncrement();
        SOURCES.put(id, source);
        String[] names = new String[fields.size()];
        int[] types = new int[fields.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = fields.get(i).name();
            types[i] = fields.get(i).type().ordinal() + 1;
        }
        Pointer p = null;
        try {
            p = N.mapnik_java_datasource_create(id,
                new double[] {envelope.minX(), envelope.minY(), envelope.maxX(), envelope.maxY()},
                names.length == 0 ? null : names, names.length == 0 ? null : types, names.length);
            if (p == null) {
                throw new MapnikException(N.mapnik_last_error());
            }
            return new JavaDatasource(p);
        } finally {
            if (p == null) {
                SOURCES.remove(id);
            }
        }
    }

    /** Change the extent reported for the data, for example as it grows. */
    public JavaDatasource setEnvelope(Box2d envelope) {
        N.mapnik_java_datasource_set_envelope(ptr(), envelope.minX(), envelope.minY(), envelope.maxX(), envelope.maxY());
        return this;
    }

    /** How many Java datasources Mapnik is still using. For tests. */
    static int liveSources() {
        return SOURCES.size();
    }

    // ------------------------------------------------------------------ the native callback

    private static int onFeatures(long id, double minx, double miny, double maxx, double maxy, double rx, double ry,
                                  double scale, Pointer out, Pointer len) {
        try {
            FeatureSource source = SOURCES.get(id);
            if (source == null) {
                throw new IllegalStateException("the Java feature source was already released");
            }
            Iterable<Feature> features = source.features(new FeatureRequest(new Box2d(minx, miny, maxx, maxy), rx, ry, scale));
            publish(encode(features), out, len);
            return 0;
        } catch (Throwable t) {
            String message = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
            publish(message.getBytes(StandardCharsets.UTF_8), out, len);
            return 1;
        }
    }

    private static void publish(byte[] data, Pointer out, Pointer len) {
        Memory m = new Memory(Math.max(1, data.length));
        m.write(0, data, 0, data.length);
        BUFFER.set(m); // keeps it alive until this thread's next call; native code copies before then
        out.setPointer(0, m);
        len.setInt(0, data.length);
    }

    /** The buffer layout is documented in mapnik_c.h. */
    static byte[] encode(Iterable<Feature> features) {
        Out o = new Out();
        int count = 0;
        Out body = new Out();
        if (features != null) {
            for (Feature f : features) {
                count++;
                body.i64(f.id());
                Geometry g = f.geometry();
                byte[] wkb = g.isEmpty() ? new byte[0] : g.toWkb();
                body.u32(wkb.length);
                body.bytes(wkb);
                body.u32(f.attributes().size());
                for (Map.Entry<String, Object> e : f.attributes().entrySet()) {
                    body.str(e.getKey());
                    Object v = e.getValue();
                    if (v == null) {
                        body.u8(0);
                    } else if (v instanceof Boolean) {
                        body.u8(1);
                        body.u8((Boolean) v ? 1 : 0);
                    } else if (v instanceof Double) {
                        body.u8(3);
                        body.f64((Double) v);
                    } else if (v instanceof String) {
                        body.u8(4);
                        body.str((String) v);
                    } else {
                        body.u8(2);
                        body.i64(((Number) v).longValue());
                    }
                }
            }
        }
        o.u32(count);
        o.bytes(body.toArray());
        return o.toArray();
    }

    /** A growing little-endian buffer. */
    private static final class Out {
        private ByteBuffer buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);

        private void ensure(int more) {
            if (buf.remaining() < more) {
                ByteBuffer bigger = ByteBuffer.allocate(Math.max(buf.capacity() * 2, buf.position() + more))
                    .order(ByteOrder.LITTLE_ENDIAN);
                buf.flip();
                bigger.put(buf);
                buf = bigger;
            }
        }

        void u8(int v) { ensure(1); buf.put((byte) v); }
        void u32(long v) { ensure(4); buf.putInt((int) v); }
        void i64(long v) { ensure(8); buf.putLong(v); }
        void f64(double v) { ensure(8); buf.putDouble(v); }
        void bytes(byte[] b) { ensure(b.length); buf.put(b); }

        void str(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            u32(b.length);
            bytes(b);
        }

        byte[] toArray() {
            byte[] out = new byte[buf.position()];
            System.arraycopy(buf.array(), 0, out, 0, out.length);
            return out;
        }
    }
}
