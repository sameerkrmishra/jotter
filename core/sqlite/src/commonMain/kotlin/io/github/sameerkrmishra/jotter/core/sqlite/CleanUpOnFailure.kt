package io.github.sameerkrmishra.jotter.core.sqlite

/**
 * Returns the result of [block]. If [block] throws, runs [cleanUp] (for example closing what
 * [block] was meant to hand over) and rethrows the exception.
 */
@Suppress("TooGenericExceptionCaught") // Everything caught is rethrown.
internal inline fun <T> cleanUpOnFailure(
    cleanUp: () -> Unit,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: Throwable) {
        cleanUp()
        throw e
    }
