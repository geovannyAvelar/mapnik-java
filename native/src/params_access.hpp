#ifndef MAPNIK_C_PARAMS_ACCESS_HPP
#define MAPNIK_C_PARAMS_ACCESS_HPP

#include "common.hpp"

#include <iterator>

namespace mc {

// Read access to a mapnik::parameters by index in key order, shared by maps and datasources.
inline std::pair<std::string const*, mapnik::value_holder const*> param_at(mapnik::parameters const& p, int i) {
    if (!valid_index(i, p.size())) return {nullptr, nullptr};
    auto it = p.begin();
    std::advance(it, i);
    return {&it->first, &it->second};
}

inline int param_type(mapnik::value_holder const& v) {
    if (v.is<mapnik::value_null>()) return 0;
    if (v.is<mapnik::value_bool>()) return 1;
    if (v.is<mapnik::value_integer>()) return 2;
    if (v.is<mapnik::value_double>()) return 3;
    return 4;
}

inline int param_bool(mapnik::value_holder const& v) {
    if (v.is<mapnik::value_bool>()) return v.get<mapnik::value_bool>() ? 1 : 0;
    if (v.is<mapnik::value_integer>()) return v.get<mapnik::value_integer>() != 0 ? 1 : 0;
    return 0;
}

inline long long param_int(mapnik::value_holder const& v) {
    if (v.is<mapnik::value_integer>()) return static_cast<long long>(v.get<mapnik::value_integer>());
    if (v.is<mapnik::value_bool>()) return v.get<mapnik::value_bool>() ? 1 : 0;
    if (v.is<mapnik::value_double>()) return static_cast<long long>(v.get<mapnik::value_double>());
    return 0;
}

inline double param_double(mapnik::value_holder const& v) {
    if (v.is<mapnik::value_double>()) return v.get<mapnik::value_double>();
    if (v.is<mapnik::value_integer>()) return static_cast<double>(v.get<mapnik::value_integer>());
    return 0;
}

inline std::string param_string(mapnik::value_holder const& v) {
    if (v.is<std::string>()) return v.get<std::string>();
    if (v.is<mapnik::value_integer>()) return std::to_string(v.get<mapnik::value_integer>());
    if (v.is<mapnik::value_bool>()) return v.get<mapnik::value_bool>() ? "true" : "false";
    if (v.is<mapnik::value_double>()) return std::to_string(v.get<mapnik::value_double>());
    return "";
}

}  // namespace mc

#endif
