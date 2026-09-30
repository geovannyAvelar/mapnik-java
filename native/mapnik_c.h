#ifndef MAPNIK_C_H
#define MAPNIK_C_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct mapnik_map mapnik_map_t;

/* Global setup. Call once. Return 0 on success. */
int  mapnik_register_datasources(const char* dir);
int  mapnik_register_fonts(const char* dir);

/* Map lifecycle. */
mapnik_map_t* mapnik_map_create(int width, int height);
void          mapnik_map_free(mapnik_map_t* m);

/* Return 0 on success, -1 on error. Message via mapnik_last_error(). */
int  mapnik_map_load(mapnik_map_t* m, const char* style_xml_path);
int  mapnik_map_load_string(mapnik_map_t* m, const char* style_xml, const char* base_path);
void mapnik_map_resize(mapnik_map_t* m, int width, int height);
void mapnik_map_zoom_to_box(mapnik_map_t* m, double minx, double miny, double maxx, double maxy);
void mapnik_map_zoom_all(mapnik_map_t* m);

/* Render. */
int  mapnik_map_render_to_file(mapnik_map_t* m, const char* path, const char* format);
/* Allocates *out. Free with mapnik_buffer_free(). */
int  mapnik_map_render_to_buffer(mapnik_map_t* m, const char* format, unsigned char** out, int* len);
void mapnik_buffer_free(unsigned char* buf);

/* Thread-local last error message. Never NULL. */
const char* mapnik_last_error(void);
const char* mapnik_version(void);

#ifdef __cplusplus
}
#endif
#endif
