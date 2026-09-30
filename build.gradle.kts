import org.gradle.api.publish.maven.MavenPom

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

// ============================================================================
// Prebuilt native libraries
// ============================================================================
// natives/linux-x86_64/Dockerfile builds Mapnik, the shim and everything they need into
// build/natives/linux-x86_64. This packages that directory as a jar, which the loader unpacks at run time.

val nativesDirectory = layout.buildDirectory.dir("natives/linux-x86_64")
val hasNatives = nativesDirectory.get().asFile.resolve("MANIFEST").exists()
val useBundledNatives = providers.gradleProperty("bundledNatives").isPresent
val nativesArtifactId = "mapnik-java-natives-linux-x86_64"

val nativesJar = tasks.register<Jar>("nativesJar") {
    description = "Packages the prebuilt Linux x86_64 native libraries."
    group = "build"
    archiveBaseName.set(nativesArtifactId)
    from(nativesDirectory) { into("dev/avelar/mapnik/natives/linux-x86_64") }
    onlyIf { hasNatives }
}

// Maven Central wants a sources and a javadoc jar for every artifact. These hold a note instead.
val nativesNote = layout.buildDirectory.file("natives-note/README.txt")
val writeNativesNote = tasks.register("writeNativesNote") {
    outputs.file(nativesNote)
    doLast {
        nativesNote.get().asFile.apply {
            parentFile.mkdirs()
            writeText("Prebuilt native libraries for mapnik-java on Linux x86_64: Mapnik, its dependencies, input plugins,\n" +
                "fonts and PROJ data. There is no Java source here. See https://github.com/geovannyAvelar/mapnik-java\n" +
                "and the NOTICE and licenses/ entries inside the main jar for what is bundled and under which licenses.\n")
        }
    }
}
val nativesSourcesJar = tasks.register<Jar>("nativesSourcesJar") {
    archiveBaseName.set(nativesArtifactId)
    archiveClassifier.set("sources")
    from(writeNativesNote)
    onlyIf { hasNatives }
}
val nativesJavadocJar = tasks.register<Jar>("nativesJavadocJar") {
    archiveBaseName.set(nativesArtifactId)
    archiveClassifier.set("javadoc")
    from(writeNativesNote)
    onlyIf { hasNatives }
}

if (useBundledNatives && !hasNatives) {
    throw GradleException("-PbundledNatives needs the native bundle: run scripts/build-natives-linux.sh first")
}

// Integration tests run against a real Mapnik install and fail if it is missing.
// Run: ./gradlew integrationTest   (build the shim first)
val integrationTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

// With -PbundledNatives the tests load Mapnik from the natives jar instead of the system.
dependencies {
    if (useBundledNatives) {
        add(integrationTest.runtimeOnlyConfigurationName, files(nativesJar.map { it.archiveFile }))
    }
}

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

// Fonts directory for text tests: MAPNIK_FONTS, else `mapnik-config --fonts`. Optional.
val mapnikFonts = providers.environmentVariable("MAPNIK_FONTS").orElse(
    providers.provider {
        try {
            providers.exec {
                commandLine("mapnik-config", "--fonts")
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
    if (useBundledNatives) {
        // No system Mapnik and no shim on the path: everything must come from the natives jar.
        systemProperty("mapnik.input.plugins", "")
        systemProperty("mapnik.fonts", "")
    } else {
        systemProperty("jna.library.path", nativeDir.get().asFile.absolutePath)
        systemProperty("mapnik.input.plugins", mapnikInputPlugins.get())
        systemProperty("mapnik.fonts", mapnikFonts.get())
    }
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("jna.library.path", nativeDir.get().asFile.absolutePath)
    // NativeApiConsistencyTest parses this, so re-run the tests when it changes.
    inputs.file("native/mapnik_c.h")
    systemProperty("native.header", file("native/mapnik_c.h").absolutePath)
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

fun MavenPom.fillPom(pomName: String, pomDescription: String, bundlesLgpl: Boolean = false) {
    name.set(pomName)
    description.set(pomDescription)
    url.set(projectUrl ?: "https://github.com/geovannyAvelar/mapnik-java")
    inceptionYear.set("2026")

    licenses {
        license {
            name.set("MIT License")
            url.set("https://opensource.org/licenses/MIT")
            distribution.set("repo")
        }
        if (bundlesLgpl) {
            license {
                name.set("GNU Lesser General Public License, version 2.1 (Mapnik and some bundled libraries)")
                url.set("https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html")
                distribution.set("repo")
            }
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

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                fillPom(projectName ?: "mapnik-java", projectDescription ?: "Java bindings for Mapnik via a C shim and JNA")
            }
        }

        if (hasNatives) {
            create<MavenPublication>("natives") {
                artifactId = nativesArtifactId
                artifact(nativesJar)
                artifact(nativesSourcesJar)
                artifact(nativesJavadocJar)
                pom {
                    fillPom("mapnik-java natives (Linux x86_64)",
                        "Prebuilt Mapnik and mapnik-java native libraries for Linux x86_64, with their dependencies, " +
                            "input plugins, fonts and PROJ data. Add it next to mapnik-java to need nothing installed.",
                        bundlesLgpl = true)
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
        if (hasNatives) {
            sign(publishing.publications["natives"])
        }
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
    if (hasNatives) {
        publish("natives") {
            username = System.getenv("SONATYPE_USERNAME") ?: ""
            password = System.getenv("SONATYPE_PASSWORD") ?: ""
            publicationType = "AUTOMATIC"
        }
    }
}
