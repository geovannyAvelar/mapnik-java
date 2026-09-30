#include "common.hpp"
#include "geometry_out.hpp"

#include <mapnik/value.hpp>

#include <set>
#include <stdexcept>
#include <string>
#include <vector>

#if defined(GRID_RENDERER)
#include <mapnik/grid/grid.hpp>
#include <mapnik/grid/grid_renderer.hpp>
#endif

using namespace mc;

#if defined(GRID_RENDERER)
namespace {

// The UTFGrid spec's id to character mapping: add 32, and step over '"' (34) and '\' (92).
void append_code(std::string& out, unsigned id) {
    unsigned c = id + 32;
    if (c >= 34) c++;
    if (c >= 92) c++;
    // UTF-8
    if (c < 0x80) {
        out += static_cast<char>(c);
    } else if (c < 0x800) {
        out += static_cast<char>(0xC0 | (c >> 6));
        out += static_cast<char>(0x80 | (c & 0x3F));
    } else if (c < 0x10000) {
        out += static_cast<char>(0xE0 | (c >> 12));
        out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
        out += static_cast<char>(0x80 | (c & 0x3F));
    } else {
        out += static_cast<char>(0xF0 | (c >> 18));
        out += static_cast<char>(0x80 | ((c >> 12) & 0x3F));
        out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
        out += static_cast<char>(0x80 | (c & 0x3F));
    }
}

void append_value(std::string& out, mapnik::value const& v) {
    if (v.is_null()) {
        out += "null";
    } else if (v.is<mapnik::value_bool>()) {
        out += v.to_bool() ? "true" : "false";
    } else if (v.is<mapnik::value_integer>()) {
        out += std::to_string(v.to_int());
    } else if (v.is<mapnik::value_double>()) {
        append_number(out, v.to_double());
    } else {
        out += json_escape(v.to_string());
    }
}

}  // namespace
#endif

extern "C" {

const char* mapnik_map_render_grid(mapnik_map_t* m, int layer_index, const char* key, const char* const* fields,
                                   int field_count, int resolution, double scale, int ox, int oy) {
#if defined(GRID_RENDERER)
    const char* result = nullptr;
    guarded([&] {
        if (!valid_index(layer_index, m->map.layer_count())) throw std::out_of_range("layer index out of range");
        if (resolution < 1) throw std::invalid_argument("resolution must be at least 1");

        mapnik::grid g(m->map.width(), m->map.height(), key);
        std::vector<std::string> wanted;
        for (int i = 0; i < field_count; i++) wanted.emplace_back(fields[i]);
        for (auto const& f : wanted) g.add_field(f);
        // The key attribute has to be fetched too, unless it is the feature id.
        if (std::string(key) != "__id__") g.add_field(key);

        std::set<std::string> names = g.get_fields();
        mapnik::grid_renderer<mapnik::grid> ren(m->map, g, scale, static_cast<unsigned>(ox), static_cast<unsigned>(oy));
        ren.apply(m->map.get_layer(static_cast<size_t>(layer_index)), names);

        // Encode. Code 0 is always "no feature" with the empty key; other keys follow in order of appearance.
        auto const& feature_keys = g.get_feature_keys();
        std::vector<std::string> keys{""};
        std::map<std::string, unsigned> code_of{{"", 0}};
        std::string rows;
        bool first_row = true;
        for (std::size_t y = 0; y < g.height(); y += static_cast<std::size_t>(resolution)) {
            std::string row;
            for (std::size_t x = 0; x < g.width(); x += static_cast<std::size_t>(resolution)) {
                auto id = g.data()(x, y);
                std::string k;
                if (id != mapnik::grid::base_mask) {
                    auto it = feature_keys.find(id);
                    if (it != feature_keys.end()) k = it->second;
                }
                auto found = code_of.find(k);
                unsigned code;
                if (found == code_of.end()) {
                    code = static_cast<unsigned>(keys.size());
                    code_of[k] = code;
                    keys.push_back(k);
                } else {
                    code = found->second;
                }
                append_code(row, code);
            }
            if (!first_row) rows += ',';
            first_row = false;
            rows += json_escape(row);
        }

        std::string json = "{\"grid\":[" + rows + "],\"keys\":[";
        for (std::size_t i = 0; i < keys.size(); i++) {
            if (i) json += ',';
            json += json_escape(keys[i]);
        }
        json += "],\"data\":{";
        bool first = true;
        auto const& features = g.get_grid_features();
        for (std::size_t i = 1; i < keys.size(); i++) {
            auto it = features.find(keys[i]);
            if (it == features.end()) continue;
            if (!first) json += ',';
            first = false;
            json += json_escape(keys[i]) + ":{";
            bool first_field = true;
            for (auto const& name : wanted) {
                if (!it->second->has_key(name)) continue;
                if (!first_field) json += ',';
                first_field = false;
                json += json_escape(name) + ":";
                append_value(json, it->second->get(name));
            }
            json += "}";
        }
        json += "}}";
        result = text(json);
    });
    return result;
#else
    (void)m; (void)layer_index; (void)key; (void)fields; (void)field_count; (void)resolution; (void)scale; (void)ox; (void)oy;
    g_error = "this Mapnik was built without the grid renderer";
    return nullptr;
#endif
}

}  // extern "C"
