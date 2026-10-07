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
val nativeDir = file("../../build/native").absolutePath

application {
    mainClass.set("demo.RenderDemo")
    applicationDefaultJvmArgs = listOf("-Djna.library.path=$nativeDir")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("jna.library.path", nativeDir)
}
