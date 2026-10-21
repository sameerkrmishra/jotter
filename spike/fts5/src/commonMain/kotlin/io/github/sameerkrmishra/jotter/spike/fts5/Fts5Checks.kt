package io.github.sameerkrmishra.jotter.spike.fts5

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.db.SqlDriver
import io.github.sameerkrmishra.jotter.spike.fts5.db.SpikeDatabase

/** What a driver reported while running [runFts5Checks]. */
public data class Fts5Report(
    val sqliteVersion: String,
    val fts5CompileOption: Long,
    val cafeResults: List<String>,
)

/**
 * Runs the same FTS5 scenario against any driver whose schema has already been created.
 * Throws [IllegalStateException] on the first failed expectation.
 */
public suspend fun runFts5Checks(driver: SqlDriver): Fts5Report {
    val q = SpikeDatabase(driver).noteQueries

    val version = q.sqliteVersion().awaitAsOne()
    // -1 when the build omits compile-option diagnostics (requery does).
    val compiled = runCatching { q.fts5CompileOption().awaitAsOne() }.getOrDefault(-1L)

    q.insertNote("Grocery list", "milk, eggs and a café au lait for the road")
    q.insertNote("Café opening hours", "the cafe opens at nine on weekdays")
    q.insertNote("Meeting notes", "we discussed the grocery budget and the coffee machine")

    // Diacritics are folded, so "cafe" matches "café". The title hit ranks first (weight 10).
    val cafe = q.search(query = "cafe", limit = 10).awaitAsList()
    check(cafe.map { it.id } == listOf(2L, 1L)) { "cafe ranking: $cafe" }
    check(cafe[0].titleHighlighted == "<b>Café</b> opening hours") { "highlight: ${cafe[0]}" }
    check(cafe[1].bodySnippet.orEmpty().contains("[café]")) { "snippet: ${cafe[1]}" }
    check(cafe[0].score < cafe[1].score) { "bm25 order: $cafe" }

    // Prefix query, phrase query, column filter.
    check(q.search("groc*", 10).awaitAsList().map { it.id } == listOf(1L, 3L)) { "prefix" }
    check(q.search("\"grocery budget\"", 10).awaitAsList().map { it.id } == listOf(3L)) { "phrase" }
    check(q.search("title:meeting", 10).awaitAsList().map { it.id } == listOf(3L)) { "col filter" }
    check(q.searchTitleOnly("meeting").awaitAsList() == listOf(3L)) { "column MATCH" }

    // The synthesized rank column (must be aliased, see Note.sq).
    val ranks = q.searchByRank("grocery").awaitAsList()
    check(ranks.size == 2 && ranks.all { it.rank < 0.0 }) { "rank: $ranks" }

    // Triggers keep the external-content index in sync on update and delete.
    q.updateBody(body = "nothing to see", id = 3L)
    check(q.search("budget", 10).awaitAsList().isEmpty()) { "update not indexed" }
    q.deleteNote(1L)
    check(q.search("milk", 10).awaitAsList().isEmpty()) { "delete not indexed" }

    // SQLDelight 2.4.1 crashes (ClassCastException) generating a labeled query that names the
    // hidden `note_fts` column (FTS5 commands), so those go straight through the driver.
    // The same statements inside triggers compile fine.
    driver.execute(null, "INSERT INTO note_fts(note_fts) VALUES ('rebuild')", 0).await()
    driver.execute(null, "INSERT INTO note_fts(note_fts, rank) VALUES ('integrity-check', 1)", 0)
        .await()

    // Malformed user input is an SQL error, so the app must escape or quote search text.
    val malformed = runCatching { q.search("\"unterminated", 10).awaitAsList() }
    check(malformed.isFailure) { "malformed MATCH unexpectedly succeeded" }

    return Fts5Report(version, compiled, cafe.map { "${it.id}:${it.titleHighlighted}:${it.score}" })
}
