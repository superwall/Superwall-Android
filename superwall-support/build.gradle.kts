// SuperwallSupport add-on (plan decision 42): Support's native chat, as its own
// AAR beside superwall-compose, so apps without Support ship nothing extra.
// It doesn't depend on :superwall; it will talk to core through
// SuperwallSupportHost (a plain-types bridge), found by class name at configure.
//
// M1 is the transport only. Not published: no maven-publish configuration on purpose.
plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.serialization)
}

android {
    namespace = "com.superwallsupport"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            // Minified when the AAR is built (decision 42); off until the module has a public API to keep.
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.all { test ->
            // End-to-end tests run when SUPPORT_FIXTURE_URL points at paywall-next's fixture server.
            System.getenv("SUPPORT_FIXTURE_URL")?.let { test.environment("SUPPORT_FIXTURE_URL", it) }
            test.testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
        }
    }
}

dependencies {
    // The WebSocket client. Core uses HttpURLConnection, which has no WebSocket; the add-on
    // brings OkHttp (validated in the M0 spike: permessage-deflate, reconnects, Android 15/17).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.lifecycle.process)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
