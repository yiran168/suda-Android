package com.qrint.studio.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class PrintDotPreviewScaleTest {
    @Test
    fun fillsAvailableWidthWithoutChangingPrinterDotGeometry() {
        assertEquals(920f / 456f, printDotPreviewScale(920, 456), 0.0001f)
        assertEquals(1_400f / 456f, printDotPreviewScale(1_400, 456), 0.0001f)
    }

    @Test
    fun alsoFitsWhenScreenIsNarrowerThanRaster() {
        assertEquals(0.5f, printDotPreviewScale(228, 456), 0.0001f)
    }

    @Test
    fun invalidDimensionsRemainFinite() {
        assertEquals(1f, printDotPreviewScale(0, 0), 0f)
    }
}
