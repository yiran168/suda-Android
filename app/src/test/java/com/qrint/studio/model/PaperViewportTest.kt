package com.qrint.studio.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PaperViewportTest {
    @Test fun wideRollShowsUnprintableMarginsAroundTheHead() {
        val viewport = PaperViewport.from(PaperSettings(contentWidthMm = 57f, headDots = 384, dpi = 203))

        assertEquals(456, viewport.paperWidthDots)
        assertEquals(384, viewport.sourceWidthDots)
        assertEquals(36, viewport.destinationStartX)
    }

    @Test fun narrowPaperCropsTheCorrectSideOfTheHead() {
        val left = PaperViewport.from(PaperSettings(contentWidthMm = 40f, horizontalAnchor = HorizontalAnchor.LEFT))
        val right = PaperViewport.from(PaperSettings(contentWidthMm = 40f, horizontalAnchor = HorizontalAnchor.RIGHT))

        assertEquals(320, left.paperWidthDots)
        assertEquals(0, left.sourceStartX)
        assertEquals(64, right.sourceStartX)
        assertEquals(0f, right.headToPaperX(64f))
        assertEquals(64f, right.paperToHeadX(0f))
    }

    @Test fun paperWiderThanTheHeadKeepsTheSelectedPhysicalEdge() {
        val left = PaperViewport.from(
            PaperSettings(contentWidthMm = 50f, horizontalAnchor = HorizontalAnchor.LEFT),
        )
        val right = PaperViewport.from(
            PaperSettings(contentWidthMm = 50f, horizontalAnchor = HorizontalAnchor.RIGHT),
        )

        assertEquals(0, left.destinationStartX)
        assertEquals(16, right.destinationStartX)
        assertEquals(16f, right.headToPaperX(0f))
    }

    @Test fun editorUsesAllPrintableDotsWithoutWideRollMargins() {
        val editor = PaperViewport.forEditor(
            PaperSettings(contentWidthMm = 57f, headDots = 384, dpi = 203),
        )

        assertEquals(384, editor.paperWidthDots)
        assertEquals(384, editor.sourceWidthDots)
        assertEquals(0, editor.destinationStartX)
        assertEquals(0f, editor.headToPaperX(0f))
        assertEquals(384f, editor.headToPaperX(384f))
    }

    @Test fun rightLoadedNarrowPaperMapsBothPrintableEdgesToEditorEdges() {
        val paper = PaperSettings(contentWidthMm = 40f, horizontalAnchor = HorizontalAnchor.RIGHT)
        val editor = PaperViewport.forEditor(paper)

        assertEquals(320, editor.paperWidthDots)
        assertEquals(0f, editor.headToPaperX(paper.printableStartX().toFloat()))
        assertEquals(320f, editor.headToPaperX(paper.printableEndX().toFloat()))
        assertEquals(paper.printableStartX().toFloat(), editor.paperToHeadX(0f))
        assertEquals(paper.printableEndX().toFloat(), editor.paperToHeadX(320f))
    }
}
