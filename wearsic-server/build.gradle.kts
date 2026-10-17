// Wearsic Server — source-level rewrite of the previously jar-only backend.
// Builds against the dependency versions proven in the Termux deployment
// (Ktor 2.3.12, NewPipeExtractor v0.26.4 via JitPack, sqlite-jdbc).
//
// `./gradlew :wearsic-server:installDist` produces
// wearsic-server/build/install/wearsic-server/{bin,lib} — the same shape as
// the existing wearsic-server/{bin,lib} folders, so run-termux.sh keeps
// working unmodified.

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

// Version is owned by ServerVersion.kt (single source of truth). The build
// parses it from source so the JAR name, manifest and /health always agree.
version = {
    val src = file("src/main/kotlin/com/wearsic/server/ServerVersion.kt").readText()
    Regex("VERSION\\s*:\\s*String\\s*=\\s*\"([^\"]+)\"").find(src)
        ?.groupValues?.get(1)
        ?: throw GradleException("Could not parse ServerVersion.VERSION from ServerVersion.kt")
}()

val ktorVersion = "2.3.12"
val coroutinesVersion = "1.7.1"
val serializationVersion = "1.7.3"

dependencies {
    // --- Ktor server (CIO engine — matches original deployment) ---
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-host-common-jvm:$ktorVersion")
    // Range handling for file responses (cached transcodes): byte-exact
    // 206s instead of re-transferring the whole song per range request.
    implementation("io.ktor:ktor-server-partial-content-jvm:$ktorVersion")
    // gzip for JSON responses. Over Tailscale Funnel (relayed link, 50-150ms
    // RTT) bytes are the scarce resource: list payloads shrink ~3-5x. An
    // allow-list keeps audio streams out of the compressor — they are already
    // compressed and Range requests must stay byte-exact.
    implementation("io.ktor:ktor-server-compression-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")

    // --- Ktor client (CIO engine — used for BOTH NewPipe extraction requests
    //     and the audio proxy, replacing the old raw HttpURLConnection path) ---
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")

    // --- Coroutines / serialization ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:$serializationVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

    // --- Extraction ---
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.4")

    // --- Persistence ---
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")

    // --- Logging ---
    implementation("ch.qos.logback:logback-classic:1.5.15")

    // --- Tests ---
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
}

application {
    // Matches the com.wearsic.server package used by the previous compiled
    // jar, so nothing about the deployment path changes.
    mainClass.set("com.wearsic.server.ApplicationKt")
}

// Stamp the version into the JAR manifest so a deployed install can be
// identified without source access:
//   unzip -p wearsic-server/lib/wearsic-server-<v>.jar META-INF/MANIFEST.MF
// Configured eagerly (not in doLast), so the configuration cache stays happy.
tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "wearsic-server",
            "Implementation-Version" to project.version,
        )
    }
}

kotlin {
    // Java 17 — matches the Termux deployment (openjdk-17).
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
