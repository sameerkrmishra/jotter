package io.github.sameerkrmishra.jotter.interop.keepimport

import io.github.sameerkrmishra.jotter.core.model.HybridTimestamp
import io.github.sameerkrmishra.jotter.core.model.NoteState
import io.github.sameerkrmishra.jotter.core.model.TagName
import io.github.sameerkrmishra.jotter.core.storage.ImportResult
import io.github.sameerkrmishra.jotter.core.storage.ImportedNote
import io.github.sameerkrmishra.jotter.core.storage.NoteFilter
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.storage.StorageBusyException
import okio.FileSystem
import okio.Path
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val CREATED_USEC = 1_488_000_000_000_000L
private const val CREATED_MILLIS = 1_488_000_000_000L

/** A synthetic Takeout document with the given extra JSON members, and fields no import uses. */
private fun takeout(
    createdUsec: Long = CREATED_USEC,
    fields: String = "",
): String {
    val extra = if (fields.isEmpty()) "" else ",$fields"
    return """{"color":"DEFAULT","isShared":false,""" +
        """"createdTimestampUsec":$createdUsec$extra}"""
}

private fun documents(vararg json: String): Sequence<KeepDocument> =
    json.asSequence().mapIndexed { index, text -> KeepDocument("note$index.json", text) }

class KeepImporterTest {
    private val dir: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "jotter-keep-test-${Random.nextLong().toULong()}"

    private fun withStore(block: (NoteStore) -> Unit) {
        FileSystem.SYSTEM.createDirectories(dir)
        try {
            val path = (dir / "test.db").toString()
            NoteStore.open(path, "Test device", EmptyCoroutineContext).use(block)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(dir)
        }
    }

    @Test
    fun aTextNoteComesInWithAllItsFields() =
        withStore { store ->
            val json =
                takeout(
                    fields =
                        """"title":"Trip","textContent":"Pack","isPinned":true,""" +
                            """"labels":[{"name":" Travel "},{"name":"Later"}],""" +
                            """"userEditedTimestampUsec":${CREATED_USEC + 2_000_500}""",
                )

            val report = KeepImporter(store.notes).import(documents(json))

            assertEquals(
                KeepNoteRef("note0.json", keepNoteId(CREATED_USEC)),
                report.imported.single(),
            )
            assertEquals(emptyList(), report.skipped)
            assertEquals(emptyList(), report.warnings)
            assertEquals(emptyList(), report.failures)
            val note = assertNotNull(store.notes.get(report.imported.single().id))
            assertEquals("Trip", note.title)
            assertEquals("Pack", note.body)
            assertEquals(setOf(TagName.parse("Travel"), TagName.parse("Later")), note.tags)
            assertTrue(note.isPinned)
            assertEquals(NoteState.ACTIVE, note.state)
            assertEquals(Instant.fromEpochMilliseconds(CREATED_MILLIS), note.createdAt)
            assertEquals(HybridTimestamp(CREATED_MILLIS + 2_000, 0), note.updatedAt)
            assertEquals(2, store.notes.history(note.id).size)
        }

    @Test
    fun aNoteWithoutATitleOrTextComesIn() =
        withStore { store ->
            val report =
                KeepImporter(
                    store.notes,
                ).import(documents(takeout(fields = "\"title\":\"\"")))

            val note = assertNotNull(store.notes.get(report.imported.single().id))
            assertEquals("", note.title)
            assertEquals("", note.body)
        }

    @Test
    fun nullFieldsCountAsMissing() =
        withStore { store ->
            val fields = """"title":null,"labels":null,"isPinned":null,"listContent":null"""

            val report = KeepImporter(store.notes).import(documents(takeout(fields = fields)))

            assertEquals(emptyList(), report.failures)
            assertEquals("", store.notes.get(report.imported.single().id)?.title)
        }

    @Test
    fun aListNoteBecomesATaskList() =
        withStore { store ->
            val fields =
                """"listContent":[{"text":"milk","isChecked":true},""" +
                    """{"text":"eggs","isChecked":false,"textHtml":"<p>eggs</p>"}]"""

            val report = KeepImporter(store.notes).import(documents(takeout(fields = fields)))

            val note = assertNotNull(store.notes.get(report.imported.single().id))
            assertEquals("- [x] milk\n- [ ] eggs", note.body)
        }

    @Test
    fun aNoteWithOnlyAWebLinkKeepsTheLink() =
        withStore { store ->
            val fields =
                """"title":"","annotations":[{"description":"An example","source":"WEBLINK",""" +
                    """"title":"Example","url":"https://example.org/a"}]"""

            val report = KeepImporter(store.notes).import(documents(takeout(fields = fields)))

            val note = assertNotNull(store.notes.get(report.imported.single().id))
            assertEquals("[Example](https://example.org/a)", note.body)
        }

    @Test
    fun archivedAndTrashedNotesKeepTheirState() =
        withStore { store ->
            val archived = takeout(1_000_000, """"isArchived":true""")
            val trashed = takeout(2_000_000, """"isArchived":true,"isTrashed":true""")

            KeepImporter(store.notes).import(documents(archived, trashed))

            assertEquals(1, store.notes.list(NoteFilter(NoteState.ARCHIVED)).size)
            assertEquals(1, store.notes.list(NoteFilter(NoteState.TRASHED)).size)
        }

    @Test
    fun importingAgainChangesNothing() =
        withStore { store ->
            val json = takeout(fields = """"title":"Same","labels":[{"name":"x"}]""")
            val importer = KeepImporter(store.notes)
            val first = importer.import(documents(json)).imported.single()
            val history = store.notes.history(first.id)

            val second = importer.import(documents(json))

            assertEquals(emptyList(), second.imported)
            assertEquals(listOf(first), second.skipped)
            assertEquals(history, store.notes.history(first.id))
            assertEquals(1, store.notes.list().size)
        }

    @Test
    fun aLaterTakeoutFindsTheSameNoteWhateverChanged() =
        withStore { store ->
            val importer = KeepImporter(store.notes)
            val first = importer.import(documents(takeout(fields = """"title":"Old""""))).imported
            val newer =
                takeout(
                    fields = """"title":"New","userEditedTimestampUsec":${CREATED_USEC * 2}""",
                )

            val second = importer.import(documents(newer))

            assertEquals(first, second.skipped)
            assertEquals(
                "Old",
                store.notes
                    .list()
                    .single()
                    .title,
            )
        }

    @Test
    fun aNoteDeletedForeverStaysDeletedOnReImport() =
        withStore { store ->
            val importer = KeepImporter(store.notes)
            val id =
                importer
                    .import(documents(takeout()))
                    .imported
                    .single()
                    .id
            store.notes.setState(id, NoteState.TRASHED)
            store.notes.deleteForever(id)

            val report = importer.import(documents(takeout()))

            assertEquals(id, report.skipped.single().id)
            assertNull(store.notes.get(id))
        }

    @Test
    fun malformedDocumentsDoNotStopTheRest() =
        withStore { store ->
            val bad =
                listOf("", "not json", "[1,2]", "null", """{"title":""", """{"isPinned":"x"}""")
            val json = bad + takeout()

            val report = KeepImporter(store.notes).import(documents(*json.toTypedArray()))

            assertEquals(1, report.imported.size)
            assertEquals("note6.json", report.imported.single().source)
            assertEquals(
                List(bad.size) { "note$it.json" },
                report.failures.map(KeepImportFailure::source),
            )
            assertTrue(report.failures.all { it.kind == KeepImportFailure.Kind.MALFORMED_JSON })
            assertTrue(report.failures.all { it.detail.isNotEmpty() })
        }

    @Test
    fun deeplyNestedUnknownFieldsAreIgnored() =
        withStore { store ->
            val depth = 100_000
            val nested = "[".repeat(depth) + "]".repeat(depth)
            val json = takeout(fields = """"unknown":$nested""")

            assertEquals(1, KeepImporter(store.notes).import(documents(json)).imported.size)
        }

    @Test
    fun aMissingCreatedTimeFailsTheDocument() =
        withStore { store ->
            val documents = documents("""{"title":"x"}""", takeout(0), takeout(-1))

            val report = KeepImporter(store.notes).import(documents)

            assertEquals(3, report.failures.size)
            assertTrue(
                report.failures.all { it.kind == KeepImportFailure.Kind.MISSING_CREATED_TIME },
            )
            assertEquals(emptyList(), store.notes.list())
        }

    @Test
    fun aMissingEditedTimeFallsBackToTheCreatedTimeWithAWarning() =
        withStore { store ->
            val zero = takeout(2_000_000, """"userEditedTimestampUsec":0""")

            val report = KeepImporter(store.notes).import(documents(takeout(), zero))

            assertEquals(
                listOf("note0.json", "note1.json"),
                report.warnings.map(KeepImportWarning::source),
            )
            assertTrue(
                report.warnings.all { it.kind == KeepImportWarning.Kind.MISSING_EDITED_TIME },
            )
            report.imported.forEach { assertEquals(1, store.notes.history(it.id).size) }
        }

    @Test
    fun anEditedTimeNotLaterThanCreatedWritesOneRevisionWithoutAWarning() =
        withStore { store ->
            val edited = listOf(CREATED_USEC, CREATED_USEC - 1_000_000, CREATED_USEC + 999)
            val json =
                edited.mapIndexed { index, usec ->
                    takeout(
                        CREATED_USEC + index * 1_000_000,
                        """"userEditedTimestampUsec":${usec + index * 1_000_000}""",
                    )
                }

            val report = KeepImporter(store.notes).import(documents(*json.toTypedArray()))

            assertEquals(3, report.imported.size)
            assertEquals(emptyList(), report.warnings)
            report.imported.forEach { assertEquals(1, store.notes.history(it.id).size) }
        }

    @Test
    fun aDuplicateIdFailsTheLaterNoteOnly() =
        withStore { store ->
            val first = takeout(fields = """"title":"First"""")
            val second = takeout(fields = """"title":"Second"""")

            val report = KeepImporter(store.notes).import(documents(first, second))

            assertEquals("note0.json", report.imported.single().source)
            assertEquals(
                KeepImportFailure("note1.json", KeepImportFailure.Kind.DUPLICATE_ID, "note0.json"),
                report.failures.single(),
            )
            assertEquals(
                "First",
                store.notes
                    .list()
                    .single()
                    .title,
            )
        }

    @Test
    fun aDuplicateIdIsReportedAlsoWhenTheFirstNoteIsSkipped() =
        withStore { store ->
            val importer = KeepImporter(store.notes)
            importer.import(documents(takeout()))

            val report = importer.import(documents(takeout(), takeout()))

            assertEquals(1, report.skipped.size)
            assertEquals(KeepImportFailure.Kind.DUPLICATE_ID, report.failures.single().kind)
        }

    @Test
    fun anInvalidLabelIsReportedAndTheNoteKept() =
        withStore { store ->
            val long = "x".repeat(TagName.MAX_LENGTH + 1)
            val fields = """"labels":[{"name":"Good"},{"name":"$long"},{"name":"  "}]"""

            val report = KeepImporter(store.notes).import(documents(takeout(fields = fields)))

            val note = assertNotNull(store.notes.get(report.imported.single().id))
            assertEquals(setOf(TagName.parse("Good")), note.tags)
            assertEquals(
                listOf(long, "  ").map {
                    KeepImportWarning("note0.json", KeepImportWarning.Kind.INVALID_TAG, it)
                },
                report.warnings.filter { it.kind == KeepImportWarning.Kind.INVALID_TAG },
            )
        }

    @Test
    fun anAttachmentIsReportedAndTheTextKept() =
        withStore { store ->
            val fields =
                """"textContent":"Photo","attachments":[{"filePath":"a.png",""" +
                    """"mimetype":"image/png"},{"filePath":"b.3gp","mimetype":"audio/3gp"}]"""

            val report = KeepImporter(store.notes).import(documents(takeout(fields = fields)))

            assertEquals("Photo", store.notes.get(report.imported.single().id)?.body)
            assertEquals(
                listOf("a.png", "b.3gp"),
                report.warnings
                    .filter { it.kind == KeepImportWarning.Kind.ATTACHMENT_DROPPED }
                    .map(KeepImportWarning::detail),
            )
        }

    @Test
    fun shareesAreCountedWithoutTheirAddresses() =
        withStore { store ->
            val fields =
                """"sharees":[{"isOwner":true,"email":"owner@example.invalid"},""" +
                    """{"isOwner":false,"type":"WRITER","email":"friend@example.invalid"}]"""

            val report = KeepImporter(store.notes).import(documents(takeout(fields = fields)))

            val warning =
                report.warnings.first {
                    it.kind == KeepImportWarning.Kind.SHARING_DROPPED
                }
            assertEquals("2", warning.detail)
            assertFalse(report.toString().contains("example.invalid"))
        }

    @Test
    fun aNoteWithAnEmptyShareeListHasNoSharingWarning() =
        withStore { store ->
            val report =
                KeepImporter(
                    store.notes,
                ).import(documents(takeout(fields = """"sharees":[]""")))

            assertTrue(report.warnings.none { it.kind == KeepImportWarning.Kind.SHARING_DROPPED })
        }

    @Test
    fun aMalformedDocumentReportsNoneOfItsContent() =
        withStore { store ->
            val broken = """{"sharees":[{"email":"friend@example.invalid"}],"isPinned":"x"}"""
            val truncated = """{"sharees":[{"email":"friend@example.invalid"""

            val report = KeepImporter(store.notes).import(documents(broken, truncated))

            assertEquals(2, report.failures.size)
            assertFalse(report.toString().contains("example.invalid"))
        }

    @Test
    fun aSkippedNoteRepeatsNoWarnings() =
        withStore { store ->
            val json = takeout(fields = """"attachments":[{"filePath":"a.png"}]""")
            val importer = KeepImporter(store.notes)
            importer.import(documents(json))

            assertEquals(emptyList(), importer.import(documents(json)).warnings)
        }

    @Test
    fun aLoneSurrogateInTheJsonIsStoredReplaced() =
        withStore { store ->
            val report =
                KeepImporter(
                    store.notes,
                ).import(documents(takeout(fields = """"title":"a\ud83db"""")))

            assertEquals("a�b", store.notes.get(report.imported.single().id)?.title)
        }

    @Test
    fun aNoteTheDatabaseCannotStoreFailsAloneAndLeavesNothingBehind() =
        withStore { store ->
            val labels = List(40_000) { index -> """{"name":"tag$index"}""" }
            val tooMany = takeout(1_000_000, """"labels":[${labels.joinToString(",")}]""")

            val report = KeepImporter(store.notes).import(documents(tooMany, takeout()))

            assertEquals("note1.json", report.imported.single().source)
            val failure = report.failures.single()
            assertEquals("note0.json", failure.source)
            assertEquals(KeepImportFailure.Kind.STORAGE_ERROR, failure.kind)
            assertTrue(failure.detail.isNotEmpty())
            assertFalse(report.stopped)
            assertEquals(emptyList(), store.notes.history(keepNoteId(1_000_000)))
        }

    @Test
    fun aLockedDatabaseStopsTheRunAndKeepsTheReportSoFar() {
        val stored = mutableListOf<ImportedNote>()
        val importer =
            KeepImporter { note ->
                if (stored.size == 1) throw StorageBusyException(IllegalStateException("locked"))
                stored += note
                ImportResult.IMPORTED
            }
        val attachment = """"attachments":[{"filePath":"a.png"}]"""
        var documentsRead = 0
        val documents =
            documents(
                takeout(1_000_000, attachment),
                "not json",
                takeout(2_000_000),
                takeout(3_000_000),
            ).onEach { documentsRead++ }

        val report = importer.import(documents)

        assertTrue(report.stopped)
        assertEquals("note0.json", report.imported.single().source)
        assertEquals("a.png", report.warnings.first().detail)
        assertEquals(
            listOf(
                KeepImportFailure.Kind.MALFORMED_JSON,
                KeepImportFailure.Kind.STORAGE_BUSY,
            ),
            report.failures.map(KeepImportFailure::kind),
        )
        assertEquals("note2.json", report.failures.last().source)
        assertEquals(3, documentsRead)
    }

    @Test
    fun aRunThatFinishesIsNotStopped() =
        withStore { store ->
            assertFalse(KeepImporter(store.notes).import(documents(takeout())).stopped)
        }

    @Test
    fun aThousandNotesImportAndReImport() =
        withStore { store ->
            val json =
                List(1_000) { index ->
                    takeout(
                        CREATED_USEC + index * 1_000L,
                        """"title":"Note $index","textContent":"Body $index"""",
                    )
                }
            val importer = KeepImporter(store.notes)

            val first = importer.import(documents(*json.toTypedArray()))
            val second = importer.import(documents(*json.toTypedArray()))

            assertEquals(1_000, first.imported.size)
            assertEquals(1_000, store.notes.list().size)
            assertEquals(1_000, second.skipped.size)
            assertEquals(0, second.imported.size + second.failures.size)
        }
}
