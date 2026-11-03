package io.github.sameerkrmishra.jotter.app.cli

import com.github.ajalt.clikt.core.CliktError
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Reading a password from stdin, as scripts give it. The terminal is the JDK's own. */
class PasswordsTest {
    private fun read(text: String) =
        Passwords(ByteArrayInputStream(text.toByteArray())) { null }.read("Password: ").reveal()

    @Test
    fun theFirstLineIsThePassword() {
        assertEquals("app-password", read("app-password\nsecond line\n"))
        assertEquals("app-password", read("app-password\r\n"))
        assertEquals("no newline", read("no newline"))
        assertEquals("ünïcode", read("ünïcode\n"))
    }

    @Test
    fun nothingOrAnUnreadableStdinIsAnError() {
        assertEquals(
            "jotter: no password was given",
            assertFailsWith<CliktError> { read("") }.message,
        )
        assertFailsWith<CliktError> { read("\n") }
        val broken =
            object : InputStream() {
                override fun read(): Int = throw IOException("broken pipe")
            }
        val failure = assertFailsWith<CliktError> { Passwords(broken) { null }.read("x") }
        assertEquals("jotter: cannot read stdin: broken pipe", failure.message)
    }

    @Test
    fun withoutATerminalTheDefaultReadsStdin() {
        // The test JVM has no terminal, so the default reads stdin as well.
        assertEquals("pw", Passwords(ByteArrayInputStream("pw\n".toByteArray())).read("x").reveal())
    }
}
