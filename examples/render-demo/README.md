# render-demo

A small application that uses mapnik-java as a dependency and renders a map to a PNG.

It resolves `dev.avelar:mapnik-java` from this repository through a Gradle composite build (`includeBuild("../..")` in `settings.gradle.kts`). To consume a released version from Maven Central instead, delete that line.

## Run

Build the native shim once from the repository root:

```bash
cmake -S native -B build/cmake && cmake --build build/cmake
```

Then:

```bash
cd examples/render-demo
./gradlew run --args="world.png"
```

Arguments: `[output.png] [style.xml] [width] [height]`. Without a style it renders the bundled demo map (two polygons and a line, from GeoJSON). Pass your own Mapnik XML style to render something else.

The input plugin directory comes from `MAPNIK_INPUT_PLUGINS`, or from `mapnik-config --input-plugins`.

## Test

```bash
./gradlew test
```

Renders the bundled map and checks the image size and the colour of a background pixel and a land pixel.
