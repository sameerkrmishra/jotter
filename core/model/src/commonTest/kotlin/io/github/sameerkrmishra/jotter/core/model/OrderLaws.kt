package io.github.sameerkrmishra.jotter.core.model

import io.kotest.property.Arb
import io.kotest.property.checkAll
import kotlin.math.sign
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks that [T]'s `compareTo` is a total order consistent with `equals`, on values from
 * [values]. The generator must produce equal values often, or the laws hold trivially.
 */
suspend fun <T : Comparable<T>> checkTotalOrderLaws(values: Arb<T>) {
    checkAll(values, values) { a, b ->
        // Antisymmetry (and totality: every pair is ordered one way, the other, or equal).
        assertEquals(a.compareTo(b).sign, -b.compareTo(a).sign, "$a vs $b")
        // Consistent with equals, and equal values hash equally.
        assertEquals(a == b, a.compareTo(b) == 0, "$a vs $b")
        if (a == b) assertEquals(a.hashCode(), b.hashCode())
    }
    checkAll(values, values, values) { a, b, c ->
        if (a <= b && b <= c) assertTrue(a <= c, "Not transitive: $a <= $b <= $c")
    }
}
