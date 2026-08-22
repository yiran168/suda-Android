package com.qrint.studio.data

import org.w3c.dom.Document
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Creates DOM parsers that work on both the Android XML implementation and the desktop JVM.
 *
 * Some Android releases throw [UnsupportedOperationException] even when XInclude is being
 * disabled. Office documents do not need XInclude or external entities, so unsupported optional
 * capabilities are ignored while an entity resolver still prevents external resource access.
 */
internal object SafeXmlParser {
    private const val DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl"
    private const val EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities"
    private const val EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities"
    private const val LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd"
    private const val ACCESS_EXTERNAL_DTD = "http://javax.xml.XMLConstants/property/accessExternalDTD"
    private const val ACCESS_EXTERNAL_SCHEMA = "http://javax.xml.XMLConstants/property/accessExternalSchema"

    fun parse(bytes: ByteArray, namespaceAware: Boolean): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = namespaceAware
            optionalCapability { setFeature(DISALLOW_DOCTYPE, true) }
            optionalCapability { setFeature(EXTERNAL_GENERAL_ENTITIES, false) }
            optionalCapability { setFeature(EXTERNAL_PARAMETER_ENTITIES, false) }
            optionalCapability { setFeature(LOAD_EXTERNAL_DTD, false) }
            optionalCapability { isXIncludeAware = false }
            optionalCapability { isExpandEntityReferences = false }
            optionalCapability { setAttribute(ACCESS_EXTERNAL_DTD, "") }
            optionalCapability { setAttribute(ACCESS_EXTERNAL_SCHEMA, "") }
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
        return builder.parse(ByteArrayInputStream(bytes))
    }

    internal inline fun optionalCapability(action: () -> Unit): Boolean =
        runCatching(action).isSuccess
}
