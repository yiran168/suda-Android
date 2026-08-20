package com.qrint.studio.render

import com.qrint.studio.model.MAX_DOCUMENT_HEIGHT_DOTS
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinuousTextHeightTest {
    @Test fun growsToMeasuredPrinterLayoutHeight() {
        assertEquals(640, fittedContinuousTextHeight(currentHeight = 64, measuredHeight = 640, y = 20))
    }

    @Test fun neverShrinksAHeightTheUserMadeLarger() {
        assertEquals(700, fittedContinuousTextHeight(currentHeight = 700, measuredHeight = 320, y = 20))
    }

    @Test fun protectsLongReceiptsFromBitmapOutOfMemory() {
        assertEquals(
            100,
            fittedContinuousTextHeight(currentHeight = 64, measuredHeight = 5_000, y = MAX_DOCUMENT_HEIGHT_DOTS - 100),
        )
    }
}
