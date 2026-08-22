package com.qrint.studio.ui.editor

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoCropSheetTest {
    @Test fun landscapePhotoIsVerticallyCenteredInsidePortraitViewport() {
        val rect = fittedPhotoRect(IntSize(1000, 1600), 1600, 900)

        assertEquals(1000f, rect.width, 0.01f)
        assertEquals(562.5f, rect.height, 0.01f)
        assertEquals((1600f - 562.5f) / 2f, rect.top, 0.01f)
    }

    @Test fun portraitPhotoIsHorizontallyCentered() {
        val rect = fittedPhotoRect(IntSize(1000, 1000), 900, 1600)

        assertEquals(562.5f, rect.width, 0.01f)
        assertEquals(1000f, rect.height, 0.01f)
        assertEquals((1000f - 562.5f) / 2f, rect.left, 0.01f)
    }
}
