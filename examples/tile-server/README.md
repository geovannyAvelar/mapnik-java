# tile-server

An XYZ tile server (`/{z}/{x}/{y}.png`, Web Mercator) built on mapnik-java and the JDK's HTTP server. It shows how to serve tiles without rendering each one alone:

- **Metatiles.** A request renders a block of tiles (4 by 4 by default) in one pass and cuts it into 256-pixel tiles. Labels and wide lines that cross a tile edge are drawn once, so neighbours match, and one large render costs less than sixteen small ones.
- **Caching.** Cut tiles go into an in-memory LRU cache and, when you give a directory, onto disk. A restart serves them without rendering. Files are written under a temporary name and renamed, so a reader never sees half a tile.
- **Async rendering.** HTTP threads never render. They ask for the metatile and answer when it is ready. Renders run on a small pool matching the `MapPool`, and requests for a metatile already being rendered wait for that render instead of starting another.

It is a demo, not a production tile server: one fixed style, no authentication, no expiry of cached tiles.

It resolves `dev.avelar:mapnik-java` from this repository through a Gradle composite build, like `examples/wms-server`.

## Run

Build the native shim once from the repository root (see the top-level README), then:

```bash
cd examples/tile-server
./gradlew run --args="8080 4 /tmp/tiles"
```

The arguments are the port, the metatile side in tiles (a power of two) and an optional cache directory. Open <http://localhost:8080/> for a Leaflet map, or:

```bash
curl -o tile.png http://localhost:8080/2/1/1.png
```

To use the prebuilt natives of a bundle instead of a shim in `build/native`:

```bash
MAPNIK_INPUT_PLUGINS=../../build/natives/linux-x86_64/plugins/input ./gradlew test -PnativeDir=$PWD/../../build/natives/linux-x86_64/lib
```
