package io.github.sameerkrmishra.jotter.backend.webdav

import androidx.test.platform.app.InstrumentationRegistry

/**
 * An instrumentation argument of the same name, since a device test sees no environment of the
 * host. The live tests do not run on a device (see build.gradle.kts), so nothing passes one yet.
 */
internal actual fun environmentVariable(name: String): String? =
    InstrumentationRegistry.getArguments().getString(name)
