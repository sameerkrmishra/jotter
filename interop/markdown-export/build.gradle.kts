plugins {
    id("jotter.kmp-library")
    id("jotter.quality")
    id("jotter.android-device-test")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Note appears in the export API, okio's FileSystem and Path in the writer's.
            api(projects.core.model)
            api(libs.okio)
        }
        commonTest.dependencies {
            implementation(libs.kotest.property)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
