import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("jotter.kotlin-jvm")
    id("jotter.compose")
    id("jotter.quality")
}

// The JDK whose jlink image the packages bundle: the build's toolchain, named here because the
// AppImage also takes its `java` launcher (ADR-0020).
val packagingJdk =
    javaToolchains
        .launcherFor { languageVersion = JavaLanguageVersion.of(21) }
        .map { it.metadata.installationPath }

val appId = "io.github.sameerkrmishra.jotter"
val icon = rootProject.file("fastlane/metadata/android/en-US/images/icon.png")

compose.desktop {
    application {
        mainClass = "io.github.sameerkrmishra.jotter.app.desktop.MainKt"
        javaHome = packagingJdk.get().asFile.absolutePath

        nativeDistributions {
            targetFormats(TargetFormat.AppImage)
            packageName = "jotter"
            // gradle.properties, as for the Android app (ADR-0019).
            packageVersion = providers.gradleProperty("jotter.versionName").get()
            description = "Local-first notes"
            vendor = "Jotter contributors"
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.instrument", "jdk.unsupported")
            linux {
                iconFile.set(icon)
            }
        }
    }
}

// The CLI's directory for the AppImage, from app:cli's `appImageCli`.
val appImageCli = configurations.dependencyScope("appImageCli")
val appImageCliFiles =
    configurations.resolvable("appImageCliFiles") {
        extendsFrom(appImageCli.get())
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named("jotter-appimage-cli"))
        }
    }

/*
 * The AppImage's contents (ADR-0020): the distributable in `opt/jotter`, with the JDK's `java`
 * launcher added to its runtime for the CLI in `opt/jotter-cli`, and the desktop entry, AppStream
 * metadata and icon. `scripts/appimage.sh` turns it into the AppImage. Modes are normalized so
 * that the image does not depend on the umask: 755 for directories and executables, 644 otherwise.
 */
tasks.register<Sync>("appImageDir") {
    // Local copies: a task's actions may not hold the build script itself.
    val appId = appId
    into(layout.buildDirectory.dir("appimage/Jotter.AppDir"))
    from("src/appimage")
    from("src/appimage/$appId.desktop") { into("usr/share/applications") }
    from(icon) { rename { "$appId.png" } }
    from(icon) { rename { ".DirIcon" } }
    from(icon) {
        into("usr/share/icons/hicolor/512x512/apps")
        rename { "$appId.png" }
    }
    from(tasks.named("createDistributable")) { into("opt") }
    from(packagingJdk.map { it.file("bin/java") }) { into("opt/jotter/lib/runtime/bin") }
    from(appImageCliFiles) { into("opt/jotter-cli") }
    dirPermissions { unix("rwxr-xr-x") }
    eachFile {
        val mode = if (permissions.user.execute) "rwxr-xr-x" else "rw-r--r--"
        permissions { unix(mode) }
    }
}

dependencies {
    implementation(projects.app.sharedUi)
    // The hub's default port.
    implementation(projects.backend.peer)
    implementation(libs.compose.desktop.linux.x64)
    runtimeOnly(libs.kotlinx.coroutines.swing)

    appImageCli(projects.app.cli)

    // To hold a lock on a test database.
    testImplementation(libs.androidx.sqlite)
    testImplementation(libs.androidx.sqlite.bundled)
}
