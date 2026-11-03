package io.github.sameerkrmishra.jotter.backend.webdav

private const val HEX_DIGITS = "0123456789ABCDEF"
private const val UNRESERVED =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
private const val BYTE_MASK = 0xFF
private const val HIGH_NIBBLE_SHIFT = 4
private const val LOW_NIBBLE = 0x0F
private const val HEX_RADIX = 16
private const val SCHEME_SEPARATOR = "://"
private const val IPV4_PARTS = 4

/**
 * Where a store lives: the origin (`https://host:port`) and the decoded segments of the path of
 * its collection. Every URL the store asks for is built from these, one segment at a time, so a
 * name is encoded the same way in every request.
 *
 * @property origin The scheme, host and port, without a trailing `/`.
 * @property segments The decoded path segments of the store's collection.
 */
internal class DavLocation private constructor(
    val origin: String,
    val segments: List<String>,
) {
    /** The URL of the store's root collection, with a trailing `/`. */
    val rootUrl: String get() = collectionUrl(emptyList())

    /** The URL of the file at the relative [path] segments. */
    fun fileUrl(path: List<String>): String = origin + encodePath(segments + path)

    /** The URL of the collection at the relative [path] segments, with a trailing `/`. */
    fun collectionUrl(path: List<String>): String = origin + encodePath(segments + path) + "/"

    override fun toString(): String = rootUrl

    companion object {
        // Group 3 is the path; anything after it (query, fragment) makes the match fail.
        private val URL_PATTERN = Regex("""([Hh][Tt][Tt][Pp][Ss]?)://([^/?#]+)(/[^?#]*)?""")

        /**
         * Checks [url] and splits it.
         *
         * @throws IllegalArgumentException if [url] is not an `https` URL, or an `http` one to
         *   this machine, or if it has user info, a query or a fragment.
         */
        fun parse(url: String): DavLocation {
            // Checked before any message repeats the URL, which could hold a password. An `@` in
            // the path is fine: Nextcloud has user names like me@example.org there.
            require('@' !in url.substringAfter(SCHEME_SEPARATOR).substringBefore('/')) {
                "The URL must not hold a user name or password; pass them as credentials"
            }
            val match =
                requireNotNull(URL_PATTERN.matchEntire(url)) {
                    "Not an http or https URL with a host and without query or fragment: $url"
                }
            val (scheme, authority, path) = match.destructured
            val lowerScheme = scheme.lowercase()
            require(lowerScheme == "https" || isLoopback(hostOf(authority))) {
                "Plain http would send the password and every note unencrypted. Use https, " +
                    "or http to this machine only (localhost, 127.0.0.0/8 or [::1])"
            }
            val segments = decodeSegments(path)
            require(segments != null && segments.none { it == "." || it == ".." }) {
                "Bad path in $url"
            }
            return DavLocation("$lowerScheme://$authority", segments)
        }

        private fun hostOf(authority: String): String =
            if (authority.startsWith('[')) {
                authority.substringBefore(']') + "]"
            } else {
                authority.substringBefore(':')
            }

        private fun isLoopback(host: String): Boolean {
            val lower = host.lowercase()
            val parts = lower.split('.')
            return lower == "localhost" ||
                lower == "[::1]" ||
                parts.size == IPV4_PARTS &&
                parts[0] == "127" &&
                parts.all { (it.toIntOrNull() ?: -1) in 0..BYTE_MASK }
        }
    }
}

/** Percent-encodes every segment of [segments], each with a leading `/`. */
internal fun encodePath(segments: List<String>): String =
    buildString {
        segments.forEach { segment ->
            append('/')
            segment.encodeToByteArray().forEach { byte ->
                val char = byte.toInt().toChar()
                if (byte >= 0 && char in UNRESERVED) {
                    append(char)
                } else {
                    val value = byte.toInt() and BYTE_MASK
                    append('%')
                    append(HEX_DIGITS[value shr HIGH_NIBBLE_SHIFT])
                    append(HEX_DIGITS[value and LOW_NIBBLE])
                }
            }
        }
    }

private val ESCAPE = Regex("%[0-9A-Fa-f]{2}")

/**
 * Splits an encoded URL path into decoded segments, dropping empty ones, so that a trailing `/`
 * makes no difference. Escapes may be in either case, and characters that a server left unescaped,
 * such as non-ASCII letters, are taken as they are. Returns null if an escape is broken or the
 * bytes are not UTF-8: such a name cannot be a path of the store.
 */
internal fun decodeSegments(path: String): List<String>? =
    path.split('/').filter { it.isNotEmpty() }.map { decodeSegment(it) ?: return null }

private fun decodeSegment(segment: String): String? {
    val bytes = mutableListOf<Byte>()
    var plainStart = 0
    (ESCAPE.findAll(segment) + null).forEach { escape ->
        val plain = segment.substring(plainStart, escape?.range?.first ?: segment.length)
        // A "%" outside an escape is a broken one.
        if ('%' in plain) return null
        bytes += plain.encodeToByteArray().asList()
        escape?.let {
            bytes +=
                it.value
                    .substring(1)
                    .toInt(HEX_RADIX)
                    .toByte()
            plainStart = it.range.last + 1
        }
    }
    return try {
        bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }
}

/**
 * The path part of [href], an element of a `PROPFIND` response: either a full URL or an absolute
 * path. Returns null for anything else, which no server this code knows sends.
 */
internal fun hrefPath(href: String): String? {
    val trimmed = href.trim()
    val path =
        when {
            trimmed.startsWith('/') -> trimmed
            SCHEME_SEPARATOR in trimmed -> urlPath(trimmed)
            else -> return null
        }
    return path.substringBefore('?').substringBefore('#')
}

/** The path of [url], a full URL, which is `/` if it has none. */
internal fun urlPath(url: String): String =
    "/" + url.substringAfter(SCHEME_SEPARATOR).substringAfter('/', "")
