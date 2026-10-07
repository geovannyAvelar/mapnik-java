package dev.avelar.mapnik.bench;

import dev.avelar.mapnik.Box2d;
import dev.avelar.mapnik.Feature;
import dev.avelar.mapnik.Geometry;
import dev.avelar.mapnik.Layer;
import dev.avelar.mapnik.MapnikMap;
import dev.avelar.mapnik.Rule;
import dev.avelar.mapnik.Style;
import dev.avelar.mapnik.Symbolizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** The same synthetic data for every benchmark: small squares scattered over a 360 by 180 world. */
final class Data {
    static final String SRS = "+proj=longlat +datum=WGS84";
    static final Box2d WORLD = new Box2d(-180, -90, 180, 90);

    private Data() {}

    static List<Feature> squares(int count) {
        Random random = new Random(42);
        List<Feature> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double x = -179 + random.nextDouble() * 358;
            double y = -89 + random.nextDouble() * 178;
            double h = 0.2 + random.nextDouble();
            out.add(Feature.create(i + 1, Geometry.rectangle(new Box2d(x - h, y - h, x + h, y + h)),
                Collections.<String, Object>singletonMap("n", (long) i)));
        }
        return out;
    }

    static Style style() {
        return Style.create("s").add(Rule.create()
            .add(Symbolizer.polygon().fill("#3b82f6"))
            .add(Symbolizer.line().stroke("#1e3a8a").strokeWidth(0.5)));
    }

    /** A 256 by 256 map over the world with one layer on the given datasource. */
    static MapnikMap map(dev.avelar.mapnik.Datasource ds) {
        MapnikMap map = new MapnikMap(256, 256).setSrs(SRS).setBackground("white");
        try (Layer layer = Layer.create("data", SRS)) {
            map.addStyle(style());
            layer.addStyle("s").setDatasource(ds);
            map.addLayer(layer);
        } catch (RuntimeException e) {
            map.close();
            throw e;
        }
        return map.zoomToBox(WORLD);
    }
}
