package com.qrint.studio.ui.editor

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun freehandSelectionUsesPolygonBoundsInsteadOfAForcedRectangle() {
        val selection = finalizeFreehandPhotoSelection(
            listOf(
                NormalizedPhotoPoint(0.10f, 0.20f),
                NormalizedPhotoPoint(0.80f, 0.25f),
                NormalizedPhotoPoint(0.62f, 0.90f),
                NormalizedPhotoPoint(0.22f, 0.72f),
            ),
        )

        assertTrue(selection.isUsable)
        assertEquals(CameraPixelCrop(100, 100, 800, 450), selection.pixelBounds(1000, 500))
    }

    @Test fun tinyTapOrLineCannotBeAcceptedAsAClosedLasso() {
        val line = finalizeFreehandPhotoSelection(
            listOf(
                NormalizedPhotoPoint(0.1f, 0.1f),
                NormalizedPhotoPoint(0.5f, 0.5f),
                NormalizedPhotoPoint(0.9f, 0.9f),
            ),
        )

        assertFalse(line.isUsable)
    }

    @Test fun lassoSamplingDropsNearDuplicatesButKeepsMeaningfulMotion() {
        val start = listOf(NormalizedPhotoPoint(0.1f, 0.1f))
        val unchanged = appendFreehandPhotoPoint(start, NormalizedPhotoPoint(0.1005f, 0.1005f))
        val moved = appendFreehandPhotoPoint(start, NormalizedPhotoPoint(0.2f, 0.2f))

        assertEquals(1, unchanged.size)
        assertEquals(2, moved.size)
    }
}
