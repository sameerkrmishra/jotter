package io.github.sameerkrmishra.jotter.backend.webdav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class XmlTest {
    @Test
    fun namespacesResolveThroughPrefixesAndDefaults() {
        val root =
            parseXml(
                """<?xml version="1.0"?>
                <!-- a comment -->
                <a:root xmlns:a="DAV:" xmlns="urn:default">
                  <plain/>
                  <a:x xmlns:a="urn:other"><a:y/></a:x>
                  <b:z xmlns:b='DAV:' attr = "v"/>
                </a:root>
                <?trailing instruction?>
                """,
            )
        assertEquals("DAV:", root.namespace)
        assertEquals("root", root.name)
        assertEquals(listOf("urn:default", "urn:other", "DAV:"), root.children.map { it.namespace })
        assertEquals(
            "urn:other",
            root
                .child("urn:other", "x")
                ?.children
                ?.single()
                ?.namespace,
        )
        assertNotNull(root.child("DAV:", "z"))
        assertNull(root.child("DAV:", "missing"))
        assertEquals(1, root.children("urn:default", "plain").size)
    }

    @Test
    fun anElementWithoutANamespaceHasTheEmptyOne() {
        assertEquals("", parseXml("<root/>").namespace)
    }

    @Test
    fun textHasReferencesReplacedAndCdataKept() {
        val root =
            parseXml(
                "<r>a&amp;b&lt;&gt;&quot;&apos;&#65;&#x42;&#x1F4DD;" +
                    "<![CDATA[<raw&>]]><!-- c --><?pi x?></r>",
            )
        assertEquals("a&b<>\"'AB📝<raw&>", root.text)
    }

    @Test
    fun attributeValuesMayHoldReferences() {
        parseXml("""<r a="x&amp;y" b='"'/>""")
    }

    @Test
    fun malformedDocumentsAreRefused() {
        listOf(
            "",
            "text",
            "<!DOCTYPE r [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><r>&e;</r>",
            "<r><!DOCTYPE x></r>",
            "<r>",
            "<r></s>",
            "<r/><s/>",
            "<r a=\"1\"b=\"2\"/>",
            "<r a=1/>",
            "<r a=\"<\"/>",
            "<r a=\"open/>",
            "<r>&unknown;</r>",
            "<r>&amp</r>",
            "<r>&#xZZ;</r>",
            "<r>&#1114112;</r>",
            "<r>&#-1;</r>",
            "<r a 'x'/>",
            "<r a=",
            "<p:r/>",
            "<r><![CDATA[open</r>",
            "<r><!-- open</r>",
            "</r>",
            "<?pi?>",
            "<r>< /r>",
            "<r =\"x\"/>",
        ).forEach { text -> assertFailsWith<XmlException>(text) { parseXml(text) } }
    }

    @Test
    fun nestingIsLimited() {
        val deep = "<a>".repeat(100) + "</a>".repeat(100)
        assertFailsWith<XmlException> { parseXml(deep) }
        parseXml("<a>".repeat(60) + "</a>".repeat(60))
    }
}
