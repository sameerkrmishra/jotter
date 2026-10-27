package io.github.sameerkrmishra.jotter.core.sqlite

import kotlin.time.Duration.Companion.milliseconds

/**
 * The second process of [TwoProcessTest]. Arguments: the database path and the number of rows to
 * write.
 *
 * It first holds a write transaction open for [TwoProcessTest.HOLD], telling the parent with
 * `locked` on stdout, so the parent's first write must wait for it. After `go` on stdin it writes
 * its rows, one transaction each, at the same time as the parent. It exits with 0 only if every
 * write succeeded.
 */
internal object SecondProcessWriter {
    @JvmStatic
    fun main(args: Array<String>) {
        val (path, rows) = args
        val db = NotesDatabase(openSqliteDriver(path, NotesSchema))
        try {
            db.transaction {
                db.insert("child hold")
                println(LOCKED)
                blockFor(TwoProcessTest.HOLD)
            }
            check(readln() == GO)
            repeat(rows.toInt()) { index ->
                db.transaction {
                    db.insert("child $index")
                    blockFor(1.milliseconds)
                }
            }
        } finally {
            db.sqlDriver.close()
        }
        println(DONE)
    }

    const val LOCKED = "locked"
    const val GO = "go"
    const val DONE = "done"
}
