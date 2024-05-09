
import groovy.json.JsonBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.io.ByteArrayOutputStream

buildscript {
    extra["awsAccessKeyId"] = System.getenv("AWS_ACCESS_KEY_ID") ?: findProperty("aws_access_key_id")
    extra["awsSecretAccessKey"] = System.getenv("AWS_SECRET_ACCESS_KEY") ?: findProperty("aws_secret_access_key")
    extra["sonatypeUsername"] = System.getenv("SONATYPE_USERNAME") ?: findProperty("sonatype_username")
    extra["sonatypePassword"] = System.getenv("SONATYPE_PASSWORD") ?: findProperty("sonatype_password")
}

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("kapt")
    kotlin("plugin.serialization") version "1.8.21"
    // Maven publishing
    id("maven-publish")
    id("signing")
}

version = "1.1.4"

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

        consumerProguardFile("proguard-rules.pro")

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

    packagingOptions {
        resources.excludes += "META-INF/LICENSE.md"
    }

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

            pom {
                name.set("Superwall")
                description.set("Remotely configure paywalls without shipping app updates")
                url.set("https://superwall.com")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://github.com/superwall/Superwall-Android?tab=MIT-1-ov-file#")
                    }
                }
                developers {
                    developer {
                        id.set("yusuftor")
                        name.set("Yusuf Tor")
                        email.set("yusuf@superwall.com")
                    }
                }
                scm {
                    connection.set("scm:git:git@github.com:superwall/Superwall-Android.git")
                    developerConnection.set("scm:git:ssh://github.com:superwall/Superwall-Android.git")
                    url.set("scm:git:https://github.com/superwall/Superwall-Android.git")
                }
            }

            afterEvaluate {
                from(components["release"])
            }
        }
    }

    repositories {
        mavenLocal()

        // Allow us to publish to S3 if we have the credentials
        // but also allow us to publish locally if we don't
        val awsAccessKeyId: String? by extra
        val awsSecretAccessKey: String? by extra
        val sonatypeUsername: String? by extra
        val sonatypePassword: String? by extra
        if (awsAccessKeyId != null && awsSecretAccessKey != null) {
            maven {
                url = uri("s3://mvn.superwall.com/release")
                credentials(AwsCredentials::class.java) {
                    accessKey = awsAccessKeyId
                    secretKey = awsSecretAccessKey
                }
            }
        }

        if (sonatypeUsername != null && sonatypePassword != null) {
            maven {
                url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                credentials(PasswordCredentials::class.java) {
                    username = sonatypeUsername
                    password = sonatypePassword
                }
            }
        }
    }
}

signing {
    sign(publishing.publications["release"])
}

tasks.register("generateBuildInfo") {
    doLast {
        var buildInfo = mapOf("version" to version)
        val jsonOutput = JsonBuilder(buildInfo).toPrettyString()
        val outputFile = File("${project.buildDir}/version.json")
        outputFile.writeText(jsonOutput)
    }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.8.1")
    implementation("androidx.lifecycle:lifecycle-process:2.5.0")
    implementation("androidx.room:room-runtime:2.5.2")
    implementation("androidx.room:room-ktx:2.5.2")
    kapt("androidx.room:room-compiler:2.5.2")
    implementation("androidx.javascriptengine:javascriptengine:1.0.0-beta01")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.6.0")

    implementation("org.threeten:threetenbp:1.6.8")
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
    testImplementation(libs.mockk)
    // ??? Not sure if we need this
    // testImplementation("org.json:json:20210307")

    // Test (Android)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
