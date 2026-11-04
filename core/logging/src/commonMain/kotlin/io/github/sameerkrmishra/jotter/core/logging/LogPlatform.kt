package io.github.sameerkrmishra.jotter.core.logging

import okio.Path

// What the log file needs that okio has no word for: file modes, a lock that other processes see,
// and this process's id (ADR-0021). Each platform's part stays thin, as the native one is outside
// the coverage gate (ADR-0006).

/**
 * Creates [directory] and its parents. [directory] itself is made readable by this user alone
 * (0700), and so made if it exists with a wider mode and is this user's.
 *
 * @throws okio.IOException if it cannot be created, or is not a directory.
 */
internal expect fun createPrivateDirectory(directory: Path)

/**
 * Appends [bytes] to [file] with one write in append mode, creating it readable by this user
 * alone (0600), and making an existing file of this user's so.
 *
 * @throws okio.IOException if that fails.
 */
internal expect fun appendPrivate(
    file: Path,
    bytes: ByteArray,
)

/**
 * Runs [block] while holding an exclusive lock on [lockFile], created 0600 if needed, which other
 * processes taking the same lock wait for: a POSIX record lock (`fcntl`), the same family on the
 * JVM, Android and Linux. One thread of a process at a time may call it for a file.
 *
 * @throws okio.IOException if the lock cannot be taken.
 */
internal expect fun <T> withProcessLock(
    lockFile: Path,
    block: () -> T,
): T

/** This process's id, which every line of a shared log carries, or 0 if it cannot be found. */
internal expect val processId: Long
