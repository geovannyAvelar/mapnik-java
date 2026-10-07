# GraalVM native image

**Not tested.** This library has not been built into a native image, and nothing in its CI does so. These
notes say what is likely to be needed, so that you can try it, and what to report if it fails.

mapnik-java loads its C library through [JNA](https://github.com/java-native-access/jna), which finds
functions by reflection and creates the native interface as a dynamic proxy. A native image needs to be
told about both.

## What to expect

- **JNA configuration.** Recent JNA versions ship some native-image metadata, but the library's own
  interface (`dev.avelar.mapnik.NativeApi`) and the callback interfaces (`JavaFeaturesHandler`,
  `JavaReleaseHandler`) need entries in `reflect-config.json` and `proxy-config.json`, and JNA itself
  needs `jni-config.json` entries. Generate them rather than writing them by hand: run your application
  once on a normal JVM with the tracing agent, exercising rendering, a `JavaDatasource` and the natives
  loading:

  ```bash
  java -agentlib:native-image-agent=config-output-dir=META-INF/native-image -jar app.jar
  ```

- **The bundled natives are resources.** `NativeLoader` unpacks them from the class path at run time.
  A native image does not include resources unless asked: add `-H:IncludeResources=dev/avelar/mapnik/natives/.*`
  (a resource config pattern with the same effect works too). The jar is large, so this makes a large
  executable. Alternatively, install the natives next to the executable and point to them with
  `-Dmapnik.native.dir=/path/to/lib`, which skips unpacking.
- **Closing handles.** Leak detection uses phantom references and a daemon thread, which native image
  supports.
- **Callbacks.** A `JavaDatasource` calls back into Java from native code. With JNA that needs the
  callback metadata above.

## If you try it

If it works, or fails in a way that points at something this library can fix, please open an issue with the
native-image version, the platform and the error. A reachability-metadata file for the library could then
be added to the jar under `META-INF/native-image/dev.avelar/mapnik-java/`.
