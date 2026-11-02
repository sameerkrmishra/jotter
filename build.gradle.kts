plugins {
    id("jotter.root")
}

dependencies {
    // Every module whose coverage counts towards the gate.
    kover(projects.app.cli)
    kover(projects.app.desktop)
    kover(projects.app.sharedUi)
    kover(projects.core.merge)
    kover(projects.backend.api)
    kover(projects.backend.localfolder)
    kover(projects.core.model)
    kover(projects.core.sqlite)
    kover(projects.core.storage)
    kover(projects.core.sync)
    kover(projects.interop.keepImport)
    kover(projects.interop.markdownExport)
}
