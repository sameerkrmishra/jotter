package io.github.sameerkrmishra.jotter.backend.peer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PeerAddressTest {
    @Test
    fun parsesWhatAUserTypes() {
        assertEquals(
            PeerAddress("100.101.102.103", PeerProtocol.DEFAULT_PORT),
            PeerAddress.parse("100.101.102.103"),
        )
        assertEquals(
            PeerAddress("my-pc.tailnet.ts.net", 8080),
            PeerAddress.parse(" my-pc.tailnet.ts.net:8080 "),
        )
        assertEquals(PeerAddress("h", 1), PeerAddress.parse("h:1"))
        assertEquals(PeerAddress("My-PC.Example", 2), PeerAddress.parse("My-PC.Example:2"))
        assertEquals("h:65535", PeerAddress.parse("h:65535").toString())
    }

    @Test
    fun refusesWhatIsNotAnAddress() {
        listOf(
            "",
            ":1",
            "h:",
            "h:x",
            "h:0",
            "h:65536",
            "h:123456",
            "h:-1",
            "a/b",
            "a b",
            "u@h",
            "[::1]:1",
            "::1",
            "h?x",
        ).forEach { text ->
            assertFailsWith<IllegalArgumentException>(text) { PeerAddress.parse(text) }
        }
        assertFailsWith<IllegalArgumentException> { PeerAddress("h", 0) }
    }
}
