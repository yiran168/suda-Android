package com.qrint.studio.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperCalibrationTest {
    @Test fun everyTenthMillimetreProducesABoundedPhysicalWindow() {
        for (tenths in 100..570) {
            val width = tenths / 10f
            for (anchor in HorizontalAnchor.entries) {
                val paper = PaperSettings(contentWidthMm = width, mediaWidthMm = width, horizontalAnchor = anchor)
                val placement = paper.placement()
                assertTrue("$width mm $anchor start", placement.paperStartDot >= 0)
                assertTrue("$width mm $anchor end", placement.paperEndDotExclusive <= placement.headDots)
                assertEquals(paper.contentWidthDots(), placement.printableDots)
            }
        }
    }

    @Test fun everySupportedWidthAndLoadingSideMapsExactlyToBothEditorEdges() {
        for (tenths in 100..570) {
            val width = tenths / 10f
            for (anchor in listOf(HorizontalAnchor.LEFT, HorizontalAnchor.RIGHT)) {
                val paper = PaperSettings(contentWidthMm = width, mediaWidthMm = width, horizontalAnchor = anchor)
                    .normalized()
                val editor = PaperViewport.forEditor(paper)
                assertEquals("$width mm $anchor left", 0f, editor.headToPaperX(paper.printableStartX().toFloat()), 0.0001f)
                assertEquals(
                    "$width mm $anchor right",
                    editor.paperWidthDots.toFloat(),
                    editor.headToPaperX(paper.printableEndX().toFloat()),
                    0.0001f,
                )
                assertEquals(
                    "$width mm $anchor reversible left",
                    paper.printableStartX().toFloat(),
                    editor.paperToHeadX(0f),
                    0.0001f,
                )
                assertEquals(
                    "$width mm $anchor reversible right",
                    paper.printableEndX().toFloat(),
                    editor.paperToHeadX(editor.paperWidthDots.toFloat()),
                    0.0001f,
                )
            }
        }
    }

    @Test fun narrowPaperAnchorsToTheRequestedPhysicalSide() {
        val left = PaperSettings(contentWidthMm = 40f, horizontalAnchor = HorizontalAnchor.LEFT).placement()
        val right = PaperSettings(contentWidthMm = 40f, horizontalAnchor = HorizontalAnchor.RIGHT).placement()
        assertEquals(0, left.paperStartDot)
        assertEquals(left.headDots, right.paperEndDotExclusive)
        assertEquals(left.printableDots, right.printableDots)
    }

    @Test fun wideRollExposesOnlyTheFixedThermalHead() {
        val placement = PaperSettings(contentWidthMm = 57f, horizontalAnchor = HorizontalAnchor.RIGHT).placement()
        assertEquals(0, placement.paperStartDot)
        assertEquals(placement.headDots, placement.paperEndDotExclusive)
        assertTrue(placement.paperDots > placement.headDots)
    }

    @Test fun generatedCalibrationDocumentIsBoundedForTheFullSupportedRange() {
        for (tenths in 100..570) {
            val width = tenths / 10f
            val paper = PaperSettings(contentWidthMm = width, mediaWidthMm = width, horizontalAnchor = HorizontalAnchor.LEFT)
            val document = createPaperCalibrationDocument(paper)
            val start = document.paper.printableStartX()
            val end = document.paper.printableEndX()
            assertTrue(document.elements.isNotEmpty())
            document.elements.forEach { element ->
                assertTrue("$width mm element left", element.x >= start)
                assertTrue("$width mm element right", element.right() <= end)
                assertTrue("$width mm element top", element.y >= 0)
                assertTrue("$width mm element bottom", element.bottom() <= document.paper.fixedHeightDots())
            }
        }
    }
}
