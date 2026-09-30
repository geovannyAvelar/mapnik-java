#include "common.hpp"

#include <mapnik/geometry/box2d.hpp>
#include <mapnik/proj_transform.hpp>
#include <mapnik/projection.hpp>

#include <cmath>
#include <memory>
#include <stdexcept>

struct mapnik_projection {
    mapnik::projection projection;
    // epsg:4326 -> this projection. Mapnik's own projection::forward/inverse only work with PROJ
    // strings and give infinity for codes like epsg:3857, so go through a proper transform.
    std::unique_ptr<mapnik::proj_transform> from_geographic;
    explicit mapnik_projection(std::string const& params) : projection(params) {}

    mapnik::proj_transform& geographic() {
        if (!from_geographic) {
            mapnik::projection wgs84("epsg:4326");
            from_geographic.reset(new mapnik::proj_transform(wgs84, projection));
        }
        return *from_geographic;
    }
};

using namespace mc;

namespace {
int fail(const char* what) {
    g_error = what;
    return -1;
}

bool finite(double a, double b) { return std::isfinite(a) && std::isfinite(b); }

int point_op(mapnik::proj_transform& t, double* x, double* y, bool forward) {
    double z = 0;
    bool ok = false;
    int rc = guarded([&] { ok = forward ? t.forward(*x, *y, z) : t.backward(*x, *y, z); });
    if (rc != 0) return rc;
    if (!ok || !finite(*x, *y)) return fail("point could not be transformed");
    return 0;
}

int box_op(mapnik_transform_t* t, double* b, int points, bool forward) {
    return guarded([&] {
        mapnik::box2d<double> box(b[0], b[1], b[2], b[3]);
        bool ok = points > 0 ? (forward ? t->transform.forward(box, static_cast<std::size_t>(points))
                                        : t->transform.backward(box, static_cast<std::size_t>(points)))
                             : (forward ? t->transform.forward(box) : t->transform.backward(box));
        if (!ok) throw std::runtime_error("box could not be transformed");
        if (!finite(box.minx(), box.miny()) || !finite(box.maxx(), box.maxy())) {
            throw std::runtime_error("box could not be transformed");
        }
        write_box(box, b);
    });
}
}  // namespace

extern "C" {

mapnik_projection_t* mapnik_projection_create(const char* params) {
    mapnik_projection_t* p = nullptr;
    guarded([&] { p = new mapnik_projection(params); });
    return p;
}

void mapnik_projection_free(mapnik_projection_t* p) { delete p; }

const char* mapnik_projection_params(mapnik_projection_t* p) { return text(p->projection.params()); }
const char* mapnik_projection_definition(mapnik_projection_t* p) { return text(p->projection.definition()); }
const char* mapnik_projection_description(mapnik_projection_t* p) { return text(p->projection.description()); }
int mapnik_projection_is_geographic(mapnik_projection_t* p) {
    if (p->projection.is_geographic()) return 1;
    // Mapnik only recognises some spellings; a raw PROJ string may say so explicitly.
    std::string const& s = p->projection.params();
    return (s.find("+proj=longlat") != std::string::npos || s.find("+proj=latlong") != std::string::npos) ? 1 : 0;
}

int mapnik_projection_area_of_use(mapnik_projection_t* p, double* out) {
    auto a = p->projection.area_of_use();
    if (!a) return 0;
    write_box(*a, out);
    return 1;
}

int mapnik_projection_forward(mapnik_projection_t* p, double* x, double* y) {
    return point_op(p->geographic(), x, y, true);
}

int mapnik_projection_inverse(mapnik_projection_t* p, double* x, double* y) {
    return point_op(p->geographic(), x, y, false);
}

mapnik_transform_t* mapnik_transform_create(mapnik_projection_t* a, mapnik_projection_t* b) {
    mapnik_transform_t* t = nullptr;
    guarded([&] { t = new mapnik_transform(a->projection, b->projection); });
    return t;
}

void mapnik_transform_free(mapnik_transform_t* t) { delete t; }
int mapnik_transform_is_identity(mapnik_transform_t* t) { return t->transform.equal() ? 1 : 0; }

int mapnik_transform_forward_point(mapnik_transform_t* t, double* x, double* y) {
    return point_op(t->transform, x, y, true);
}

int mapnik_transform_backward_point(mapnik_transform_t* t, double* x, double* y) {
    return point_op(t->transform, x, y, false);
}

int mapnik_transform_forward_box(mapnik_transform_t* t, double* box, int n) { return box_op(t, box, n, true); }
int mapnik_transform_backward_box(mapnik_transform_t* t, double* box, int n) { return box_op(t, box, n, false); }

}  // extern "C"
