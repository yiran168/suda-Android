package com.qrint.studio.ui.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCameraPipelineTest {
    @Test
    fun rotatesLuminanceClockwiseForPortraitAnalysis() {
        val source = byteArrayOf(1, 2, 3, 4, 5, 6) // 3 × 2
        val rotated = rotateLuminance(source, width = 3, height = 2, rotationDegrees = 90)
        assertEquals(2, rotated.width)
        assertEquals(3, rotated.height)
        assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), rotated.bytes)
    }

    @Test
    fun rotatesLuminanceForEverySupportedCameraOrientation() {
        val source = byteArrayOf(1, 2, 3, 4, 5, 6) // 3 × 2
        val unchanged = rotateLuminance(source, width = 3, height = 2, rotationDegrees = 0)
        assertEquals(3, unchanged.width)
        assertEquals(2, unchanged.height)
        assertArrayEquals(source, unchanged.bytes)

        val upsideDown = rotateLuminance(source, width = 3, height = 2, rotationDegrees = 180)
        assertEquals(3, upsideDown.width)
        assertEquals(2, upsideDown.height)
        assertArrayEquals(byteArrayOf(6, 5, 4, 3, 2, 1), upsideDown.bytes)

        val counterClockwise = rotateLuminance(source, width = 3, height = 2, rotationDegrees = 270)
        assertEquals(2, counterClockwise.width)
        assertEquals(3, counterClockwise.height)
        assertArrayEquals(byteArrayOf(3, 6, 2, 5, 1, 4), counterClockwise.bytes)
    }

    @Test
    fun codeMustRepeatBeforeItIsEmitted() {
        val gate = StableValueGate<String>(2)
        assertNull(gate.offer("A"))
        assertEquals("A", gate.offer("A"))
        assertNull(gate.offer("A"))
        assertNull(gate.offer("B"))
        assertEquals("B", gate.offer("B"))
        gate.reset()
        assertNull(gate.offer("B"))
        assertEquals("B", gate.offer("B"))
    }

    @Test
    fun selectedRegionCropsThePixelsActuallySentToRecognition() {
        val frame = LuminanceFrame(
            bytes = byteArrayOf(
                1, 2, 3, 4,
                5, 6, 7, 8,
                9, 10, 11, 12,
                13, 14, 15, 16,
            ),
            width = 4,
            height = 4,
        )
        val cropped = frame.cropTo(CameraScanRegion(0.25f, 0.25f, 0.75f, 0.75f))
        assertEquals(2, cropped.width)
        assertEquals(2, cropped.height)
        assertArrayEquals(byteArrayOf(6, 7, 10, 11), cropped.bytes)
    }

    @Test
    fun capturedPhotoUsesTheSameNormalizedBlueFrameCrop() {
        val crop = CameraScanRegion(0.10f, 0.20f, 0.90f, 0.80f).toPixelCrop(
            width = 2_000,
            height = 3_000,
        )
        assertEquals(200, crop.left)
        assertEquals(600, crop.top)
        assertEquals(1_800, crop.right)
        assertEquals(2_400, crop.bottom)
        assertEquals(1_600, crop.width)
        assertEquals(1_800, crop.height)
    }

    @Test
    fun capturedPhotoCropAlwaysContainsAtLeastOnePixelAtEdges() {
        val crop = CameraScanRegion(0f, 0f, 1f, 1f).toPixelCrop(width = 1, height = 1)
        assertEquals(0, crop.left)
        assertEquals(0, crop.top)
        assertEquals(1, crop.right)
        assertEquals(1, crop.bottom)
    }

    @Test
    fun cameraPreviewReservesGuidanceBelowWithoutChangingItsAspectRatio() {
        val tall = fitCameraPreview(containerWidth = 410f, containerHeight = 700f, reservedBottom = 96f)
        assertEquals(410f, tall.width, 0.001f)
        assertEquals(410f * 4f / 3f, tall.height, 0.001f)
        assertTrue(700f - tall.height >= 96f)

        val short = fitCameraPreview(containerWidth = 410f, containerHeight = 500f, reservedBottom = 96f)
        assertEquals(404f, short.height, 0.001f)
        assertEquals(303f, short.width, 0.001f)
        assertTrue(500f - short.height >= 96f)
    }

    @Test
    fun everyEdgeAndCornerCanResizeWithoutLeavingTheFrame() {
        val original = CameraScanRegion(0.2f, 0.2f, 0.8f, 0.8f)
        CameraScanHandle.entries.filterNot { it == CameraScanHandle.MOVE }.forEach { handle ->
            val resized = transformCameraScanRegion(original, handle, deltaX = 0.14f, deltaY = -0.14f)
            assertTrue(resized.left >= 0f && resized.top >= 0f)
            assertTrue(resized.right <= 1f && resized.bottom <= 1f)
            assertTrue(resized.width >= 0.16f && resized.height >= 0.12f)
        }
    }

    @Test
    fun movingRegionClampsAtCameraEdges() {
        val original = CameraScanRegion(0.2f, 0.2f, 0.8f, 0.8f)
        val moved = transformCameraScanRegion(original, CameraScanHandle.MOVE, 2f, -2f)
        assertEquals(0.4f, moved.left, 0.0001f)
        assertEquals(1f, moved.right, 0.0001f)
        assertEquals(0f, moved.top, 0.0001f)
        assertEquals(0.6f, moved.bottom, 0.0001f)
    }

    @Test
    fun flatFrameIsRejectedByQualityGate() {
        val frame = LuminanceFrame(ByteArray(400) { 128.toByte() }, 20, 20)
        assertTrue(frame.measureQuality().guidance?.contains("对比度") == true)
    }

    @Test
    fun liveQualitySamplingHonorsItsPixelBudget() {
        val stride = qualitySampleStride(width = 1_280, height = 960)
        assertEquals(4, stride)
        val sampled = ((1_280 + stride - 1) / stride) * ((960 + stride - 1) / stride)
        assertTrue(sampled <= 80_000)
        assertEquals(1, qualitySampleStride(width = 20, height = 20))
    }
}
