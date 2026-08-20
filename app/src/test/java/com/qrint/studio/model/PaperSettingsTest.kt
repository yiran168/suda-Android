package com.qrint.studio.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperSettingsTest {
    @Test fun qringProfileUses384DotHead() {
        val paper = PaperSettings()
        assertEquals(384, paper.headDots)
        assertEquals(384, paper.contentWidthDots())
        assertTrue(paper.dotsToMm(384) in 47.9f..48.1f)
    }

    @Test fun calibrationOffsetCanBeNegative() {
        val paper = PaperSettings(offsetXmm = -1f, offsetYmm = -2f)
        assertTrue(paper.mmToDots(paper.offsetXmm) < 0)
        assertTrue(paper.mmToDots(paper.offsetYmm) < 0)
    }

    @Test fun everyPaperBelow55MmRequiresAnEdgeLoadingChoice() {
        assertTrue(PaperSettings(contentWidthMm = 10f).requiresNarrowLoading())
        assertTrue(PaperSettings(contentWidthMm = 54.9f).requiresNarrowLoading())
        assertFalse(PaperSettings(contentWidthMm = 55f).requiresNarrowLoading())
        assertFalse(PaperSettings(contentWidthMm = 57f).requiresNarrowLoading())
    }

    @Test fun legacyCenteredNarrowPaperIsMigratedToAVisibleEdge() {
        val normalized = PaperSettings(
            contentWidthMm = 54.9f,
            horizontalAnchor = HorizontalAnchor.CENTER,
        ).normalized()

        assertEquals(HorizontalAnchor.LEFT, normalized.horizontalAnchor)
    }

    @Test fun continuousPaperFitsContentButLabelIsFixed() {
        val element = LabelElement(kind = ElementKind.TEXT, y = 100, height = 50)
        val continuous = LabelDocument(paper = PaperSettings(mode = PaperMode.CONTINUOUS), elements = listOf(element))
        assertTrue(continuous.outputHeightDots() > 150)
        val label = continuous.copy(paper = continuous.paper.copy(mode = PaperMode.LABEL, labelHeightMm = 30f))
        assertEquals(label.paper.mmToDots(30f), label.outputHeightDots())
    }

    @Test fun continuousHeightIncludesRotatedVisualBounds() {
        val flat = LabelElement(kind = ElementKind.TEXT, y = 20, width = 160, height = 20, rotation = 0f)
        val rotated = flat.copy(rotation = 45f)

        assertTrue(rotated.visualBottom() > flat.visualBottom())
        val document = LabelDocument(elements = listOf(rotated))
        assertTrue(document.continuousHeightDots() >= rotated.visualBottom())
    }

    @Test fun normalizingKeepsEditableElementsFullyInsideNarrowPaper() {
        val paper = PaperSettings(contentWidthMm = 40f, horizontalAnchor = HorizontalAnchor.RIGHT, mode = PaperMode.LABEL)
        val document = LabelDocument(
            paper = paper,
            elements = listOf(LabelElement(kind = ElementKind.TEXT, x = -100, y = -20, width = 900, height = 900)),
        ).normalized()
        val element = document.elements.single()

        assertTrue(element.x >= paper.contentStartX())
        assertTrue(element.right() <= paper.contentStartX() + paper.contentWidthDots())
        assertTrue(element.y >= 0)
        assertTrue(element.bottom() <= paper.fixedHeightDots())
    }
}
