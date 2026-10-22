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
        maven(url = "https://jitpack.io")
    }
}
rootProject.name = "MusicVault"
include(":app")

// Instrumentation infrastructure is explicitly opt-in and absent from normal release builds.
if (providers.gradleProperty("musicvault.benchmark").orNull == "true") {
    include(":benchmark")
}
