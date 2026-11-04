package io.github.sameerkrmishra.jotter.core.logging

/** What stands in for a removed secret. */
public const val REDACTED: String = "<redacted>"

/** A message longer than this is cut: a log line is not a place for a whole document. */
internal const val MAX_MESSAGE_CHARS = 1_000

private const val MAX_CAUSES = 6
private const val MAX_FRAMES = 12

/** The user name and password of a URL, such as `https://me:secret@host/`. */
private val URL_USER_INFO = Regex("""([A-Za-z][A-Za-z0-9+.-]*://)[^/\s?#@]*@""")

/** An `Authorization` header or field, to the end of its line. */
private val AUTHORIZATION =
    Regex("""(?i)\b((?:proxy-)?authorization)(\s*[:=]\s*)[^\r\n]*""")

/** A credential sent with an HTTP authentication scheme. */
private val SCHEME_CREDENTIAL = Regex("""(?i)\b(bearer|basic|digest)\s+[A-Za-z0-9._~+/=-]{6,}""")

/** `password=...`, `"token": "..."` and the like, in a query string, JSON or a properties file. */
private val NAMED_SECRET =
    Regex(
        """(?i)\b(password|passwd|passphrase|token|secret|api[_-]?key)(["']?\s*[:=]\s*["']?)""" +
            """[^\s"'&,;}]+""",
    )

/**
 * Removes what looks like a credential from [text]: user info in URLs, `Authorization` values,
 * `Bearer` and `Basic` credentials, and values named password, token, secret or API key.
 *
 * This is the second line of defence. The first is that code never logs a secret or a note's
 * content at all ([Logger]); a pattern cannot recognise a note's text, so nothing here tries.
 */
public fun redact(text: String): String =
    text
        .replace(URL_USER_INFO, "$1$REDACTED@")
        .replace(AUTHORIZATION, "$1$2$REDACTED")
        .replace(SCHEME_CREDENTIAL, "$1 $REDACTED")
        .replace(NAMED_SECRET, "$1$2$REDACTED")

/** [text] on one line, with line breaks and other control characters as spaces, cut if long. */
internal fun oneLine(text: String): String {
    val flat = text.map { if (it.isISOControl()) ' ' else it }.joinToString("")
    return if (flat.length > MAX_MESSAGE_CHARS) flat.take(MAX_MESSAGE_CHARS) + "..." else flat
}

/**
 * Exceptions whose messages are safe to log, by the package or name of their class: they say
 * which host, file, port or SQLite error, never what a note or a server's answer held. Any other
 * exception is logged by its class and stack frames only, because its message may quote data. A
 * JSON parser, for one, quotes the input it failed on, which can be a note's text from a damaged
 * change file; `require` and `check` messages may name a tag.
 */
private val SAFE_MESSAGES =
    listOf(
        "java.net.",
        "javax.net.ssl.",
        "java.nio.file.",
        "java.io.",
        "java.nio.channels.",
        "java.util.concurrent.TimeoutException",
        "io.ktor.client.network.sockets.",
        "io.ktor.client.plugins.HttpRequestTimeoutException",
        "io.ktor.network.",
        "androidx.sqlite.SQLiteException",
        "io.github.sameerkrmishra.jotter.",
    )

/**
 * [failure] for a log: for it and each of its causes, the class name, the message if the class is
 * one whose messages are known to be safe, and the first stack frames. Every message is redacted.
 */
public fun describeFailure(failure: Throwable): String =
    buildString {
        val seen = mutableListOf<Throwable>()
        var current: Throwable? = failure
        while (current != null && seen.size < MAX_CAUSES && seen.none { it === current }) {
            seen += current
            if (seen.size > 1) append("\ncaused by: ")
            append(headline(current))
            stackFrames(current).take(MAX_FRAMES).forEach { append("\n  at ").append(it) }
            current = current.cause
        }
    }

private fun headline(failure: Throwable): String {
    val name = failure::class.qualifiedName ?: failure::class.simpleName ?: "Throwable"
    val message = failure.message
    return if (message != null && SAFE_MESSAGES.any(name::startsWith)) {
        "$name: ${oneLine(redact(message))}"
    } else {
        name
    }
}

/** The stack frames of [failure], innermost first, one line each, without its message. */
internal expect fun stackFrames(failure: Throwable): List<String>
