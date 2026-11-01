package io.github.sameerkrmishra.jotter.app.cli

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabasePathTest {
    private fun path(
        option: String? = null,
        vararg environment: Pair<String, String>,
    ) = databasePath(option, mapOf(*environment)::get, "/fallback")

    @Test
    fun theOptionComesFirst() {
        assertEquals(Path.of("/a/b.db"), path("/a/b.db", "XDG_DATA_HOME" to "/xdg"))
    }

    @Test
    fun anEmptyOptionCountsAsNotGiven() {
        assertEquals(Path.of("/xdg/jotter/jotter.db"), path("", "XDG_DATA_HOME" to "/xdg"))
    }

    @Test
    fun xdgDataHomeComesBeforeHome() {
        val path = path(null, "XDG_DATA_HOME" to "/xdg", "HOME" to "/home/me")

        assertEquals(Path.of("/xdg/jotter/jotter.db"), path)
    }

    @Test
    fun anEmptyOrRelativeXdgDataHomeIsIgnored() {
        val expected = Path.of("/home/me/.local/share/jotter/jotter.db")

        assertEquals(expected, path(null, "XDG_DATA_HOME" to "", "HOME" to "/home/me"))
        assertEquals(expected, path(null, "XDG_DATA_HOME" to "rel", "HOME" to "/home/me"))
    }

    @Test
    fun withoutHomeTheUsersHomeDirectoryIsUsed() {
        assertEquals(Path.of("/fallback/.local/share/jotter/jotter.db"), path())
    }
}
