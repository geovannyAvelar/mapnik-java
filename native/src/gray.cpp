#include "common.hpp"

#include <mapnik/color.hpp>
#include <mapnik/feature_factory.hpp>
#include <mapnik/image.hpp>
#include <mapnik/image_any.hpp>
#include <mapnik/image_util.hpp>
#include <mapnik/raster_colorizer.hpp>
#include <mapnik/util/variant.hpp>

#include <cmath>
#include <limits>

using namespace mc;

namespace {

struct Range {
    double lo, hi;
    bool integer;
};

// The values a pixel type can hold, for refusing what would silently wrap.
Range range_of(mapnik::image_dtype t) {
    using L8 = std::numeric_limits<std::uint8_t>;
    switch (t) {
        case mapnik::image_dtype_gray8: return {0, static_cast<double>(L8::max()), true};
        case mapnik::image_dtype_gray8s: return {-128, 127, true};
        case mapnik::image_dtype_gray16: return {0, 65535, true};
        case mapnik::image_dtype_gray16s: return {-32768, 32767, true};
        case mapnik::image_dtype_gray32: return {0, 4294967295.0, true};
        case mapnik::image_dtype_gray32s: return {-2147483648.0, 2147483647.0, true};
        case mapnik::image_dtype_gray64: return {0, 9007199254740992.0, true};   // exact range of a double
        case mapnik::image_dtype_gray64s: return {-9007199254740992.0, 9007199254740992.0, true};
        case mapnik::image_dtype_gray32f: return {-std::numeric_limits<float>::max(), std::numeric_limits<float>::max(), false};
        case mapnik::image_dtype_gray64f: return {-std::numeric_limits<double>::max(), std::numeric_limits<double>::max(), false};
        default: throw std::invalid_argument("not a single-band image type");
    }
}

bool is_gray(int t) { return t >= mapnik::image_dtype_gray8 && t <= mapnik::image_dtype_gray64f; }

void check_value(mapnik_gray_t* g, double v) {
    Range r = range_of(g->image.get_dtype());
    if (std::isnan(v)) {
        if (r.integer) throw std::invalid_argument("NaN does not fit an integer image");
        return;
    }
    if (v < r.lo || v > r.hi) throw std::out_of_range("value " + std::to_string(v) + " does not fit this image type");
}

void set_any(mapnik::image_any& im, std::size_t x, std::size_t y, double v) {
    switch (im.get_dtype()) {
        case mapnik::image_dtype_gray8: mapnik::set_pixel<std::uint8_t>(im, x, y, static_cast<std::uint8_t>(std::llround(v))); break;
        case mapnik::image_dtype_gray8s: mapnik::set_pixel<std::int8_t>(im, x, y, static_cast<std::int8_t>(std::llround(v))); break;
        case mapnik::image_dtype_gray16: mapnik::set_pixel<std::uint16_t>(im, x, y, static_cast<std::uint16_t>(std::llround(v))); break;
        case mapnik::image_dtype_gray16s: mapnik::set_pixel<std::int16_t>(im, x, y, static_cast<std::int16_t>(std::llround(v))); break;
        case mapnik::image_dtype_gray32: mapnik::set_pixel<std::uint32_t>(im, x, y, static_cast<std::uint32_t>(std::llround(v))); break;
        case mapnik::image_dtype_gray32s: mapnik::set_pixel<std::int32_t>(im, x, y, static_cast<std::int32_t>(std::llround(v))); break;
        case mapnik::image_dtype_gray64: mapnik::set_pixel<std::uint64_t>(im, x, y, static_cast<std::uint64_t>(std::llround(v))); break;
        case mapnik::image_dtype_gray64s: mapnik::set_pixel<std::int64_t>(im, x, y, static_cast<std::int64_t>(std::llround(v))); break;
        case mapnik::image_dtype_gray32f: mapnik::set_pixel<float>(im, x, y, static_cast<float>(v)); break;
        case mapnik::image_dtype_gray64f: mapnik::set_pixel<double>(im, x, y, v); break;
        default: throw std::invalid_argument("not a single-band image");
    }
}

double get_any(mapnik::image_any const& im, std::size_t x, std::size_t y) {
    switch (im.get_dtype()) {
        case mapnik::image_dtype_gray8: return mapnik::get_pixel<std::uint8_t>(im, x, y);
        case mapnik::image_dtype_gray8s: return mapnik::get_pixel<std::int8_t>(im, x, y);
        case mapnik::image_dtype_gray16: return mapnik::get_pixel<std::uint16_t>(im, x, y);
        case mapnik::image_dtype_gray16s: return mapnik::get_pixel<std::int16_t>(im, x, y);
        case mapnik::image_dtype_gray32: return mapnik::get_pixel<std::uint32_t>(im, x, y);
        case mapnik::image_dtype_gray32s: return mapnik::get_pixel<std::int32_t>(im, x, y);
        case mapnik::image_dtype_gray64: return static_cast<double>(mapnik::get_pixel<std::uint64_t>(im, x, y));
        case mapnik::image_dtype_gray64s: return static_cast<double>(mapnik::get_pixel<std::int64_t>(im, x, y));
        case mapnik::image_dtype_gray32f: return mapnik::get_pixel<float>(im, x, y);
        case mapnik::image_dtype_gray64f: return mapnik::get_pixel<double>(im, x, y);
        default: throw std::invalid_argument("not a single-band image");
    }
}

struct colorize_visitor {
    mapnik::image_rgba8& out;
    mapnik::raster_colorizer const& colorizer;
    std::optional<double> const& nodata;
    mapnik::feature_impl const& feature;

    template <typename T>
    void operator()(T const& in) const { colorizer.colorize(out, in, nodata, feature); }
    void operator()(mapnik::image_rgba8 const&) const { throw std::invalid_argument("not a single-band image"); }
    void operator()(mapnik::image_null const&) const { throw std::invalid_argument("empty image"); }
};

mapnik::colorizer_mode_enum mode_of(int m) {
    switch (m) {
        case 0: return mapnik::colorizer_mode_enum::COLORIZER_DISCRETE;
        case 1: return mapnik::colorizer_mode_enum::COLORIZER_LINEAR;
        case 2: return mapnik::colorizer_mode_enum::COLORIZER_EXACT;
        case 3: return mapnik::colorizer_mode_enum::COLORIZER_INHERIT;
    }
    throw std::invalid_argument("unknown colorizer mode");
}

}  // namespace

extern "C" {

mapnik_gray_t* mapnik_gray_create(int type, int w, int h, double initial) {
    mapnik_gray_t* g = nullptr;
    guarded([&] {
        if (w <= 0 || h <= 0) throw std::invalid_argument("image size must be positive");
        if (!is_gray(type)) throw std::invalid_argument("not a single-band image type");
        if (static_cast<double>(w) * h > 1.0e9) throw std::invalid_argument("image is too large");
        auto dtype = static_cast<mapnik::image_dtype>(type);
        std::unique_ptr<mapnik_gray> out(new mapnik_gray(mapnik::image_any(w, h, dtype, true)));
        check_value(out.get(), initial);
        if (initial != 0.0) {
            for (int y = 0; y < h; ++y)
                for (int x = 0; x < w; ++x) set_any(out->image, x, y, initial);
        }
        g = out.release();
    });
    return g;
}

void mapnik_gray_free(mapnik_gray_t* g) { delete g; }

int mapnik_gray_width(mapnik_gray_t* g) { return static_cast<int>(g->image.width()); }
int mapnik_gray_height(mapnik_gray_t* g) { return static_cast<int>(g->image.height()); }
int mapnik_gray_type(mapnik_gray_t* g) { return static_cast<int>(g->image.get_dtype()); }

int mapnik_gray_get(mapnik_gray_t* g, int x, int y, double* out) {
    return guarded([&] {
        if (x < 0 || y < 0 || static_cast<std::size_t>(x) >= g->image.width() || static_cast<std::size_t>(y) >= g->image.height())
            throw std::out_of_range("pixel is outside the image");
        *out = get_any(g->image, x, y);
    });
}

int mapnik_gray_set(mapnik_gray_t* g, int x, int y, double v) {
    return guarded([&] {
        if (x < 0 || y < 0 || static_cast<std::size_t>(x) >= g->image.width() || static_cast<std::size_t>(y) >= g->image.height())
            throw std::out_of_range("pixel is outside the image");
        check_value(g, v);
        set_any(g->image, x, y, v);
    });
}

int mapnik_gray_read(mapnik_gray_t* g, double* out, long long count) {
    return guarded([&] {
        const std::size_t w = g->image.width(), h = g->image.height();
        if (count != static_cast<long long>(w * h)) throw std::invalid_argument("buffer size does not match the image");
        for (std::size_t y = 0; y < h; ++y)
            for (std::size_t x = 0; x < w; ++x) out[y * w + x] = get_any(g->image, x, y);
    });
}

int mapnik_gray_write(mapnik_gray_t* g, const double* in, long long count) {
    return guarded([&] {
        const std::size_t w = g->image.width(), h = g->image.height();
        if (count != static_cast<long long>(w * h)) throw std::invalid_argument("buffer size does not match the image");
        for (long long i = 0; i < count; ++i) check_value(g, in[i]);   // all or nothing
        for (std::size_t y = 0; y < h; ++y)
            for (std::size_t x = 0; x < w; ++x) set_any(g->image, x, y, in[y * w + x]);
    });
}

int mapnik_gray_range(mapnik_gray_t* g, int has_nodata, double nodata, double* out_min_max) {
    return guarded([&] {
        const std::size_t w = g->image.width(), h = g->image.height();
        double lo = std::numeric_limits<double>::infinity(), hi = -lo;
        for (std::size_t y = 0; y < h; ++y)
            for (std::size_t x = 0; x < w; ++x) {
                double v = get_any(g->image, x, y);
                if (std::isnan(v) || (has_nodata && v == nodata)) continue;
                if (v < lo) lo = v;
                if (v > hi) hi = v;
            }
        if (lo > hi) throw std::runtime_error("the image has no valid values");
        out_min_max[0] = lo;
        out_min_max[1] = hi;
    });
}

mapnik_image_t* mapnik_gray_colorize(mapnik_gray_t* g, const double* values, const int* rgba, const int* modes,
                                     int stops, int default_mode, int default_rgba, double epsilon,
                                     int has_nodata, double nodata) {
    mapnik_image_t* out = nullptr;
    guarded([&] {
        auto colorizer = std::make_shared<mapnik::raster_colorizer>(
            mode_of(default_mode == 3 ? 1 : default_mode), mapnik::color(static_cast<unsigned int>(default_rgba)));
        for (int i = 0; i < stops; ++i) {
            colorizer->add_stop(mapnik::colorizer_stop(static_cast<float>(values[i]), mode_of(modes[i]), mapnik::color(static_cast<unsigned int>(rgba[i]))));
        }
        if (epsilon > 0) colorizer->set_epsilon(static_cast<float>(epsilon));
        mapnik::image_rgba8 img(static_cast<int>(g->image.width()), static_cast<int>(g->image.height()));
        img.set(0);
        auto ctx = std::make_shared<mapnik::context_type>();
        mapnik::feature_ptr feature = mapnik::feature_factory::create(ctx, 1);
        std::optional<double> nd;
        if (has_nodata) nd = nodata;
        mapnik::util::apply_visitor(colorize_visitor{img, *colorizer, nd, *feature}, g->image);
        img.set_premultiplied(false);
        out = new mapnik_image(std::move(img));
    });
    return out;
}

}  // extern "C"
