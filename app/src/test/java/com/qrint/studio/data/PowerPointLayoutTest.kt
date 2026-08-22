package com.qrint.studio.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PowerPointLayoutTest {
    @Test
    fun fontUsesTheSameSlideScaleAsItsTextBox() {
        // A 32 pt title on a 13.333 inch-wide slide scaled to 448 printer dots is about 13.3 dots.
        val slideWidthEmu = 12_192_000.0
        val scale = 448.0 / slideWidthEmu

        val dots = pptPointSizeToOutputDots(32f, scale)

        assertEquals(14.93f, dots, 0.02f)
    }

    @Test
    fun verySmallScaledTextRemainsReadable() {
        val dots = pptPointSizeToOutputDots(10f, 448.0 / 12_192_000.0)

        assertEquals(8f, dots, 0f)
    }
}
