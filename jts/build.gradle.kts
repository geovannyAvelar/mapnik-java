plugins {
    id("java-library")
    id("maven-publish")
    id("signing")
}

group = rootProject.group
version = rootProject.version

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-options")
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc> {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

tasks.jar {
    manifest { attributes("Automatic-Module-Name" to "dev.avelar.mapnik.jts") }
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":"))
    // The caller's JTS (or GeoTools') version wins: this is the API it needs, not a version it forces.
    api("org.locationtech.jts:jts-core:1.20.0")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "mapnik-java-jts"
            pom {
                name.set("mapnik-java JTS")
                description.set("Converts between mapnik-java geometries and JTS geometries (GeoTools, Hibernate Spatial, ...).")
                url.set("https://github.com/geovannyAvelar/mapnik-java")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("avelar")
                        name.set("Giovani Avelar")
                        email.set("github@avelar.dev")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/geovannyAvelar/mapnik-java.git")
                    developerConnection.set("scm:git:https://github.com/geovannyAvelar/mapnik-java.git")
                    url.set("https://github.com/geovannyAvelar/mapnik-java")
                }
                issueManagement {
                    system.set("GitHub Issues")
                    url.set("https://github.com/geovannyAvelar/mapnik-java/issues")
                }
            }
        }
    }
}

signing {
    val gpgKey = System.getenv("GPG_PRIVATE_KEY") ?: findProperty("signing.key") as String?
    val gpgPassphrase = System.getenv("GPG_PASSPHRASE") ?: findProperty("signing.password") as String?
    if (gpgKey != null && gpgPassphrase != null) {
        useInMemoryPgpKeys(gpgKey, gpgPassphrase)
        sign(publishing.publications["mavenJava"])
    }
}
