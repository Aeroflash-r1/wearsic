package com.wearsic.server

/**
 * The single source of truth for the server version.
 *
 * `build.gradle.kts` reads the VERSION line below (so the JAR name, manifest
 * and installDist all match), and [HealthResponse] reports it so the Termux
 * release verification (`curl /health`) can assert the deployed build equals
 * the released source.
 */
object ServerVersion {
    // VERSION:<do not remove this marker, build.gradle.kts parses it>
    const val VERSION: String = "1.8.0"
}
