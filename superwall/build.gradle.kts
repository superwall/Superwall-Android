
import java.text.SimpleDateFormat
import java.util.Date
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.ByteArrayOutputStream

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization") version "1.8.21"
    // Maven publishing
    id("maven-publish")
}


version = "1.0.0-alpha01"

android {
    compileSdk = 33
    namespace = "com.superwall.sdk"

    defaultConfig {
        minSdkVersion(26)
        targetSdkVersion(33)

        aarMetadata {
          minCompileSdk = 26
        }

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

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}


publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.superwall.sdk"
            artifactId = "superwall-android"
            version = version

            afterEvaluate {
                from(components["release"])
            }
        }
    }

    repositories {
        mavenLocal()

    }
//        maven {
//            name = "MavenCentral"
//            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
//        }
//    }
}

dependencies {
    implementation("androidx.lifecycle:lifecycle-process:2.2.0")
    // Billing
    implementation(libs.billing)

    // Browser
    implementation(libs.browser)


    // Core
    implementation(libs.core)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.core.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)

    // Serialization
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.gson)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // ??? Not sure if we need this
    // testImplementation("org.json:json:20210307")

    // Test (Android)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
