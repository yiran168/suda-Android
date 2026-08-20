package com.qrint.studio.render

import com.qrint.studio.model.HorizontalAnchor
import com.qrint.studio.model.PaperMode
import com.qrint.studio.model.PaperSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineTextScanTest {
    private val scan = OfflineTextScan(
        sourceUri = "content://test/scan.jpg",
        imageWidth = 1_000,
        imageHeight = 500,
        lines = listOf(
            RecognizedTextLine("第一行", 100, 50, 600, 100),
            RecognizedTextLine("第二行", 120, 150, 900, 220),
        ),
    )

    @Test fun mapsTextInsideEverySupportedNarrowPaperWindow() {
        for (tenths in 100..480) {
            for (anchor in listOf(HorizontalAnchor.LEFT, HorizontalAnchor.RIGHT)) {
                val paper = PaperSettings(contentWidthMm = tenths / 10f, horizontalAnchor = anchor)
                val elements = scan.toEditableElements(paper, startYDots = 12, fitInsideFixedLabel = false)
                assertEquals(2, elements.size)
                elements.forEach {
                    assertTrue(it.x >= paper.printableStartX())
                    assertTrue(it.right() <= paper.printableEndX())
                    assertTrue(it.y >= 12)
                }
            }
        }
    }

    @Test fun fixedLabelMappingFitsBothAxes() {
        val paper = PaperSettings(
            mode = PaperMode.LABEL,
            contentWidthMm = 30f,
            labelHeightMm = 20f,
            horizontalAnchor = HorizontalAnchor.LEFT,
        )
        val elements = scan.toEditableElements(paper, fitInsideFixedLabel = true)
        elements.forEach {
            assertTrue(it.right() <= paper.printableEndX())
            assertTrue(it.bottom() <= paper.fixedHeightDots())
        }
    }
}
