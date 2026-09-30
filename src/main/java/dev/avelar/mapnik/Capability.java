package dev.avelar.mapnik;

/** Something a Mapnik build may or may not include. See {@link Mapnik#supports(Capability)}. */
public enum Capability {
    /** PDF, SVG and PostScript output. */
    CAIRO,
    /** Reading and writing JPEG. */
    JPEG,
    /** Reading and writing PNG. */
    PNG,
    /** Reading and writing TIFF. */
    TIFF,
    /** Reading and writing WebP. */
    WEBP,
    /** Projection support through PROJ. */
    PROJ,
    /** The grid renderer, for UTFGrid output. */
    GRID,
    /** Safe for several threads to render at once, each with its own map. */
    THREADSAFE,
    /** Mapnik writes messages to its log. Without it, {@link Logging} settings have nothing to show. */
    LOGGING
}
