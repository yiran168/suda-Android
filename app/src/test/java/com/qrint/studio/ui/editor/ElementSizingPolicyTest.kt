package com.qrint.studio.ui.editor

import com.qrint.studio.model.ElementKind
import com.qrint.studio.model.LabelElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementSizingPolicyTest {
    @Test
    fun imageScalePreservesAspectRatioAndCentre() {
        val source = LabelElement(kind = ElementKind.IMAGE, x = 100, y = 80, width = 200, height = 100)

        val scaled = ElementSizingPolicy.scaleToWidth(
            element = source,
            targetWidthDots = 300,
            contentStart = 0,
            contentEnd = 384,
            heightLimit = 500,
        )

        assertEquals(300, scaled.width)
        assertEquals(150, scaled.height)
        assertEquals(source.x + source.width / 2, scaled.x + scaled.width / 2)
        assertEquals(source.y + source.height / 2, scaled.y + scaled.height / 2)
    }

    @Test
    fun textScaleChangesBoxAndTypographyTogether() {
        val source = LabelElement(
            kind = ElementKind.TEXT,
            x = 20,
            y = 30,
            width = 100,
            height = 40,
            fontSizeDots = 20f,
            letterSpacingDots = 2f,
            lineSpacingDots = 4f,
        )

        val scaled = ElementSizingPolicy.scaleToWidth(source, 200, 0, 384, 500)

        assertEquals(200, scaled.width)
        assertEquals(80, scaled.height)
        assertEquals(40f, scaled.fontSizeDots, 0.01f)
        assertEquals(4f, scaled.letterSpacingDots, 0.01f)
        assertEquals(8f, scaled.lineSpacingDots, 0.01f)
    }

    @Test
    fun contentFitHonoursRightTextAnchorAndPaperBounds() {
        val source = LabelElement(kind = ElementKind.TEXT, x = 200, y = 10, width = 160, height = 80)

        val fitted = ElementSizingPolicy.fitToContent(
            element = source,
            contentWidthDots = 90,
            contentHeightDots = 36,
            contentStart = 0,
            contentEnd = 384,
            heightLimit = 300,
            horizontalBias = 1f,
        )

        assertEquals(source.x + source.width, fitted.x + fitted.width)
        assertEquals(90, fitted.width)
        assertEquals(36, fitted.height)
        assertTrue(fitted.x >= 0 && fitted.x + fitted.width <= 384)
    }
}
