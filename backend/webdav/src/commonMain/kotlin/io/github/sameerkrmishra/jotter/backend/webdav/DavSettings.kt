package io.github.sameerkrmishra.jotter.backend.webdav

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The largest file the store reads or writes: the limit of a sync file (ADR-0014). */
internal const val MAX_FILE_BYTES: Long = 32L * 1024 * 1024

/**
 * The largest answer to one `PROPFIND`. With the two properties asked for, a member takes a few
 * hundred bytes, so this holds about a hundred thousand files in one folder.
 */
internal const val MAX_LISTING_BYTES: Long = 32L * 1024 * 1024

/**
 * How the store talks to its server. The defaults are for real use; tests shorten them.
 *
 * @property connectTimeout How long to wait for a connection.
 * @property socketTimeout How long to wait for the next bytes once connected. This is what stops
 *   a server that hangs.
 * @property requestTimeout How long one request may take in all, so that a server that sends a
 *   byte now and then cannot hold a sync for ever. A 32 MiB file at 1 Mbit/s takes 4.5 minutes.
 * @property attempts How often a request is sent before its failure counts: the first time and
 *   the retries.
 * @property retryDelay The wait before the first retry. It doubles for each further one.
 * @property maxRetryAfter The longest `Retry-After` that is waited for. A server that asks for
 *   more fails the call instead.
 * @property maxFileBytes The largest file read or written.
 * @property maxListingBytes The largest answer to one `PROPFIND`.
 */
@Suppress("LongParameterList") // Each with its default, and a test changes one or two.
internal class DavSettings(
    val connectTimeout: Duration = 20.seconds,
    val socketTimeout: Duration = 60.seconds,
    val requestTimeout: Duration = 10.minutes,
    val attempts: Int = 3,
    val retryDelay: Duration = 500.milliseconds,
    val maxRetryAfter: Duration = 30.seconds,
    val maxFileBytes: Long = MAX_FILE_BYTES,
    val maxListingBytes: Long = MAX_LISTING_BYTES,
)
