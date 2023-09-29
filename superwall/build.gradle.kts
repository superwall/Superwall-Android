
import java.text.SimpleDateFormat
import java.util.Date
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.ByteArrayOutputStream

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization") version "1.8.21"
}


version = "0.0.1"

android {
    compileSdk = 33
    namespace = "com.superwall.sdk"

    defaultConfig {
        minSdkVersion(26)
        targetSdkVersion(33)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true


        val gitSha = project.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            standardOutput = ByteArrayOutputStream()
        }.toString().trim()
        buildConfigField("String", "GIT_SHA", "\"${gitSha}\"")

        val currentTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(Date())
        buildConfigField("String", "BUILD_TIME", "\"${currentTime}\"")

        buildConfigField("String", "SDK_VERSION", "\"${version}\"")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.4.7"
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    testOptions { }
}

dependencies {
    implementation("androidx.core:core:1.6.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.8.0")
    implementation("androidx.core:core-ktx:1.6.0")
    implementation("com.android.billingclient:billing:5.2.1")
    implementation("androidx.core:core-ktx:1.8.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")

    implementation(platform("androidx.compose:compose-bom:2022.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20210307")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

    implementation("androidx.browser:browser:1.5.0")
    implementation("com.google.code.gson:gson:2.8.5")
}
