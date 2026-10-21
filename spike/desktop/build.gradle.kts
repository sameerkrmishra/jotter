import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("jotter.desktop-app")
    id("jotter.quality")
}

dependencies {
    implementation(libs.compose.desktop.linux.x64)
    implementation(libs.compose.material3)
}

compose.desktop {
    application {
        mainClass = "io.github.sameerkrmishra.jotter.spike.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.AppImage, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "jotter-spike"
            packageVersion = "0.1.0"
            description = "Jotter desktop packaging spike"
            vendor = "Jotter contributors"
            licenseFile.set(rootProject.file("LICENSE"))
            // Only the JDK modules the app needs; see `suggestRuntimeModules`.
            modules("java.instrument", "jdk.unsupported")
            linux {
                appCategory = "Office"
                debMaintainer = "jotter@example.invalid"
                menuGroup = "Office"
            }
        }

        buildTypes.release.proguard {
            isEnabled =
                providers.gradleProperty("spike.proguard").map { it.toBoolean() }.orElse(false)
            optimize = true
        }
    }
}
