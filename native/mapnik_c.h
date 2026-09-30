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

/* ---- runtime ------------------------------------------------------------------------------ */

const char* mapnik_last_error(void);
const char* mapnik_version(void);
int         mapnik_version_number(void);

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
 * grid, threadsafe. */
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
void mapnik_buffer_free(unsigned char* buf);

#ifdef __cplusplus
}
#endif
#endif
