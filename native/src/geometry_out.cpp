#include "geometry_out.hpp"

#include <charconv>
#include <cmath>
#include <cstdio>

namespace mc {

void append_number(std::string& out, double v) {
    char buf[64];
    auto r = std::to_chars(buf, buf + sizeof buf, v);
    out.append(buf, r.ptr);
}

std::string json_escape(std::string const& s) {
    std::string out = "\"";
    for (unsigned char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char b[8];
                    std::snprintf(b, sizeof b, "\\u%04x", c);
                    out += b;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    return out + "\"";
}

namespace {

namespace geom = mapnik::geometry;

// ---- WKT ---------------------------------------------------------------------------------

void wkt_xy(std::string& o, geom::point<double> const& p) {
    append_number(o, p.x);
    o += ' ';
    append_number(o, p.y);
}

template <typename Range>
void wkt_coords(std::string& o, Range const& pts) {
    bool first = true;
    for (auto const& p : pts) {
        if (!first) o += ',';
        first = false;
        wkt_xy(o, p);
    }
}

// A polygon is a list of rings: the first is the exterior, the rest are holes.
void wkt_polygon_body(std::string& o, geom::polygon<double> const& poly) {
    o += '(';
    bool first = true;
    for (auto const& ring : poly) {
        if (!first) o += ',';
        first = false;
        o += '(';
        wkt_coords(o, ring);
        o += ')';
    }
    o += ')';
}

struct wkt_writer {
    std::string& o;

    void operator()(geom::geometry_empty const&) const { o += "GEOMETRYCOLLECTION EMPTY"; }
    void operator()(geom::point<double> const& p) const {
        o += "POINT(";
        wkt_xy(o, p);
        o += ')';
    }
    void operator()(geom::line_string<double> const& l) const {
        o += "LINESTRING(";
        wkt_coords(o, l);
        o += ')';
    }
    void operator()(geom::polygon<double> const& p) const {
        o += "POLYGON";
        wkt_polygon_body(o, p);
    }
    void operator()(geom::multi_point<double> const& mp) const {
        o += "MULTIPOINT(";
        bool first = true;
        for (auto const& p : mp) {
            if (!first) o += ',';
            first = false;
            o += '(';
            wkt_xy(o, p);
            o += ')';
        }
        o += ')';
    }
    void operator()(geom::multi_line_string<double> const& ml) const {
        o += "MULTILINESTRING(";
        bool first = true;
        for (auto const& l : ml) {
            if (!first) o += ',';
            first = false;
            o += '(';
            wkt_coords(o, l);
            o += ')';
        }
        o += ')';
    }
    void operator()(geom::multi_polygon<double> const& mp) const {
        o += "MULTIPOLYGON(";
        bool first = true;
        for (auto const& p : mp) {
            if (!first) o += ',';
            first = false;
            wkt_polygon_body(o, p);
        }
        o += ')';
    }
    void operator()(geom::geometry_collection<double> const& gc) const {
        o += "GEOMETRYCOLLECTION(";
        bool first = true;
        for (auto const& g : gc) {
            if (!first) o += ',';
            first = false;
            mapnik::util::apply_visitor(*this, g);
        }
        o += ')';
    }
};

// ---- GeoJSON -----------------------------------------------------------------------------

void json_xy(std::string& o, geom::point<double> const& p) {
    o += '[';
    append_number(o, p.x);
    o += ',';
    append_number(o, p.y);
    o += ']';
}

template <typename Range>
void json_coords(std::string& o, Range const& pts) {
    o += '[';
    bool first = true;
    for (auto const& p : pts) {
        if (!first) o += ',';
        first = false;
        json_xy(o, p);
    }
    o += ']';
}

void json_polygon_coords(std::string& o, geom::polygon<double> const& poly) {
    o += '[';
    bool first = true;
    for (auto const& ring : poly) {
        if (!first) o += ',';
        first = false;
        json_coords(o, ring);
    }
    o += ']';
}

struct json_writer {
    std::string& o;

    void operator()(geom::geometry_empty const&) const { o += "null"; }
    void operator()(geom::point<double> const& p) const {
        o += "{\"type\":\"Point\",\"coordinates\":";
        json_xy(o, p);
        o += '}';
    }
    void operator()(geom::line_string<double> const& l) const {
        o += "{\"type\":\"LineString\",\"coordinates\":";
        json_coords(o, l);
        o += '}';
    }
    void operator()(geom::polygon<double> const& p) const {
        o += "{\"type\":\"Polygon\",\"coordinates\":";
        json_polygon_coords(o, p);
        o += '}';
    }
    void operator()(geom::multi_point<double> const& mp) const {
        o += "{\"type\":\"MultiPoint\",\"coordinates\":";
        json_coords(o, mp);
        o += '}';
    }
    void operator()(geom::multi_line_string<double> const& ml) const {
        o += "{\"type\":\"MultiLineString\",\"coordinates\":[";
        bool first = true;
        for (auto const& l : ml) {
            if (!first) o += ',';
            first = false;
            json_coords(o, l);
        }
        o += "]}";
    }
    void operator()(geom::multi_polygon<double> const& mp) const {
        o += "{\"type\":\"MultiPolygon\",\"coordinates\":[";
        bool first = true;
        for (auto const& p : mp) {
            if (!first) o += ',';
            first = false;
            json_polygon_coords(o, p);
        }
        o += "]}";
    }
    void operator()(geom::geometry_collection<double> const& gc) const {
        o += "{\"type\":\"GeometryCollection\",\"geometries\":[";
        bool first = true;
        for (auto const& g : gc) {
            if (!first) o += ',';
            first = false;
            mapnik::util::apply_visitor(*this, g);
        }
        o += "]}";
    }
};

}  // namespace

std::string geometry_to_wkt(mapnik::geometry::geometry<double> const& g) {
    std::string out;
    mapnik::util::apply_visitor(wkt_writer{out}, g);
    return out;
}

std::string geometry_to_geojson(mapnik::geometry::geometry<double> const& g) {
    std::string out;
    mapnik::util::apply_visitor(json_writer{out}, g);
    return out;
}

}  // namespace mc
