package org.mapnik;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.nio.file.Path;

/** A Mapnik map. Not thread-safe: use one instance per thread. */
public final class MapnikMap implements AutoCloseable {
    private static final NativeApi N = NativeApi.INSTANCE;

    private Pointer handle;

    public MapnikMap(int width, int height) {
        handle = N.mapnik_map_create(width, height);
        if (handle == null) {
            throw new MapnikException(N.mapnik_last_error());
        }
    }

    public MapnikMap load(Path styleXml) {
        Mapnik.check(N.mapnik_map_load(ptr(), styleXml.toString()));
        return this;
    }

    public MapnikMap loadString(String xml, Path basePath) {
        Mapnik.check(N.mapnik_map_load_string(ptr(), xml, basePath == null ? null : basePath.toString()));
        return this;
    }

    public MapnikMap resize(int width, int height) {
        N.mapnik_map_resize(ptr(), width, height);
        return this;
    }

    public MapnikMap zoomToBox(double minx, double miny, double maxx, double maxy) {
        N.mapnik_map_zoom_to_box(ptr(), minx, miny, maxx, maxy);
        return this;
    }

    public MapnikMap zoomAll() {
        N.mapnik_map_zoom_all(ptr());
        return this;
    }

    public void renderToFile(Path out, String format) {
        Mapnik.check(N.mapnik_map_render_to_file(ptr(), out.toString(), format));
    }

    public byte[] renderToBytes(String format) {
        PointerByReference out = new PointerByReference();
        IntByReference len = new IntByReference();
        Mapnik.check(N.mapnik_map_render_to_buffer(ptr(), format, out, len));
        Pointer buf = out.getValue();
        try {
            return buf.getByteArray(0, len.getValue());
        } finally {
            N.mapnik_buffer_free(buf);
        }
    }

    public byte[] renderToPng() {
        return renderToBytes("png");
    }

    private Pointer ptr() {
        if (handle == null) {
            throw new IllegalStateException("map closed");
        }
        return handle;
    }

    @Override
    public void close() {
        if (handle != null) {
            N.mapnik_map_free(handle);
            handle = null;
        }
    }
}
