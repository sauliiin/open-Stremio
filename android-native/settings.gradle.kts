pluginManagement {
    // Kotlin 2.4 metadata requires R8 9.1.29+. AGP 8.13 can use that
    // compiler directly; without the override it emits hundreds of metadata
    // parse warnings while shrinking the release and may discard metadata.
    buildscript {
        repositories {
            mavenCentral()
            maven(url = "https://storage.googleapis.com/r8-releases/raw")
        }
        dependencies {
            classpath("com.android.tools:r8:9.1.29")
        }
    }
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

// Lets modules refer to each other as `projects.core.model` instead of
// stringly-typed paths, so a renamed module fails at configuration time.
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "mdblist-hub-tv"

include(
    ":app",
    ":core:model",
    ":core:network",
    ":core:database",
    ":core:data",
    ":core:ui",
    ":player",
    // Not shipped. Builds a `com.android.test` APK that drives the release
    // app on a device to record which code its startup actually runs; the
    // result is baked into the release APK by the plugin.
    ":baselineprofile",
)
