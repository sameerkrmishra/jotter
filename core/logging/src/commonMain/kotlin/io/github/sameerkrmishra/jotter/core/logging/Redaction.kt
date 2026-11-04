package io.github.sameerkrmishra.jotter.core.logging

/** What stands in for a removed secret. */
public const val REDACTED: String = "<redacted>"

/** A message longer than this is cut: a log line is not a place for a whole document. */
internal const val MAX_MESSAGE_CHARS = 1_000

private const val MAX_CAUSES = 6
private const val MAX_FRAMES = 12

/**
 * A URL, kept as its scheme, host and port only: its user info is a credential, and its path and
 * query often hold the user name, as in Nextcloud's `/remote.php/dav/files/me/` (ADR-0021).
 */
private val URL =
    Regex("""\b([A-Za-z][A-Za-z0-9+.-]*)://(?:[^/\s?#]*@)?([^/\s?#@"'<>()]+)[^\s"'<>()]*""")

/** An `Authorization` header or field, to the end of its line. */
private val AUTHORIZATION =
    Regex("""(?i)\b((?:proxy-)?authorization)(\s*[:=]\s*)[^\r\n]*""")

/** A credential sent with an HTTP authentication scheme. */
private val SCHEME_CREDENTIAL = Regex("""(?i)\b(bearer|basic|digest)\s+[A-Za-z0-9._~+/=-]{6,}""")

/**
 * `password=...`, `"token": "..."` and the like, in a query string, JSON or a properties file. A
 * quoted value goes whole, spaces and all; an unquoted one with the rest of its line, as nothing
 * says where it ends.
 */
private val NAMED_SECRET =
    Regex(
        """(?i)\b(password|passwd|passphrase|token|secret|api[_-]?key)(["']?\s*[:=]\s*)""" +
            """(?:"(?:[^"\\\r\n]|\\.)*"?|'[^'\r\n]*'?|[^\r\n]*)""",
    )

/**
 * Removes what looks like a credential or a private place from [text]: URLs keep only their
 * scheme, host and port; `Authorization` values, `Bearer` and `Basic` credentials and values named
 * password, token, secret or API key become [REDACTED]; and the home directory [home] reads as
 * `~`, so that the user's name in it stays out (ADR-0021). [home] is by default the one given to
 * [Logging.install].
 *
 * This is the second line of defence. The first is that code never logs a secret or a note's
 * content at all ([Logger]); a pattern cannot recognise a note's text, so nothing here tries.
 */
public fun redact(
    text: String,
    home: String? = Logging.home,
): String {
    val scrubbed =
        text
            .replace(URL, "$1://$2")
            .replace(AUTHORIZATION, "$1$2$REDACTED")
            .replace(SCHEME_CREDENTIAL, "$1 $REDACTED")
            .replace(NAMED_SECRET, "$1$2$REDACTED")
    val prefix =
        home?.trimEnd('/')?.takeIf { it.length > 1 && it.startsWith('/') } ?: return scrubbed
    return Regex(Regex.escape(prefix) + "(?=/|$|[^A-Za-z0-9._-])").replace(scrubbed, "~")
}

/** [text] on one line, with line breaks and other control characters as spaces, cut if long. */
internal fun oneLine(text: String): String {
    val flat = text.map { if (it.isISOControl()) ' ' else it }.joinToString("")
    return if (flat.length > MAX_MESSAGE_CHARS) flat.take(MAX_MESSAGE_CHARS) + "..." else flat
}

/**
 * Exceptions whose messages are safe to log, by the package of their class: they say which host,
 * file, port or SQLite error, never what a note or a server's answer held. Any other exception is
 * logged by its class and stack frames only, because its message may quote data. A JSON parser,
 * for one, quotes the input it failed on, which can be a note's text from a damaged change file;
 * `require` and `check` messages may name a tag.
 */
private val SAFE_PACKAGES =
    listOf(
        "java.net.",
        "javax.net.ssl.",
        "java.nio.file.",
        "java.io.",
        "java.nio.channels.",
        "okio.",
        "io.ktor.client.network.sockets.",
        "io.ktor.network.",
    )

/**
 * Jotter's own exceptions and a few others whose messages are written whole by their code, from
 * ids, numbers and paths: none copies another exception's message into its own. An exception that
 * wraps another, such as `BlobStoreFailureException`, is not here, as its message may repeat its
 * cause's; the cause is logged by its own rule.
 */
private val SAFE_CLASSES =
    setOf(
        "java.util.concurrent.TimeoutException",
        "io.ktor.client.plugins.HttpRequestTimeoutException",
        "androidx.sqlite.SQLiteException",
        "io.github.sameerkrmishra.jotter.core.storage.StorageBusyException",
        "io.github.sameerkrmishra.jotter.core.storage.NoSuchNoteException",
        "io.github.sameerkrmishra.jotter.core.storage.StaleEditException",
        "io.github.sameerkrmishra.jotter.core.storage.NoteTooLargeException",
        "io.github.sameerkrmishra.jotter.core.sqlite.DatabaseTooNewException",
        "io.github.sameerkrmishra.jotter.core.sqlite.DatabaseUpgradeException",
        "io.github.sameerkrmishra.jotter.core.sync.SegmentFormatException",
        "io.github.sameerkrmishra.jotter.backend.api.BlobNotFoundException",
        "io.github.sameerkrmishra.jotter.backend.api.BlobAlreadyExistsException",
    )

/** Whether the message of a [className] is logged. */
internal fun hasSafeMessage(className: String): Boolean =
    className in SAFE_CLASSES || SAFE_PACKAGES.any(className::startsWith)

/**
 * [failure] for a log: for it and each of its causes, the class name, the message if the class is
 * one whose messages are known to be safe, and the first stack frames. Every message is redacted.
 */
public fun describeFailure(
    failure: Throwable,
    home: String? = Logging.home,
): String =
    buildString {
        val seen = mutableListOf<Throwable>()
        var current: Throwable? = failure
        while (current != null && seen.size < MAX_CAUSES && seen.none { it === current }) {
            seen += current
            if (seen.size > 1) append("\ncaused by: ")
            append(headline(current, home))
            stackFrames(current).take(MAX_FRAMES).forEach { append("\n  at ").append(it) }
            current = current.cause
        }
    }

private fun headline(
    failure: Throwable,
    home: String?,
): String {
    val name = failure::class.qualifiedName ?: failure::class.simpleName ?: "Throwable"
    val message = failure.message
    return if (message != null && hasSafeMessage(name)) {
        "$name: ${oneLine(redact(message, home))}"
    } else {
        name
    }
}

/** The stack frames of [failure], innermost first, one line each, without its message. */
internal expect fun stackFrames(failure: Throwable): List<String>
