#include "common.hpp"

#include <mapnik/attribute.hpp>
#include <mapnik/expression.hpp>
#include <mapnik/expression_evaluator.hpp>
#include <mapnik/parse_path.hpp>
#include <mapnik/path_expression.hpp>
#include <mapnik/transform/parse_transform.hpp>
#include <mapnik/unicode.hpp>
#include <mapnik/value.hpp>

#include <stdexcept>

struct mapnik_expression {
    mapnik::expression_ptr expression;
};

struct mapnik_path_expression {
    mapnik::path_expression_ptr path;
};

struct mapnik_value {
    mapnik::value value;
};

using namespace mc;

namespace {

mapnik::value evaluate(mapnik_expression_t* e, mapnik_feature_builder_t* b) {
    mapnik::feature_ptr f = build_feature(b);
    mapnik::attributes vars;
    for (auto const& kv : b->variables) vars[kv.first] = kv.second;
    return mapnik::util::apply_visitor(mapnik::evaluate<mapnik::feature_impl, mapnik::value, mapnik::attributes>(*f, vars),
                                       *e->expression);
}

}  // namespace

extern "C" {

mapnik_expression_t* mapnik_expression_parse(const char* text) {
    mapnik_expression_t* e = nullptr;
    guarded([&] {
        auto parsed = mapnik::parse_expression(text);
        if (!parsed) throw std::invalid_argument(std::string("failed to parse expression: ") + text);
        e = new mapnik_expression{parsed};
    });
    return e;
}

void mapnik_expression_free(mapnik_expression_t* e) { delete e; }

mapnik_value_t* mapnik_expression_evaluate(mapnik_expression_t* e, mapnik_feature_builder_t* b) {
    mapnik_value_t* v = nullptr;
    guarded([&] { v = new mapnik_value{evaluate(e, b)}; });
    return v;
}

int mapnik_expression_test(mapnik_expression_t* e, mapnik_feature_builder_t* b) {
    int result = -1;
    guarded([&] { result = evaluate(e, b).to_bool() ? 1 : 0; });
    return result;
}

void mapnik_value_free(mapnik_value_t* v) { delete v; }

int mapnik_value_type(mapnik_value_t* v) {
    mapnik::value const& x = v->value;
    if (x.is_null()) return 0;
    if (x.is<mapnik::value_bool>()) return 1;
    if (x.is<mapnik::value_integer>()) return 2;
    if (x.is<mapnik::value_double>()) return 3;
    return 4;
}

int mapnik_value_bool(mapnik_value_t* v) { return v->value.to_bool() ? 1 : 0; }
long long mapnik_value_int(mapnik_value_t* v) { return static_cast<long long>(v->value.to_int()); }
double mapnik_value_double(mapnik_value_t* v) { return v->value.to_double(); }
const char* mapnik_value_string(mapnik_value_t* v) { return text(v->value.to_string()); }

mapnik_path_expression_t* mapnik_path_expression_parse(const char* text) {
    mapnik_path_expression_t* p = nullptr;
    guarded([&] {
        // Mapnik's path grammar throws a bare Boost exception whose message says nothing useful.
        mapnik::path_expression_ptr parsed;
        try {
            parsed = mapnik::parse_path(text);
        } catch (std::exception const&) {
            throw std::invalid_argument(std::string("failed to parse path expression: ") + text);
        }
        if (!parsed) throw std::invalid_argument(std::string("failed to parse path expression: ") + text);
        p = new mapnik_path_expression{parsed};
    });
    return p;
}

void mapnik_path_expression_free(mapnik_path_expression_t* p) { delete p; }

const char* mapnik_path_expression_evaluate(mapnik_path_expression_t* p, mapnik_feature_builder_t* b) {
    const char* r = nullptr;
    guarded([&] {
        mapnik::feature_ptr f = build_feature(b);
        r = text(mapnik::path_processor::evaluate(*p->path, *f));
    });
    return r;
}

int mapnik_transform_check(const char* text_in) {
    return guarded([&] {
        auto list = mapnik::parse_transform(text_in);
        if (!list) throw std::invalid_argument(std::string("failed to parse transform: ") + text_in);
    });
}

}  // extern "C"
