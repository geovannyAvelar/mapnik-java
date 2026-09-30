package dev.avelar.mapnik;

/**
 * Supplies features to a {@link JavaDatasource} when Mapnik asks for them. Mapnik calls it while it
 * renders, from the thread that called the render, and once per layer per render. Several maps
 * rendering at once on different threads call it at the same time, so an implementation must be
 * thread-safe.
 */
@FunctionalInterface
public interface FeatureSource {
    /**
     * Features that touch the requested area. Returning extras is fine: Mapnik clips when it draws.
     * Throwing an exception fails the render with a {@link MapnikException} carrying the message.
     */
    Iterable<Feature> features(FeatureRequest request) throws Exception;
}
