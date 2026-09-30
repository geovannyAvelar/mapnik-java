#ifndef MAPNIK_C_GEOMETRY_OUT_HPP
#define MAPNIK_C_GEOMETRY_OUT_HPP

#include <mapnik/geometry.hpp>
#include <mapnik/value.hpp>

#include <string>

namespace mc {

// Hand-written writers. Mapnik's own to_wkt/to_geojson live in static libraries that are built
// against a specific ICU, which makes them fragile to link into a shared library.
std::string geometry_to_wkt(mapnik::geometry::geometry<double> const& g);
std::string geometry_to_geojson(mapnik::geometry::geometry<double> const& g);
std::string json_escape(std::string const& s);
void append_number(std::string& out, double v);

}  // namespace mc

#endif
