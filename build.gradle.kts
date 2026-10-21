plugins {
    id("jotter.root")
}

dependencies {
    // Every module whose coverage counts towards the gate.
    kover(projects.core.model)
}
