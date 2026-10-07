plugins {
    java
    id("me.champeau.jmh") version "0.7.2"
}

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    jmh("dev.avelar:mapnik-java:4.3.2.0")
}

// Use the bundled natives of this machine's platform when built (see ../README.md), else libmapnik_c
// from ../build/native.
val platform = run {
    val arch = System.getProperty("os.arch")
    val os = if (System.getProperty("os.name").lowercase().startsWith("mac")) "macos" else "linux"
    os + "-" + if (arch == "aarch64" || arch == "arm64") "aarch64" else "x86_64"
}
val bundle = file("../build/natives/$platform")

jmh {
    warmupIterations.set(3)
    warmup.set("1s")
    iterations.set(5)
    timeOnIteration.set("2s")
    fork.set(1)
    resultFormat.set("JSON")
    if (bundle.resolve("MANIFEST").exists()) {
        // The loader reads the bundle from the class path; point it at the directory instead.
        jvmArgs.set(listOf("-Dmapnik.native.dir=${bundle.resolve("lib")}"))
    } else {
        jvmArgs.set(listOf("-Djna.library.path=${file("../build/native").absolutePath}"))
    }
}
