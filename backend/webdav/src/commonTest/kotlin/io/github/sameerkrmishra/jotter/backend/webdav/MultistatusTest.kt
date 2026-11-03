package io.github.sameerkrmishra.jotter.backend.webdav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MultistatusTest {
    @Test
    fun propertiesCountOnlyFromSuccessfulPropstats() {
        // As Apache answers: DAV: under two prefixes, and the length of a collection as 404.
        val resources =
            parseMultistatus(
                """
                <D:multistatus xmlns:D="DAV:"><D:response xmlns:lp1="DAV:">
                  <D:href>/s/</D:href>
                  <D:propstat><D:prop><lp1:resourcetype><D:collection/></lp1:resourcetype></D:prop>
                    <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                  <D:propstat><D:prop><D:getcontentlength>99</D:getcontentlength></D:prop>
                    <D:status>HTTP/1.1 404 Not Found</D:status></D:propstat>
                </D:response><D:response>
                  <D:href>/s/f</D:href>
                  <D:propstat><D:prop xmlns:x="urn:other"><x:resourcetype><D:collection/></x:resourcetype>
                    <x:getcontentlength>5</x:getcontentlength><D:displayname>f</D:displayname>
                    <D:resourcetype/><D:getcontentlength> 12 </D:getcontentlength>
                  </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
                </D:response></D:multistatus>
                """,
            )
        assertEquals(listOf("/s/", "/s/f"), resources.map { it.href })
        assertEquals(listOf(true, false), resources.map { it.isCollection })
        assertEquals(listOf(null, 12L), resources.map { it.size })
        assertEquals(listOf(null, null), resources.map { it.status })
    }

    @Test
    fun aResponseWithAStatusAppliesItToEachHref() {
        val resources =
            parseMultistatus(
                """<multistatus xmlns="DAV:"><response><href>/a</href><href>/b</href>
                <status>HTTP/1.1 403 Forbidden</status></response></multistatus>""",
            )
        assertEquals(listOf(403, 403), resources.map { it.status })
        assertNull(resources.first().isCollection)
    }

    @Test
    fun aPropstatWithoutStatusCountsForNothing() {
        val resource =
            parseMultistatus(
                """<multistatus xmlns="DAV:"><response><href>/a</href><propstat><prop><resourcetype/>
                </prop></propstat></response></multistatus>""",
            ).single()
        assertNull(resource.isCollection)
    }

    @Test
    fun answersThatAreNotAMultistatusAreRefused() {
        listOf(
            "<html/>",
            """<D:prop xmlns:D="DAV:"/>""",
            """<x:multistatus xmlns:x="urn:not-dav"/>""",
            """<multistatus xmlns="DAV:"><response/></multistatus>""",
            """<multistatus xmlns="DAV:"><response><href>/a</href><status>broken</status>
            </response></multistatus>""",
            """<multistatus xmlns="DAV:"><response><href>/a</href><status>HTTP/1.1</status>
            </response></multistatus>""",
            """<multistatus xmlns="DAV:"><response><href>/a</href><propstat><prop>
            <getcontentlength>-1</getcontentlength>
            </prop><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>""",
            """<multistatus xmlns="DAV:"><response><href>/a</href><propstat><prop><getcontentlength>x</getcontentlength>
            </prop><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>""",
        ).forEach { text -> assertFailsWith<XmlException>(text) { parseMultistatus(text) } }
    }
}
