#ifndef MAPNIK_C_COMMON_HPP
#define MAPNIK_C_COMMON_HPP

#include "../mapnik_c.h"

#include <mapnik/datasource.hpp>
#include <mapnik/feature.hpp>
#include <mapnik/featureset.hpp>
#include <mapnik/geometry/box2d.hpp>
#include <mapnik/image.hpp>
#include <mapnik/image_any.hpp>
#include <mapnik/layer.hpp>
#include <mapnik/map.hpp>
#include <mapnik/params.hpp>
#include <mapnik/proj_transform.hpp>

#include <exception>
#include <map>
#include <optional>
#include <mapnik/geometry.hpp>
#include <mapnik/value.hpp>
#include <memory>
#include <string>
#include <utility>
#include <vector>

struct mapnik_image {
    mapnik::image_rgba8 image;  // always straight alpha outside of a render call
    mapnik_image(int w, int h) : image(w, h) {}
    explicit mapnik_image(mapnik::image_rgba8&& i) : image(std::move(i)) {}
};

struct mapnik_gray {
    mapnik::image_any image;
    explicit mapnik_gray(mapnik::image_any&& i) : image(std::move(i)) {}
};

struct mapnik_map {
    mapnik::Map map;
    mapnik_map(int w, int h) : map(w, h) {}
};

struct mapnik_feature_builder {
    long long id;
    std::optional<mapnik::geometry::geometry<double>> geometry;
    // name -> value; ordered, and a repeated name replaces the earlier one
    std::map<std::string, mapnik::value> attributes;
    // what @name means in an expression
    std::map<std::string, mapnik::value> variables;
};

struct mapnik_params {
    mapnik::parameters params;
};

struct mapnik_datasource {
    std::shared_ptr<mapnik::datasource> ds;
};

struct mapnik_featureset {
    mapnik::featureset_ptr fs;  // may be null: an empty result
};

struct mapnik_feature {
    mapnik::feature_ptr feature;
    std::vector<std::pair<std::string, std::size_t>> attributes;  // name, slot; sorted by name
};

struct mapnik_transform {
    mapnik::proj_transform transform;
    mapnik_transform(mapnik::projection const& a, mapnik::projection const& b) : transform(a, b) {}
};

namespace mc {

extern thread_local std::string g_error;
extern thread_local std::string g_text;  // backing store for returned const char*

// mapnik_layer_t is really a mapnik::layer; it is never defined in C++.
inline mapnik::layer& L(mapnik_layer_t* l) { return *reinterpret_cast<mapnik::layer*>(l); }
inline mapnik_layer_t* H(mapnik::layer* l) { return reinterpret_cast<mapnik_layer_t*>(l); }

// Turn a builder into a real Mapnik feature.
mapnik::feature_ptr build_feature(mapnik_feature_builder_t* b);

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
