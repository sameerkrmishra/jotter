package io.github.sameerkrmishra.jotter.interop.markdownexport

/** What [parseFile] reads back from an exported file. */
data class ParsedFile(
    val fields: Map<String, String>,
    val tags: List<String>,
    val body: String,
)

/**
 * Parses the subset of YAML the export writes, strictly: anything else fails the test. It splits
 * the file at the first `---` line, as real front-matter readers do.
 */
fun parseFile(content: String): ParsedFile {
    require(content.startsWith("---\n")) { "No opening fence: $content" }
    val rest = content.removePrefix("---\n")
    val end = rest.indexOf("---\n").also { require(it >= 0) { "No closing fence: $content" } }
    val header = rest.take(end)
    require(header.isEmpty() || header.endsWith('\n')) { "Fence inside a line: $content" }

    val fields = LinkedHashMap<String, String>()
    val tags = ArrayList<String>()
    for (line in header.lines().dropLast(1)) {
        if (line.startsWith("  - ")) {
            tags += parseScalar(line.removePrefix("  - "))
        } else {
            val key = line.substringBefore(':')
            val value = line.removePrefix("$key:").removePrefix(" ")
            fields[key] = parseScalar(value)
        }
    }
    return ParsedFile(fields, tags, rest.substring(end + "---\n".length))
}

private fun parseScalar(text: String): String =
    if (text.startsWith('"')) {
        parseQuoted(text)
    } else {
        // The plain scalars: ids, booleans, states, timestamps and the empty list marker.
        require(text.none { it in " \"'#" }) { "Should be quoted: $text" }
        text
    }

private fun parseQuoted(text: String): String {
    require(text.length >= 2 && text.endsWith('"')) { "Unterminated scalar: $text" }
    val inner = text.substring(1, text.length - 1)
    return buildString {
        var index = 0
        while (index < inner.length) {
            val char = inner[index++]
            require(char != '"') { "Unescaped quote: $text" }
            if (char != '\\') {
                append(char)
                continue
            }
            when (val escape = inner[index++]) {
                '"', '\\' -> {
                    append(escape)
                }

                'u' -> {
                    append(inner.substring(index, index + 4).toInt(radix = 16).toChar())
                    index += 4
                }

                else -> {
                    error("Unknown escape \\$escape: $text")
                }
            }
        }
    }
}
