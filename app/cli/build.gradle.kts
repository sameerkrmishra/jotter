import org.gradle.jvm.application.scripts.TemplateBasedScriptGenerator

plugins {
    id("jotter.jvm-app")
    id("jotter.quality")
}

application {
    applicationName = "jotter"
    mainClass = "io.github.sameerkrmishra.jotter.app.cli.MainKt"
}

// A smaller, faster script than Gradle's, with the JVM flags for startup. See README.md.
tasks.startScripts {
    val template = file("src/main/scripts/start-script.template")
    inputs.file(template)
    (unixStartScriptGenerator as TemplateBasedScriptGenerator).template =
        resources.text.fromFile(template)
}

// The CLI inside the desktop app's AppImage (ADR-0020): the same jars, and a start script that
// runs the runtime bundled there. app:desktop takes the directory through `appImageCli`.
val appImageStartScripts =
    tasks.register<CreateStartScripts>("appImageStartScripts") {
        val template = file("src/main/scripts/appimage-script.template")
        inputs.file(template)
        applicationName = "jotter"
        mainClass = application.mainClass
        classpath = files(tasks.jar, configurations.runtimeClasspath)
        outputDir = layout.buildDirectory.dir("appimage-scripts").get().asFile
        (unixStartScriptGenerator as TemplateBasedScriptGenerator).template =
            resources.text.fromFile(template)
    }

val appImageDist =
    tasks.register<Sync>("appImageDist") {
        into(layout.buildDirectory.dir("appimage/jotter-cli"))
        from(appImageStartScripts) {
            into("bin")
            exclude("*.bat")
        }
        from(files(tasks.jar, configurations.runtimeClasspath)) { into("lib") }
    }

configurations.consumable("appImageCli") {
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named("jotter-appimage-cli"))
    }
    outgoing.artifact(appImageDist.map { it.destinationDir }) { builtBy(appImageDist) }
}

// StartScriptTest runs the real start script from the installed distribution.
tasks.test {
    dependsOn(tasks.installDist)
    val installation = layout.buildDirectory.dir("install/jotter")
    inputs.dir(installation).withPathSensitivity(PathSensitivity.RELATIVE)
    jvmArgumentProviders.add { listOf("-Djotter.installation=${installation.get().asFile}") }
}

dependencies {
    implementation(projects.core.storage)
    implementation(projects.core.sync)
    implementation(projects.app.sync)
    implementation(projects.interop.keepImport)
    implementation(projects.interop.markdownExport)
    // The exception NoteStore.open lets through, to report it without a stack trace.
    implementation(libs.androidx.sqlite)
    implementation(libs.clikt.core)

    testImplementation(libs.clikt)
    // To make conflicts with core:sync between two databases.
    testImplementation(projects.backend.api.contract)
    testImplementation(libs.kotlinx.coroutines.core)
    // To hold locks on a test database and to make files that are not Jotter's.
    testImplementation(libs.androidx.sqlite.bundled)
    // A real PC hub on 127.0.0.1, for `jotter backends add peer`.
    testImplementation(projects.server)
}
