import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val liveTestProps = Properties().apply {
    val f = rootProject.file("local-test.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun liveTestValue(key: String, envKey: String): String {
    return System.getenv(envKey) ?: liveTestProps.getProperty(key) ?: ""
}

android {
    namespace = "com.voidnullvalue.icseelocal"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.voidnullvalue.icseelocal"
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = "0.15.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Live-hardware test parameters. These are OPT-IN and never committed:
        // see local-test.properties.example / TESTING.md. Falls back to empty
        // strings so ordinary unit tests never touch real hardware.
        buildConfigField("String", "ICSEE_TEST_HOST", "\"${liveTestValue("ICSEE_TEST_HOST", "ICSEE_TEST_HOST")}\"")
        buildConfigField("String", "ICSEE_TEST_PORT", "\"${liveTestValue("ICSEE_TEST_PORT", "ICSEE_TEST_PORT")}\"")
        buildConfigField("String", "ICSEE_TEST_USERNAME", "\"${liveTestValue("ICSEE_TEST_USERNAME", "ICSEE_TEST_USERNAME")}\"")
        buildConfigField("String", "ICSEE_TEST_PASSWORD", "\"${liveTestValue("ICSEE_TEST_PASSWORD", "ICSEE_TEST_PASSWORD")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sign the release build with the standard debug key so the produced
            // app-release.apk is directly installable (sideload). Swap this for a
            // real keystore/signingConfig when publishing through a store.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.datastore:datastore-preferences:1.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Live video: this camera's DVRIP media channel (OPMonitor/msg 1412) claims
    // successfully but doesn't deliver media bytes (see PROTOCOL_STATUS.md). It
    // also exposes a standard RTSP/RTP stream (H.265 + PCMA) confirmed live
    // 2026-07-01 -- media3's RTSP extension handles that natively.
    implementation("androidx.media3:media3-exoplayer:1.10.0")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.10.0")
    implementation("androidx.media3:media3-ui:1.10.0")

    // Easter-egg dance visual: plays a YouTube clip on-screen (muted) while the
    // camera speaker plays the local dance track and PTZ dances to the beat.
    // Uses YouTube's official IFrame Player API (via this well-maintained
    // wrapper) rather than a hand-rolled WebView <iframe>, which rendered blank
    // -- the player must be driven through the IFrame API handshake to work.
    implementation("com.pierfrancescosoffritti.androidyoutubeplayer:core:12.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("app.cash.turbine:turbine:1.2.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
