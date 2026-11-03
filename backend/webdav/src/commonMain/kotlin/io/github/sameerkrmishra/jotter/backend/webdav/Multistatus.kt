package io.github.sameerkrmishra.jotter.backend.webdav

internal const val DAV_NAMESPACE = "DAV:"

/** The body of a `PROPFIND` that asks for what a listing needs, and nothing else. */
internal const val PROPFIND_BODY: String =
    """<?xml version="1.0" encoding="utf-8"?>""" +
        """<D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/><D:getcontentlength/></D:prop>""" +
        """</D:propfind>"""

private const val STATUS_CLASS = 100
private const val SUCCESS_CLASS = 2

/**
 * One `response` of a `207 Multi-Status` answer to [PROPFIND_BODY].
 *
 * @property href The `href`, as the server sent it.
 * @property status The status of the whole response, if it has one instead of properties. A
 *   server sends that for a member it cannot describe.
 * @property isCollection Whether `resourcetype` says collection, or null if the server did not
 *   report `resourcetype`.
 * @property size `getcontentlength`, or null if the server did not report it.
 */
internal class DavResource(
    val href: String,
    val status: Int?,
    val isCollection: Boolean?,
    val size: Long?,
)

/**
 * Reads a `207 Multi-Status` body. A property counts only from a `propstat` whose status is a
 * success, so a member whose properties failed reads as unknown, never as an empty file.
 *
 * @throws XmlException if [text] is not a `multistatus` document.
 */
internal fun parseMultistatus(text: String): List<DavResource> {
    val root = parseXml(text)
    if (root.namespace != DAV_NAMESPACE || root.name != "multistatus") {
        throw XmlException("the answer is not a DAV:multistatus but ${root.name}")
    }
    return root.children(DAV_NAMESPACE, "response").flatMap(::resources)
}

private fun resources(response: XmlElement): List<DavResource> {
    val hrefs = response.children(DAV_NAMESPACE, "href").map { it.text.trim() }
    if (hrefs.isEmpty()) throw XmlException("a response has no href")
    val status = response.child(DAV_NAMESPACE, "status")?.let { statusCode(it.text) }
    val props =
        response
            .children(DAV_NAMESPACE, "propstat")
            .filter { propstat ->
                val code = propstat.child(DAV_NAMESPACE, "status")?.let { statusCode(it.text) }
                code != null && code / STATUS_CLASS == SUCCESS_CLASS
            }.flatMap { it.children(DAV_NAMESPACE, "prop") }
            .flatMap { it.children }
    val resourceType =
        props.firstOrNull {
            it.namespace == DAV_NAMESPACE &&
                it.name == "resourcetype"
        }
    val length =
        props.firstOrNull {
            it.namespace == DAV_NAMESPACE && it.name == "getcontentlength"
        }
    val size =
        length?.let {
            it.text
                .trim()
                .toLongOrNull()
                ?.takeIf { size -> size >= 0 }
                ?: throw XmlException("bad getcontentlength \"${it.text}\"")
        }
    val isCollection = resourceType?.let { it.child(DAV_NAMESPACE, "collection") != null }
    return hrefs.map { DavResource(it, status, isCollection, size) }
}

/** The code in a status line such as `HTTP/1.1 200 OK`. */
private fun statusCode(line: String): Int =
    line
        .trim()
        .split(' ')
        .getOrNull(1)
        ?.toIntOrNull()
        ?: throw XmlException("bad status line \"${line.trim()}\"")
