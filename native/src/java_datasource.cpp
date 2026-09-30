#include "common.hpp"

#include <mapnik/datasource.hpp>
#include <mapnik/feature_factory.hpp>
#include <mapnik/feature_layer_desc.hpp>
#include <mapnik/featureset.hpp>
#include <mapnik/unicode.hpp>
#include <mapnik/value.hpp>
#include <mapnik/wkb.hpp>
#include <mapnik/query.hpp>

#include <cstdint>
#include <cstring>
#include <mutex>
#include <stdexcept>
#include <vector>

using namespace mc;

namespace {

mapnik_java_features_fn g_features = nullptr;
mapnik_java_release_fn g_release = nullptr;

// A bounds-checked little-endian reader: the buffer comes from Java, and a bug there must not crash the process.
class Reader {
  public:
    Reader(const unsigned char* data, int length) : p_(data), end_(data + length) {}

    std::uint32_t u32() { return read<std::uint32_t>(4); }
    std::int64_t i64() { return static_cast<std::int64_t>(read<std::uint64_t>(8)); }
    double f64() {
        std::uint64_t bits = read<std::uint64_t>(8);
        double d;
        std::memcpy(&d, &bits, sizeof d);
        return d;
    }
    std::uint8_t u8() { return read<std::uint8_t>(1); }
    const unsigned char* bytes(std::uint32_t n) {
        need(n);
        const unsigned char* r = p_;
        p_ += n;
        return r;
    }
    std::string str() {
        std::uint32_t n = u32();
        const unsigned char* b = bytes(n);
        return std::string(reinterpret_cast<const char*>(b), n);
    }
    bool done() const { return p_ == end_; }

  private:
    template <typename T>
    T read(unsigned n) {
        need(n);
        std::uint64_t v = 0;
        for (unsigned i = 0; i < n; i++) v |= static_cast<std::uint64_t>(p_[i]) << (8 * i);
        p_ += n;
        return static_cast<T>(v);
    }
    void need(std::uint32_t n) const {
        if (static_cast<std::ptrdiff_t>(n) > end_ - p_) throw std::runtime_error("feature buffer from Java is truncated");
    }
    const unsigned char* p_;
    const unsigned char* end_;
};

class list_featureset : public mapnik::Featureset {
  public:
    explicit list_featureset(std::vector<mapnik::feature_ptr> f) : features_(std::move(f)) {}
    mapnik::feature_ptr next() override { return index_ < features_.size() ? features_[index_++] : mapnik::feature_ptr(); }

  private:
    std::vector<mapnik::feature_ptr> features_;
    std::size_t index_ = 0;
};

std::vector<mapnik::feature_ptr> parse(const unsigned char* data, int length) {
    static const mapnik::transcoder utf8("utf-8");
    Reader r(data, length);
    std::uint32_t count = r.u32();
    std::vector<mapnik::feature_ptr> out;
    out.reserve(std::min<std::uint32_t>(count, 100000));
    for (std::uint32_t i = 0; i < count; i++) {
        std::int64_t id = r.i64();
        std::uint32_t wkb_len = r.u32();
        const unsigned char* wkb = r.bytes(wkb_len);
        std::uint32_t n_attrs = r.u32();

        std::vector<std::pair<std::string, mapnik::value>> attrs;
        for (std::uint32_t a = 0; a < n_attrs; a++) {
            std::string name = r.str();
            switch (r.u8()) {
                case 0: attrs.emplace_back(name, mapnik::value()); break;
                case 1: attrs.emplace_back(name, mapnik::value(static_cast<mapnik::value_bool>(r.u8() != 0))); break;
                case 2: attrs.emplace_back(name, mapnik::value(static_cast<mapnik::value_integer>(r.i64()))); break;
                case 3: attrs.emplace_back(name, mapnik::value(r.f64())); break;
                case 4: attrs.emplace_back(name, mapnik::value(utf8.transcode(r.str().c_str()))); break;
                default: throw std::runtime_error("unknown attribute type in feature buffer from Java");
            }
        }
        auto ctx = std::make_shared<mapnik::context_type>();
        for (auto const& kv : attrs) ctx->push(kv.first);
        mapnik::feature_ptr f = mapnik::feature_factory::create(ctx, static_cast<mapnik::value_integer>(id));
        for (auto const& kv : attrs) f->put(kv.first, mapnik::value(kv.second));
        if (wkb_len > 0) {
            f->set_geometry(mapnik::geometry_utils::from_wkb(reinterpret_cast<const char*>(wkb), wkb_len, mapnik::wkbGeneric));
        }
        out.push_back(f);
    }
    if (!r.done()) throw std::runtime_error("feature buffer from Java has unexpected trailing data");
    return out;
}

class java_datasource : public mapnik::datasource {
  public:
    java_datasource(long long id, mapnik::box2d<double> env, mapnik::layer_descriptor desc)
        : mapnik::datasource(make_params()), id_(id), envelope_(env), desc_(std::move(desc)) {}

    ~java_datasource() override {
        if (g_release) {
            try {
                g_release(id_);
            } catch (...) {
            }
        }
    }

    mapnik::datasource::datasource_t type() const override { return mapnik::datasource::Vector; }

    std::optional<mapnik::datasource_geometry_t> get_geometry_type() const override { return mapnik::datasource_geometry_t::Collection; }

    mapnik::box2d<double> envelope() const override {
        std::lock_guard<std::mutex> lock(mutex_);
        return envelope_;
    }

    void set_envelope(mapnik::box2d<double> const& e) {
        std::lock_guard<std::mutex> lock(mutex_);
        envelope_ = e;
    }

    mapnik::layer_descriptor get_descriptor() const override { return desc_; }

    mapnik::featureset_ptr features(mapnik::query const& q) const override {
        auto const& b = q.get_bbox();
        return fetch(b.minx(), b.miny(), b.maxx(), b.maxy(), std::get<0>(q.resolution()), std::get<1>(q.resolution()),
                     q.scale_denominator());
    }

    mapnik::featureset_ptr features_at_point(mapnik::coord2d const& pt, double tol) const override {
        return fetch(pt.x - tol, pt.y - tol, pt.x + tol, pt.y + tol, 1.0, 1.0, 1.0);
    }

  private:
    static mapnik::parameters make_params() {
        mapnik::parameters p;
        p["type"] = std::string("java");
        return p;
    }

    mapnik::featureset_ptr fetch(double minx, double miny, double maxx, double maxy, double rx, double ry, double scale) const {
        if (!g_features) throw std::runtime_error("no Java feature handler is registered");
        unsigned char* buf = nullptr;
        int len = 0;
        int rc = g_features(id_, minx, miny, maxx, maxy, rx, ry, scale, &buf, &len);
        if (rc != 0) {
            throw std::runtime_error(buf && len > 0 ? std::string(reinterpret_cast<const char*>(buf), static_cast<size_t>(len))
                                                    : std::string("the Java feature source failed"));
        }
        if (len < 4 || !buf) return std::make_shared<list_featureset>(std::vector<mapnik::feature_ptr>());
        return std::make_shared<list_featureset>(parse(buf, len));
    }

    long long id_;
    mutable std::mutex mutex_;
    mapnik::box2d<double> envelope_;
    mapnik::layer_descriptor desc_;
};

}  // namespace

extern "C" {

void mapnik_java_set_handlers(mapnik_java_features_fn features, mapnik_java_release_fn release) {
    g_features = features;
    g_release = release;
}

mapnik_datasource_t* mapnik_java_datasource_create(long long id, const double* e, const char* const* names,
                                                   const int* types, int count) {
    mapnik_datasource_t* out = nullptr;
    guarded([&] {
        mapnik::layer_descriptor desc("java", "utf-8");
        for (int i = 0; names && i < count; i++) {
            desc.add_descriptor(mapnik::attribute_descriptor(names[i], static_cast<unsigned>(types[i])));
        }
        out = new mapnik_datasource{std::make_shared<java_datasource>(id, mapnik::box2d<double>(e[0], e[1], e[2], e[3]), desc)};
    });
    return out;
}

void mapnik_java_datasource_set_envelope(mapnik_datasource_t* ds, double a, double b, double c, double d) {
    guarded([&] {
        auto* j = dynamic_cast<java_datasource*>(ds->ds.get());
        if (!j) throw std::invalid_argument("not a Java datasource");
        j->set_envelope(mapnik::box2d<double>(a, b, c, d));
    });
}

}  // extern "C"
