plugins {
    id("jotter.kmp-library")
    id("jotter.quality")
    id("jotter.android-device-test")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotest.property)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
