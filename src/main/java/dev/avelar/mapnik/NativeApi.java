package dev.avelar.mapnik;

import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

/** Raw JNA mapping of native/mapnik_c.h. Use the classes in this package instead. */
interface NativeApi extends Library {
    NativeApi INSTANCE = NativeLoader.load();

    // runtime
    String mapnik_last_error();
    String mapnik_version();
    int mapnik_version_number();
    int mapnik_set_environment(String projDataDir);
    int mapnik_register_datasources(String dir);
    int mapnik_register_fonts(String dir);
    String mapnik_datasource_plugin_names();
    double mapnik_scale_denominator(double mapUnitsPerPixel, int geographic);
    int mapnik_color_parse(String text, byte[] out);
    String mapnik_color_to_string(int r, int g, int b, int a);
    String mapnik_color_to_hex(int r, int g, int b, int a);
    String mapnik_capabilities();
    int mapnik_log_get_severity();
    void mapnik_log_set_severity(int severity);
    int mapnik_log_get_object_severity(String object);
    void mapnik_log_set_object_severity(String object, int severity);
    void mapnik_log_clear_object_severities();
    String mapnik_log_get_format();
    void mapnik_log_set_format(String format);
    int mapnik_log_use_file(String path);
    void mapnik_log_use_console();
    String mapnik_font_face_names();
    String mapnik_font_face_file(String face);
    int mapnik_register_font_file(String path);
    int mapnik_register_datasource_file(String path);
    String mapnik_datasource_plugin_directories();
    int mapnik_datasource_plugin_registered(String name);
    void mapnik_clear_caches();

    // map
    Pointer mapnik_map_create(int width, int height);
    void mapnik_map_free(Pointer map);
    int mapnik_map_load(Pointer map, String path, int strict);
    int mapnik_map_load_string(Pointer map, String xml, String basePath, int strict);
    String mapnik_map_save_to_string(Pointer map, int explicitDefaults);
    int mapnik_map_save(Pointer map, String path, int explicitDefaults);
    int mapnik_map_width(Pointer map);
    int mapnik_map_height(Pointer map);
    void mapnik_map_resize(Pointer map, int width, int height);
    String mapnik_map_get_srs(Pointer map);
    int mapnik_map_set_srs(Pointer map, String srs);
    String mapnik_map_get_background(Pointer map);
    int mapnik_map_set_background(Pointer map, String color);
    String mapnik_map_get_background_image(Pointer map);
    void mapnik_map_set_background_image(Pointer map, String path);
    double mapnik_map_get_background_image_opacity(Pointer map);
    void mapnik_map_set_background_image_opacity(Pointer map, double opacity);
    String mapnik_map_get_background_image_comp_op(Pointer map);
    int mapnik_map_set_background_image_comp_op(Pointer map, String name);
    String mapnik_map_get_font_directory(Pointer map);
    void mapnik_map_set_font_directory(Pointer map, String dir);
    int mapnik_map_param_count(Pointer map);
    String mapnik_map_param_name(Pointer map, int index);
    int mapnik_map_param_type(Pointer map, int index);
    int mapnik_map_param_bool(Pointer map, int index);
    long mapnik_map_param_int(Pointer map, int index);
    double mapnik_map_param_double(Pointer map, int index);
    String mapnik_map_param_string(Pointer map, int index);
    void mapnik_map_set_param_string(Pointer map, String key, String value);
    void mapnik_map_set_param_int(Pointer map, String key, long value);
    void mapnik_map_set_param_double(Pointer map, String key, double value);
    void mapnik_map_set_param_bool(Pointer map, String key, int value);
    void mapnik_map_remove_param(Pointer map, String key);
    void mapnik_map_world_to_pixel(Pointer map, double[] x, double[] y);
    void mapnik_map_pixel_to_world(Pointer map, double[] x, double[] y);
    int mapnik_map_get_buffer_size(Pointer map);
    void mapnik_map_set_buffer_size(Pointer map, int size);
    int mapnik_map_get_maximum_extent(Pointer map, double[] out);
    void mapnik_map_set_maximum_extent(Pointer map, double minx, double miny, double maxx, double maxy);
    void mapnik_map_reset_maximum_extent(Pointer map);
    String mapnik_map_get_base_path(Pointer map);
    void mapnik_map_set_base_path(Pointer map, String path);
    int mapnik_map_get_aspect_fix_mode(Pointer map);
    void mapnik_map_set_aspect_fix_mode(Pointer map, int mode);
    void mapnik_map_zoom(Pointer map, double factor);
    void mapnik_map_zoom_to_box(Pointer map, double minx, double miny, double maxx, double maxy);
    void mapnik_map_zoom_all(Pointer map);
    void mapnik_map_pan(Pointer map, int x, int y);
    void mapnik_map_pan_and_zoom(Pointer map, int x, int y, double zoom);
    void mapnik_map_get_current_extent(Pointer map, double[] out);
    void mapnik_map_get_buffered_extent(Pointer map, double[] out);
    double mapnik_map_scale(Pointer map);
    double mapnik_map_scale_denominator(Pointer map);
    void mapnik_map_remove_all(Pointer map);
    int mapnik_map_layer_count(Pointer map);
    Pointer mapnik_map_get_layer(Pointer map, int index);
    int mapnik_map_add_layer(Pointer map, Pointer layer);
    int mapnik_map_remove_layer(Pointer map, int index);
    int mapnik_map_style_count(Pointer map);
    String mapnik_map_style_name(Pointer map, int index);
    void mapnik_map_remove_style(Pointer map, String name);
    int mapnik_map_register_fonts(Pointer map, String dir, int recurse);
    int mapnik_map_load_fonts(Pointer map);

    // layer
    Pointer mapnik_layer_create(String name, String srs);
    void mapnik_layer_free(Pointer layer);
    String mapnik_layer_get_name(Pointer layer);
    void mapnik_layer_set_name(Pointer layer, String name);
    String mapnik_layer_get_srs(Pointer layer);
    void mapnik_layer_set_srs(Pointer layer, String srs);
    void mapnik_layer_add_style(Pointer layer, String styleName);
    int mapnik_layer_style_count(Pointer layer);
    String mapnik_layer_style_name(Pointer layer, int index);
    double mapnik_layer_get_minimum_scale_denominator(Pointer layer);
    void mapnik_layer_set_minimum_scale_denominator(Pointer layer, double v);
    double mapnik_layer_get_maximum_scale_denominator(Pointer layer);
    void mapnik_layer_set_maximum_scale_denominator(Pointer layer, double v);
    int mapnik_layer_visible(Pointer layer, double scaleDenominator);
    int mapnik_layer_get_active(Pointer layer);
    void mapnik_layer_set_active(Pointer layer, int v);
    int mapnik_layer_get_queryable(Pointer layer);
    void mapnik_layer_set_queryable(Pointer layer, int v);
    int mapnik_layer_get_clear_label_cache(Pointer layer);
    void mapnik_layer_set_clear_label_cache(Pointer layer, int v);
    int mapnik_layer_get_cache_features(Pointer layer);
    void mapnik_layer_set_cache_features(Pointer layer, int v);
    String mapnik_layer_get_group_by(Pointer layer);
    void mapnik_layer_set_group_by(Pointer layer, String column);
    String mapnik_layer_get_comp_op(Pointer layer);
    int mapnik_layer_set_comp_op(Pointer layer, String name);
    void mapnik_layer_add_child(Pointer parent, Pointer child);
    int mapnik_layer_child_count(Pointer layer);
    Pointer mapnik_layer_child_copy(Pointer layer, int index);
    double mapnik_layer_get_opacity(Pointer layer);
    void mapnik_layer_set_opacity(Pointer layer, double v);
    int mapnik_layer_get_buffer_size(Pointer layer, int[] out);
    void mapnik_layer_set_buffer_size(Pointer layer, int size);
    void mapnik_layer_reset_buffer_size(Pointer layer);
    int mapnik_layer_get_maximum_extent(Pointer layer, double[] out);
    void mapnik_layer_set_maximum_extent(Pointer layer, double minx, double miny, double maxx, double maxy);
    void mapnik_layer_reset_maximum_extent(Pointer layer);
    void mapnik_layer_set_datasource(Pointer layer, Pointer datasource);
    Pointer mapnik_layer_get_datasource(Pointer layer);
    int mapnik_layer_envelope(Pointer layer, double[] out);

    // datasource
    Pointer mapnik_params_create();
    void mapnik_params_free(Pointer params);
    void mapnik_params_set_string(Pointer params, String key, String value);
    void mapnik_params_set_int(Pointer params, String key, long value);
    void mapnik_params_set_double(Pointer params, String key, double value);
    void mapnik_params_set_bool(Pointer params, String key, int value);
    Pointer mapnik_datasource_create(Pointer params);
    void mapnik_datasource_free(Pointer datasource);
    int mapnik_datasource_type(Pointer datasource);
    int mapnik_datasource_geometry_type(Pointer datasource);
    int mapnik_datasource_envelope(Pointer datasource, double[] out);
    String mapnik_datasource_layer_name(Pointer datasource);
    String mapnik_datasource_encoding(Pointer datasource);
    int mapnik_datasource_param_count(Pointer datasource);
    String mapnik_datasource_param_name(Pointer datasource, int index);
    int mapnik_datasource_param_type(Pointer datasource, int index);
    int mapnik_datasource_param_bool(Pointer datasource, int index);
    long mapnik_datasource_param_int(Pointer datasource, int index);
    double mapnik_datasource_param_double(Pointer datasource, int index);
    String mapnik_datasource_param_string(Pointer datasource, int index);
    int mapnik_datasource_field_count(Pointer datasource);
    String mapnik_datasource_field_name(Pointer datasource, int index);
    int mapnik_datasource_field_type(Pointer datasource, int index);

    // features
    Pointer mapnik_datasource_features(Pointer datasource, double minx, double miny, double maxx, double maxy,
                                       double resolutionX, double resolutionY, double scaleDenominator,
                                       String[] propertyNames, int propertyCount);
    Pointer mapnik_datasource_features_at_point(Pointer datasource, double x, double y, double tolerance);
    Pointer mapnik_map_query_point(Pointer map, int layerIndex, double x, double y);
    Pointer mapnik_map_query_map_point(Pointer map, int layerIndex, double px, double py);
    void mapnik_featureset_free(Pointer featureset);
    int mapnik_featureset_next(Pointer featureset, PointerByReference out);
    void mapnik_feature_free(Pointer feature);
    long mapnik_feature_id(Pointer feature);
    int mapnik_feature_attribute_count(Pointer feature);
    String mapnik_feature_attribute_name(Pointer feature, int index);
    int mapnik_feature_attribute_type(Pointer feature, int index);
    int mapnik_feature_attribute_bool(Pointer feature, int index);
    long mapnik_feature_attribute_int(Pointer feature, int index);
    double mapnik_feature_attribute_double(Pointer feature, int index);
    String mapnik_feature_attribute_string(Pointer feature, int index);
    int mapnik_feature_geometry_type(Pointer feature);
    int mapnik_feature_envelope(Pointer feature, double[] out);
    String mapnik_feature_geometry_wkt(Pointer feature);
    String mapnik_feature_geometry_geojson(Pointer feature);
    String mapnik_feature_to_geojson(Pointer feature);

    // building features, memory datasource
    Pointer mapnik_feature_builder_create(long id);
    void mapnik_feature_builder_free(Pointer builder);
    int mapnik_feature_builder_set_geometry_wkb(Pointer builder, byte[] wkb, int length);
    void mapnik_feature_builder_put_null(Pointer builder, String key);
    void mapnik_feature_builder_put_string(Pointer builder, String key, String value);
    void mapnik_feature_builder_put_int(Pointer builder, String key, long value);
    void mapnik_feature_builder_put_double(Pointer builder, String key, double value);
    void mapnik_feature_builder_put_bool(Pointer builder, String key, int value);
    void mapnik_feature_builder_put_var_null(Pointer builder, String key);
    void mapnik_feature_builder_put_var_string(Pointer builder, String key, String value);
    void mapnik_feature_builder_put_var_int(Pointer builder, String key, long value);
    void mapnik_feature_builder_put_var_double(Pointer builder, String key, double value);
    void mapnik_feature_builder_put_var_bool(Pointer builder, String key, int value);
    Pointer mapnik_memory_datasource_create();
    int mapnik_memory_datasource_push(Pointer datasource, Pointer builder);
    int mapnik_memory_datasource_size(Pointer datasource);
    void mapnik_memory_datasource_clear(Pointer datasource);
    void mapnik_memory_datasource_set_envelope(Pointer datasource, double minx, double miny, double maxx, double maxy);

    // expressions
    Pointer mapnik_expression_parse(String text);
    void mapnik_expression_free(Pointer expression);
    Pointer mapnik_expression_evaluate(Pointer expression, Pointer builder);
    int mapnik_expression_test(Pointer expression, Pointer builder);
    void mapnik_value_free(Pointer value);
    int mapnik_value_type(Pointer value);
    int mapnik_value_bool(Pointer value);
    long mapnik_value_int(Pointer value);
    double mapnik_value_double(Pointer value);
    String mapnik_value_string(Pointer value);
    Pointer mapnik_path_expression_parse(String text);
    void mapnik_path_expression_free(Pointer path);
    String mapnik_path_expression_evaluate(Pointer path, Pointer builder);
    int mapnik_transform_check(String text);

    // geometry operations
    int mapnik_geometry_centroid(byte[] wkb, int length, double[] outXy);
    int mapnik_geometry_interior_point(byte[] wkb, int length, double scaleFactor, double[] outXy);
    int mapnik_geometry_closest_point(byte[] wkb, int length, double x, double y, double[] outXyd);
    int mapnik_geometry_is_valid(byte[] wkb, int length);
    String mapnik_geometry_validity_reason(byte[] wkb, int length);
    int mapnik_geometry_is_simple(byte[] wkb, int length);
    String mapnik_geometry_correct(byte[] wkb, int length);
    String mapnik_geometry_simplify(byte[] wkb, int length, String algorithm, double tolerance);
    String mapnik_geometry_offset(byte[] wkb, int length, double distance);
    String mapnik_geometry_reproject(byte[] wkb, int length, Pointer transform);

    // datasource implemented in Java
    void mapnik_java_set_handlers(JavaFeaturesHandler features, JavaReleaseHandler release);
    Pointer mapnik_java_datasource_create(long id, double[] envelope, String[] fieldNames, int[] fieldTypes, int fieldCount);
    void mapnik_java_datasource_set_envelope(Pointer datasource, double minx, double miny, double maxx, double maxy);

    // projections
    Pointer mapnik_projection_create(String params);
    void mapnik_projection_free(Pointer projection);
    String mapnik_projection_params(Pointer projection);
    String mapnik_projection_definition(Pointer projection);
    String mapnik_projection_description(Pointer projection);
    int mapnik_projection_is_geographic(Pointer projection);
    int mapnik_projection_area_of_use(Pointer projection, double[] out);
    int mapnik_projection_forward(Pointer projection, double[] x, double[] y);
    int mapnik_projection_inverse(Pointer projection, double[] x, double[] y);
    Pointer mapnik_transform_create(Pointer source, Pointer dest);
    void mapnik_transform_free(Pointer transform);
    int mapnik_transform_is_identity(Pointer transform);
    int mapnik_transform_forward_point(Pointer transform, double[] x, double[] y);
    int mapnik_transform_backward_point(Pointer transform, double[] x, double[] y);
    int mapnik_transform_forward_box(Pointer transform, double[] box, int densifyPoints);
    int mapnik_transform_backward_box(Pointer transform, double[] box, int densifyPoints);

    // images
    Pointer mapnik_image_create(int width, int height);
    Pointer mapnik_image_load_file(String path);
    Pointer mapnik_image_load_bytes(byte[] data, int length);
    void mapnik_image_free(Pointer image);
    int mapnik_image_width(Pointer image);
    int mapnik_image_height(Pointer image);
    int mapnik_image_get_pixel(Pointer image, int x, int y);
    int mapnik_image_set_pixel(Pointer image, int x, int y, int rgba);
    int mapnik_image_fill(Pointer image, String color);
    int mapnik_image_is_solid(Pointer image);
    void mapnik_image_copy_rgba(Pointer image, byte[] out);
    int mapnik_image_save(Pointer image, String path, String format);
    int mapnik_image_save_to_buffer(Pointer image, String format, PointerByReference out, IntByReference len);

    // image operations
    int mapnik_image_filter(Pointer image, String filters, double scaleFactor);
    int mapnik_image_composite(Pointer dst, Pointer src, String mode, double opacity, int dx, int dy);
    Pointer mapnik_image_scale(Pointer image, int width, int height, String method);
    Pointer mapnik_image_warp(Pointer image, String sourceSrs, double[] sourceExtent, String targetSrs,
                              double[] targetExtent, int width, int height, int meshSize, String method);
    Pointer mapnik_image_crop(Pointer image, int x, int y, int width, int height);
    Pointer mapnik_image_copy(Pointer image);
    int mapnik_image_apply_opacity(Pointer image, double opacity);
    int mapnik_image_color_to_alpha(Pointer image, String color);
    int mapnik_image_probe_file(String path, int[] width, int[] height);
    int mapnik_image_probe_bytes(byte[] data, int length, int[] width, int[] height);

    // render
    int mapnik_map_render_to_file(Pointer map, String path, String format, double scaleFactor, int offsetX, int offsetY);
    int mapnik_map_render_to_buffer(Pointer map, String format, PointerByReference out, IntByReference len,
                                    double scaleFactor, int offsetX, int offsetY);
    int mapnik_map_render_to_image(Pointer map, Pointer image, double scaleFactor, int offsetX, int offsetY);
    int mapnik_map_render_to_cairo_file(Pointer map, String path, String type, double scaleFactor);
    int mapnik_cairo_available();
    String mapnik_map_render_grid(Pointer map, int layerIndex, String key, String[] fields, int fieldCount,
                                  int resolution, double scaleFactor, int offsetX, int offsetY);
    void mapnik_buffer_free(Pointer buf);
}
