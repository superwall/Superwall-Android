
import groovy.json.JsonBuilder
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date

buildscript {
    extra["awsAccessKeyId"] = System.getenv("AWS_ACCESS_KEY_ID") ?: findProperty("aws_access_key_id")
    extra["awsSecretAccessKey"] = System.getenv("AWS_SECRET_ACCESS_KEY") ?: findProperty("aws_secret_access_key")
    extra["sonatypeUsername"] = System.getenv("SONATYPE_USERNAME") ?: findProperty("sonatype_username")
    extra["sonatypePassword"] = System.getenv("SONATYPE_PASSWORD") ?: findProperty("sonatype_password")
    extra["signingKeyId"] = System.getenv("GPG_SIGNING_KEY_ID") ?: findProperty("gpg_signing_key_id")
    extra["signingPassword"] = System.getenv("GPG_SIGNING_KEY_PASSPHRASE") ?: findProperty("gpg_signing_key_passphrase")
    extra["signingKey"] = System.getenv("GPG_SIGNING_KEY") ?: findProperty("gpg_signing_key")
}

plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("kapt")
    alias(libs.plugins.serialization) // Maven publishing
    id("maven-publish")
    id("signing")
}

version = "1.4.0-beta.2"

android {
    compileSdk = 34
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

        val gitSha =
            project
                .exec {
                    commandLine("git", "rev-parse", "--short", "HEAD")
                    standardOutput = ByteArrayOutputStream()
                }.toString()
                .trim()
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
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.0"
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    packaging {
        resources.excludes += "META-INF/LICENSE.md"
        resources.excludes += "META-INF/LICENSE-notice.md"
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
    val signingKeyId: String? by extra
    val signingPassword: String? by extra
    val signingKey: String? by extra
    if (signingKey != null && signingKeyId != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
    }
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
    implementation(libs.work.runtime.ktx)
    implementation(libs.lifecycle.process)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
    implementation(libs.javascriptengine)
    implementation(libs.kotlinx.coroutines.guava)

    implementation(libs.threetenbp)
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

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk.core)

    // Test (Android)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.mockk.android)
}
