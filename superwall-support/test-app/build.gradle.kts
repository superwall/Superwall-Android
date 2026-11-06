// Drives the Support transport on a device or emulator against the fixture server
// (or a Core preview) and logs every transport event. Not shipped.
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
}

android {
    namespace = "com.superwallsupport.testapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.superwall.support.transporttest"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Minified to measure what the add-on (and OkHttp) adds to an app.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":superwall-support"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // For the app's own verify call only.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
