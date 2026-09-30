#include "common.hpp"

#include <mapnik/config.hpp>
#include <mapnik/datasource_cache.hpp>
#include <mapnik/debug.hpp>
#include <mapnik/font_engine_freetype.hpp>
#include <mapnik/mapped_memory_cache.hpp>
#include <mapnik/marker_cache.hpp>

#include <filesystem>

using namespace mc;

extern "C" {

const char* mapnik_capabilities(void) {
    std::string out;
    auto add = [&](const char* name) {
        if (!out.empty()) out += '\n';
        out += name;
    };
#if defined(HAVE_CAIRO)
    add("cairo");
#endif
#if defined(HAVE_JPEG)
    add("jpeg");
#endif
#if defined(HAVE_PNG)
    add("png");
#endif
#if defined(HAVE_TIFF)
    add("tiff");
#endif
#if defined(HAVE_WEBP)
    add("webp");
#endif
#if defined(MAPNIK_USE_PROJ)
    add("proj");
#endif
#if defined(GRID_RENDERER)
    add("grid");
#endif
#if defined(MAPNIK_THREADSAFE)
    add("threadsafe");
#endif
    return text(out);
}

int mapnik_log_get_severity(void) { return static_cast<int>(mapnik::logger::get_severity()); }

void mapnik_log_set_severity(int s) {
    if (s >= 0 && s <= 3) mapnik::logger::set_severity(static_cast<mapnik::logger::severity_type>(s));
}

int mapnik_log_get_object_severity(const char* object) {
    return static_cast<int>(mapnik::logger::get_object_severity(object));
}

void mapnik_log_set_object_severity(const char* object, int s) {
    if (s >= 0 && s <= 3) mapnik::logger::set_object_severity(object, static_cast<mapnik::logger::severity_type>(s));
}

void mapnik_log_clear_object_severities(void) { mapnik::logger::clear_object_severity(); }

const char* mapnik_log_get_format(void) { return text(mapnik::logger::get_format()); }
void mapnik_log_set_format(const char* format) { mapnik::logger::set_format(format); }

int mapnik_log_use_file(const char* path) {
    return guarded([&] { mapnik::logger::use_file(path); });
}

void mapnik_log_use_console(void) { mapnik::logger::use_console(); }

const char* mapnik_font_face_names(void) {
    std::string out;
    for (auto const& n : mapnik::freetype_engine::face_names()) {
        if (!out.empty()) out += '\n';
        out += n;
    }
    return text(out);
}

const char* mapnik_font_face_file(const char* face) {
    auto const& mapping = mapnik::freetype_engine::get_mapping();
    auto it = mapping.find(face);
    return it == mapping.end() ? nullptr : text(it->second.second);
}

int mapnik_register_font_file(const char* path) {
    bool ok = false;
    int rc = guarded([&] { ok = mapnik::freetype_engine::register_font(path); });
    if (rc == 0 && !ok) {
        g_error = std::string("not a font Mapnik can read: ") + path;
        return -1;
    }
    return rc;
}

int mapnik_register_datasource_file(const char* path) {
    bool ok = false;
    int rc = guarded([&] { ok = mapnik::datasource_cache::instance().register_datasource(path); });
    if (rc == 0 && !ok) {
        // Mapnik also says false for a plugin it already has. Plugin files are named after the plugin.
        std::string stem = std::filesystem::path(path).stem().string();
        if (mapnik::datasource_cache::instance().plugin_registered(stem)) return 0;
        g_error = std::string("not an input plugin Mapnik can load: ") + path;
        return -1;
    }
    return rc;
}

const char* mapnik_datasource_plugin_directories(void) {
    return text(mapnik::datasource_cache::instance().plugin_directories());
}

int mapnik_datasource_plugin_registered(const char* name) {
    return mapnik::datasource_cache::instance().plugin_registered(name) ? 1 : 0;
}

void mapnik_clear_caches(void) {
    mapnik::marker_cache::instance().clear();
    mapnik::mapped_memory_cache::instance().clear();
}

}  // extern "C"
