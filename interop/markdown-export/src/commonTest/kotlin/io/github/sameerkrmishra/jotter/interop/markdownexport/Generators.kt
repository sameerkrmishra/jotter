package io.github.sameerkrmishra.jotter.interop.markdownexport

import io.kotest.property.Arb
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map

private val surrogates = 0xD800..0xDFFF

/** Fragments that can break file names or YAML, alone or in combination. */
private val awkward: List<String> =
    listOf(
        "/",
        "\\",
        "..",
        ".",
        " ",
        "\n",
        "\r\n",
        "\t",
        "\u0000",
        "\u001F",
        "\u007F",
        "\u0085",
        "\u00A0",
        "\u2028",
        "\u200B",
        "\u202E",
        "\uFEFF",
        ":",
        ": ",
        " #",
        "#",
        "'",
        "\"",
        "\\n",
        "---",
        "- ",
        "? ",
        "[",
        "{",
        "&",
        "*",
        "!",
        "|",
        ">",
        "%",
        "@",
        "`",
        "~",
        "null",
        "true",
        "CON",
        "nul",
        "COM1",
        "a",
        "A",
        "é",
        "e\u0301",
        "ß",
        "SS",
        "ſ",
        "\u212A",
        "語",
        "😀",
        "x".repeat(150),
        "語".repeat(60),
        "😀".repeat(40),
    )

/**
 * Text that mixes [awkward] fragments with arbitrary UTF-16 code units. Without
 * [allowLoneSurrogates] it is well formed, so UTF-8 can hold it.
 */
fun texts(allowLoneSurrogates: Boolean): Arb<String> {
    val units =
        Arb.int(0..0xFFFF).map { unit ->
            if (!allowLoneSurrogates && unit in surrogates) "\uFFFD" else unit.toChar().toString()
        }
    return Arb.list(Arb.choice(Arb.element(awkward), units), 0..12).map { it.joinToString("") }
}
