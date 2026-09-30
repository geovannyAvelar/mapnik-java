#include "common.hpp"

#include <mapnik/color.hpp>
#include <mapnik/image.hpp>
#include <mapnik/image_compositing.hpp>
#include <mapnik/image_filter.hpp>
#include <mapnik/image_reader.hpp>
#include <mapnik/image_scaling.hpp>
#include <mapnik/image_util.hpp>

#include <algorithm>
#include <cmath>
#include <memory>
#include <stdexcept>

mapnik::image_rgba8& mc_image_of(mapnik_image_t* img);
mapnik_image_t* mc_image_wrap(mapnik::image_rgba8&& im);

using namespace mc;

namespace {

// Mapnik's operations work on premultiplied pixels; Image is straight alpha outside of them.
struct Premultiplied {
    mapnik::image_rgba8& im;
    explicit Premultiplied(mapnik::image_rgba8& i) : im(i) { mapnik::premultiply_alpha(im); }
    ~Premultiplied() { mapnik::demultiply_alpha(im); }
};

mapnik::image_rgba8 straight_copy(mapnik::image_rgba8 const& src) {
    mapnik::image_rgba8 c(src);
    c.set_premultiplied(false);
    return c;
}

}  // namespace

extern "C" {

int mapnik_image_filter(mapnik_image_t* img, const char* filters, double scale_factor) {
    return guarded([&] {
        mapnik::image_rgba8& im = mc_image_of(img);
        Premultiplied p(im);
        mapnik::filter::filter_image(im, filters, scale_factor);
    });
}

int mapnik_image_composite(mapnik_image_t* dst, mapnik_image_t* src, const char* mode, double opacity, int dx, int dy) {
    return guarded([&] {
        auto op = mapnik::comp_op_from_string(mode);
        if (!op) throw std::invalid_argument(std::string("unknown blend mode: ") + mode);
        mapnik::image_rgba8 s = straight_copy(mc_image_of(src));
        mapnik::premultiply_alpha(s);
        mapnik::image_rgba8& d = mc_image_of(dst);
        Premultiplied p(d);
        mapnik::composite(d, s, *op, static_cast<float>(opacity), dx, dy);
    });
}

mapnik_image_t* mapnik_image_scale(mapnik_image_t* img, int w, int h, const char* method) {
    mapnik_image_t* out = nullptr;
    guarded([&] {
        if (w <= 0 || h <= 0) throw std::invalid_argument("image size must be positive");
        auto m = mapnik::scaling_method_from_string(method);
        if (!m) throw std::invalid_argument(std::string("unknown scaling method: ") + method);
        mapnik::image_rgba8 s = straight_copy(mc_image_of(img));
        mapnik::premultiply_alpha(s);
        mapnik::image_rgba8 t(w, h);
        t.set(0);
        double rx = static_cast<double>(w) / static_cast<double>(s.width());
        double ry = static_cast<double>(h) / static_cast<double>(s.height());
        mapnik::scale_image_agg(t, s, *m, rx, ry, 0.0, 0.0, 1.0);
        // The scaler leaves premultiplied pixels without saying so; demultiply_alpha trusts the flag.
        t.set_premultiplied(true);
        mapnik::demultiply_alpha(t);
        t.set_premultiplied(false);
        out = mc_image_wrap(std::move(t));
    });
    return out;
}

mapnik_image_t* mapnik_image_crop(mapnik_image_t* img, int x, int y, int w, int h) {
    mapnik_image_t* out = nullptr;
    guarded([&] {
        mapnik::image_rgba8 const& s = mc_image_of(img);
        if (w <= 0 || h <= 0) throw std::invalid_argument("crop size must be positive");
        if (x < 0 || y < 0 || static_cast<size_t>(x) + static_cast<size_t>(w) > s.width() ||
            static_cast<size_t>(y) + static_cast<size_t>(h) > s.height()) {
            throw std::out_of_range("crop area is outside the image");
        }
        mapnik::image_rgba8 t(w, h);
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                t(static_cast<size_t>(col), static_cast<size_t>(row)) =
                    s(static_cast<size_t>(x + col), static_cast<size_t>(y + row));
            }
        }
        t.set_premultiplied(false);
        out = mc_image_wrap(std::move(t));
    });
    return out;
}

mapnik_image_t* mapnik_image_copy(mapnik_image_t* img) {
    mapnik_image_t* out = nullptr;
    guarded([&] { out = mc_image_wrap(straight_copy(mc_image_of(img))); });
    return out;
}

int mapnik_image_apply_opacity(mapnik_image_t* img, double opacity) {
    return guarded([&] {
        if (!(opacity >= 0.0 && opacity <= 1.0)) throw std::invalid_argument("opacity must be from 0 to 1");
        mapnik::image_rgba8& im = mc_image_of(img);
        for (size_t y = 0; y < im.height(); y++) {
            for (size_t x = 0; x < im.width(); x++) {
                std::uint32_t px = im(x, y);
                double a = static_cast<double>(px >> 24) * opacity;
                std::uint32_t na = static_cast<std::uint32_t>(std::lround(a));
                im(x, y) = (px & 0x00FFFFFFu) | (na << 24);
            }
        }
    });
}

int mapnik_image_color_to_alpha(mapnik_image_t* img, const char* color) {
    return guarded([&] {
        mapnik::image_rgba8& im = mc_image_of(img);
        mapnik::color c(color);
        Premultiplied p(im);
        mapnik::set_color_to_alpha(im, c);
    });
}

int mapnik_image_probe_file(const char* path, int* w, int* h) {
    return guarded([&] {
        std::unique_ptr<mapnik::image_reader> r(mapnik::get_image_reader(path));
        *w = static_cast<int>(r->width());
        *h = static_cast<int>(r->height());
    });
}

int mapnik_image_probe_bytes(const unsigned char* data, int length, int* w, int* h) {
    return guarded([&] {
        if (length <= 0) throw std::invalid_argument("no image data");
        std::unique_ptr<mapnik::image_reader> r(
            mapnik::get_image_reader(reinterpret_cast<const char*>(data), static_cast<size_t>(length)));
        *w = static_cast<int>(r->width());
        *h = static_cast<int>(r->height());
    });
}

}  // extern "C"
