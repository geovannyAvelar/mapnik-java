#include "common.hpp"

#include <mapnik/color.hpp>
#include <mapnik/image.hpp>
#include <mapnik/image_reader.hpp>
#include <mapnik/image_util.hpp>

#include <cstdlib>
#include <cstring>
#include <memory>

struct mapnik_image {
    mapnik::image_rgba8 image;  // always straight alpha outside of a render call
    mapnik_image(int w, int h) : image(w, h) {}
    explicit mapnik_image(mapnik::image_rgba8&& i) : image(std::move(i)) {}
};

using namespace mc;

namespace {

bool in_range(mapnik_image_t* i, int x, int y) {
    return x >= 0 && y >= 0 && static_cast<size_t>(x) < i->image.width() && static_cast<size_t>(y) < i->image.height();
}

// A freshly read or created image is straight alpha; make sure Mapnik agrees.
mapnik_image_t* from_reader(std::unique_ptr<mapnik::image_reader> reader) {
    mapnik::image_rgba8 im(static_cast<int>(reader->width()), static_cast<int>(reader->height()));
    reader->read(0, 0, im);
    if (im.get_premultiplied()) mapnik::demultiply_alpha(im);
    im.set_premultiplied(false);
    return new mapnik_image(std::move(im));
}

}  // namespace

extern "C" {

mapnik_image_t* mapnik_image_create(int w, int h) {
    mapnik_image_t* i = nullptr;
    guarded([&] {
        if (w <= 0 || h <= 0) throw std::invalid_argument("image size must be positive");
        i = new mapnik_image(w, h);
        i->image.set(0);
    });
    return i;
}

mapnik_image_t* mapnik_image_load_file(const char* path) {
    mapnik_image_t* i = nullptr;
    guarded([&] { i = from_reader(std::unique_ptr<mapnik::image_reader>(mapnik::get_image_reader(path))); });
    return i;
}

mapnik_image_t* mapnik_image_load_bytes(const unsigned char* data, int length) {
    mapnik_image_t* i = nullptr;
    guarded([&] {
        if (length <= 0) throw std::invalid_argument("no image data");
        i = from_reader(std::unique_ptr<mapnik::image_reader>(
            mapnik::get_image_reader(reinterpret_cast<const char*>(data), static_cast<size_t>(length))));
    });
    return i;
}

void mapnik_image_free(mapnik_image_t* img) { delete img; }

int mapnik_image_width(mapnik_image_t* img) { return static_cast<int>(img->image.width()); }
int mapnik_image_height(mapnik_image_t* img) { return static_cast<int>(img->image.height()); }

unsigned int mapnik_image_get_pixel(mapnik_image_t* img, int x, int y) {
    if (!in_range(img, x, y)) return 0;
    return img->image(static_cast<size_t>(x), static_cast<size_t>(y));
}

int mapnik_image_set_pixel(mapnik_image_t* img, int x, int y, unsigned int rgba) {
    if (!in_range(img, x, y)) {
        g_error = "pixel out of range";
        return -1;
    }
    img->image(static_cast<size_t>(x), static_cast<size_t>(y)) = rgba;
    return 0;
}

int mapnik_image_fill(mapnik_image_t* img, const char* color) {
    return guarded([&] { img->image.set(mapnik::color(color).rgba()); });
}

int mapnik_image_is_solid(mapnik_image_t* img) { return mapnik::is_solid(img->image) ? 1 : 0; }

void mapnik_image_copy_rgba(mapnik_image_t* img, unsigned char* out) {
    std::memcpy(out, img->image.bytes(), img->image.width() * img->image.height() * 4);
}

int mapnik_image_save(mapnik_image_t* img, const char* path, const char* format) {
    return guarded([&] { mapnik::save_to_file(img->image, path, format); });
}

int mapnik_image_save_to_buffer(mapnik_image_t* img, const char* format, unsigned char** out, int* len) {
    return guarded([&] {
        std::string s = mapnik::save_to_string(img->image, format);
        auto* buf = static_cast<unsigned char*>(std::malloc(s.size()));
        if (!buf) throw std::bad_alloc();
        std::memcpy(buf, s.data(), s.size());
        *out = buf;
        *len = static_cast<int>(s.size());
    });
}

}  // extern "C"

// Used by render.cpp.
mapnik::image_rgba8& mc_image_of(mapnik_image_t* img) { return img->image; }
mapnik_image_t* mc_image_wrap(mapnik::image_rgba8&& im) { return new mapnik_image(std::move(im)); }
