package com.qrint.studio.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test
import com.qrint.studio.model.MAX_PAPER_WIDTH_MM
import com.qrint.studio.model.MIN_PAPER_WIDTH_MM
import com.qrint.studio.model.PaperSettings
import kotlin.math.roundToInt

class PaperPresetsTest {
    @Test fun labelPresetsMatchSupportedMedia() {
        assertEquals(
            listOf(30f to 20f, 40f to 30f, 50f to 30f, 50f to 50f, 57f to 30f),
            LABEL_SIZE_PRESETS.map { it.widthMm to it.heightMm },
        )
    }

    @Test fun continuousPresetsUseRequestedOrder() {
        assertEquals(listOf(57f, 50f, 40f, 30f), CONTINUOUS_WIDTH_PRESETS)
    }

    @Test fun everyTenthMillimetreFrom10To57MapsToAValidViewport() {
        for (tenths in 100..570) {
            val paper = PaperSettings(contentWidthMm = tenths / 10f)
            val expectedDots = (tenths / 10f * paper.dpi / 25.4f).roundToInt().coerceAtLeast(8)
            assertEquals("纸宽 ${tenths / 10f} mm", expectedDots, paper.paperWidthDots())
        }
        assertEquals(10f, MIN_PAPER_WIDTH_MM)
        assertEquals(57f, MAX_PAPER_WIDTH_MM)
    }
}
