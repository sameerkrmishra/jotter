pluginManagement {
    // Convention plugins (jotter.*) live in this included build.
    includeBuild("build-logic")
    repositories {
        // Only Android and AndroidX artifacts come from Google's repository, so Play Services
        // and Firebase can never be resolved by accident (F-Droid). Other com.google groups
        // (Guava, protobuf, ...) resolve from Maven Central. This filter is repeated in the
        // block below and in build-logic/settings.gradle.kts; keep all three copies in sync.
        // (pluginManagement {} is evaluated on its own and cannot share code with the rest.)
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle download a JDK 21 toolchain (also for the daemon) when none is installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        // Same filter as in pluginManagement above; keep in sync.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "jotter"

include(":core:model")
