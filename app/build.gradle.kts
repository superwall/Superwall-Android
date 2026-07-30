plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.serialization)
    alias(libs.plugins.dropshot)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.superwall.superapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.superwall.superapp"
        minSdk = 23
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        testInstrumentationRunnerArguments["no-isolated-storage"] = "1"

        // Enables Superwall test mode in the preload benchmark (billing is
        // unavailable on CI emulators). Pass -PbenchmarkTestMode=true when
        // assembling the benchmark APKs; defaults to false everywhere else.
        buildConfigField(
            "boolean",
            "BENCHMARK_TEST_MODE",
            (project.findProperty("benchmarkTestMode") ?: "false").toString(),
        )
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        animationsDisabled = true
        resultsDir
    }

    buildTypes {
        debug {
            isMinifyEnabled = true
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "META-INF/LICENSE.md"
        resources.excludes += "META-INF/LICENSE-notice.md"
    }
}

// RevenueCat isn't Billing 9 compatible yet, so pin Billing for this app.
configurations.all {
    resolutionStrategy.force("com.android.billingclient:billing:8.3.0")
}

dependencies {
    // Billing
    implementation(libs.billing)
    implementation(libs.revenue.cat)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)

    // Core
    implementation(libs.core)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Superwall
    implementation(project(":superwall"))
    implementation(project(":superwall-compose"))

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Test (Android)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.test.core)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.rules)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    androidTestImplementation(libs.uiautomator)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.mockk.core)
    androidTestUtil(libs.orchestrator)

    // Debug
    // debugImplementation(libs.leakcanary.android)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
}

tasks.register<Exec>("pullEventTimelines") {
    description = "Pull event timeline JSON files from the device after instrumentation tests"
    group = "verification"
    val outputDir = layout.buildDirectory.dir("outputs/event-timelines").get().asFile
    doFirst {
        outputDir.mkdirs()
    }
    commandLine(
        "adb", "pull",
        "/sdcard/Download/superwall-event-timelines/.",
        outputDir.absolutePath,
    )
    isIgnoreExitValue = true
}

tasks.register<Exec>("clearEventTimelines") {
    description = "Clear event timeline files from the device"
    group = "verification"
    commandLine("adb", "shell", "rm", "-rf", "/sdcard/Download/superwall-event-timelines/")
    isIgnoreExitValue = true
}

tasks.register<Exec>("pullBenchmarkResults") {
    description = "Pull paywall preload benchmark JSON files from the device after instrumentation tests"
    group = "verification"
    val outputDir = layout.buildDirectory.dir("outputs/benchmark").get().asFile
    doFirst {
        outputDir.mkdirs()
    }
    commandLine(
        "adb", "pull",
        "/sdcard/Download/superwall-benchmark/.",
        outputDir.absolutePath,
    )
    isIgnoreExitValue = true
}

tasks.register<Exec>("clearBenchmarkResults") {
    description = "Clear paywall preload benchmark files from the device"
    group = "verification"
    commandLine("adb", "shell", "rm", "-rf", "/sdcard/Download/superwall-benchmark/")
    isIgnoreExitValue = true
}
