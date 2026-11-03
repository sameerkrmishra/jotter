package io.github.sameerkrmishra.jotter.backend.api

private const val SEPARATOR = '/'
private const val BACKSLASH = '\\'
private const val NUL = '\u0000'
private const val COLON = ':'

/**
 * The start of a path segment that is kept for a backend's own files, such as the temporary files
 * of a local folder. No valid path has it, ignoring case, because a store on a FAT drive ignores
 * case too.
 */
public const val RESERVED_SEGMENT_PREFIX: String = ".jotter-"

/**
 * Checks a path that comes from outside. Paths are read from remote stores, so this is a security
 * boundary, and every [BlobStore] method applies it before touching anything.
 *
 * A valid path is not empty and consists of segments separated by a single `/`. So it has no
 * leading or trailing `/`. No segment is empty, `.` or `..`, contains a backslash, a NUL or a
 * colon, or starts with [RESERVED_SEGMENT_PREFIX]. A colon has no use in the backend layout, FAT
 * cannot store it, and a segment like `C:` is a drive letter to path libraries, which would leave
 * the store's root.
 *
 * @throws InvalidBlobPathException if [path] breaks one of these rules.
 */
public fun requireValidBlobPath(path: String) {
    if (path.isEmpty()) throw InvalidBlobPathException(path, "it is empty")
    path.split(SEPARATOR).forEach { segment ->
        segmentProblem(segment)?.let { throw InvalidBlobPathException(path, it) }
    }
}

/**
 * Checks the argument of [BlobStore.list]: the empty string, which means the whole store, or a
 * valid path as in [requireValidBlobPath].
 *
 * @throws InvalidBlobPathException if [prefix] is neither.
 */
public fun requireValidBlobPrefix(prefix: String) {
    if (prefix.isNotEmpty()) requireValidBlobPath(prefix)
}

/** Whether [name], a file or directory name found in a store, can be part of a valid path. */
public fun isValidBlobSegment(name: String): Boolean = segmentProblem(name) == null

private fun segmentProblem(segment: String): String? =
    when {
        segment.isEmpty() -> "it has an empty segment"
        segment == "." || segment == ".." -> "it has a \"$segment\" segment"
        BACKSLASH in segment -> "it has a backslash"
        NUL in segment -> "it has a NUL character"
        COLON in segment -> "it has a colon"
        segment.startsWith(RESERVED_SEGMENT_PREFIX, ignoreCase = true) ->
            "it has a segment starting with the reserved \"$RESERVED_SEGMENT_PREFIX\""
        else -> null
    }
