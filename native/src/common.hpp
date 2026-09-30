#ifndef MAPNIK_C_COMMON_HPP
#define MAPNIK_C_COMMON_HPP

#include "../mapnik_c.h"

#include <mapnik/datasource.hpp>
#include <mapnik/geometry/box2d.hpp>
#include <mapnik/layer.hpp>
#include <mapnik/map.hpp>
#include <mapnik/params.hpp>

#include <exception>
#include <memory>
#include <string>

struct mapnik_map {
    mapnik::Map map;
    mapnik_map(int w, int h) : map(w, h) {}
};

struct mapnik_params {
    mapnik::parameters params;
};

struct mapnik_datasource {
    std::shared_ptr<mapnik::datasource> ds;
};

namespace mc {

extern thread_local std::string g_error;
extern thread_local std::string g_text;  // backing store for returned const char*

// mapnik_layer_t is really a mapnik::layer; it is never defined in C++.
inline mapnik::layer& L(mapnik_layer_t* l) { return *reinterpret_cast<mapnik::layer*>(l); }
inline mapnik_layer_t* H(mapnik::layer* l) { return reinterpret_cast<mapnik_layer_t*>(l); }

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

inline const char* text(std::string const& s) {
    g_text = s;
    return g_text.c_str();
}

inline void write_box(mapnik::box2d<double> const& b, double* out) {
    out[0] = b.minx();
    out[1] = b.miny();
    out[2] = b.maxx();
    out[3] = b.maxy();
}

inline bool valid_index(int i, size_t n) { return i >= 0 && static_cast<size_t>(i) < n; }

}  // namespace mc

#endif
