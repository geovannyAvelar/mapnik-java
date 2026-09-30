package dev.avelar.mapnik;

/** The kind of geometry a feature holds. The order matches Mapnik's enum. */
public enum GeometryKind {
    UNKNOWN,
    POINT,
    LINE_STRING,
    POLYGON,
    MULTI_POINT,
    MULTI_LINE_STRING,
    MULTI_POLYGON,
    GEOMETRY_COLLECTION
}
