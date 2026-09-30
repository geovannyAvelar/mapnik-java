#include "common.hpp"
#include "params_access.hpp"

#include <mapnik/color.hpp>
#include <mapnik/feature_type_style.hpp>
#include <mapnik/font_set.hpp>
#include <mapnik/image_compositing.hpp>
#include <mapnik/view_transform.hpp>
#include <mapnik/load_map.hpp>
#include <mapnik/save_map.hpp>

#include <iterator>

using namespace mc;

extern "C" {

mapnik_map_t* mapnik_map_create(int w, int h) {
    mapnik_map_t* m = nullptr;
    guarded([&] { m = new mapnik_map(w, h); });
    return m;
}

void mapnik_map_free(mapnik_map_t* m) { delete m; }

int mapnik_map_load(mapnik_map_t* m, const char* path, int strict) {
    return guarded([&] { mapnik::load_map(m->map, path, strict != 0); });
}

int mapnik_map_load_string(mapnik_map_t* m, const char* xml, const char* base, int strict) {
    return guarded([&] { mapnik::load_map_string(m->map, xml, strict != 0, base ? base : ""); });
}

const char* mapnik_map_save_to_string(mapnik_map_t* m, int explicit_defaults) {
    const char* r = nullptr;
    guarded([&] { r = text(mapnik::save_map_to_string(m->map, explicit_defaults != 0)); });
    return r;
}

int mapnik_map_save(mapnik_map_t* m, const char* path, int explicit_defaults) {
    return guarded([&] { mapnik::save_map(m->map, path, explicit_defaults != 0); });
}

int mapnik_map_width(mapnik_map_t* m) { return static_cast<int>(m->map.width()); }
int mapnik_map_height(mapnik_map_t* m) { return static_cast<int>(m->map.height()); }
void mapnik_map_resize(mapnik_map_t* m, int w, int h) { m->map.resize(w, h); }

const char* mapnik_map_get_srs(mapnik_map_t* m) { return text(m->map.srs()); }
int mapnik_map_set_srs(mapnik_map_t* m, const char* srs) {
    return guarded([&] { m->map.set_srs(srs); });
}

const char* mapnik_map_get_background(mapnik_map_t* m) {
    auto const& c = m->map.background();
    return c ? text(c->to_string()) : nullptr;
}

int mapnik_map_set_background(mapnik_map_t* m, const char* color) {
    return guarded([&] { m->map.set_background(mapnik::color(color)); });
}

const char* mapnik_map_get_background_image(mapnik_map_t* m) {
    auto const& p = m->map.background_image();
    return p ? text(*p) : nullptr;
}

void mapnik_map_set_background_image(mapnik_map_t* m, const char* path) {
    m->map.set_background_image(path);
}

double mapnik_map_get_background_image_opacity(mapnik_map_t* m) {
    return m->map.background_image_opacity();
}

void mapnik_map_set_background_image_opacity(mapnik_map_t* m, double v) {
    m->map.set_background_image_opacity(static_cast<float>(v));
}

const char* mapnik_map_get_background_image_comp_op(mapnik_map_t* m) {
    auto name = mapnik::comp_op_to_string(m->map.background_image_comp_op());
    return name ? text(*name) : nullptr;
}

int mapnik_map_set_background_image_comp_op(mapnik_map_t* m, const char* name) {
    auto op = mapnik::comp_op_from_string(name);
    if (!op) {
        g_error = std::string("unknown blend mode: ") + name;
        return -1;
    }
    m->map.set_background_image_comp_op(*op);
    return 0;
}

const char* mapnik_map_get_font_directory(mapnik_map_t* m) {
    auto const& d = m->map.font_directory();
    return d ? text(*d) : nullptr;
}

void mapnik_map_set_font_directory(mapnik_map_t* m, const char* dir) { m->map.set_font_directory(dir); }

int mapnik_map_param_count(mapnik_map_t* m) { return static_cast<int>(m->map.get_extra_parameters().size()); }

const char* mapnik_map_param_name(mapnik_map_t* m, int i) {
    auto p = param_at(m->map.get_extra_parameters(), i);
    return p.first ? text(*p.first) : nullptr;
}

int mapnik_map_param_type(mapnik_map_t* m, int i) {
    auto p = param_at(m->map.get_extra_parameters(), i);
    return p.second ? param_type(*p.second) : -1;
}

int mapnik_map_param_bool(mapnik_map_t* m, int i) {
    auto p = param_at(m->map.get_extra_parameters(), i);
    return p.second ? param_bool(*p.second) : 0;
}

long long mapnik_map_param_int(mapnik_map_t* m, int i) {
    auto p = param_at(m->map.get_extra_parameters(), i);
    return p.second ? param_int(*p.second) : 0;
}

double mapnik_map_param_double(mapnik_map_t* m, int i) {
    auto p = param_at(m->map.get_extra_parameters(), i);
    return p.second ? param_double(*p.second) : 0;
}

const char* mapnik_map_param_string(mapnik_map_t* m, int i) {
    auto p = param_at(m->map.get_extra_parameters(), i);
    return p.second ? text(param_string(*p.second)) : nullptr;
}

void mapnik_map_set_param_string(mapnik_map_t* m, const char* k, const char* v) {
    m->map.get_extra_parameters()[k] = std::string(v);
}
void mapnik_map_set_param_int(mapnik_map_t* m, const char* k, long long v) {
    m->map.get_extra_parameters()[k] = static_cast<mapnik::value_integer>(v);
}
void mapnik_map_set_param_double(mapnik_map_t* m, const char* k, double v) { m->map.get_extra_parameters()[k] = v; }
void mapnik_map_set_param_bool(mapnik_map_t* m, const char* k, int v) {
    m->map.get_extra_parameters()[k] = static_cast<mapnik::value_bool>(v != 0);
}
void mapnik_map_remove_param(mapnik_map_t* m, const char* k) { m->map.get_extra_parameters().erase(k); }

void mapnik_map_world_to_pixel(mapnik_map_t* m, double* x, double* y) { m->map.transform().forward(x, y); }
void mapnik_map_pixel_to_world(mapnik_map_t* m, double* x, double* y) { m->map.transform().backward(x, y); }

int mapnik_map_get_buffer_size(mapnik_map_t* m) { return m->map.buffer_size(); }
void mapnik_map_set_buffer_size(mapnik_map_t* m, int size) { m->map.set_buffer_size(size); }

int mapnik_map_get_maximum_extent(mapnik_map_t* m, double* out) {
    auto const& e = m->map.maximum_extent();
    if (!e) return 0;
    write_box(*e, out);
    return 1;
}

void mapnik_map_set_maximum_extent(mapnik_map_t* m, double a, double b, double c, double d) {
    m->map.set_maximum_extent(mapnik::box2d<double>(a, b, c, d));
}

void mapnik_map_reset_maximum_extent(mapnik_map_t* m) { m->map.reset_maximum_extent(); }

const char* mapnik_map_get_base_path(mapnik_map_t* m) { return text(m->map.base_path()); }
void mapnik_map_set_base_path(mapnik_map_t* m, const char* path) { m->map.set_base_path(path); }

int mapnik_map_get_aspect_fix_mode(mapnik_map_t* m) { return static_cast<int>(m->map.get_aspect_fix_mode()); }
void mapnik_map_set_aspect_fix_mode(mapnik_map_t* m, int mode) {
    if (mode >= 0 && mode < mapnik::Map::aspect_fix_mode_MAX) {
        m->map.set_aspect_fix_mode(static_cast<mapnik::Map::aspect_fix_mode>(mode));
    }
}

void mapnik_map_zoom(mapnik_map_t* m, double factor) { m->map.zoom(factor); }

void mapnik_map_zoom_to_box(mapnik_map_t* m, double a, double b, double c, double d) {
    m->map.zoom_to_box(mapnik::box2d<double>(a, b, c, d));
}

void mapnik_map_zoom_all(mapnik_map_t* m) { m->map.zoom_all(); }
void mapnik_map_pan(mapnik_map_t* m, int x, int y) { m->map.pan(x, y); }
void mapnik_map_pan_and_zoom(mapnik_map_t* m, int x, int y, double z) { m->map.pan_and_zoom(x, y, z); }
void mapnik_map_get_current_extent(mapnik_map_t* m, double* out) { write_box(m->map.get_current_extent(), out); }
void mapnik_map_get_buffered_extent(mapnik_map_t* m, double* out) { write_box(m->map.get_buffered_extent(), out); }
double mapnik_map_scale(mapnik_map_t* m) { return m->map.scale(); }
double mapnik_map_scale_denominator(mapnik_map_t* m) { return m->map.scale_denominator(); }

void mapnik_map_remove_all(mapnik_map_t* m) { m->map.remove_all(); }

int mapnik_map_layer_count(mapnik_map_t* m) { return static_cast<int>(m->map.layer_count()); }

mapnik_layer_t* mapnik_map_get_layer(mapnik_map_t* m, int index) {
    if (!valid_index(index, m->map.layer_count())) return nullptr;
    return H(&m->map.get_layer(static_cast<size_t>(index)));
}

int mapnik_map_add_layer(mapnik_map_t* m, mapnik_layer_t* layer) {
    return guarded([&] { m->map.add_layer(L(layer)); });
}

int mapnik_map_remove_layer(mapnik_map_t* m, int index) {
    if (!valid_index(index, m->map.layer_count())) {
        g_error = "layer index out of range";
        return -1;
    }
    m->map.remove_layer(static_cast<size_t>(index));
    return 0;
}

int mapnik_map_style_count(mapnik_map_t* m) { return static_cast<int>(m->map.styles().size()); }

const char* mapnik_map_style_name(mapnik_map_t* m, int index) {
    if (!valid_index(index, m->map.styles().size())) return nullptr;
    auto it = m->map.styles().begin();
    std::advance(it, index);
    return text(it->first);
}

void mapnik_map_remove_style(mapnik_map_t* m, const char* name) { m->map.remove_style(name); }

int mapnik_map_register_fonts(mapnik_map_t* m, const char* dir, int recurse) {
    bool ok = false;
    int rc = guarded([&] { ok = m->map.register_fonts(dir, recurse != 0); });
    if (rc == 0 && !ok) {
        g_error = std::string("no fonts registered from ") + dir;
        return -1;
    }
    return rc;
}

int mapnik_map_load_fonts(mapnik_map_t* m) {
    return guarded([&] { m->map.load_fonts(); });
}

}  // extern "C"
