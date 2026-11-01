package io.github.sameerkrmishra.jotter.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseLocationTest {
    private fun path(
        explicit: String? = null,
        vararg environment: Pair<String, String>,
    ) = linuxDatabasePath(explicit, mapOf(*environment)::get, "/fallback")

    @Test
    fun anExplicitPathComesFirst() {
        assertEquals("/a/b.db", path("/a/b.db", "XDG_DATA_HOME" to "/xdg"))
    }

    @Test
    fun anEmptyExplicitPathCountsAsNotGiven() {
        assertEquals("/xdg/jotter/jotter.db", path("", "XDG_DATA_HOME" to "/xdg"))
    }

    @Test
    fun xdgDataHomeComesBeforeHome() {
        val path = path(null, "XDG_DATA_HOME" to "/xdg", "HOME" to "/home/me")

        assertEquals("/xdg/jotter/jotter.db", path)
    }

    @Test
    fun aTrailingSlashGivesNoDoubleSlash() {
        assertEquals("/xdg/jotter/jotter.db", path(null, "XDG_DATA_HOME" to "/xdg/"))
    }

    @Test
    fun anEmptyOrRelativeXdgDataHomeIsIgnored() {
        val expected = "/home/me/.local/share/jotter/jotter.db"

        assertEquals(expected, path(null, "XDG_DATA_HOME" to "", "HOME" to "/home/me"))
        assertEquals(expected, path(null, "XDG_DATA_HOME" to "rel", "HOME" to "/home/me"))
    }

    @Test
    fun withoutHomeTheUsersHomeDirectoryIsUsed() {
        assertEquals("/fallback/.local/share/jotter/jotter.db", path())
    }
}
