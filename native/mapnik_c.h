#ifndef MAPNIK_C_H
#define MAPNIK_C_H

/*
 * C API over the parts of Mapnik that mapnik-java wraps.
 *
 * Conventions
 *  - Functions returning int return 0 on success and -1 on error unless noted. The message is in
 *    mapnik_last_error() (thread-local, never NULL).
 *  - Functions returning const char* point into a thread-local buffer that is valid until the next
 *    call on the same thread. Copy immediately.
 *  - A box is written to a caller-provided double[4]: minx, miny, maxx, maxy.
 *  - Objects from *_create are owned by the caller and released with the matching *_free.
 *    mapnik_map_get_layer returns a borrowed pointer into the map: do not free it, and do not use
 *    it after the map's layer list changes (add/remove/load) or the map is freed.
 */

#ifdef __cplusplus
extern "C" {
#endif

typedef struct mapnik_map mapnik_map_t;
typedef struct mapnik_layer mapnik_layer_t;
typedef struct mapnik_params mapnik_params_t;
typedef struct mapnik_datasource mapnik_datasource_t;

/* ---- runtime ------------------------------------------------------------------------------ */

const char* mapnik_last_error(void);
const char* mapnik_version(void);
int         mapnik_version_number(void);

int         mapnik_register_datasources(const char* dir);
int         mapnik_register_fonts(const char* dir);
/* Registered input plugin names, newline separated. */
const char* mapnik_datasource_plugin_names(void);

/* ---- map ---------------------------------------------------------------------------------- */

mapnik_map_t* mapnik_map_create(int width, int height);
void          mapnik_map_free(mapnik_map_t* m);

int  mapnik_map_load(mapnik_map_t* m, const char* style_xml_path);
int  mapnik_map_load_string(mapnik_map_t* m, const char* style_xml, const char* base_path);
/* NULL on error. */
const char* mapnik_map_save_to_string(mapnik_map_t* m, int explicit_defaults);
int  mapnik_map_save(mapnik_map_t* m, const char* path, int explicit_defaults);

int  mapnik_map_width(mapnik_map_t* m);
int  mapnik_map_height(mapnik_map_t* m);
void mapnik_map_resize(mapnik_map_t* m, int width, int height);

const char* mapnik_map_get_srs(mapnik_map_t* m);
int         mapnik_map_set_srs(mapnik_map_t* m, const char* srs);

/* NULL when no background colour is set. Colour strings: "red", "#rrggbb", "rgba(r,g,b,a)". Opaque colours print as "rgb(r,g,b)". */
const char* mapnik_map_get_background(mapnik_map_t* m);
int         mapnik_map_set_background(mapnik_map_t* m, const char* color);
/* NULL when no background image is set. */
const char* mapnik_map_get_background_image(mapnik_map_t* m);
void        mapnik_map_set_background_image(mapnik_map_t* m, const char* path);
double      mapnik_map_get_background_image_opacity(mapnik_map_t* m);
void        mapnik_map_set_background_image_opacity(mapnik_map_t* m, double opacity);

int  mapnik_map_get_buffer_size(mapnik_map_t* m);
void mapnik_map_set_buffer_size(mapnik_map_t* m, int size);

/* Returns 1 and fills out if a maximum extent is set, else 0. */
int  mapnik_map_get_maximum_extent(mapnik_map_t* m, double* out);
void mapnik_map_set_maximum_extent(mapnik_map_t* m, double minx, double miny, double maxx, double maxy);
void mapnik_map_reset_maximum_extent(mapnik_map_t* m);

const char* mapnik_map_get_base_path(mapnik_map_t* m);
void        mapnik_map_set_base_path(mapnik_map_t* m, const char* path);

/* Aspect fix mode: index into GROW_BBOX, GROW_CANVAS, SHRINK_BBOX, SHRINK_CANVAS,
 * ADJUST_BBOX_WIDTH, ADJUST_BBOX_HEIGHT, ADJUST_CANVAS_WIDTH, ADJUST_CANVAS_HEIGHT, RESPECT. */
int  mapnik_map_get_aspect_fix_mode(mapnik_map_t* m);
void mapnik_map_set_aspect_fix_mode(mapnik_map_t* m, int mode);

void   mapnik_map_zoom(mapnik_map_t* m, double factor);
void   mapnik_map_zoom_to_box(mapnik_map_t* m, double minx, double miny, double maxx, double maxy);
void   mapnik_map_zoom_all(mapnik_map_t* m);
void   mapnik_map_pan(mapnik_map_t* m, int x, int y);
void   mapnik_map_pan_and_zoom(mapnik_map_t* m, int x, int y, double zoom);
void   mapnik_map_get_current_extent(mapnik_map_t* m, double* out);
void   mapnik_map_get_buffered_extent(mapnik_map_t* m, double* out);
double mapnik_map_scale(mapnik_map_t* m);
double mapnik_map_scale_denominator(mapnik_map_t* m);

void mapnik_map_remove_all(mapnik_map_t* m);

int         mapnik_map_layer_count(mapnik_map_t* m);
/* Borrowed. NULL for a bad index. */
mapnik_layer_t* mapnik_map_get_layer(mapnik_map_t* m, int index);
/* Copies the layer into the map. */
int         mapnik_map_add_layer(mapnik_map_t* m, mapnik_layer_t* layer);
int         mapnik_map_remove_layer(mapnik_map_t* m, int index);

int         mapnik_map_style_count(mapnik_map_t* m);
const char* mapnik_map_style_name(mapnik_map_t* m, int index);
void        mapnik_map_remove_style(mapnik_map_t* m, const char* name);

int mapnik_map_register_fonts(mapnik_map_t* m, const char* dir, int recurse);
int mapnik_map_load_fonts(mapnik_map_t* m);

/* ---- layer -------------------------------------------------------------------------------- */

mapnik_layer_t* mapnik_layer_create(const char* name, const char* srs);
void            mapnik_layer_free(mapnik_layer_t* l);

const char* mapnik_layer_get_name(mapnik_layer_t* l);
void        mapnik_layer_set_name(mapnik_layer_t* l, const char* name);
const char* mapnik_layer_get_srs(mapnik_layer_t* l);
void        mapnik_layer_set_srs(mapnik_layer_t* l, const char* srs);

void        mapnik_layer_add_style(mapnik_layer_t* l, const char* style_name);
int         mapnik_layer_style_count(mapnik_layer_t* l);
const char* mapnik_layer_style_name(mapnik_layer_t* l, int index);

double mapnik_layer_get_minimum_scale_denominator(mapnik_layer_t* l);
void   mapnik_layer_set_minimum_scale_denominator(mapnik_layer_t* l, double v);
double mapnik_layer_get_maximum_scale_denominator(mapnik_layer_t* l);
void   mapnik_layer_set_maximum_scale_denominator(mapnik_layer_t* l, double v);
int    mapnik_layer_visible(mapnik_layer_t* l, double scale_denominator);

int  mapnik_layer_get_active(mapnik_layer_t* l);
void mapnik_layer_set_active(mapnik_layer_t* l, int v);
int  mapnik_layer_get_queryable(mapnik_layer_t* l);
void mapnik_layer_set_queryable(mapnik_layer_t* l, int v);
int  mapnik_layer_get_clear_label_cache(mapnik_layer_t* l);
void mapnik_layer_set_clear_label_cache(mapnik_layer_t* l, int v);
int  mapnik_layer_get_cache_features(mapnik_layer_t* l);
void mapnik_layer_set_cache_features(mapnik_layer_t* l, int v);

const char* mapnik_layer_get_group_by(mapnik_layer_t* l);
void        mapnik_layer_set_group_by(mapnik_layer_t* l, const char* column);

double mapnik_layer_get_opacity(mapnik_layer_t* l);
void   mapnik_layer_set_opacity(mapnik_layer_t* l, double v);

/* Returns 1 and fills *out if a buffer size is set, else 0. */
int  mapnik_layer_get_buffer_size(mapnik_layer_t* l, int* out);
void mapnik_layer_set_buffer_size(mapnik_layer_t* l, int size);
void mapnik_layer_reset_buffer_size(mapnik_layer_t* l);

int  mapnik_layer_get_maximum_extent(mapnik_layer_t* l, double* out);
void mapnik_layer_set_maximum_extent(mapnik_layer_t* l, double minx, double miny, double maxx, double maxy);
void mapnik_layer_reset_maximum_extent(mapnik_layer_t* l);

/* The layer keeps its own reference; free your datasource handle independently. */
void                 mapnik_layer_set_datasource(mapnik_layer_t* l, mapnik_datasource_t* ds);
/* New handle, or NULL if the layer has no datasource. */
mapnik_datasource_t* mapnik_layer_get_datasource(mapnik_layer_t* l);
/* Extent of the layer's data. Error if the layer has no datasource. */
int                  mapnik_layer_envelope(mapnik_layer_t* l, double* out);

/* ---- datasource --------------------------------------------------------------------------- */

mapnik_params_t* mapnik_params_create(void);
void             mapnik_params_free(mapnik_params_t* p);
void             mapnik_params_set_string(mapnik_params_t* p, const char* key, const char* value);
void             mapnik_params_set_int(mapnik_params_t* p, const char* key, long long value);
void             mapnik_params_set_double(mapnik_params_t* p, const char* key, double value);
void             mapnik_params_set_bool(mapnik_params_t* p, const char* key, int value);

/* Needs the plugin named by the "type" parameter to be registered. NULL on error. */
mapnik_datasource_t* mapnik_datasource_create(mapnik_params_t* p);
void                 mapnik_datasource_free(mapnik_datasource_t* ds);

/* 0 vector, 1 raster. */
int  mapnik_datasource_type(mapnik_datasource_t* ds);
/* 0 unknown, 1 point, 2 linestring, 3 polygon, 4 collection. */
int  mapnik_datasource_geometry_type(mapnik_datasource_t* ds);
int  mapnik_datasource_envelope(mapnik_datasource_t* ds, double* out);

int         mapnik_datasource_field_count(mapnik_datasource_t* ds);
const char* mapnik_datasource_field_name(mapnik_datasource_t* ds, int index);
/* 1 integer, 2 float, 3 double, 4 string, 5 boolean, 6 geometry, 7 object. */
int         mapnik_datasource_field_type(mapnik_datasource_t* ds, int index);

/* ---- render ------------------------------------------------------------------------------- */

int  mapnik_map_render_to_file(mapnik_map_t* m, const char* path, const char* format);
/* Allocates *out. Free with mapnik_buffer_free(). */
int  mapnik_map_render_to_buffer(mapnik_map_t* m, const char* format, unsigned char** out, int* len);
void mapnik_buffer_free(unsigned char* buf);

#ifdef __cplusplus
}
#endif
#endif
