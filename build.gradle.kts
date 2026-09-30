plugins {
    id("java-library")
    id("maven-publish")
    id("signing")
    id("com.gradleup.nmcp") version "0.0.9"
}

val mapnikVersion = providers.gradleProperty("mapnik.version").get()
val wrapperRevision = providers.gradleProperty("wrapper.revision").get()

group = "dev.avelar"
version = "$mapnikVersion.$wrapperRevision"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    // Runtime dependency: loads the native C shim.
    implementation("net.java.dev.jna:jna:5.14.0")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The C shim is built separately with CMake (see README). Unit tests skip when the
// shared library is not found.
val nativeDir = layout.buildDirectory.dir("native")

// Integration tests run against a real Mapnik install and fail if it is missing.
// Run: ./gradlew integrationTest   (build the shim first)
val integrationTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

// Input plugin directory: MAPNIK_INPUT_PLUGINS, else `mapnik-config --input-plugins`.
// A CMake-installed Mapnik has no mapnik-config, so set MAPNIK_INPUT_PLUGINS for it.
val mapnikInputPlugins = providers.environmentVariable("MAPNIK_INPUT_PLUGINS").orElse(
    providers.provider {
        try {
            providers.exec {
                commandLine("mapnik-config", "--input-plugins")
                isIgnoreExitValue = true
            }.standardOutput.asText.get().trim()
        } catch (e: Exception) {
            ""
        }
    }
)

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests against a real Mapnik install."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    useJUnitPlatform()
    systemProperty("jna.library.path", nativeDir.get().asFile.absolutePath)
    systemProperty("mapnik.input.plugins", mapnikInputPlugins.get())
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("jna.library.path", nativeDir.get().asFile.absolutePath)
}

tasks.processResources {
    inputs.property("mapnikVersion", mapnikVersion)
    filesMatching("mapnik-java.properties") {
        expand("mapnikVersion" to mapnikVersion)
    }
}

tasks.register("printVersion") {
    doLast { println(project.version) }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc> {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

// ============================================================================
// Maven Publication
// ============================================================================

val projectUrl = providers.gradleProperty("project.url").orNull
val projectName = providers.gradleProperty("project.name").orNull
val projectDescription = providers.gradleProperty("project.description").orNull
val developerId = providers.gradleProperty("developer.id").orNull
val developerName = providers.gradleProperty("developer.name").orNull
val developerEmail = providers.gradleProperty("developer.email").orNull

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set(projectName ?: "mapnik-java")
                description.set(projectDescription ?: "Java bindings for Mapnik via a C shim and JNA")
                url.set(projectUrl ?: "https://github.com/geovannyAvelar/mapnik-java")

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
                        id.set(developerId ?: "avelar")
                        name.set(developerName ?: "Giovani Avelar")
                        email.set(developerEmail ?: "github@avelar.dev")
                    }
                }

                scm {
                    val scmUrl = projectUrl ?: "https://github.com/geovannyAvelar/mapnik-java"
                    connection.set("scm:git:${scmUrl}.git")
                    developerConnection.set("scm:git:${scmUrl}.git")
                    url.set(scmUrl)
                }

                issueManagement {
                    system.set("GitHub Issues")
                    url.set("https://github.com/geovannyAvelar/mapnik-java/issues")
                }
            }
        }
    }

    repositories {
        // GitHub Packages
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/geovannyAvelar/mapnik-java")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: findProperty("githubUsername") as String?
                password = System.getenv("GITHUB_TOKEN") ?: findProperty("githubToken") as String?
            }
        }
        // Maven Central is handled by the nmcp plugin below.
    }
}

// ============================================================================
// Signing
// ============================================================================

signing {
    val gpgKey = System.getenv("GPG_PRIVATE_KEY") ?: findProperty("signing.key") as String?
    val gpgPassphrase = System.getenv("GPG_PASSPHRASE") ?: findProperty("signing.password") as String?
    if (gpgKey != null && gpgPassphrase != null) {
        useInMemoryPgpKeys(gpgKey, gpgPassphrase)
        sign(publishing.publications["mavenJava"])
    }
}

// ============================================================================
// Sonatype Central Portal (nmcp)
// Token from central.sonatype.com -> Account -> Generate User Token
// ============================================================================

nmcp {
    publish("mavenJava") {
        username = System.getenv("SONATYPE_USERNAME") ?: ""
        password = System.getenv("SONATYPE_PASSWORD") ?: ""
        publicationType = "AUTOMATIC"
    }
}
