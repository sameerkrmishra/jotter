// A parser fails in many places, each with its own message.
@file:Suppress("ThrowsCount")

package io.github.sameerkrmishra.jotter.backend.webdav

private const val HEX_RADIX = 16
private const val MAX_DEPTH = 64

/**
 * An XML element with its namespace resolved.
 *
 * @property namespace The namespace URI, or the empty string for none.
 * @property name The local name.
 * @property children The child elements, in order.
 * @property text The character data directly inside, with entities replaced.
 */
internal class XmlElement(
    val namespace: String,
    val name: String,
    val children: List<XmlElement>,
    val text: String,
) {
    /** The children named [name] in [namespace]. */
    fun children(
        namespace: String,
        name: String,
    ): List<XmlElement> = children.filter { it.namespace == namespace && it.name == name }

    /** The first child named [name] in [namespace], if any. */
    fun child(
        namespace: String,
        name: String,
    ): XmlElement? = children.firstOrNull { it.namespace == namespace && it.name == name }
}

/** The text is not XML this parser accepts. */
internal class XmlException(
    message: String,
) : Exception(message)

/**
 * Parses [text] into its root element. A small parser for the documents a WebDAV server sends:
 * elements, attributes, namespaces, character and entity references, CDATA, comments and
 * processing instructions. A document type declaration is refused, so no entity can be defined
 * and nothing outside the text is ever read.
 *
 * @throws XmlException if [text] is not a well-formed document of that kind.
 */
internal fun parseXml(text: String): XmlElement = XmlParser(text).document()

@Suppress("TooManyFunctions") // One small function per part of the grammar, kept together.
private class XmlParser(
    private val text: String,
) {
    private var position = 0

    private class Open(
        val qualifiedName: String,
        val namespaces: Map<String, String>,
        val children: MutableList<XmlElement> = mutableListOf(),
        val text: StringBuilder = StringBuilder(),
    )

    fun document(): XmlElement {
        skipMisc()
        // skipMisc() has passed comments and processing instructions, and refused "<!".
        if (peek() != '<' ||
            text.startsWith("</", position)
        ) {
            throw malformed("expected the root element")
        }
        val root = element(mapOf("xml" to XML_NAMESPACE), depth = 0)
        skipMisc()
        if (position != text.length) throw malformed("text after the root element")
        return root
    }

    /** Skips whitespace, comments and processing instructions outside the root element. */
    private fun skipMisc() {
        while (true) {
            skipSpace()
            when {
                text.startsWith("<?", position) -> skipPast("?>")
                text.startsWith("<!--", position) -> skipPast("-->")
                text.startsWith(
                    "<!",
                    position,
                ) -> throw malformed("document type declarations are refused")
                else -> return
            }
        }
    }

    private fun element(
        inherited: Map<String, String>,
        depth: Int,
    ): XmlElement {
        if (depth > MAX_DEPTH) throw malformed("elements nested too deep")
        expect('<')
        val qualifiedName = name()
        val attributes = mutableListOf<Pair<String, String>>()
        while (true) {
            val hadSpace = skipSpace()
            when {
                peek() == '>' || text.startsWith("/>", position) -> break
                !hadSpace -> throw malformed("expected a space before an attribute")
            }
            val attribute = name()
            skipSpace()
            expect('=')
            skipSpace()
            attributes += attribute to quoted()
        }
        val namespaces = inherited.toMutableMap()
        attributes.forEach { (attribute, value) ->
            when {
                attribute == "xmlns" -> namespaces[""] = value
                attribute.startsWith("xmlns:") ->
                    namespaces[attribute.removePrefix("xmlns:")] =
                        value
            }
        }
        val open = Open(qualifiedName, namespaces)
        if (text.startsWith("/>", position)) {
            position += 2
        } else {
            expect('>')
            content(open, depth)
        }
        val prefix = qualifiedName.substringBefore(':', "")
        // The empty prefix is bound to no namespace unless an xmlns says otherwise.
        val namespace =
            namespaces[prefix] ?: prefix.takeIf { it.isEmpty() }
                ?: throw malformed("unbound prefix $prefix")
        return XmlElement(
            namespace,
            qualifiedName.substringAfter(':'),
            open.children,
            open.text.toString(),
        )
    }

    private fun content(
        open: Open,
        depth: Int,
    ) {
        while (true) {
            if (position >= text.length) throw malformed("${open.qualifiedName} is not closed")
            when {
                text.startsWith("</", position) -> {
                    position += 2
                    if (name() !=
                        open.qualifiedName
                    ) {
                        throw malformed("${open.qualifiedName} closed by another tag")
                    }
                    skipSpace()
                    expect('>')
                    return
                }
                text.startsWith("<!--", position) -> skipPast("-->")
                text.startsWith("<![CDATA[", position) -> {
                    val end = text.indexOf("]]>", position)
                    if (end < 0) throw malformed("CDATA is not closed")
                    open.text.append(text, position + CDATA_START.length, end)
                    position = end + CDATA_END_LENGTH
                }
                text.startsWith("<?", position) -> skipPast("?>")
                text.startsWith(
                    "<!",
                    position,
                ) -> throw malformed("declarations are refused inside elements")
                peek() == '<' -> open.children += element(open.namespaces, depth + 1)
                peek() == '&' -> open.text.append(reference())
                else -> open.text.append(text[position++])
            }
        }
    }

    private fun quoted(): String {
        val quote = peek()
        if (quote !in QUOTES) throw malformed("expected a quoted value")
        position++
        val value = StringBuilder()
        while (true) {
            if (position >= text.length) throw malformed("a value is not closed")
            val char = text[position]
            when (char) {
                quote -> {
                    position++
                    return value.toString()
                }
                '<' -> throw malformed("< in a value")
                '&' -> value.append(reference())
                else -> {
                    value.append(char)
                    position++
                }
            }
        }
    }

    private fun reference(): String {
        val end = text.indexOf(';', position)
        if (end < 0) throw malformed("a reference is not closed")
        val body = text.substring(position + 1, end)
        position = end + 1
        return when {
            body.startsWith("#x") -> codePoint(body.substring(2).toIntOrNull(HEX_RADIX))
            body.startsWith("#") -> codePoint(body.substring(1).toIntOrNull())
            else -> PREDEFINED[body] ?: throw malformed("unknown entity &$body;")
        }
    }

    private fun codePoint(value: Int?): String {
        if (value == null || value !in 0..MAX_CODE_POINT) throw malformed("bad character reference")
        return if (value < SUPPLEMENTARY_START) {
            value.toChar().toString()
        } else {
            val offset = value - SUPPLEMENTARY_START
            charArrayOf(
                (HIGH_SURROGATE_START + (offset shr SURROGATE_BITS)).toChar(),
                (LOW_SURROGATE_START + (offset and SURROGATE_MASK)).toChar(),
            ).concatToString()
        }
    }

    private fun name(): String {
        val start = position
        while (peek().isNameChar()) position++
        if (position == start) throw malformed("expected a name")
        return text.substring(start, position)
    }

    private fun skipSpace(): Boolean {
        val start = position
        while (peek().isXmlSpace()) position++
        return position > start
    }

    private fun skipPast(end: String) {
        val index = text.indexOf(end, position)
        if (index < 0) throw malformed("$end is missing")
        position = index + end.length
    }

    private fun expect(char: Char) {
        if (peek() != char) throw malformed("expected '$char'")
        position++
    }

    /** The next character, or [END] at the end of the text. */
    private fun peek(): Char = if (position < text.length) text[position] else END

    private fun malformed(message: String) = XmlException("$message at offset $position")

    private fun Char.isXmlSpace() = this in SPACE

    private fun Char.isNameChar() = this !in NOT_IN_NAMES

    companion object {
        const val END = '\u0000'
        const val SPACE = " \t\n\r"
        const val QUOTES = "\"'"
        const val NOT_IN_NAMES = "$SPACE<>/=\"'&;!?$END"
        const val XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace"
        const val CDATA_START = "<![CDATA["
        const val CDATA_END_LENGTH = 3
        const val MAX_CODE_POINT = 0x10FFFF
        const val SUPPLEMENTARY_START = 0x10000
        const val HIGH_SURROGATE_START = 0xD800
        const val LOW_SURROGATE_START = 0xDC00
        const val SURROGATE_BITS = 10
        const val SURROGATE_MASK = 0x3FF
        val PREDEFINED =
            mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")
    }
}
