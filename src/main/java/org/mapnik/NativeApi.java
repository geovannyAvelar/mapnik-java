package org.mapnik;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

/** Raw JNA mapping of native/mapnik_c.h. Use {@link MapnikMap} instead. */
interface NativeApi extends Library {
    NativeApi INSTANCE = Native.load("mapnik_c", NativeApi.class);

    int mapnik_register_datasources(String dir);
    int mapnik_register_fonts(String dir);

    Pointer mapnik_map_create(int width, int height);
    void mapnik_map_free(Pointer map);

    int mapnik_map_load(Pointer map, String path);
    int mapnik_map_load_string(Pointer map, String xml, String basePath);
    void mapnik_map_resize(Pointer map, int width, int height);
    void mapnik_map_zoom_to_box(Pointer map, double minx, double miny, double maxx, double maxy);
    void mapnik_map_zoom_all(Pointer map);

    int mapnik_map_render_to_file(Pointer map, String path, String format);
    int mapnik_map_render_to_buffer(Pointer map, String format, PointerByReference out, IntByReference len);
    void mapnik_buffer_free(Pointer buf);

    String mapnik_last_error();
    String mapnik_version();
}
