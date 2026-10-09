plugins {
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("dev.avelar:mapnik-java:4.3.2.0")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// libmapnik_c.so built from ../../native (see the top-level README).
// -PnativeDir=<folder with libmapnik_c> to use another, such as build/natives/linux-x86_64/lib of a bundle.
val nativeDir = providers.gradleProperty("nativeDir").orElse(file("../../build/native").absolutePath).get()

application {
    mainClass.set("tiles.TileServer")
    applicationDefaultJvmArgs = listOf("-Djna.library.path=$nativeDir")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("jna.library.path", nativeDir)
}
