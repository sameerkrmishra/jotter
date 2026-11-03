package io.github.sameerkrmishra.jotter.backend.webdav

import io.github.sameerkrmishra.jotter.backend.api.BlobStoreFailureException

/**
 * What a `PROPFIND` with `Depth: 1` found: the resource itself and, for a collection, its members.
 *
 * @property isCollection Whether the resource is a collection.
 * @property members The direct members, by decoded name.
 */
internal class Listing(
    val isCollection: Boolean,
    val members: List<Member>,
)

/**
 * A direct member of a collection.
 *
 * @property name The decoded name.
 * @property isCollection Whether it is a collection.
 * @property size Its length in bytes, 0 for a collection.
 */
internal class Member(
    val name: String,
    val isCollection: Boolean,
    val size: Long,
)

/**
 * Reads [response], the `207` answer to a `PROPFIND` with `Depth: 1`, all or nothing: every member
 * must say what it is, and a file how long it is, and the answer may name nothing but the resource
 * and its direct members, each once. An `href` that is no path, or not UTF-8, cannot be a path of
 * the store and is skipped, like any other name that cannot be one.
 *
 * @throws BlobStoreFailureException if the answer is incomplete or names something else.
 * @throws XmlException if the answer is not a `multistatus` document.
 */
@Suppress("ThrowsCount") // One message for each way an answer can be wrong.
internal fun listingOf(response: DavResponse): Listing {
    // Not ours if a redirect led here.
    val self =
        decodeSegments(urlPath(response.url))
            ?: throw BlobStoreFailureException("Cannot read the URL ${response.url}")
    var selfIsCollection: Boolean? = null
    val members = mutableMapOf<String, Member>()
    parseMultistatus(response.body.decodeToString()).forEach { resource ->
        val segments = hrefPath(resource.href)?.let(::decodeSegments) ?: return@forEach
        val isMember = segments.size == self.size + 1 && segments.subList(0, self.size) == self
        when {
            segments == self -> selfIsCollection = describe(resource).isCollection
            !isMember ->
                throw BlobStoreFailureException(
                    "${response.url} lists ${resource.href}, which is not in it",
                )
            members.put(segments.last(), describe(resource).named(segments.last())) != null ->
                throw BlobStoreFailureException("${response.url} lists ${resource.href} twice")
        }
    }
    // Some servers leave the collection itself out. With members it must be one.
    if (selfIsCollection == null && members.isEmpty()) {
        throw BlobStoreFailureException("${response.url} did not describe itself")
    }
    return Listing(selfIsCollection ?: true, members.values.toList())
}

private class Description(
    val isCollection: Boolean,
    val size: Long,
) {
    fun named(name: String) = Member(name, isCollection, size)
}

@Suppress("ThrowsCount") // One message for each way a description can be incomplete.
private fun describe(resource: DavResource): Description {
    val status = resource.status
    if (status != null && !Status.isSuccess(status)) {
        throw BlobStoreFailureException(
            "The server could not describe ${resource.href}: status $status",
        )
    }
    val isCollection =
        resource.isCollection
            ?: throw BlobStoreFailureException("The server did not say what ${resource.href} is")
    val size =
        if (isCollection) {
            0
        } else {
            resource.size
                ?: throw BlobStoreFailureException(
                    "The server did not give the size of ${resource.href}",
                )
        }
    return Description(isCollection, size)
}
