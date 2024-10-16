// Top-level build file where you can add configuration options common to all sub-projects/modules.
@Suppress("DSL_SCOPE_VIOLATION") // TODO: Remove once KTIJ-19369 is fixed
plugins {
    id("dev.testify") version "3.0.0" apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.jetbrainsKotlinJvm) apply false
}
true

buildscript {
    apply(from = "./scripts/old-agp-auto-downgrade.gradle.kts")
}
