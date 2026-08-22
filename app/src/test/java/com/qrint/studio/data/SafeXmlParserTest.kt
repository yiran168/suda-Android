package com.qrint.studio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeXmlParserTest {
    @Test
    fun parsesOrdinaryOfficeXml() {
        val document = SafeXmlParser.parse(
            "<w:document xmlns:w=\"urn:test\"><w:t>素打</w:t></w:document>".toByteArray(),
            namespaceAware = true,
        )

        assertEquals("素打", document.documentElement.textContent)
    }

    @Test
    fun unsupportedAndroidXmlCapabilityDoesNotAbortImport() {
        val applied = SafeXmlParser.optionalCapability {
            throw UnsupportedOperationException(
                "This parser does not support specification \"Unknown\" version \"0.0\"",
            )
        }

        assertFalse(applied)
    }

    @Test
    fun supportedXmlCapabilityIsApplied() {
        var called = false

        val applied = SafeXmlParser.optionalCapability { called = true }

        assertTrue(applied)
        assertTrue(called)
    }
}
