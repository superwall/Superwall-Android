pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://mvn.superwall.com/release") }
    }
}

rootProject.name = "Superwall Example App"
include(":app")

include(":superwall")
project(":superwall").projectDir = file("../superwall")