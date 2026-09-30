#include "common.hpp"
#include "geometry_out.hpp"

#include <mapnik/datasource.hpp>
#include <mapnik/geometry/box2d.hpp>
#include <mapnik/geometry/envelope.hpp>
#include <mapnik/geometry/geometry_type.hpp>
#include <mapnik/query.hpp>
#include <mapnik/value.hpp>

#include <stdexcept>

using namespace mc;

namespace {

mapnik::value const& attr(mapnik_feature_t* f, int index, bool& ok) {
    static const mapnik::value null_value;
    if (!valid_index(index, f->attributes.size())) {
        ok = false;
        return null_value;
    }
    ok = true;
    return f->feature->get(f->attributes[static_cast<size_t>(index)].second);
}

}  // namespace

extern "C" {

mapnik_featureset_t* mapnik_datasource_features(mapnik_datasource_t* ds, double minx, double miny, double maxx,
                                                double maxy, double rx, double ry, double scale_denominator,
                                                const char* const* names, int count) {
    mapnik_featureset_t* out = nullptr;
    guarded([&] {
        mapnik::query q(mapnik::box2d<double>(minx, miny, maxx, maxy), mapnik::query::resolution_type(rx, ry),
                        scale_denominator);
        for (int i = 0; names && i < count; i++) q.add_property_name(names[i]);
        out = new mapnik_featureset{ds->ds->features(q)};
    });
    return out;
}

mapnik_featureset_t* mapnik_datasource_features_at_point(mapnik_datasource_t* ds, double x, double y, double tol) {
    mapnik_featureset_t* out = nullptr;
    guarded([&] { out = new mapnik_featureset{ds->ds->features_at_point(mapnik::coord2d(x, y), tol)}; });
    return out;
}

mapnik_featureset_t* mapnik_map_query_point(mapnik_map_t* m, int layer, double x, double y) {
    mapnik_featureset_t* out = nullptr;
    guarded([&] {
        if (!valid_index(layer, m->map.layer_count())) throw std::out_of_range("layer index out of range");
        out = new mapnik_featureset{m->map.query_point(static_cast<unsigned>(layer), x, y)};
    });
    return out;
}

mapnik_featureset_t* mapnik_map_query_map_point(mapnik_map_t* m, int layer, double px, double py) {
    mapnik_featureset_t* out = nullptr;
    guarded([&] {
        if (!valid_index(layer, m->map.layer_count())) throw std::out_of_range("layer index out of range");
        out = new mapnik_featureset{m->map.query_map_point(static_cast<unsigned>(layer), px, py)};
    });
    return out;
}

void mapnik_featureset_free(mapnik_featureset_t* fs) { delete fs; }

int mapnik_featureset_next(mapnik_featureset_t* fs, mapnik_feature_t** out) {
    if (!fs->fs) return 0;
    int result = 0;
    int rc = guarded([&] {
        mapnik::feature_ptr f = fs->fs->next();
        if (!f) return;
        auto* wrapper = new mapnik_feature{f, {}};
        auto ctx = f->context();
        for (auto const& kv : *ctx) wrapper->attributes.emplace_back(kv.first, kv.second);
        *out = wrapper;
        result = 1;
    });
    return rc == 0 ? result : -1;
}

void mapnik_feature_free(mapnik_feature_t* f) { delete f; }

long long mapnik_feature_id(mapnik_feature_t* f) { return static_cast<long long>(f->feature->id()); }

int mapnik_feature_attribute_count(mapnik_feature_t* f) { return static_cast<int>(f->attributes.size()); }

const char* mapnik_feature_attribute_name(mapnik_feature_t* f, int i) {
    if (!valid_index(i, f->attributes.size())) return nullptr;
    return text(f->attributes[static_cast<size_t>(i)].first);
}

int mapnik_feature_attribute_type(mapnik_feature_t* f, int i) {
    bool ok;
    mapnik::value const& v = attr(f, i, ok);
    if (!ok) return -1;
    if (v.is_null()) return 0;
    if (v.is<mapnik::value_bool>()) return 1;
    if (v.is<mapnik::value_integer>()) return 2;
    if (v.is<mapnik::value_double>()) return 3;
    return 4;
}

int mapnik_feature_attribute_bool(mapnik_feature_t* f, int i) {
    bool ok;
    return attr(f, i, ok).to_bool() ? 1 : 0;
}

long long mapnik_feature_attribute_int(mapnik_feature_t* f, int i) {
    bool ok;
    return static_cast<long long>(attr(f, i, ok).to_int());
}

double mapnik_feature_attribute_double(mapnik_feature_t* f, int i) {
    bool ok;
    return attr(f, i, ok).to_double();
}

const char* mapnik_feature_attribute_string(mapnik_feature_t* f, int i) {
    bool ok;
    mapnik::value const& v = attr(f, i, ok);
    return ok ? text(v.to_string()) : nullptr;
}

int mapnik_feature_geometry_type(mapnik_feature_t* f) {
    return static_cast<int>(mapnik::geometry::geometry_type(f->feature->get_geometry()));
}

int mapnik_feature_envelope(mapnik_feature_t* f, double* out) {
    return guarded([&] { write_box(mapnik::geometry::envelope(f->feature->get_geometry()), out); });
}

const char* mapnik_feature_geometry_wkt(mapnik_feature_t* f) {
    const char* r = nullptr;
    guarded([&] { r = text(geometry_to_wkt(f->feature->get_geometry())); });
    return r;
}

const char* mapnik_feature_geometry_geojson(mapnik_feature_t* f) {
    const char* r = nullptr;
    guarded([&] { r = text(geometry_to_geojson(f->feature->get_geometry())); });
    return r;
}

const char* mapnik_feature_to_geojson(mapnik_feature_t* f) {
    const char* r = nullptr;
    guarded([&] {
        std::string s = "{\"type\":\"Feature\",\"id\":" + std::to_string(f->feature->id()) + ",\"geometry\":" +
                        geometry_to_geojson(f->feature->get_geometry()) + ",\"properties\":{";
        bool first = true;
        for (size_t i = 0; i < f->attributes.size(); i++) {
            if (!first) s += ',';
            first = false;
            s += json_escape(f->attributes[i].first) + ":";
            mapnik::value const& v = f->feature->get(f->attributes[i].second);
            if (v.is_null()) {
                s += "null";
            } else if (v.is<mapnik::value_bool>()) {
                s += v.to_bool() ? "true" : "false";
            } else if (v.is<mapnik::value_integer>()) {
                s += std::to_string(v.to_int());
            } else if (v.is<mapnik::value_double>()) {
                append_number(s, v.to_double());
            } else {
                s += json_escape(v.to_string());
            }
        }
        s += "}}";
        r = text(s);
    });
    return r;
}

}  // extern "C"
