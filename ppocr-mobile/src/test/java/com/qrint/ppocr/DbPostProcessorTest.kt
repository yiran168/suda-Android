package com.qrint.ppocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DbPostProcessorTest {
    @Test
    fun extractsAndScalesHighConfidenceTextRegion() {
        val width = 64
        val height = 48
        val map = FloatArray(width * height) { 0.02f }
        for (y in 16..27) for (x in 9..51) map[y * width + x] = 0.94f

        val boxes = DbPostProcessor.processMap(
            probabilities = map,
            mapWidth = width,
            mapHeight = height,
            originalWidth = 128,
            originalHeight = 96,
            options = PpOcrOptions(),
        )

        assertEquals(1, boxes.size)
        val box = boxes.single()
        assertTrue(box.score > 0.90f)
        assertTrue(box.quad.left < 18f)
        assertTrue(box.quad.right > 102f)
        assertTrue(box.quad.top < 32f)
        assertTrue(box.quad.bottom > 54f)
    }

    @Test
    fun rejectsRegionBelowBoxConfidenceThreshold() {
        val map = FloatArray(32 * 32) { 0.01f }
        for (y in 8..18) for (x in 6..24) map[y * 32 + x] = 0.40f
        val boxes = DbPostProcessor.processMap(
            probabilities = map,
            mapWidth = 32,
            mapHeight = 32,
            originalWidth = 32,
            originalHeight = 32,
            options = PpOcrOptions(detectionPixelThreshold = 0.30f, detectionBoxThreshold = 0.60f),
        )
        assertTrue(boxes.isEmpty())
    }
}
