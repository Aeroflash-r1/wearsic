plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.screenshot)
}

android {
  namespace = "com.wearsic.app"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.wearsic.app"
    minSdk = 26
    targetSdk = 36
    versionCode = 58
    versionName = "1.9.28"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
            storeFile = file(keystorePath)
            storePassword = System.getenv("STORE_PASSWORD")
            keyAlias = "upload"
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
      signingConfig = signingConfigs.getByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debug") }

    // On-device diagnostic build: R8-minified like release (so it is smooth on
    // the watch — plain debug bytecode is unusably slow) but signed with the
    // debug key so it installs without the release keystore. Its own
    // applicationId lets it sit next to the release app for A/B testing.
    create("minified") {
      initWith(getByName("release"))
      signingConfig = signingConfigs.getByName("debug")
      applicationIdSuffix = ".minified"
      versionNameSuffix = "-minified"
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  // Run builds/tests on a JDK 21 toolchain (auto-provisioned by the foojay
  // resolver): Robolectric's Android SDK 36 sandbox refuses to start on
  // Java <= 20. Bytecode targets stay at Java 11 via compileOptions.
  java {
    toolchain {
      languageVersion = JavaLanguageVersion.of(21)
    }
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  // Opt in to the experimental screenshotTest source set (layoutlib renders).
  experimentalProperties["android.experimental.enableScreenshotTest"] = true
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.wear.compose.material3)
  implementation(libs.androidx.wear.compose.foundation)
  implementation(libs.androidx.wear.compose.navigation)
  implementation(libs.androidx.wear.tooling.preview)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.session)
  implementation(libs.androidx.media3.common)
  implementation(libs.androidx.media3.datasource.okhttp)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)
  implementation(libs.coil.compose)
    implementation(libs.androidx.tiles)
    implementation(libs.androidx.ongoing)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.okhttp)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.serialization.core)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.androidx.wear.compose.ui.tooling)
  // Compose Preview Screenshot Testing: real Skia/font rendering of @Preview
  // composables at true Galaxy Watch7 round dimensions.
  screenshotTestImplementation(libs.screenshot.validation.api)
  screenshotTestImplementation(libs.androidx.compose.ui.tooling)
}

// Robolectric ships no linux-aarch64 native runtime (upstream limitation,
// verified against the loader sources through 4.17 — only x86_64 Linux,
// x86_64/macOS-arm, and Windows are supported). Those tests can never
// start on ARM Linux phones/boxes: they fail 100%, hiding real results.
// Exclude them there so `./gradlew test` is green on every dev machine;
// CI (x86_64) and Macs still run the full suite. When adding a Robolectric
// (or Roborazzi) test, append its class here or ARM builds go red.
val robolectricOnlyClasses = listOf(
  "com.wearsic.app.AlbumBrowsingTest",
  "com.wearsic.app.DownloadOwnershipTest",
  "com.wearsic.app.ExampleRobolectricTest",
  "com.wearsic.app.GreetingScreenshotTest",
  "com.wearsic.app.OfflinePersistenceTest",
  "com.wearsic.app.PairingImportTest",
  "com.wearsic.app.PlayerInteractionTest",
  "com.wearsic.app.RecentPlaybackIdentityTest",
  "com.wearsic.app.RelaunchWithDataTest",
  "com.wearsic.app.ScreenGalleryTest",
  "com.wearsic.app.SearchResultsRenderTest"
)

tasks.withType<Test>().configureEach {
  // CI diagnostic: log every test as it starts, so if the suite ever wedges,
  // the CI log names the test it died in instead of going silent until the
  // job backstop kills the run (see the 2026-10-18 Robolectric wedge).
  testLogging { events("started") }
  val arch = System.getProperty("os.arch") ?: ""
  val os = System.getProperty("os.name")?.lowercase() ?: ""
  if (os.contains("linux") && arch == "aarch64") {
    logger.lifecycle(
      "Excluding ${robolectricOnlyClasses.size} Robolectric classes " +
        "(no linux-aarch64 native runtime) — full suite runs on x86_64/macOS"
    )
    robolectricOnlyClasses.forEach { filter.excludeTestsMatching(it) }
  }
}
