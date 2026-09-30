#include "common.hpp"

#include <mapnik/agg_renderer.hpp>
#include <mapnik/image.hpp>
#include <mapnik/image_util.hpp>

#if defined(HAVE_CAIRO)
#include <mapnik/cairo_io.hpp>
#endif

#include <cstdlib>
#include <cstring>
#include <stdexcept>

mapnik::image_rgba8& mc_image_of(mapnik_image_t* img);

using namespace mc;

namespace {

mapnik::image_rgba8 render(mapnik_map_t* m, double scale, int ox, int oy) {
    mapnik::image_rgba8 im(m->map.width(), m->map.height());
    mapnik::agg_renderer<mapnik::image_rgba8> r(m->map, im, scale, static_cast<unsigned>(ox), static_cast<unsigned>(oy));
    r.apply();
    return im;
}

}  // namespace

extern "C" {

int mapnik_map_render_to_file(mapnik_map_t* m, const char* path, const char* fmt, double scale, int ox, int oy) {
    return guarded([&] {
        auto im = render(m, scale, ox, oy);
        mapnik::save_to_file(im, path, fmt);
    });
}

int mapnik_map_render_to_buffer(mapnik_map_t* m, const char* fmt, unsigned char** out, int* len, double scale,
                                int ox, int oy) {
    return guarded([&] {
        auto im = render(m, scale, ox, oy);
        std::string s = mapnik::save_to_string(im, fmt);
        auto* buf = static_cast<unsigned char*>(std::malloc(s.size()));
        if (!buf) throw std::bad_alloc();
        std::memcpy(buf, s.data(), s.size());
        *out = buf;
        *len = static_cast<int>(s.size());
    });
}

int mapnik_map_render_to_image(mapnik_map_t* m, mapnik_image_t* img, double scale, int ox, int oy) {
    return guarded([&] {
        mapnik::image_rgba8& im = mc_image_of(img);
        if (im.width() != m->map.width() || im.height() != m->map.height()) {
            throw std::invalid_argument("image size must match the map size");
        }
        // The renderer blends onto a premultiplied canvas; Image is straight alpha outside this call.
        mapnik::premultiply_alpha(im);
        try {
            mapnik::agg_renderer<mapnik::image_rgba8> r(m->map, im, scale, static_cast<unsigned>(ox),
                                                       static_cast<unsigned>(oy));
            r.apply();
        } catch (...) {
            mapnik::demultiply_alpha(im);
            throw;
        }
        mapnik::demultiply_alpha(im);
    });
}

int mapnik_cairo_available(void) {
#if defined(HAVE_CAIRO)
    return 1;
#else
    return 0;
#endif
}

int mapnik_map_render_to_cairo_file(mapnik_map_t* m, const char* path, const char* type, double scale) {
#if defined(HAVE_CAIRO)
    return guarded([&] { mapnik::save_to_cairo_file(m->map, path, type, scale); });
#else
    (void)m; (void)path; (void)type; (void)scale;
    g_error = "this Mapnik was built without Cairo support";
    return -1;
#endif
}

void mapnik_buffer_free(unsigned char* buf) { std::free(buf); }

}  // extern "C"
