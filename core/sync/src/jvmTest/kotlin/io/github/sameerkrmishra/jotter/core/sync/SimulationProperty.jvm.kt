package io.github.sameerkrmishra.jotter.core.sync

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.Callable
import java.util.concurrent.Executors

internal actual fun simulationProperty(name: String): Long? =
    System.getenv(name.replace('.', '_').uppercase())?.toLong()

internal actual val simulationDefaultCases: Long = 800

/**
 * One thread per core, or `JOTTER_SIM_THREADS`. Each seed runs in `runBlocking` on one pool thread,
 * so a case never moves between threads, as it does not when it runs alone.
 */
internal actual suspend fun simulateEach(seeds: List<Long>): List<Result<SimOutcome>> {
    val threads =
        simulationProperty("jotter.sim.threads")?.toInt()
            ?: Runtime.getRuntime().availableProcessors()
    val pool = Executors.newFixedThreadPool(threads.coerceIn(1, maxOf(1, seeds.size)))
    try {
        val results =
            seeds.map { seed ->
                pool.submit(Callable { runCatching { runBlocking { simulate(seed) } } })
            }
        // Interruptible, so that the test's timeout stops the wait.
        return runInterruptible { results.map { it.get() } }
    } finally {
        pool.shutdownNow()
    }
}
