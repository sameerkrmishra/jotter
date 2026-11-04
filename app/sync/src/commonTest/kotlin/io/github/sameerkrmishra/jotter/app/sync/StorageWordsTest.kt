package io.github.sameerkrmishra.jotter.app.sync

import androidx.sqlite.SQLiteException
import io.github.sameerkrmishra.jotter.core.sqlite.DatabaseTooNewException
import io.github.sameerkrmishra.jotter.core.sqlite.DatabaseUpgradeException
import io.github.sameerkrmishra.jotter.core.sqlite.ForeignDatabaseException
import io.github.sameerkrmishra.jotter.core.storage.StorageBusyException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Every database failure in plain words: what happened, what to do, and never the exception's own
 * text, which is SQLite's or a stack trace's language.
 */
class StorageWordsTest {
    private val path = "/home/me/.local/share/jotter/jotter.db"

    private fun sqlite(code: Int) = SQLiteException("Error code: $code, message: jargon")

    private val failures =
        mapOf(
            "in use by another Jotter program" to StorageBusyException(sqlite(5)),
            "is full. Free some space" to sqlite(13),
            "is damaged, so Jotter cannot read it. Jotter has not changed or replaced it" to
                sqlite(26),
            "may read your notes" to sqlite(8),
            "Check that its folder exists" to sqlite(14),
            "If it is a removable drive" to sqlite(10),
            "saved by a newer version of Jotter. Update Jotter" to
                DatabaseTooNewException(4, 3),
            "is not a Jotter notes file, so Jotter left it alone" to
                ForeignDatabaseException("jargon"),
            "could not update your notes" to
                DatabaseUpgradeException(sqlite(1)),
            "could not open your notes" to IllegalStateException("jargon"),
        )

    @Test
    fun eachOpenFailureSaysWhatHappenedAndWhatToDo() {
        failures.forEach { (expected, failure) ->
            val text = describeOpenFailure(failure, path)
            assertContains(text, expected)
            assertFalse("jargon" in text, text)
            assertFalse("Error code" in text, text)
            assertFalse("Exception" in text, text)
        }
    }

    @Test
    fun theFileIsNamedWhereItMatters() {
        assertContains(describeOpenFailure(sqlite(26), path), path)
        assertContains(describeOpenFailure(sqlite(14), path), path)
    }

    @Test
    fun aFailedWriteSaysWhyInPlainWords() {
        assertEquals(
            "The disk is full. Free some space, then try again.",
            describeStorageFailure(sqlite(13)),
        )
        assertContains(describeStorageFailure(StorageBusyException(sqlite(5))), "Another Jotter")
        assertContains(describeStorageFailure(sqlite(11)), "damaged")
        assertContains(describeStorageFailure(sqlite(8)), "not allowed to change")
        assertContains(describeStorageFailure(sqlite(14)), "not allowed to change")
        assertContains(describeStorageFailure(sqlite(10)), "removable drive")
        assertContains(describeStorageFailure(IllegalStateException("jargon")), "debug log")
        failures.values.forEach { assertFalse("jargon" in describeStorageFailure(it)) }
    }
}
