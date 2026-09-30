#include "common.hpp"

#include <mapnik/datasource_cache.hpp>
#include <mapnik/font_engine_freetype.hpp>
#include <mapnik/version.hpp>

namespace mc {
thread_local std::string g_error;
thread_local std::string g_text;
}  // namespace mc

using namespace mc;

extern "C" {

const char* mapnik_last_error(void) { return g_error.c_str(); }
const char* mapnik_version(void) { return MAPNIK_VERSION_STRING; }
int mapnik_version_number(void) { return MAPNIK_VERSION; }

int mapnik_register_datasources(const char* dir) {
    return guarded([&] { mapnik::datasource_cache::instance().register_datasources(dir); });
}

int mapnik_register_fonts(const char* dir) {
    return guarded([&] { mapnik::freetype_engine::register_fonts(dir, true); });
}

const char* mapnik_datasource_plugin_names(void) {
    std::string out;
    for (auto const& n : mapnik::datasource_cache::instance().plugin_names()) {
        if (!out.empty()) out += '\n';
        out += n;
    }
    return text(out);
}

}  // extern "C"
