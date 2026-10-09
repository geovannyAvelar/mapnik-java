#include "common.hpp"

#include <mapnik/marker.hpp>
#include <mapnik/marker_cache.hpp>

using namespace mc;

extern "C" {

int mapnik_marker_inspect(const char* path, int strict, int* kind, double* out) {
    return guarded([&] {
        // Not cached: this only looks at the file. Strict makes the SVG parser refuse what it would otherwise skip.
        auto marker = mapnik::marker_cache::instance().find(path, false, strict != 0);
        if (!marker || marker->is<mapnik::marker_null>()) {
            throw std::runtime_error(std::string("not a readable SVG or image marker (with strict, anything Mapnik cannot understand counts; its log says what): ") + path);
        }
        if (marker->is<mapnik::marker_svg>()) {
            auto const& svg = marker->get_unchecked<mapnik::marker_svg>();
            auto box = svg.bounding_box();
            auto dim = svg.dimensions();
            *kind = 1;
            out[0] = box.minx();
            out[1] = box.miny();
            out[2] = box.maxx();
            out[3] = box.maxy();
            out[4] = std::get<0>(dim);
            out[5] = std::get<1>(dim);
        } else {
            *kind = 2;
            out[0] = 0;
            out[1] = 0;
            out[2] = marker->width();
            out[3] = marker->height();
            out[4] = marker->width();
            out[5] = marker->height();
        }
    });
}

}  // extern "C"
