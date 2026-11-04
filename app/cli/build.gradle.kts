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

// The version the debug log names (ADR-0019: from gradle.properties only).
tasks.jar {
    val version = providers.gradleProperty("jotter.versionName")
    inputs.property("version", version)
    manifest { attributes("Implementation-Version" to version.get()) }
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
    implementation(projects.core.logging)
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
