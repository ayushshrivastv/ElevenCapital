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
    }
}

rootProject.name = "ElevenCapital"
include(":core")

// Core data tests can run on the 8GB Mac before the Android SDK is installed.
// Android Studio import/build: pass -PandroidBuild=true (see README).
if (providers.gradleProperty("androidBuild").orNull == "true") {
    include(":app")
}
