plugins {
    id("jotter.kmp-library")
    id("jotter.quality")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
