-- Table for PostgisIntegrationTest: one square in the middle of the map.
CREATE EXTENSION IF NOT EXISTS postgis;
DROP TABLE IF EXISTS mapnik_java_squares;
CREATE TABLE mapnik_java_squares (id serial PRIMARY KEY, name text, geom geometry(Polygon, 4326));
INSERT INTO mapnik_java_squares (name, geom) VALUES ('middle', ST_GeomFromText('POLYGON((-20 -20, 20 -20, 20 20, -20 20, -20 -20))', 4326));
