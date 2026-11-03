package io.github.sameerkrmishra.jotter.backend.peer

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Fails a request that makes no progress for [timeout]. Ktor's CIO engine does not apply its
 * socket timeout on linuxX64, so the client keeps this watch itself, on every target: [progress]
 * is called when the answer's head arrives and after each part of a body, in either direction.
 */
internal class IdleWatch(
    private val timeout: Duration,
) {
    @Volatile
    private var last = TimeSource.Monotonic.markNow()

    /** Records that bytes moved. */
    fun progress() {
        last = TimeSource.Monotonic.markNow()
    }

    private suspend fun watch(): Nothing {
        while (true) {
            val left = timeout - last.elapsedNow()
            if (!left.isPositive()) throw HubSilentException(timeout)
            delay(left)
        }
    }

    companion object {
        /** Runs [block] under a watch, which fails it with [HubSilentException] if it stalls. */
        suspend fun <T> watching(
            timeout: Duration,
            block: suspend (IdleWatch) -> T,
        ): T =
            coroutineScope {
                val watch = IdleWatch(timeout)
                val dog = launch { watch.watch() }
                try {
                    block(watch)
                } finally {
                    dog.cancel()
                }
            }
    }
}

/** A hub that sent or took nothing for [timeout]. */
internal class HubSilentException(
    timeout: Duration,
) : Exception("The hub sent or took nothing for $timeout")
