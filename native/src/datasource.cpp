#include "common.hpp"
#include "params_access.hpp"

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

const char* mapnik_datasource_layer_name(mapnik_datasource_t* ds) {
    auto desc = ds->ds->get_descriptor();
    return text(desc.get_name());
}

const char* mapnik_datasource_encoding(mapnik_datasource_t* ds) {
    auto desc = ds->ds->get_descriptor();
    return text(desc.get_encoding());
}

int mapnik_datasource_param_count(mapnik_datasource_t* ds) { return static_cast<int>(ds->ds->params().size()); }

const char* mapnik_datasource_param_name(mapnik_datasource_t* ds, int i) {
    auto p = param_at(ds->ds->params(), i);
    return p.first ? text(*p.first) : nullptr;
}

int mapnik_datasource_param_type(mapnik_datasource_t* ds, int i) {
    auto p = param_at(ds->ds->params(), i);
    return p.second ? param_type(*p.second) : -1;
}

int mapnik_datasource_param_bool(mapnik_datasource_t* ds, int i) {
    auto p = param_at(ds->ds->params(), i);
    return p.second ? param_bool(*p.second) : 0;
}

long long mapnik_datasource_param_int(mapnik_datasource_t* ds, int i) {
    auto p = param_at(ds->ds->params(), i);
    return p.second ? param_int(*p.second) : 0;
}

double mapnik_datasource_param_double(mapnik_datasource_t* ds, int i) {
    auto p = param_at(ds->ds->params(), i);
    return p.second ? param_double(*p.second) : 0;
}

const char* mapnik_datasource_param_string(mapnik_datasource_t* ds, int i) {
    auto p = param_at(ds->ds->params(), i);
    return p.second ? text(param_string(*p.second)) : nullptr;
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
