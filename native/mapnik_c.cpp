#include "mapnik_c.h"

#include <mapnik/agg_renderer.hpp>
#include <mapnik/geometry/box2d.hpp>
#include <mapnik/datasource_cache.hpp>
#include <mapnik/font_engine_freetype.hpp>
#include <mapnik/image.hpp>
#include <mapnik/image_util.hpp>
#include <mapnik/load_map.hpp>
#include <mapnik/map.hpp>
#include <mapnik/version.hpp>

#include <cstdlib>
#include <cstring>
#include <exception>
#include <string>

struct mapnik_map {
    mapnik::Map map;
    mapnik_map(int w, int h) : map(w, h) {}
};

namespace {
thread_local std::string g_error;

template <typename F>
int guarded(F&& f) {
    try {
        f();
        return 0;
    } catch (std::exception const& e) {
        g_error = e.what();
    } catch (...) {
        g_error = "unknown C++ exception";
    }
    return -1;
}

mapnik::image_rgba8 render(mapnik_map_t* m) {
    mapnik::image_rgba8 im(m->map.width(), m->map.height());
    mapnik::agg_renderer<mapnik::image_rgba8> r(m->map, im);
    r.apply();
    return im;
}
}  // namespace

extern "C" {

int mapnik_register_datasources(const char* dir) {
    return guarded([&] { mapnik::datasource_cache::instance().register_datasources(dir); });
}

int mapnik_register_fonts(const char* dir) {
    return guarded([&] { mapnik::freetype_engine::register_fonts(dir, true); });
}

mapnik_map_t* mapnik_map_create(int w, int h) {
    mapnik_map_t* m = nullptr;
    guarded([&] { m = new mapnik_map(w, h); });
    return m;
}

void mapnik_map_free(mapnik_map_t* m) { delete m; }

int mapnik_map_load(mapnik_map_t* m, const char* path) {
    return guarded([&] { mapnik::load_map(m->map, path); });
}

int mapnik_map_load_string(mapnik_map_t* m, const char* xml, const char* base) {
    return guarded([&] { mapnik::load_map_string(m->map, xml, false, base ? base : ""); });
}

void mapnik_map_resize(mapnik_map_t* m, int w, int h) { m->map.resize(w, h); }

void mapnik_map_zoom_to_box(mapnik_map_t* m, double a, double b, double c, double d) {
    m->map.zoom_to_box(mapnik::box2d<double>(a, b, c, d));
}

void mapnik_map_zoom_all(mapnik_map_t* m) { m->map.zoom_all(); }

int mapnik_map_render_to_file(mapnik_map_t* m, const char* path, const char* fmt) {
    return guarded([&] {
        auto im = render(m);
        mapnik::save_to_file(im, path, fmt);
    });
}

int mapnik_map_render_to_buffer(mapnik_map_t* m, const char* fmt, unsigned char** out, int* len) {
    return guarded([&] {
        auto im = render(m);
        std::string s = mapnik::save_to_string(im, fmt);
        auto* buf = static_cast<unsigned char*>(std::malloc(s.size()));
        if (!buf) throw std::bad_alloc();
        std::memcpy(buf, s.data(), s.size());
        *out = buf;
        *len = static_cast<int>(s.size());
    });
}

void mapnik_buffer_free(unsigned char* buf) { std::free(buf); }

const char* mapnik_last_error(void) { return g_error.c_str(); }
const char* mapnik_version(void) { return MAPNIK_VERSION_STRING; }

}  // extern "C"
