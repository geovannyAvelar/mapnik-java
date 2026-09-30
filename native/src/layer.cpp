#include "common.hpp"

#include <mapnik/image_compositing.hpp>

using namespace mc;

extern "C" {

mapnik_layer_t* mapnik_layer_create(const char* name, const char* srs) {
    mapnik_layer_t* l = nullptr;
    guarded([&] {
        l = H(srs && *srs ? new mapnik::layer(name, srs) : new mapnik::layer(name));
    });
    return l;
}

void mapnik_layer_free(mapnik_layer_t* l) { delete reinterpret_cast<mapnik::layer*>(l); }

const char* mapnik_layer_get_name(mapnik_layer_t* l) { return text(L(l).name()); }
void mapnik_layer_set_name(mapnik_layer_t* l, const char* n) { L(l).set_name(n); }
const char* mapnik_layer_get_srs(mapnik_layer_t* l) { return text(L(l).srs()); }
void mapnik_layer_set_srs(mapnik_layer_t* l, const char* s) { L(l).set_srs(s); }

void mapnik_layer_add_style(mapnik_layer_t* l, const char* name) { L(l).add_style(name); }
int mapnik_layer_style_count(mapnik_layer_t* l) { return static_cast<int>(L(l).styles().size()); }
const char* mapnik_layer_style_name(mapnik_layer_t* l, int i) {
    if (!valid_index(i, L(l).styles().size())) return nullptr;
    return text(L(l).styles()[static_cast<size_t>(i)]);
}

double mapnik_layer_get_minimum_scale_denominator(mapnik_layer_t* l) { return L(l).minimum_scale_denominator(); }
void mapnik_layer_set_minimum_scale_denominator(mapnik_layer_t* l, double v) { L(l).set_minimum_scale_denominator(v); }
double mapnik_layer_get_maximum_scale_denominator(mapnik_layer_t* l) { return L(l).maximum_scale_denominator(); }
void mapnik_layer_set_maximum_scale_denominator(mapnik_layer_t* l, double v) { L(l).set_maximum_scale_denominator(v); }
int mapnik_layer_visible(mapnik_layer_t* l, double s) { return L(l).visible(s) ? 1 : 0; }

int mapnik_layer_get_active(mapnik_layer_t* l) { return L(l).active() ? 1 : 0; }
void mapnik_layer_set_active(mapnik_layer_t* l, int v) { L(l).set_active(v != 0); }
int mapnik_layer_get_queryable(mapnik_layer_t* l) { return L(l).queryable() ? 1 : 0; }
void mapnik_layer_set_queryable(mapnik_layer_t* l, int v) { L(l).set_queryable(v != 0); }
int mapnik_layer_get_clear_label_cache(mapnik_layer_t* l) { return L(l).clear_label_cache() ? 1 : 0; }
void mapnik_layer_set_clear_label_cache(mapnik_layer_t* l, int v) { L(l).set_clear_label_cache(v != 0); }
int mapnik_layer_get_cache_features(mapnik_layer_t* l) { return L(l).cache_features() ? 1 : 0; }
void mapnik_layer_set_cache_features(mapnik_layer_t* l, int v) { L(l).set_cache_features(v != 0); }

const char* mapnik_layer_get_group_by(mapnik_layer_t* l) { return text(L(l).group_by()); }
void mapnik_layer_set_group_by(mapnik_layer_t* l, const char* c) { L(l).set_group_by(c); }

const char* mapnik_layer_get_comp_op(mapnik_layer_t* l) {
    auto op = L(l).comp_op();
    if (!op) return nullptr;
    auto name = mapnik::comp_op_to_string(*op);
    return name ? text(*name) : nullptr;
}

int mapnik_layer_set_comp_op(mapnik_layer_t* l, const char* name) {
    auto op = mapnik::comp_op_from_string(name);
    if (!op) {
        g_error = std::string("unknown blend mode: ") + name;
        return -1;
    }
    L(l).set_comp_op(*op);
    return 0;
}

void mapnik_layer_add_child(mapnik_layer_t* parent, mapnik_layer_t* child) { L(parent).add_layer(L(child)); }
int mapnik_layer_child_count(mapnik_layer_t* l) { return static_cast<int>(L(l).layers().size()); }

mapnik_layer_t* mapnik_layer_child_copy(mapnik_layer_t* l, int i) {
    if (!valid_index(i, L(l).layers().size())) return nullptr;
    mapnik_layer_t* out = nullptr;
    guarded([&] { out = H(new mapnik::layer(L(l).layers()[static_cast<size_t>(i)])); });
    return out;
}

double mapnik_layer_get_opacity(mapnik_layer_t* l) { return L(l).get_opacity(); }
void mapnik_layer_set_opacity(mapnik_layer_t* l, double v) { L(l).set_opacity(v); }

int mapnik_layer_get_buffer_size(mapnik_layer_t* l, int* out) {
    auto const& b = L(l).buffer_size();
    if (!b) return 0;
    *out = *b;
    return 1;
}
void mapnik_layer_set_buffer_size(mapnik_layer_t* l, int s) { L(l).set_buffer_size(s); }
void mapnik_layer_reset_buffer_size(mapnik_layer_t* l) { L(l).reset_buffer_size(); }

int mapnik_layer_get_maximum_extent(mapnik_layer_t* l, double* out) {
    auto const& e = L(l).maximum_extent();
    if (!e) return 0;
    write_box(*e, out);
    return 1;
}
void mapnik_layer_set_maximum_extent(mapnik_layer_t* l, double a, double b, double c, double d) {
    L(l).set_maximum_extent(mapnik::box2d<double>(a, b, c, d));
}
void mapnik_layer_reset_maximum_extent(mapnik_layer_t* l) { L(l).reset_maximum_extent(); }

void mapnik_layer_set_datasource(mapnik_layer_t* l, mapnik_datasource_t* ds) { L(l).set_datasource(ds->ds); }

mapnik_datasource_t* mapnik_layer_get_datasource(mapnik_layer_t* l) {
    auto ds = L(l).datasource();
    if (!ds) return nullptr;
    return new mapnik_datasource{ds};
}

int mapnik_layer_envelope(mapnik_layer_t* l, double* out) {
    return guarded([&] {
        if (!L(l).datasource()) throw std::runtime_error("layer has no datasource");
        write_box(L(l).envelope(), out);
    });
}

}  // extern "C"
