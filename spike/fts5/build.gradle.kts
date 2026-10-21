// Throwaway spike for ADR-0002: FTS5 through SQLDelight on the JVM and Android.
// Not part of the product build. See docs/spikes/0001-fts5-sqldelight.md.

plugins {
    id("jotter.kmp-library")
    id("app.cash.sqldelight") version "2.4.1"
}

val sqldelight = "2.4.1"
val androidxSqlite = "2.7.1"
val syncGeneration = findProperty("spike.sync") != null

sqldelight {
    databases {
        create("SpikeDatabase") {
            packageName.set("io.github.sameerkrmishra.jotter.spike.fts5.db")
            // The eygraber androidx driver only accepts an async schema. -Pspike.sync switches to
            // synchronous generation and runs only src/jvmSyncTest (./gradlew :spike:fts5:jvmTest).
            generateAsync.set(!syncGeneration)
            (findProperty("spike.dialect") as String?)?.let { dialect("app.cash.sqldelight:$it:$sqldelight") }
        }
    }
}

kotlin {
    android {
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("app.cash.sqldelight:runtime:$sqldelight")
            implementation("app.cash.sqldelight:async-extensions:$sqldelight")
            implementation("androidx.sqlite:sqlite:$androidxSqlite")
            implementation("androidx.sqlite:sqlite-bundled:$androidxSqlite")
            implementation("com.eygraber:sqldelight-androidx-driver:0.2.2")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        jvmMain.dependencies {
            implementation("app.cash.sqldelight:sqlite-driver:$sqldelight")
        }
        if (syncGeneration) jvmTest { kotlin.setSrcDirs(listOf("src/jvmSyncTest/kotlin")) }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
        }
        androidMain.dependencies {
            implementation("app.cash.sqldelight:android-driver:$sqldelight")
            implementation("com.github.requery:sqlite-android:3.49.0")
        }
        getByName("androidDeviceTest").dependencies {
            implementation(kotlin("test-junit"))
            implementation("androidx.test:runner:1.7.0")
            implementation("androidx.test.ext:junit:1.3.0")
        }
    }
}
