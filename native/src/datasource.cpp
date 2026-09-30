#include "common.hpp"

#include <mapnik/datasource_cache.hpp>
#include <mapnik/feature_layer_desc.hpp>

using namespace mc;

extern "C" {

mapnik_params_t* mapnik_params_create(void) { return new mapnik_params(); }
void mapnik_params_free(mapnik_params_t* p) { delete p; }

void mapnik_params_set_string(mapnik_params_t* p, const char* k, const char* v) {
    p->params[k] = std::string(v);
}
void mapnik_params_set_int(mapnik_params_t* p, const char* k, long long v) {
    p->params[k] = static_cast<mapnik::value_integer>(v);
}
void mapnik_params_set_double(mapnik_params_t* p, const char* k, double v) { p->params[k] = v; }
void mapnik_params_set_bool(mapnik_params_t* p, const char* k, int v) {
    p->params[k] = static_cast<mapnik::value_bool>(v != 0);
}

mapnik_datasource_t* mapnik_datasource_create(mapnik_params_t* p) {
    mapnik_datasource_t* out = nullptr;
    guarded([&] {
        auto ds = mapnik::datasource_cache::instance().create(p->params);
        if (!ds) throw std::runtime_error("could not create datasource");
        out = new mapnik_datasource{ds};
    });
    return out;
}

void mapnik_datasource_free(mapnik_datasource_t* ds) { delete ds; }

int mapnik_datasource_type(mapnik_datasource_t* ds) { return static_cast<int>(ds->ds->type()); }

int mapnik_datasource_geometry_type(mapnik_datasource_t* ds) {
    auto t = ds->ds->get_geometry_type();
    return t ? static_cast<int>(*t) : 0;
}

int mapnik_datasource_envelope(mapnik_datasource_t* ds, double* out) {
    return guarded([&] { write_box(ds->ds->envelope(), out); });
}

// get_descriptor() returns by value, so keep the copy alive while reading from it.
int mapnik_datasource_field_count(mapnik_datasource_t* ds) {
    auto desc = ds->ds->get_descriptor();
    return static_cast<int>(desc.get_descriptors().size());
}

const char* mapnik_datasource_field_name(mapnik_datasource_t* ds, int i) {
    auto desc = ds->ds->get_descriptor();
    auto const& d = desc.get_descriptors();
    if (!valid_index(i, d.size())) return nullptr;
    return text(d[static_cast<size_t>(i)].get_name());
}

int mapnik_datasource_field_type(mapnik_datasource_t* ds, int i) {
    auto desc = ds->ds->get_descriptor();
    auto const& d = desc.get_descriptors();
    if (!valid_index(i, d.size())) return -1;
    return static_cast<int>(d[static_cast<size_t>(i)].get_type());
}

}  // extern "C"
