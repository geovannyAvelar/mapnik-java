#include "common.hpp"

#include <mapnik/agg_renderer.hpp>
#include <mapnik/image.hpp>
#include <mapnik/image_util.hpp>

#include <cstdlib>
#include <cstring>

using namespace mc;

namespace {
mapnik::image_rgba8 render(mapnik_map_t* m) {
    mapnik::image_rgba8 im(m->map.width(), m->map.height());
    mapnik::agg_renderer<mapnik::image_rgba8> r(m->map, im);
    r.apply();
    return im;
}
}  // namespace

extern "C" {

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

}  // extern "C"
