package io.github.sameerkrmishra.jotter.app.desktop

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals

class SignalCloseTest {
    private val steps: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val signals = SignalClose { steps += "after" }

    private fun closer() =
        Closer(
            keep = { steps += "keep" },
            finish = { steps += "finish" },
            stopHub = { steps += "hub" },
            closeStore = { steps += "closer's store" },
        )

    @Test
    fun aSignalBeforeTheStoreOpensClosesNothing() {
        signals.onSignal()

        assertEquals(listOf("after"), steps)
    }

    @Test
    fun aSignalBetweenOpeningTheStoreAndStartingTheAppClosesTheStore() {
        signals.storeOpened { steps += "store" }

        signals.onSignal()

        assertEquals(listOf("store", "after"), steps)
    }

    @Test
    fun onceTheAppIsAttachedASignalSavesAndClosesThroughItsCloserBeforeAnythingElse() {
        signals.storeOpened { steps += "store" }
        signals.attach(closer())

        signals.onSignal()

        assertEquals(listOf("keep", "finish", "hub", "closer's store", "after"), steps)
    }
}
