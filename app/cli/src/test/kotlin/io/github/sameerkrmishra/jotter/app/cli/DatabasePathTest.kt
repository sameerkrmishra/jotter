package io.github.sameerkrmishra.jotter.app.cli

import com.github.ajalt.clikt.core.CliktError
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The order of the locations is tested with `linuxDatabasePath`, in core:storage. */
class DatabasePathTest {
    private fun path(
        option: String? = null,
        vararg environment: Pair<String, String>,
    ) = databasePath(option, mapOf(*environment)::get, "/fallback")

    @Test
    fun theLocationBecomesAPath() {
        assertEquals(Path.of("/a/b.db"), path("/a/b.db"))
    }

    @Test
    fun aRelativeHomeIsNoUsableLocation() {
        val error = assertFailsWith<CliktError> { path(null, "HOME" to "relative") }

        assertContains(error.message.orEmpty(), "no database location")
    }

    @Test
    fun noHomeAtAllIsNoUsableLocation() {
        val error = assertFailsWith<CliktError> { databasePath(null, { null }, "?") }

        assertContains(error.message.orEmpty(), "no database location")
    }

    @Test
    fun aPathTheFileSystemCannotHoldIsAnError() {
        val error = assertFailsWith<CliktError> { path("a\u0000b") }

        assertContains(error.message.orEmpty(), "not a valid path")
    }
}
