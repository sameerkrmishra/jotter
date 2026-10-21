plugins {
    id("jotter.coverage")
}

dependencies {
    // Every module whose coverage counts towards the gate.
    kover(projects.core.model)
}
