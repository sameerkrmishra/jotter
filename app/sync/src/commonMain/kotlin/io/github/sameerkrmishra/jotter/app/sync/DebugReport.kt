package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.core.logging.DEFAULT_RECENT_BYTES
import io.github.sameerkrmishra.jotter.core.logging.LogFiles
import io.github.sameerkrmishra.jotter.core.logging.Logging
import io.github.sameerkrmishra.jotter.core.logging.redact
import io.github.sameerkrmishra.jotter.core.storage.NoteStore
import io.github.sameerkrmishra.jotter.core.sync.SyncEngine
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

/**
 * Which app this is, for a debug log.
 *
 * @property name The app: `desktop`, `cli` or `android`.
 * @property version Its version, such as `0.1.0`.
 * @property platform The system it runs on, such as `Linux 6.18 (amd64), Java 21.0.9`.
 */
public data class AppInfo(
    val name: String,
    val version: String,
    val platform: String,
)

/** What a debug log starts with: what it is, and what it never holds. */
public const val DEBUG_LOG_PREAMBLE: String =
    "This is all of the debug log, exactly as it would be saved. It holds no note, title, tag, " +
        "password or token, and no server address with a password in it. Read it before you " +
        "attach it to an issue. Jotter never sends it anywhere by itself."

/**
 * Builds the debug log that the user can attach to an issue (ADR-0021): the app, its version and
 * platform, the formats it writes, each backend's kind and last sync with its issues, and the
 * newest lines of each log. An app shows the whole text before it is saved or shared, and nothing
 * sends it anywhere.
 *
 * Each backend is named by its id and kind: its name and configured location are left out, as they
 * are the user's. Its last sync's detail and issues are included, scrubbed as every log line is
 * (ADR-0021): a URL keeps only its scheme, host and port, the home directory [home] reads as `~`,
 * and anything that looks like a credential is removed. A folder outside the home directory keeps
 * its path. The whole text is [redact]ed this way once more.
 *
 * @param app This app.
 * @param logs The logs to include, or null where there are none.
 * @param clock When the log is made.
 * @param recentBytes How much of each log to include, from its end.
 * @param home The home directory, written as `~`; by default the one the log was installed with.
 */
public class DebugReport(
    private val app: AppInfo,
    private val logs: LogFiles?,
    private val clock: Clock = Clock.System,
    private val recentBytes: Int = DEFAULT_RECENT_BYTES,
    private val home: String? = Logging.home,
) {
    /**
     * The debug log. [manager] gives the backends, or is null when the notes could not be opened;
     * then [openProblem] says why, in the words the user saw.
     */
    public suspend fun build(
        manager: BackendManager?,
        openProblem: String? = null,
    ): String =
        redact(
            home = home,
            text =
                buildString {
                    appendLine("Jotter debug log")
                    appendLine()
                    appendLine(DEBUG_LOG_PREAMBLE)
                    appendLine()
                    appendLine("Created: ${clock.now()}")
                    appendLine("App: ${app.name} ${app.version}")
                    appendLine("Platform: ${app.platform}")
                    appendLine("Database schema: ${NoteStore.SCHEMA_VERSION}")
                    appendLine("Sync format: ${SyncEngine.FORMAT}")
                    appendLine()
                    openProblem?.let { appendLine("The notes could not be opened: $it") }
                    if (manager != null) appendBackends(manager)
                    appendLogs()
                },
        )

    @Suppress("TooGenericExceptionCaught") // A debug log is wanted most when things fail.
    private suspend fun StringBuilder.appendBackends(manager: BackendManager) {
        val backends =
            try {
                manager.list()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendLine("Backends: cannot be read. ${describeStorageFailure(e)}")
                return
            }
        appendLine("Backends: ${backends.size}")
        backends.forEach { backend ->
            val kind = BackendKinds.kindOf(backend.config)
            val attempt = backend.lastAttempt
            appendLine("- ${backend.id} ($kind), added ${backend.addedAt}")
            if (attempt == null) {
                appendLine("  last sync: never")
            } else {
                appendLine("  last sync: ${attempt.outcome} at ${attempt.at}: ${attempt.detail}")
                attempt.issues.forEach { appendLine("  issue: $it") }
            }
            appendLine("  last success: ${backend.lastSuccessAt ?: "never"}")
        }
    }

    private fun StringBuilder.appendLogs() {
        val names = logs?.names().orEmpty()
        if (names.isEmpty()) {
            appendLine()
            appendLine("Logs: none yet")
        }
        names.forEach { name ->
            appendLine()
            appendLine("Log: $name (the newest ${recentBytes / BYTES_PER_KIB} KiB)")
            append(logs?.recent(name, recentBytes).orEmpty())
        }
    }

    private companion object {
        const val BYTES_PER_KIB = 1024
    }
}
