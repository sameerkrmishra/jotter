package io.github.sameerkrmishra.jotter.core.storage

/**
 * Returns this string with every unpaired surrogate replaced by U+FFFD, as UTF-8 encoders do.
 *
 * SQLite stores text as UTF-8, which cannot encode an unpaired surrogate, and the bundled SQLite
 * build does not agree with itself across platforms: on the JVM a lone surrogate turns into some
 * other character (a high one swallows the character after it), on Native into U+FFFD. Text is
 * therefore made valid before it reaches the database.
 */
internal fun String.withoutLoneSurrogates(): String {
    val text = this
    return buildString(text.length) {
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char.isHighSurrogate() && text.getOrNull(index + 1)?.isLowSurrogate() == true) {
                append(char).append(text[index + 1])
                index += 2
            } else {
                append(if (char.isSurrogate()) '�' else char)
                index++
            }
        }
    }
}

/** Whether this string is valid UTF-16, that is, has no unpaired surrogate. */
internal fun String.isValidUtf16(): Boolean = withoutLoneSurrogates() == this
