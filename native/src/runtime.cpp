#include "common.hpp"

#ifdef _WIN32
#define NOMINMAX
#include <windows.h>
#endif

#include <cstdlib>

#include <mapnik/color.hpp>
#include <mapnik/datasource_cache.hpp>
#include <mapnik/font_engine_freetype.hpp>
#include <mapnik/scale_denominator.hpp>
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

double mapnik_scale_denominator(double scale, int geographic) {
    return mapnik::scale_denominator(scale, geographic != 0);
}

int mapnik_color_parse(const char* t, unsigned char* out) {
    return guarded([&] {
        mapnik::color c(t);
        out[0] = c.red();
        out[1] = c.green();
        out[2] = c.blue();
        out[3] = c.alpha();
    });
}

static mapnik::color make_color(int r, int g, int b, int a) {
    auto clamp = [](int v) { return static_cast<std::uint8_t>(v < 0 ? 0 : v > 255 ? 255 : v); };
    return mapnik::color(clamp(r), clamp(g), clamp(b), clamp(a));
}

const char* mapnik_color_to_string(int r, int g, int b, int a) { return text(make_color(r, g, b, a).to_string()); }
const char* mapnik_color_to_hex(int r, int g, int b, int a) { return text(make_color(r, g, b, a).to_hex_string()); }

int mapnik_set_environment(const char* proj_data_dir) {
    // PROJ_DATA is read by PROJ 9.1 and later, PROJ_LIB by older versions.
#ifdef _WIN32
    if (_putenv_s("PROJ_DATA", proj_data_dir) != 0 || _putenv_s("PROJ_LIB", proj_data_dir) != 0) {
#else
    if (setenv("PROJ_DATA", proj_data_dir, 1) != 0 || setenv("PROJ_LIB", proj_data_dir, 1) != 0) {
#endif
        g_error = "could not set the PROJ data directory";
        return -1;
    }
    return 0;
}

int mapnik_set_library_directory(const char* dir) {
#ifdef _WIN32
    // Input plugins are DLLs in another directory than the libraries they need. Windows looks for a
    // DLL's dependencies in the directories of the process, the system and this one, so add it.
    int n = MultiByteToWideChar(CP_UTF8, 0, dir, -1, nullptr, 0);
    if (n <= 0) {
        g_error = "the library directory is not valid UTF-8";
        return -1;
    }
    std::wstring wide(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, dir, -1, &wide[0], n);
    if (!SetDllDirectoryW(wide.c_str())) {
        g_error = "could not add the library directory to the DLL search path";
        return -1;
    }
#else
    (void)dir;   // other systems find the libraries through the plugins' own run path
#endif
    return 0;
}

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
