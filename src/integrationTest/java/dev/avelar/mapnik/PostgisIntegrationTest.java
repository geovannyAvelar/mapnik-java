package dev.avelar.mapnik;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The PostGIS input plugin from the add-on bundle. Runs with {@code -PnativesExtras=postgis}; without that
 * the tests are skipped. The drawing test also needs a PostGIS server with the table from
 * {@code natives/postgis-fixture.sql}, given by the usual PGHOST, PGPORT, PGUSER, PGPASSWORD and PGDATABASE
 * variables, and is skipped without one.
 */
class PostgisIntegrationTest {
    private static boolean withAddOn() {
        return java.util.Arrays.asList(System.getProperty("mapnik.test.extras", "").split(",")).contains("postgis");
    }

    @BeforeAll
    static void needTheAddOn() throws Exception {
        Fixtures.dir();
        assumeTrue(withAddOn(), "run with -PnativesExtras=postgis");
    }

    @Test
    void thePluginIsRegisteredAndItsLibrariesLoad() {
        assertTrue(Mapnik.isDatasourceRegistered("postgis"), Mapnik.datasourcePlugins().toString());
        assertTrue(Mapnik.isDatasourceRegistered("pgraster"), "the same plugin file carries pgraster");
    }

    @Test
    void connectingToNothingFailsWithADatabaseErrorNotAMissingLibrary() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "postgis");
        p.put("host", "127.0.0.1");
        p.put("port", 1);   // nothing listens here
        p.put("dbname", "none");
        p.put("table", "t");
        p.put("connect_timeout", 2);
        MapnikException e = assertThrows(MapnikException.class, () -> Datasource.create(p));
        String m = e.getMessage().toLowerCase();
        assertFalse(m.contains("plugin") && m.contains("not"), "the plugin loaded: " + e.getMessage());
        assertTrue(m.contains("connect") || m.contains("postgis") || m.contains("refused"), e.getMessage());
    }

    @Test
    void aTableIsDrawn() {
        String host = System.getenv("PGHOST");
        assumeTrue(host != null && !host.isEmpty(), "set PGHOST (and PGUSER, PGPASSWORD, PGDATABASE) for a PostGIS server");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "postgis");
        p.put("host", host);
        p.put("port", System.getenv().getOrDefault("PGPORT", "5432"));
        p.put("user", System.getenv().getOrDefault("PGUSER", "postgres"));
        p.put("password", System.getenv().getOrDefault("PGPASSWORD", ""));
        p.put("dbname", System.getenv().getOrDefault("PGDATABASE", "postgres"));
        p.put("table", "mapnik_java_squares");
        p.put("geometry_field", "geom");
        p.put("srid", 4326);
        p.put("extent", "-50,-50,50,50");
        try (Datasource ds = Datasource.create(p);
             MapnikMap map = new MapnikMap(100, 100).setSrs("epsg:4326").setBackground("white");
             Layer layer = Layer.create("squares", "epsg:4326")) {
            assertEquals(Datasource.GeometryType.POLYGON, ds.geometryType());
            map.addStyle(Style.create("s").add(Rule.create().add(Symbolizer.polygon().fill("#ff0000"))));
            layer.addStyle("s").setDatasource(ds);
            map.addLayer(layer).zoomToBox(-50, -50, 50, 50);
            try (Image img = map.renderToImage()) {
                assertEquals(0xFFFF0000, img.getArgb(50, 50), "the square in the middle");
                assertEquals(0xFFFFFFFF, img.getArgb(5, 5), "outside it");
            }
        }
    }
}
