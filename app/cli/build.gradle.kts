plugins {
    id("jotter.jvm-app")
    id("jotter.quality")
}

application {
    applicationName = "jotter"
    mainClass = "io.github.sameerkrmishra.jotter.app.cli.MainKt"
}

dependencies {
    implementation(projects.core.storage)
    // isSqliteBusy, and the exception NoteStore.open lets through, to report them without a trace.
    implementation(projects.core.sqlite)
    implementation(libs.androidx.sqlite)
    implementation(libs.clikt.core)

    testImplementation(libs.clikt)
    // To hold locks on a test database and to make files that are not Jotter's.
    testImplementation(libs.androidx.sqlite.bundled)
}
