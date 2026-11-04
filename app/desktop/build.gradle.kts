import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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

/*
 * Three changes to the distributable, for the AppImage (ADR-0020), made right after jpackage:
 *
 * - Compose 1.12.2 takes the native library out of Skiko's runtime jar and writes what is left
 *   with `new ZipEntry(name)`, so its entries carry the build's clock time, and the jar's hashed
 *   name in lib/app and jotter.cfg changes with it. The jar is written again with sorted, stored
 *   entries at a fixed time, renamed after its new content, and jotter.cfg follows.
 * - Compose's jlink strips the runtime's bin/; the JDK's own `java` launcher goes back, for the
 *   CLI in the AppImage.
 * - The runtime's base class data archive (lib/server/classes.jsa), which jlink does not make:
 *   `java -Xshare:dump`, in an empty environment and with a fixed heap and collector, gives the
 *   same bytes on every run and machine (the reproducibility check covers it), and speeds up the
 *   start of both the app and the CLI.
 *
 * Compose registers its tasks after the build script runs, hence the name filter.
 */
tasks.named { it == "createDistributable" }.configureEach {
    val appDir = layout.buildDirectory.dir("compose/binaries/main/app/jotter")
    val javaLauncher = packagingJdk.map { it.file("bin/java").asFile }
    inputs.file(javaLauncher)
    doLast {
        val app = appDir.get().asFile
        val lib = app.resolve("lib/app")
        val jar =
            lib
                .listFiles { f ->
                    f.name.matches(Regex("skiko-awt-runtime-.*-[0-9a-f]+\\.jar"))
                }!!
                .single()
        val entries =
            ZipFile(jar).use { zip ->
                zip
                    .entries()
                    .toList()
                    .sortedBy { it.name }
                    .map { it.name to zip.getInputStream(it).readBytes() }
            }
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            for ((name, data) in entries) {
                val crc = CRC32()
                crc.update(data)
                val entry = ZipEntry(name)
                entry.method = ZipEntry.STORED
                entry.size = data.size.toLong()
                entry.compressedSize = data.size.toLong()
                entry.crc = crc.value
                // Local time without a zone, so the time zone of the build cannot change it.
                entry.setTimeLocal(LocalDateTime.of(1980, 2, 1, 0, 0))
                out.putNextEntry(entry)
                out.write(data)
                out.closeEntry()
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
        val hash = digest.take(16).joinToString("") { "%02x".format(it) }
        val renamed = jar.name.replace(Regex("-[0-9a-f]+\\.jar$"), "-$hash.jar")
        jar.delete()
        lib.resolve(renamed).writeBytes(bytes.toByteArray())
        val cfg = lib.resolve("jotter.cfg")
        cfg.writeText(cfg.readText().replace("/${jar.name}\n", "/$renamed\n"))
        check(renamed in cfg.readText()) { "jotter.cfg does not name $renamed" }

        val launcher = app.resolve("lib/runtime/bin/java")
        javaLauncher.get().copyTo(launcher, overwrite = true)
        launcher.setExecutable(true, false)
        val dump =
            // A fixed heap and collector: by default both follow the build machine's memory and
            // processors, and so did the archive (the same for 1, 4 and 12 processors with these).
            ProcessBuilder(launcher.path, "-Xmx128m", "-Xms128m", "-XX:+UseG1GC", "-Xshare:dump")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        // An empty environment: the locale is archived (C.UTF-8 and POSIX give different
        // archives), and JAVA_TOOL_OPTIONS and the like must not reach it either.
        dump.environment().clear()
        check(dump.start().waitFor() == 0) { "java -Xshare:dump failed" }
        check(app.resolve("lib/runtime/lib/server/classes.jsa").isFile) { "no classes.jsa" }
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
 * The AppImage's contents (ADR-0020): the distributable in `opt/jotter` (whose runtime has `java`
 * for the CLI, see above), the CLI in `opt/jotter-cli`, and the desktop entry, AppStream
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
