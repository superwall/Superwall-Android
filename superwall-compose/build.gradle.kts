import groovy.json.JsonBuilder
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
    id("maven-publish")
    id("signing")
}

version = "2.0.5"

android {
    namespace = "com.superwall.sdk.composable"
    compileSdk = 34

    defaultConfig {
        minSdk = 22

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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
        release {
            isMinifyEnabled = false
            consumerProguardFiles("consumer-rules.pro")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "../proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.0"
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
            artifactId = "superwall-compose"
            version = version

            pom {
                name.set("Superwall Compose")
                description.set("Remotely configure paywalls without shipping app updates - Jetpack Compose support")
                url.set("https://superwall.com")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://github.com/superwall/Superwall-Android?tab=MIT-1-ov-file#")
                    }
                }
                developers {
                    developer {
                        id.set("ianrumac")
                        name.set("Ian Rumac")
                        email.set("ian@superwall.com")
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
    implementation(platform(libs.compose.bom))
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)

    // Compose
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)
    implementation(project(":superwall"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
