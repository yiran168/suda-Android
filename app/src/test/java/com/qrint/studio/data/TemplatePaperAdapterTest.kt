package com.qrint.studio.data

import com.qrint.studio.model.ElementKind
import com.qrint.studio.model.HorizontalAnchor
import com.qrint.studio.model.LabelDocument
import com.qrint.studio.model.LabelElement
import com.qrint.studio.model.PaperMode
import com.qrint.studio.model.PaperSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplatePaperAdapterTest {
    private val sourcePaper = PaperSettings(
        mode = PaperMode.LABEL,
        mediaWidthMm = 40f,
        contentWidthMm = 40f,
        labelHeightMm = 30f,
        horizontalAnchor = HorizontalAnchor.LEFT,
    )
    private val source = LabelDocument(
        id = "fit-test",
        paper = sourcePaper,
        elements = listOf(
            LabelElement(kind = ElementKind.IMAGE, x = 0, y = 0, width = sourcePaper.contentWidthDots(), height = sourcePaper.fixedHeightDots()),
            LabelElement(kind = ElementKind.TEXT, x = 24, y = 32, width = 180, height = 48, text = "不会被截断"),
        ),
        builtIn = true,
    ).normalized()

    @Test fun fixedLabelFitsInsideEveryEdgeWithoutClipping() {
        val target = sourcePaper.copy(contentWidthMm = 30f, mediaWidthMm = 30f, labelHeightMm = 20f)
        val fitted = source.fittedToPaper(target)
        assertEquals(PaperMode.LABEL, fitted.paper.mode)
        assertTrue(fitted.elements.all { it.x >= fitted.paper.printableStartX() })
        assertTrue(fitted.elements.all { it.right() <= fitted.paper.printableEndX() })
        assertTrue(fitted.elements.all { it.y >= 0 && it.bottom() <= fitted.paper.fixedHeightDots() })
    }

    @Test fun continuousPaperUsesOnlySelectedWidthAndRetainsContentAspect() {
        val target = sourcePaper.copy(
            mode = PaperMode.CONTINUOUS,
            contentWidthMm = 10f,
            mediaWidthMm = 10f,
            horizontalAnchor = HorizontalAnchor.RIGHT,
        )
        val fitted = source.fittedToPaper(target)
        assertEquals(PaperMode.CONTINUOUS, fitted.paper.mode)
        assertEquals(10f, fitted.paper.contentWidthMm)
        assertEquals(HorizontalAnchor.RIGHT, fitted.paper.horizontalAnchor)
        assertTrue(fitted.outputHeightDots() >= fitted.elements.maxOf { it.bottom() })
        assertTrue(fitted.elements.all { it.x >= fitted.paper.printableStartX() && it.right() <= fitted.paper.printableEndX() })
    }
}
