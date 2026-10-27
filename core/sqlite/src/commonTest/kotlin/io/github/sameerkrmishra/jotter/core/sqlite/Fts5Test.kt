package io.github.sameerkrmishra.jotter.core.sqlite

import app.cash.sqldelight.db.QueryResult
import kotlin.test.Test
import kotlin.test.assertEquals

/** FTS5 is the reason for bundling SQLite (ADR-0002): check that it works through the driver. */
class Fts5Test {
    @Test
    fun fts5TableIsSearchableWithMatch() =
        withNotesDatabase { db ->
            val driver = db.sqlDriver
            driver.execute(
                null,
                "CREATE VIRTUAL TABLE note_fts USING fts5(title, body, " +
                    "tokenize = 'unicode61 remove_diacritics 2')",
                0,
            )
            driver.execute(
                null,
                "INSERT INTO note_fts(title, body) VALUES " +
                    "('Shopping', 'oat milk, coffee'), " +
                    "('Café ideas', 'coffee coffee coffee'), " +
                    "('Groceries', 'bread')",
                0,
            )

            assertEquals(listOf("Café ideas"), driver.search("cafe"))
            assertEquals(listOf("Groceries"), driver.search("groc*"))
            // bm25() ranks the note that mentions coffee most often first.
            assertEquals(listOf("Café ideas", "Shopping"), driver.search("coffee"))
            assertEquals(emptyList(), driver.search("tea"))
        }

    private fun app.cash.sqldelight.db.SqlDriver.search(query: String): List<String> =
        executeQuery(
            identifier = 1,
            sql = "SELECT title FROM note_fts WHERE note_fts MATCH ? ORDER BY bm25(note_fts)",
            mapper = { cursor ->
                val titles = mutableListOf<String>()
                while (cursor.next().value) titles += checkNotNull(cursor.getString(0))
                QueryResult.Value(titles)
            },
            parameters = 1,
        ) { bindString(0, query) }.value
}
