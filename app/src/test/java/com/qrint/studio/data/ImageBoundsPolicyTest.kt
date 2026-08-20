package com.qrint.studio.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageBoundsPolicyTest {
    @Test
    fun zeroOrNegativeDecoderBoundsAreRejected() {
        assertFalse(ImageBoundsPolicy.hasPixels(0, 100))
        assertFalse(ImageBoundsPolicy.hasPixels(100, -1))
        assertFalse(ImageBoundsPolicy.isSafe(0, 100))
    }

    @Test
    fun normalCameraBoundsAreAccepted() {
        assertTrue(ImageBoundsPolicy.hasPixels(4032, 3024))
        assertTrue(ImageBoundsPolicy.isSafe(4032, 3024))
    }

    @Test
    fun oversizedBoundsAreRejectedBeforeFullDecode() {
        assertTrue(ImageBoundsPolicy.hasPixels(ImageBoundsPolicy.MAX_DIMENSION + 1, 100))
        assertFalse(ImageBoundsPolicy.isSafe(ImageBoundsPolicy.MAX_DIMENSION + 1, 100))
    }
}
