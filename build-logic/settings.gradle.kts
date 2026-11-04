// Where this build's own plugins (kotlin-dsl) and their dependencies come from. Without it, only
// the Gradle Plugin Portal, which proxies Maven Central for the Kotlin artifacts and once answered
// "not found" for them in CI (the desktop reproducibility job on 1cc2b9a). Maven Central first.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        // Same filter as in the root settings.gradle.kts; keep in sync.
        google {
            content {
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("androidx")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
