import org.gradle.api.publish.maven.MavenPom

plugins {
    id("java-library")
    id("maven-publish")
    id("signing")
    id("com.gradleup.nmcp") version "1.6.2"
    id("com.gradleup.nmcp.aggregation") version "1.6.2"
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

// The minimum Java is 8: it still runs on the long-lived servers this library is meant for. Newer JDKs
// warn that release 8 is obsolete; that is expected.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-options")
}

tasks.jar {
    manifest {
        // A stable module name for the module path until there is a module descriptor.
        attributes("Automatic-Module-Name" to "dev.avelar.mapnik")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Runtime dependency: loads the native C shim.
    implementation("net.java.dev.jna:jna:5.19.1")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The C shim is built separately with CMake (see README). Unit tests skip when the
// shared library is not found.
val nativeDir = layout.buildDirectory.dir("native")

// ============================================================================
// Prebuilt native libraries
// ============================================================================
// natives/linux/Dockerfile builds Mapnik, the shim and everything they need into
// build/natives/<platform>. This packages each directory found there as a jar, which the loader
// unpacks at run time.

// An add-on target (such as the PostGIS plugin) has a name after the platform, like linux-x86_64-postgis.
class NativesTarget(val platform: String, val label: String, val addOn: String? = null) {
    val directory = layout.buildDirectory.dir("natives/$platform")
    val present: Boolean get() = directory.get().asFile.resolve("MANIFEST").exists()
    val artifactId = "mapnik-java-natives-$platform"
    val id: String = platform.split("-").joinToString("") { it.replaceFirstChar(Char::uppercase) }.replace("_", "")
}

val nativesTargets = listOf(
    NativesTarget("linux-x86_64", "Linux x86_64"),
    NativesTarget("linux-aarch64", "Linux aarch64"),
    NativesTarget("macos-aarch64", "macOS aarch64 (Apple Silicon)"),
    NativesTarget("macos-x86_64", "macOS x86_64 (Intel)"),
    NativesTarget("linux-x86_64-postgis", "Linux x86_64, PostGIS plugin", "postgis"),
    NativesTarget("linux-aarch64-postgis", "Linux aarch64, PostGIS plugin", "postgis")
)

// -PnativesExtras=postgis adds the named add-on bundles to the class path of -PbundledNatives test runs.
val testedExtras = providers.gradleProperty("nativesExtras").orElse("").get().split(",").filter { it.isNotBlank() }
val useBundledNatives = providers.gradleProperty("bundledNatives").isPresent

// The platform the integration tests use the bundle of: -PnativesPlatform=, else this machine's.
val hostPlatform = run {
    val arch = System.getProperty("os.arch")
    val os = if (System.getProperty("os.name").lowercase().startsWith("mac")) "macos" else "linux"
    os + "-" + if (arch == "aarch64" || arch == "arm64") "aarch64" else "x86_64"
}
val testedPlatform = providers.gradleProperty("nativesPlatform").orElse(hostPlatform).get()

// Maven Central wants a sources and a javadoc jar for every artifact. These hold a note instead.
val nativesNote = layout.buildDirectory.file("natives-note/README.txt")
val writeNativesNote = tasks.register("writeNativesNote") {
    outputs.file(nativesNote)
    doLast {
        nativesNote.get().asFile.apply {
            parentFile.mkdirs()
            writeText("Prebuilt native libraries for mapnik-java: Mapnik, its dependencies, input plugins,\n" +
                "fonts and PROJ data. There is no Java source here. See https://github.com/geovannyAvelar/mapnik-java\n" +
                "and the NOTICE and licenses/ entries inside the main jar for what is bundled and under which licenses.\n")
        }
    }
}

class NativesTasks(val jar: TaskProvider<Jar>, val sources: TaskProvider<Jar>, val javadoc: TaskProvider<Jar>)

val nativesTasks = nativesTargets.associateWith { t ->
    val jar = tasks.register<Jar>("nativesJar${t.id}") {
        description = "Packages the prebuilt ${t.label} native libraries."
        group = "build"
        archiveBaseName.set(t.artifactId)
        from(t.directory) { into("dev/avelar/mapnik/natives/${t.platform}") }
        onlyIf { t.present }
    }
    val sources = tasks.register<Jar>("nativesSourcesJar${t.id}") {
        archiveBaseName.set(t.artifactId)
        archiveClassifier.set("sources")
        from(writeNativesNote)
        onlyIf { t.present }
    }
    val javadoc = tasks.register<Jar>("nativesJavadocJar${t.id}") {
        archiveBaseName.set(t.artifactId)
        archiveClassifier.set("javadoc")
        from(writeNativesNote)
        onlyIf { t.present }
    }
    NativesTasks(jar, sources, javadoc)
}

tasks.register("nativesJar") {
    description = "Packages every prebuilt native bundle found under build/natives."
    group = "build"
    dependsOn(nativesTasks.values.map { it.jar })
}

val testedTarget = nativesTargets.firstOrNull { it.platform == testedPlatform }
if (useBundledNatives && (testedTarget == null || !testedTarget.present)) {
    throw GradleException("-PbundledNatives needs the native bundle for $testedPlatform: run scripts/build-natives-linux.sh first")
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
        add(integrationTest.runtimeOnlyConfigurationName, files(nativesTasks.getValue(testedTarget!!).jar.map { it.archiveFile }))
        for (extra in testedExtras) {
            val addOn = nativesTargets.firstOrNull { it.platform == "$testedPlatform-$extra" }
                ?: throw GradleException("no add-on bundle called $extra for $testedPlatform")
            if (!addOn.present) {
                throw GradleException("-PnativesExtras=$extra needs build/natives/${addOn.platform}: run scripts/build-natives-linux.sh")
            }
            add(integrationTest.runtimeOnlyConfigurationName, files(nativesTasks.getValue(addOn).jar.map { it.archiveFile }))
        }
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
        systemProperty("mapnik.test.extras", testedExtras.joinToString(","))
        // PostGIS tests connect to a database given by PG* environment variables, when there is one.
        for (v in listOf("PGHOST", "PGPORT", "PGUSER", "PGPASSWORD", "PGDATABASE")) {
            System.getenv(v)?.let { environment(v, it) }
        }
    } else {
        systemProperty("jna.library.path", nativeDir.get().asFile.absolutePath)
        systemProperty("mapnik.input.plugins", mapnikInputPlugins.get())
        systemProperty("mapnik.fonts", mapnikFonts.get())
    }
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
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

        for (t in nativesTargets.filter { it.present }) {
            val tasksOfTarget = nativesTasks.getValue(t)
            create<MavenPublication>("natives${t.id}") {
                artifactId = t.artifactId
                artifact(tasksOfTarget.jar)
                artifact(tasksOfTarget.sources)
                artifact(tasksOfTarget.javadoc)
                pom {
                    if (t.addOn == null) {
                        fillPom("mapnik-java natives (${t.label})",
                            "Prebuilt Mapnik and mapnik-java native libraries for ${t.label}, with their dependencies, " +
                                "input plugins, fonts and PROJ data. Add it next to mapnik-java to need nothing installed.",
                            bundlesLgpl = true)
                    } else {
                        val base = t.platform.removeSuffix("-${t.addOn}")
                        fillPom("mapnik-java natives add-on (${t.label})",
                            "The ${t.addOn} input plugin and the libraries only it needs, for the mapnik-java natives of " +
                                "$base. Add it next to mapnik-java-natives-$base; the library finds it by itself.",
                            bundlesLgpl = true)
                        // The add-on is useless alone, so depend on the main natives of the same version.
                        withXml {
                            val deps = asNode().appendNode("dependencies")
                            val dep = deps.appendNode("dependency")
                            dep.appendNode("groupId", project.group.toString())
                            dep.appendNode("artifactId", "mapnik-java-natives-$base")
                            dep.appendNode("version", project.version.toString())
                            dep.appendNode("scope", "runtime")
                        }
                    }
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
        for (t in nativesTargets.filter { it.present }) {
            sign(publishing.publications["natives${t.id}"])
        }
    }
}

// ============================================================================
// Sonatype Central Portal (nmcp)
// Token from central.sonatype.com -> Account -> Generate User Token
// ============================================================================

// One deployment holds the library and the natives of every platform that was built, so a release is
// published whole or not at all. Run: ./gradlew publishAggregationToCentralPortal
nmcpAggregation {
    centralPortal {
        username = System.getenv("SONATYPE_USERNAME") ?: ""
        password = System.getenv("SONATYPE_PASSWORD") ?: ""
        publishingType = "AUTOMATIC"
    }
}

dependencies {
    nmcpAggregation(project(":"))
}
