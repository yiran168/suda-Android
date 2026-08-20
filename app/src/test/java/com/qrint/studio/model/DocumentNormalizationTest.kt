package com.qrint.studio.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentNormalizationTest {
    @Test
    fun invalidPaperAndElementNumbersAreMadeFinite() {
        val normalized = LabelDocument(
            paper = PaperSettings(
                contentWidthMm = Float.NaN,
                labelHeightMm = Float.POSITIVE_INFINITY,
                offsetXmm = Float.NEGATIVE_INFINITY,
            ),
            elements = listOf(
                LabelElement(
                    id = "bad",
                    kind = ElementKind.TEXT,
                    rotation = Float.NaN,
                    fontSizeDots = Float.POSITIVE_INFINITY,
                    contrast = Float.NaN,
                ),
            ),
        ).normalized()

        assertTrue(normalized.paper.contentWidthMm.isFinite())
        assertTrue(normalized.paper.labelHeightMm.isFinite())
        assertTrue(normalized.elements.single().rotation.isFinite())
        assertTrue(normalized.elements.single().fontSizeDots.isFinite())
        assertTrue(normalized.elements.single().contrast.isFinite())
    }

    @Test
    fun duplicateAndBlankIdsAreReplaced() {
        val normalized = LabelDocument(
            elements = listOf(
                LabelElement(id = "same", kind = ElementKind.TEXT),
                LabelElement(id = "same", kind = ElementKind.TEXT),
                LabelElement(id = "", kind = ElementKind.TEXT),
            ),
        ).normalized()

        assertEquals(3, normalized.elements.map { it.id }.distinct().size)
        assertNotEquals("", normalized.elements.last().id)
    }

    @Test
    fun importedElementCountIsBounded() {
        val source = List(MAX_DOCUMENT_ELEMENTS + 25) { index ->
            LabelElement(id = "element-$index", kind = ElementKind.SHAPE)
        }
        assertEquals(MAX_DOCUMENT_ELEMENTS, LabelDocument(elements = source).normalized().elements.size)
    }
}
