#include "common.hpp"
#include "geometry_out.hpp"

#include <mapnik/geometry.hpp>
#include <mapnik/geometry/centroid.hpp>
#include <mapnik/geometry/closest_point.hpp>
#include <mapnik/geometry/correct.hpp>
#include <mapnik/geometry/interior.hpp>
#include <mapnik/geometry/is_simple.hpp>
#include <mapnik/geometry/is_valid.hpp>
#include <mapnik/geometry/reprojection.hpp>
#include <mapnik/offset_converter.hpp>
#include <mapnik/simplify.hpp>
#include <mapnik/simplify_converter.hpp>
#include <mapnik/vertex.hpp>
#include <mapnik/vertex_adapters.hpp>
#include <mapnik/wkb.hpp>

#include <cmath>
#include <functional>
#include <stdexcept>

using namespace mc;
namespace geom = mapnik::geometry;
using Geom = geom::geometry<double>;

namespace {

Geom read(const unsigned char* wkb, int length) {
    if (length <= 0) throw std::invalid_argument("no geometry data");
    return mapnik::geometry_utils::from_wkb(reinterpret_cast<const char*>(wkb), static_cast<std::size_t>(length),
                                           mapnik::wkbGeneric);
}

// Run a vertex source to the end and collect its points.
template <typename Source>
geom::line_string<double> collect(Source& src) {
    geom::line_string<double> out;
    double x = 0, y = 0;
    src.rewind(0);
    unsigned cmd;
    while ((cmd = src.vertex(&x, &y)) != mapnik::SEG_END) {
        if (cmd == mapnik::SEG_MOVETO || cmd == mapnik::SEG_LINETO) out.emplace_back(x, y);
    }
    return out;
}

using LineFn = std::function<geom::line_string<double>(geom::line_string<double> const&)>;

// Apply a function to every line string of a geometry, and to every ring of its polygons.
struct Mapper {
    LineFn fn;
    bool rings;  // also rewrite polygon rings
    bool only_lines;

    Geom operator()(geom::geometry_empty const& g) const { return g; }
    Geom operator()(geom::point<double> const& g) const {
        if (only_lines) throw std::invalid_argument("this operation applies to lines, not points");
        return g;
    }
    Geom operator()(geom::multi_point<double> const& g) const {
        if (only_lines) throw std::invalid_argument("this operation applies to lines, not points");
        return g;
    }
    Geom operator()(geom::line_string<double> const& g) const { return fn(g); }
    Geom operator()(geom::multi_line_string<double> const& g) const {
        geom::multi_line_string<double> out;
        for (auto const& l : g) out.push_back(fn(l));
        return out;
    }
    geom::polygon<double> polygon(geom::polygon<double> const& p) const {
        if (only_lines) throw std::invalid_argument("this operation applies to lines, not polygons");
        if (!rings) return p;
        geom::polygon<double> out;
        for (auto const& ring : p) {
            geom::line_string<double> line(ring.begin(), ring.end());
            geom::line_string<double> mapped = fn(line);
            // Never collapse a ring: a ring needs four points to be a ring.
            if (mapped.size() < 4) {
                out.push_back(ring);
            } else {
                geom::linear_ring<double> r(mapped.begin(), mapped.end());
                out.push_back(std::move(r));
            }
        }
        return out;
    }
    Geom operator()(geom::polygon<double> const& p) const { return polygon(p); }
    Geom operator()(geom::multi_polygon<double> const& mp) const {
        geom::multi_polygon<double> out;
        for (auto const& p : mp) out.push_back(polygon(p));
        return out;
    }
    Geom operator()(geom::geometry_collection<double> const& gc) const {
        geom::geometry_collection<double> out;
        for (auto const& g : gc) out.push_back(mapnik::util::apply_visitor(*this, g));
        return out;
    }
};

const char* wkt_of(Geom const& g) { return text(geometry_to_wkt(g)); }

// Counts coordinates that are not finite numbers, which is how a failed projection shows up.
struct NonFinite {
    std::size_t operator()(geom::geometry_empty const&) const { return 0; }
    std::size_t pt(geom::point<double> const& p) const { return (std::isfinite(p.x) && std::isfinite(p.y)) ? 0 : 1; }
    std::size_t operator()(geom::point<double> const& p) const { return pt(p); }
    template <typename Range>
    std::size_t range(Range const& r) const {
        std::size_t n = 0;
        for (auto const& p : r) n += pt(p);
        return n;
    }
    std::size_t operator()(geom::multi_point<double> const& g) const { return range(g); }
    std::size_t operator()(geom::line_string<double> const& g) const { return range(g); }
    std::size_t operator()(geom::multi_line_string<double> const& g) const {
        std::size_t n = 0;
        for (auto const& l : g) n += range(l);
        return n;
    }
    std::size_t poly(geom::polygon<double> const& g) const {
        std::size_t n = 0;
        for (auto const& r : g) n += range(r);
        return n;
    }
    std::size_t operator()(geom::polygon<double> const& g) const { return poly(g); }
    std::size_t operator()(geom::multi_polygon<double> const& g) const {
        std::size_t n = 0;
        for (auto const& p : g) n += poly(p);
        return n;
    }
    std::size_t operator()(geom::geometry_collection<double> const& g) const {
        std::size_t n = 0;
        for (auto const& x : g) n += mapnik::util::apply_visitor(*this, x);
        return n;
    }
};

}  // namespace

extern "C" {

int mapnik_geometry_centroid(const unsigned char* wkb, int length, double* out) {
    return guarded([&] {
        Geom g = read(wkb, length);
        geom::point<double> c;
        if (!geom::centroid(g, c)) throw std::runtime_error("the geometry has no centroid (it is empty)");
        out[0] = c.x;
        out[1] = c.y;
    });
}

int mapnik_geometry_interior_point(const unsigned char* wkb, int length, double scale, double* out) {
    return guarded([&] {
        Geom g = read(wkb, length);
        if (!g.is<geom::polygon<double>>()) throw std::invalid_argument("an interior point needs a polygon");
        geom::point<double> p;
        if (!geom::interior(g.get<geom::polygon<double>>(), scale, p)) {
            throw std::runtime_error("no interior point could be found");
        }
        out[0] = p.x;
        out[1] = p.y;
    });
}

int mapnik_geometry_closest_point(const unsigned char* wkb, int length, double x, double y, double* out) {
    return guarded([&] {
        Geom g = read(wkb, length);
        auto r = geom::closest_point(g, geom::point<double>(x, y));
        if (r.distance < 0) throw std::runtime_error("the geometry has no points (it is empty)");
        out[0] = r.x;
        out[1] = r.y;
        out[2] = r.distance;
    });
}

int mapnik_geometry_is_valid(const unsigned char* wkb, int length) {
    int result = -1;
    guarded([&] { result = geom::is_valid(read(wkb, length)) ? 1 : 0; });
    return result;
}

const char* mapnik_geometry_validity_reason(const unsigned char* wkb, int length) {
    const char* r = nullptr;
    guarded([&] {
        std::string message;
        geom::is_valid(read(wkb, length), message);
        r = text(message);
    });
    return r;
}

int mapnik_geometry_is_simple(const unsigned char* wkb, int length) {
    int result = -1;
    guarded([&] { result = geom::is_simple(read(wkb, length)) ? 1 : 0; });
    return result;
}

const char* mapnik_geometry_correct(const unsigned char* wkb, int length) {
    const char* r = nullptr;
    guarded([&] {
        Geom g = read(wkb, length);
        geom::correct(g);
        r = wkt_of(g);
    });
    return r;
}

const char* mapnik_geometry_simplify(const unsigned char* wkb, int length, const char* algorithm, double tolerance) {
    const char* r = nullptr;
    guarded([&] {
        auto algo = mapnik::simplify_algorithm_from_string(algorithm);
        if (!algo) throw std::invalid_argument(std::string("unknown simplify algorithm: ") + algorithm);
        if (!(tolerance >= 0.0)) throw std::invalid_argument("tolerance must not be negative");
        Geom g = read(wkb, length);
        Mapper m{[&](geom::line_string<double> const& line) {
                     geom::line_string_vertex_adapter<double> va(line);
                     mapnik::simplify_converter<geom::line_string_vertex_adapter<double>> simp(va);
                     simp.set_simplify_algorithm(*algo);
                     simp.set_simplify_tolerance(tolerance);
                     geom::line_string<double> out = collect(simp);
                     return out.size() < 2 ? line : out;
                 },
                 true, false};
        r = wkt_of(mapnik::util::apply_visitor(m, g));
    });
    return r;
}

const char* mapnik_geometry_offset(const unsigned char* wkb, int length, double distance) {
    const char* r = nullptr;
    guarded([&] {
        Geom g = read(wkb, length);
        Mapper m{[&](geom::line_string<double> const& line) {
                     geom::line_string_vertex_adapter<double> va(line);
                     mapnik::offset_converter<geom::line_string_vertex_adapter<double>> off(va);
                     off.set_offset(distance);
                     geom::line_string<double> out = collect(off);
                     return out.size() < 2 ? line : out;
                 },
                 false, true};
        r = wkt_of(mapnik::util::apply_visitor(m, g));
    });
    return r;
}

const char* mapnik_geometry_reproject(const unsigned char* wkb, int length, mapnik_transform_t* t) {
    const char* r = nullptr;
    guarded([&] {
        Geom g = read(wkb, length);
        unsigned n_err = 0;
        Geom out = geom::reproject_copy(g, t->transform, n_err);
        // Mapnik does not always count a failed point in n_err: it can leave infinity behind instead.
        std::size_t bad = n_err + mapnik::util::apply_visitor(NonFinite{}, out);
        if (bad > 0) throw std::runtime_error(std::to_string(bad) + " point(s) could not be transformed");
        r = wkt_of(out);
    });
    return r;
}

}  // extern "C"
