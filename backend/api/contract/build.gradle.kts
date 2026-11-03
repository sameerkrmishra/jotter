plugins {
    id("jotter.kmp-library")
    id("jotter.quality")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.backend.api)
            // Only test source sets depend on this module, so kotlin.test stays out of production.
            api(kotlin("test"))
            api(libs.kotlinx.coroutines.test)
            implementation(libs.atomicfu)
        }
        // On these targets @Test comes from the JUnit flavour. Gradle only picks it by itself for
        // test source sets, and this suite lives in a main one.
        jvmMain.dependencies {
            api(kotlin("test-junit"))
        }
        androidMain.dependencies {
            api(kotlin("test-junit"))
        }
    }
}
