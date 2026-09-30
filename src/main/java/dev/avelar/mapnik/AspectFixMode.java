package dev.avelar.mapnik;

/**
 * What {@link MapnikMap#zoomToBox} does when the requested box does not have the same aspect
 * ratio as the map's pixel size. The order matches Mapnik's enum.
 */
public enum AspectFixMode {
    /** Grow the box's width or height to fill the map size. The default. */
    GROW_BBOX,
    /** Grow the map's width or height to fit the box. */
    GROW_CANVAS,
    /** Shrink the box's width or height to fill the map size. */
    SHRINK_BBOX,
    /** Shrink the map's width or height to fit the box. */
    SHRINK_CANVAS,
    /** Adjust the box's width; leave its height and the map size. */
    ADJUST_BBOX_WIDTH,
    /** Adjust the box's height; leave its width and the map size. */
    ADJUST_BBOX_HEIGHT,
    /** Adjust the map's width; leave its height and the box. */
    ADJUST_CANVAS_WIDTH,
    /** Adjust the map's height; leave its width and the box. */
    ADJUST_CANVAS_HEIGHT,
    /** Do nothing: the box is used exactly, so pixels may be stretched. */
    RESPECT
}
