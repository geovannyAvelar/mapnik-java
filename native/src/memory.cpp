#include "common.hpp"

#include <mapnik/feature_factory.hpp>
#include <mapnik/geometry.hpp>
#include <mapnik/memory_datasource.hpp>
#include <mapnik/unicode.hpp>
#include <mapnik/value.hpp>
#include <mapnik/wkb.hpp>

#include <map>
#include <optional>
#include <stdexcept>

struct mapnik_feature_builder {
    long long id;
    std::optional<mapnik::geometry::geometry<double>> geometry;
    // name -> value; ordered, and a repeated name replaces the earlier one
    std::map<std::string, mapnik::value> attributes;
};

using namespace mc;

extern "C" {

mapnik_feature_builder_t* mapnik_feature_builder_create(long long id) {
    mapnik_feature_builder_t* b = nullptr;
    guarded([&] {
        b = new mapnik_feature_builder();
        b->id = id;
    });
    return b;
}

void mapnik_feature_builder_free(mapnik_feature_builder_t* b) { delete b; }

int mapnik_feature_builder_set_geometry_wkb(mapnik_feature_builder_t* b, const unsigned char* wkb, int length) {
    return guarded([&] {
        if (length <= 0) throw std::invalid_argument("no geometry data");
        b->geometry = mapnik::geometry_utils::from_wkb(reinterpret_cast<const char*>(wkb),
                                                      static_cast<std::size_t>(length), mapnik::wkbGeneric);
    });
}

void mapnik_feature_builder_put_null(mapnik_feature_builder_t* b, const char* key) {
    b->attributes[key] = mapnik::value();
}

void mapnik_feature_builder_put_string(mapnik_feature_builder_t* b, const char* key, const char* value) {
    static const mapnik::transcoder utf8("utf-8");
    b->attributes[key] = mapnik::value(utf8.transcode(value));
}

void mapnik_feature_builder_put_int(mapnik_feature_builder_t* b, const char* key, long long value) {
    b->attributes[key] = mapnik::value(static_cast<mapnik::value_integer>(value));
}

void mapnik_feature_builder_put_double(mapnik_feature_builder_t* b, const char* key, double value) {
    b->attributes[key] = mapnik::value(value);
}

void mapnik_feature_builder_put_bool(mapnik_feature_builder_t* b, const char* key, int value) {
    b->attributes[key] = mapnik::value(static_cast<mapnik::value_bool>(value != 0));
}

mapnik_datasource_t* mapnik_memory_datasource_create(void) {
    mapnik_datasource_t* out = nullptr;
    guarded([&] {
        mapnik::parameters p;
        p["type"] = std::string("memory");
        out = new mapnik_datasource{std::make_shared<mapnik::memory_datasource>(p)};
    });
    return out;
}

namespace {
mapnik::memory_datasource& memory(mapnik_datasource_t* ds) {
    auto* m = dynamic_cast<mapnik::memory_datasource*>(ds->ds.get());
    if (!m) throw std::invalid_argument("not a memory datasource");
    return *m;
}
}  // namespace

int mapnik_memory_datasource_push(mapnik_datasource_t* ds, mapnik_feature_builder_t* b) {
    return guarded([&] {
        auto ctx = std::make_shared<mapnik::context_type>();
        for (auto const& kv : b->attributes) ctx->push(kv.first);
        mapnik::feature_ptr f = mapnik::feature_factory::create(ctx, static_cast<mapnik::value_integer>(b->id));
        for (auto const& kv : b->attributes) f->put(kv.first, mapnik::value(kv.second));
        if (b->geometry) f->set_geometry_copy(*b->geometry);
        memory(ds).push(f);
    });
}

int mapnik_memory_datasource_size(mapnik_datasource_t* ds) {
    int n = 0;
    guarded([&] { n = static_cast<int>(memory(ds).size()); });
    return n;
}

void mapnik_memory_datasource_clear(mapnik_datasource_t* ds) {
    guarded([&] { memory(ds).clear(); });
}

void mapnik_memory_datasource_set_envelope(mapnik_datasource_t* ds, double a, double b, double c, double d) {
    guarded([&] { memory(ds).set_envelope(mapnik::box2d<double>(a, b, c, d)); });
}

}  // extern "C"
