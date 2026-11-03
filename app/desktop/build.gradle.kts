import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("jotter.kotlin-jvm")
    id("jotter.compose")
    id("jotter.quality")
}

compose.desktop {
    application {
        mainClass = "io.github.sameerkrmishra.jotter.app.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.AppImage)
            packageName = "jotter"
            packageVersion = "0.1.0"
            description = "Local-first notes"
            vendor = "Jotter contributors"
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.instrument", "jdk.unsupported")
        }
    }
}

dependencies {
    implementation(projects.app.sharedUi)
    implementation(libs.compose.desktop.linux.x64)
    runtimeOnly(libs.kotlinx.coroutines.swing)

    // To hold a lock on a test database.
    testImplementation(libs.androidx.sqlite)
    testImplementation(libs.androidx.sqlite.bundled)
}
