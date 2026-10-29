package io.github.sameerkrmishra.jotter.core.storage

import io.github.sameerkrmishra.jotter.core.model.TagName

/**
 * A tag in the tag list, with the number of notes outside the trash that have it.
 *
 * @property tag The tag.
 * @property count How many active or archived notes have [tag], at least 1.
 */
public data class TagCount(
    val tag: TagName,
    val count: Int,
)
