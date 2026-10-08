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
typedef struct mapnik_featureset mapnik_featureset_t;
typedef struct mapnik_feature mapnik_feature_t;
typedef struct mapnik_projection mapnik_projection_t;
typedef struct mapnik_transform mapnik_transform_t;
typedef struct mapnik_image mapnik_image_t;
typedef struct mapnik_gray mapnik_gray_t;
typedef struct mapnik_feature_builder mapnik_feature_builder_t;
typedef struct mapnik_expression mapnik_expression_t;
typedef struct mapnik_path_expression mapnik_path_expression_t;
typedef struct mapnik_value mapnik_value_t;

/* ---- runtime ------------------------------------------------------------------------------ */

const char* mapnik_last_error(void);
const char* mapnik_version(void);
int         mapnik_version_number(void);

/* Point PROJ at its data directory (proj.db and friends), for a bundled copy. Call before anything
 * creates a projection. */
int         mapnik_set_environment(const char* proj_data_dir);
/* Windows: add a directory to the places DLLs are searched for, so that plugins find their libraries
 * there. Does nothing elsewhere. Returns 0, or -1 with the error set. */
int         mapnik_set_library_directory(const char* dir);
int         mapnik_register_datasources(const char* dir);
int         mapnik_register_fonts(const char* dir);
/* Registered input plugin names, newline separated. */
const char* mapnik_datasource_plugin_names(void);

double      mapnik_scale_denominator(double map_units_per_pixel, int geographic);

/* Colour strings: a name, "#rrggbb", "#rrggbbaa" or "rgba(r,g,b,a)" with a from 0 to 1.
 * Parse writes r, g, b, a as four bytes into out. */
int         mapnik_color_parse(const char* text, unsigned char* out);
/* Mapnik's own spelling: "rgb(255,0,0)" or "rgba(255,0,0,0.5)". */
const char* mapnik_color_to_string(int r, int g, int b, int a);
/* "#rrggbb", or "#rrggbbaa" when not opaque. */
const char* mapnik_color_to_hex(int r, int g, int b, int a);

/* What this Mapnik was built with, as newline separated names: cairo, jpeg, png, tiff, webp, proj,
 * grid, threadsafe, logging. */
const char* mapnik_capabilities(void);

/* Logging. Severity: 0 debug, 1 warn, 2 error, 3 none. */
int         mapnik_log_get_severity(void);
void        mapnik_log_set_severity(int severity);
/* Severity for one named part of Mapnik, which falls back to the global one. */
int         mapnik_log_get_object_severity(const char* object);
void        mapnik_log_set_object_severity(const char* object, int severity);
void        mapnik_log_clear_object_severities(void);
const char* mapnik_log_get_format(void);
void        mapnik_log_set_format(const char* format);
int         mapnik_log_use_file(const char* path);
void        mapnik_log_use_console(void);

/* Fonts registered process-wide. Face names are newline separated. */
const char* mapnik_font_face_names(void);
/* File holding a face, or NULL if the face is unknown. */
const char* mapnik_font_face_file(const char* face);
/* 0 on success, -1 if the file is not a font Mapnik can read. */
int         mapnik_register_font_file(const char* path);

/* Register one input plugin file. 0 on success, -1 on error. */
int         mapnik_register_datasource_file(const char* path);
const char* mapnik_datasource_plugin_directories(void);
int         mapnik_datasource_plugin_registered(const char* name);

/* Drop cached marker images and memory-mapped files. */
void        mapnik_clear_caches(void);

/* ---- map ---------------------------------------------------------------------------------- */

mapnik_map_t* mapnik_map_create(int width, int height);
void          mapnik_map_free(mapnik_map_t* m);

/* strict != 0 makes Mapnik's XML loader stricter: it reports problems it would otherwise skip. */
int  mapnik_map_load(mapnik_map_t* m, const char* style_xml_path, int strict);
int  mapnik_map_load_string(mapnik_map_t* m, const char* style_xml, const char* base_path, int strict);
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

/* Blend mode names as in the XML, e.g. "multiply". NULL if unset. -1 from the setter for an unknown name. */
const char* mapnik_map_get_background_image_comp_op(mapnik_map_t* m);
int         mapnik_map_set_background_image_comp_op(mapnik_map_t* m, const char* name);

const char* mapnik_map_get_font_directory(mapnik_map_t* m);  /* NULL if unset */
void        mapnik_map_set_font_directory(mapnik_map_t* m, const char* dir);

/* Extra parameters: arbitrary key/value pairs stored on the map, written as <Parameters> in XML.
 * By index in key order. Types: 0 null, 1 boolean, 2 integer, 3 double, 4 string, -1 bad index. */
int         mapnik_map_param_count(mapnik_map_t* m);
const char* mapnik_map_param_name(mapnik_map_t* m, int index);
int         mapnik_map_param_type(mapnik_map_t* m, int index);
int         mapnik_map_param_bool(mapnik_map_t* m, int index);
long long   mapnik_map_param_int(mapnik_map_t* m, int index);
double      mapnik_map_param_double(mapnik_map_t* m, int index);
const char* mapnik_map_param_string(mapnik_map_t* m, int index);
void        mapnik_map_set_param_string(mapnik_map_t* m, const char* key, const char* value);
void        mapnik_map_set_param_int(mapnik_map_t* m, const char* key, long long value);
void        mapnik_map_set_param_double(mapnik_map_t* m, const char* key, double value);
void        mapnik_map_set_param_bool(mapnik_map_t* m, const char* key, int value);
void        mapnik_map_remove_param(mapnik_map_t* m, const char* key);

/* Convert between map coordinates (in the map's projection) and pixels of the rendered image.
 * In place. The map needs an extent, e.g. from zoom_to_box. */
void        mapnik_map_world_to_pixel(mapnik_map_t* m, double* x, double* y);
void        mapnik_map_pixel_to_world(mapnik_map_t* m, double* x, double* y);

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

/* Blend mode name, e.g. "multiply". NULL if unset. -1 from the setter for an unknown name. */
const char* mapnik_layer_get_comp_op(mapnik_layer_t* l);
int         mapnik_layer_set_comp_op(mapnik_layer_t* l, const char* name);

/* Child layers. add_child copies. child_copy returns a new layer to free, or NULL for a bad index. */
void            mapnik_layer_add_child(mapnik_layer_t* parent, mapnik_layer_t* child);
int             mapnik_layer_child_count(mapnik_layer_t* l);
mapnik_layer_t* mapnik_layer_child_copy(mapnik_layer_t* l, int index);

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

const char* mapnik_datasource_layer_name(mapnik_datasource_t* ds);
const char* mapnik_datasource_encoding(mapnik_datasource_t* ds);

/* The parameters the datasource was created with, by index in key order. Types as for map parameters. */
int         mapnik_datasource_param_count(mapnik_datasource_t* ds);
const char* mapnik_datasource_param_name(mapnik_datasource_t* ds, int index);
int         mapnik_datasource_param_type(mapnik_datasource_t* ds, int index);
int         mapnik_datasource_param_bool(mapnik_datasource_t* ds, int index);
long long   mapnik_datasource_param_int(mapnik_datasource_t* ds, int index);
double      mapnik_datasource_param_double(mapnik_datasource_t* ds, int index);
const char* mapnik_datasource_param_string(mapnik_datasource_t* ds, int index);

int         mapnik_datasource_field_count(mapnik_datasource_t* ds);
const char* mapnik_datasource_field_name(mapnik_datasource_t* ds, int index);
/* 1 integer, 2 float, 3 double, 4 string, 5 boolean, 6 geometry, 7 object. */
int         mapnik_datasource_field_type(mapnik_datasource_t* ds, int index);

/* ---- features ----------------------------------------------------------------------------- */

/* Query a datasource. Resolution is in pixels per map unit on each axis; property_names may be NULL.
 * NULL on error. */
mapnik_featureset_t* mapnik_datasource_features(mapnik_datasource_t* ds,
                                                double minx, double miny, double maxx, double maxy,
                                                double resolution_x, double resolution_y, double scale_denominator,
                                                const char* const* property_names, int property_count);
mapnik_featureset_t* mapnik_datasource_features_at_point(mapnik_datasource_t* ds, double x, double y, double tolerance);
/* Features of a layer at a point in map coordinates, or at a pixel. NULL on error. */
mapnik_featureset_t* mapnik_map_query_point(mapnik_map_t* m, int layer_index, double x, double y);
mapnik_featureset_t* mapnik_map_query_map_point(mapnik_map_t* m, int layer_index, double px, double py);

void mapnik_featureset_free(mapnik_featureset_t* fs);
/* Next feature into *out: 1 got one, 0 no more, -1 error. Free it with mapnik_feature_free. */
int  mapnik_featureset_next(mapnik_featureset_t* fs, mapnik_feature_t** out);

void      mapnik_feature_free(mapnik_feature_t* f);
long long mapnik_feature_id(mapnik_feature_t* f);

/* Attributes by index, in name order. */
int         mapnik_feature_attribute_count(mapnik_feature_t* f);
const char* mapnik_feature_attribute_name(mapnik_feature_t* f, int index);
/* 0 null, 1 boolean, 2 integer, 3 double, 4 string, -1 bad index. */
int         mapnik_feature_attribute_type(mapnik_feature_t* f, int index);
int         mapnik_feature_attribute_bool(mapnik_feature_t* f, int index);
long long   mapnik_feature_attribute_int(mapnik_feature_t* f, int index);
double      mapnik_feature_attribute_double(mapnik_feature_t* f, int index);
/* The value as text, whatever its type. NULL for a bad index. */
const char* mapnik_feature_attribute_string(mapnik_feature_t* f, int index);

/* 0 unknown, 1 point, 2 linestring, 3 polygon, 4 multipoint, 5 multilinestring, 6 multipolygon,
 * 7 geometry collection. */
int         mapnik_feature_geometry_type(mapnik_feature_t* f);
int         mapnik_feature_envelope(mapnik_feature_t* f, double* out);
/* NULL on error. */
const char* mapnik_feature_geometry_wkt(mapnik_feature_t* f);
const char* mapnik_feature_geometry_geojson(mapnik_feature_t* f);
/* The whole feature (geometry and attributes) as a GeoJSON Feature. NULL on error. */
const char* mapnik_feature_to_geojson(mapnik_feature_t* f);

/* ---- building features, memory datasource ------------------------------------------------- */

/* A feature under construction. Give it a geometry (2D WKB) and attributes, then push it into a
 * memory datasource. Setting a name twice keeps the last value. */
mapnik_feature_builder_t* mapnik_feature_builder_create(long long id);
void mapnik_feature_builder_free(mapnik_feature_builder_t* b);
int  mapnik_feature_builder_set_geometry_wkb(mapnik_feature_builder_t* b, const unsigned char* wkb, int length);
void mapnik_feature_builder_put_null(mapnik_feature_builder_t* b, const char* key);
void mapnik_feature_builder_put_string(mapnik_feature_builder_t* b, const char* key, const char* value);
void mapnik_feature_builder_put_int(mapnik_feature_builder_t* b, const char* key, long long value);
void mapnik_feature_builder_put_double(mapnik_feature_builder_t* b, const char* key, double value);
void mapnik_feature_builder_put_bool(mapnik_feature_builder_t* b, const char* key, int value);

/* Variables are what @name means in an expression. They are not attributes of the feature. */
void mapnik_feature_builder_put_var_null(mapnik_feature_builder_t* b, const char* key);
void mapnik_feature_builder_put_var_string(mapnik_feature_builder_t* b, const char* key, const char* value);
void mapnik_feature_builder_put_var_int(mapnik_feature_builder_t* b, const char* key, long long value);
void mapnik_feature_builder_put_var_double(mapnik_feature_builder_t* b, const char* key, double value);
void mapnik_feature_builder_put_var_bool(mapnik_feature_builder_t* b, const char* key, int value);

/* A datasource that holds features in memory. NULL on error. Use it like any datasource. */
mapnik_datasource_t* mapnik_memory_datasource_create(void);
/* Copies the feature out of the builder. The builder can be reused or freed. */
int  mapnik_memory_datasource_push(mapnik_datasource_t* ds, mapnik_feature_builder_t* b);
int  mapnik_memory_datasource_size(mapnik_datasource_t* ds);
void mapnik_memory_datasource_clear(mapnik_datasource_t* ds);
void mapnik_memory_datasource_set_envelope(mapnik_datasource_t* ds, double minx, double miny, double maxx, double maxy);

/* ---- expressions ---------------------------------------------------------------------------- */

/* Mapnik's expression language, as used in filters: "[population] > 1000 and [kind] = 'city'".
 * parse returns NULL on a syntax error, with Mapnik's message in mapnik_last_error(). */
mapnik_expression_t* mapnik_expression_parse(const char* text);
void                 mapnik_expression_free(mapnik_expression_t* e);
/* Evaluate against a feature and variables held by a builder. NULL on error. Free with mapnik_value_free. */
mapnik_value_t*      mapnik_expression_evaluate(mapnik_expression_t* e, mapnik_feature_builder_t* b);
/* 1 if the result is true (as a filter sees it), 0 if false, -1 on error. */
int                  mapnik_expression_test(mapnik_expression_t* e, mapnik_feature_builder_t* b);

void        mapnik_value_free(mapnik_value_t* v);
/* 0 null, 1 boolean, 2 integer, 3 double, 4 string. */
int         mapnik_value_type(mapnik_value_t* v);
int         mapnik_value_bool(mapnik_value_t* v);
long long   mapnik_value_int(mapnik_value_t* v);
double      mapnik_value_double(mapnik_value_t* v);
const char* mapnik_value_string(mapnik_value_t* v);

/* A text pattern with attributes in brackets, such as "icons/[type].png". */
mapnik_path_expression_t* mapnik_path_expression_parse(const char* text);
void                      mapnik_path_expression_free(mapnik_path_expression_t* p);
/* NULL on error. */
const char*               mapnik_path_expression_evaluate(mapnik_path_expression_t* p, mapnik_feature_builder_t* b);

/* Check that an SVG-style transform such as "translate(10,20) rotate(45)" parses. 0 if it does, -1 if not,
 * with Mapnik's message in mapnik_last_error(). */
int mapnik_transform_check(const char* text);

/* ---- geometry operations -------------------------------------------------------------------- */

/* Each takes a geometry as 2D WKB. Operations that return a geometry return it as WKT, or NULL on
 * error. Scalars go in caller arrays. All return 0 or a valid result on success and -1 on error unless
 * noted. */
int         mapnik_geometry_centroid(const unsigned char* wkb, int length, double* out_xy);
/* For a polygon. scale_factor is polylabel's precision as a fraction of the polygon's size (try 1). */
int         mapnik_geometry_interior_point(const unsigned char* wkb, int length, double scale_factor, double* out_xy);
/* out_xyd receives the closest point's x and y, then its distance from (x, y). */
int         mapnik_geometry_closest_point(const unsigned char* wkb, int length, double x, double y, double* out_xyd);
/* 1 valid, 0 invalid, -1 error. The reason is in mapnik_geometry_validity_reason. */
int         mapnik_geometry_is_valid(const unsigned char* wkb, int length);
const char* mapnik_geometry_validity_reason(const unsigned char* wkb, int length);
/* 1 simple, 0 not, -1 error. */
int         mapnik_geometry_is_simple(const unsigned char* wkb, int length);
/* Fix ring orientation and closure. */
const char* mapnik_geometry_correct(const unsigned char* wkb, int length);
/* algorithm: radial-distance, douglas-peucker, visvalingam-whyatt or zhao-saalfeld. */
const char* mapnik_geometry_simplify(const unsigned char* wkb, int length, const char* algorithm, double tolerance);
/* Parallel copy of lines at a distance: positive to the left, negative to the right. */
const char* mapnik_geometry_offset(const unsigned char* wkb, int length, double distance);
/* In the transform's forward direction. Error if any point cannot be transformed. */
const char* mapnik_geometry_reproject(const unsigned char* wkb, int length, mapnik_transform_t* transform);

/* ---- datasource implemented in Java ---------------------------------------------------------- */

/* One pair of handlers is registered for the whole process. Each Java datasource has an id that they get
 * back. The features callback fills *out with a buffer it keeps alive until its next call on the same
 * thread, and returns its length in *len (0 or more) and 0 on success. On failure it returns non-zero
 * and *out is an error message in UTF-8 of *len bytes. Buffer layout, little endian:
 *   u32 count, then per feature:
 *     i64 id, u32 wkb_length, wkb bytes (2D), u32 attribute_count, then per attribute:
 *       u32 name_length, name bytes, u8 type (0 null, 1 bool, 2 int, 3 double, 4 string),
 *       then u8 / i64 / f64 / (u32 length, bytes) for bool, int, double, string. */
typedef int  (*mapnik_java_features_fn)(long long id, double minx, double miny, double maxx, double maxy,
                                        double resolution_x, double resolution_y, double scale_denominator,
                                        unsigned char** out, int* len);
/* Called when Mapnik destroys the datasource, so Java can forget the id. */
typedef void (*mapnik_java_release_fn)(long long id);

void mapnik_java_set_handlers(mapnik_java_features_fn features, mapnik_java_release_fn release);
/* envelope is double[4]. fields are optional (may be NULL with count 0); types as for datasource fields. */
mapnik_datasource_t* mapnik_java_datasource_create(long long id, const double* envelope,
                                                   const char* const* field_names, const int* field_types,
                                                   int field_count);
void mapnik_java_datasource_set_envelope(mapnik_datasource_t* ds, double minx, double miny, double maxx, double maxy);

/* ---- projections -------------------------------------------------------------------------- */

/* params is anything Mapnik accepts: "epsg:3857", "+proj=utm +zone=33 ...". NULL on error. */
mapnik_projection_t* mapnik_projection_create(const char* params);
void                 mapnik_projection_free(mapnik_projection_t* p);

const char* mapnik_projection_params(mapnik_projection_t* p);
const char* mapnik_projection_definition(mapnik_projection_t* p);
const char* mapnik_projection_description(mapnik_projection_t* p);
int         mapnik_projection_is_geographic(mapnik_projection_t* p);
/* Returns 1 and fills out if the area of use is known, else 0. */
int         mapnik_projection_area_of_use(mapnik_projection_t* p, double* out);
/* In place. forward: geographic (lon, lat) to projected; inverse: projected to geographic. */
int         mapnik_projection_forward(mapnik_projection_t* p, double* x, double* y);
int         mapnik_projection_inverse(mapnik_projection_t* p, double* x, double* y);

/* A transform between two projections. The projections are copied; free yours independently. */
mapnik_transform_t* mapnik_transform_create(mapnik_projection_t* source, mapnik_projection_t* dest);
void                mapnik_transform_free(mapnik_transform_t* t);
int                 mapnik_transform_is_identity(mapnik_transform_t* t);
/* In place. forward: source to destination; backward: destination to source. 0 ok, -1 failed. */
int                 mapnik_transform_forward_point(mapnik_transform_t* t, double* x, double* y);
int                 mapnik_transform_backward_point(mapnik_transform_t* t, double* x, double* y);
/* box is double[4], in place. densify_points > 0 also samples that many points along each edge. */
int                 mapnik_transform_forward_box(mapnik_transform_t* t, double* box, int densify_points);
int                 mapnik_transform_backward_box(mapnik_transform_t* t, double* box, int densify_points);

/* ---- images ------------------------------------------------------------------------------- */

/* An RGBA image. Pixels are straight (not premultiplied) 32-bit values: red in the low byte, then
 * green, blue, alpha in the high byte. NULL on error for the constructors. */
mapnik_image_t* mapnik_image_create(int width, int height);
mapnik_image_t* mapnik_image_load_file(const char* path);
mapnik_image_t* mapnik_image_load_bytes(const unsigned char* data, int length);
void            mapnik_image_free(mapnik_image_t* img);

int          mapnik_image_width(mapnik_image_t* img);
int          mapnik_image_height(mapnik_image_t* img);
/* Bad coordinates: get returns 0, set returns -1. */
unsigned int mapnik_image_get_pixel(mapnik_image_t* img, int x, int y);
int          mapnik_image_set_pixel(mapnik_image_t* img, int x, int y, unsigned int rgba);
int          mapnik_image_fill(mapnik_image_t* img, const char* color);

/* ---- single-band images ------------------------------------------------------------------- */

/* One number per pixel. type is Mapnik's pixel type: 1 gray8, 2 gray8s, 3 gray16, 4 gray16s,
 * 5 gray32, 6 gray32s, 7 gray32f, 8 gray64, 9 gray64s, 10 gray64f. Values pass as doubles; one
 * that does not fit the type is refused (-1), never wrapped. Constructors return NULL on error. */
mapnik_gray_t* mapnik_gray_create(int type, int width, int height, double initial);
void           mapnik_gray_free(mapnik_gray_t* g);
int            mapnik_gray_width(mapnik_gray_t* g);
int            mapnik_gray_height(mapnik_gray_t* g);
int            mapnik_gray_type(mapnik_gray_t* g);
int            mapnik_gray_get(mapnik_gray_t* g, int x, int y, double* out);
int            mapnik_gray_set(mapnik_gray_t* g, int x, int y, double value);
/* Bulk access, row by row from the top left; count must be width * height. write is all or nothing. */
int            mapnik_gray_read(mapnik_gray_t* g, double* out, long long count);
int            mapnik_gray_write(mapnik_gray_t* g, const double* in, long long count);
/* Smallest and largest value, skipping NaN and, if has_nodata, the nodata value. */
int            mapnik_gray_range(mapnik_gray_t* g, int has_nodata, double nodata, double* out_min_max);
/* Colour by stops: modes are 0 discrete, 1 linear, 2 exact, 3 the colorizer's default. Returns a new
 * RGBA image, or NULL on error. */
mapnik_image_t* mapnik_gray_colorize(mapnik_gray_t* g, const double* values, const int* rgba,
                                     const int* modes, int stops, int default_mode, int default_rgba,
                                     double epsilon, int has_nodata, double nodata);
int          mapnik_image_is_solid(mapnik_image_t* img);
/* Copies width*height*4 bytes (r, g, b, a per pixel, row by row) into out. */
void         mapnik_image_copy_rgba(mapnik_image_t* img, unsigned char* out);

int  mapnik_image_save(mapnik_image_t* img, const char* path, const char* format);
/* Allocates *out. Free with mapnik_buffer_free(). */
int  mapnik_image_save_to_buffer(mapnik_image_t* img, const char* format, unsigned char** out, int* len);

/* ---- image operations ----------------------------------------------------------------------- */

/* Apply filters, in place, with the syntax of the image-filters style attribute, for example
 * "blur", "agg-stack-blur(5,5)", "invert gray". scale_factor scales sizes inside filters. */
int  mapnik_image_filter(mapnik_image_t* img, const char* filters, double scale_factor);
/* Draw src onto dst at (dx, dy) with a blend mode name such as "src-over" or "multiply". */
int  mapnik_image_composite(mapnik_image_t* dst, mapnik_image_t* src, const char* mode, double opacity, int dx, int dy);
/* New image. method is a name such as "bilinear" or "lanczos". NULL on error. */
mapnik_image_t* mapnik_image_scale(mapnik_image_t* img, int width, int height, const char* method);
mapnik_image_t* mapnik_image_crop(mapnik_image_t* img, int x, int y, int width, int height);
mapnik_image_t* mapnik_image_copy(mapnik_image_t* img);
/* Multiply every alpha by opacity (0 to 1), in place. */
int  mapnik_image_apply_opacity(mapnik_image_t* img, double opacity);
/* Make pixels of this colour transparent, blending partly matching ones, in place. */
int  mapnik_image_color_to_alpha(mapnik_image_t* img, const char* color);
/* Reproject a raster. source_extent is where the image sits in source_srs; the result covers
 * target_extent in target_srs at width x height pixels. Extents are double[4]. mesh_size is the
 * size in pixels of the grid the warp is approximated on (16 is Mapnik's default). NULL on error. */
mapnik_image_t* mapnik_image_warp(mapnik_image_t* img, const char* source_srs, const double* source_extent,
                                  const char* target_srs, const double* target_extent,
                                  int width, int height, int mesh_size, const char* method);

/* Size of an image file or of encoded data, without decoding the pixels. */
int  mapnik_image_probe_file(const char* path, int* width, int* height);
int  mapnik_image_probe_bytes(const unsigned char* data, int length, int* width, int* height);

/* ---- render ------------------------------------------------------------------------------- */

/* scale_factor scales line widths, symbols and text (1.0 is normal; 2.0 suits a high-dpi image).
 * offset_x and offset_y make the image a window starting at that pixel of a larger map image: the
 * drawing moves left by offset_x and up by offset_y. */
int  mapnik_map_render_to_file(mapnik_map_t* m, const char* path, const char* format,
                               double scale_factor, int offset_x, int offset_y);
/* Allocates *out. Free with mapnik_buffer_free(). */
int  mapnik_map_render_to_buffer(mapnik_map_t* m, const char* format, unsigned char** out, int* len,
                                 double scale_factor, int offset_x, int offset_y);
/* Draws onto an existing image, which must be the same size as the map. */
int  mapnik_map_render_to_image(mapnik_map_t* m, mapnik_image_t* img,
                                double scale_factor, int offset_x, int offset_y);
/* Vector output through Cairo. type is "pdf", "svg" or "ps". Fails if Mapnik has no Cairo support. */
int  mapnik_map_render_to_cairo_file(mapnik_map_t* m, const char* path, const char* type, double scale_factor);
int  mapnik_cairo_available(void);
/* UTFGrid: an interactive-map companion to an image that says which feature is under each cell. Renders
 * one layer and returns the grid as JSON ({"grid":[...],"keys":[...],"data":{...}}), or NULL on error
 * (including a Mapnik without the grid renderer). key is "__id__" or an attribute whose value names each
 * feature; fields are the attributes to put in "data". resolution is pixels per cell (4 is usual). */
const char* mapnik_map_render_grid(mapnik_map_t* m, int layer_index, const char* key,
                                   const char* const* fields, int field_count, int resolution,
                                   double scale_factor, int offset_x, int offset_y);
void mapnik_buffer_free(unsigned char* buf);

#ifdef __cplusplus
}
#endif
#endif
