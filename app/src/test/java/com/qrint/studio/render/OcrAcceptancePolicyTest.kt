package com.qrint.studio.render

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrAcceptancePolicyTest {
    @Test fun stillPhotoAndGalleryOcrShareQualityGates() {
        assertEquals(0.60f, OcrAcceptancePolicy.MIN_MODEL_CONFIDENCE, 0f)
        assertEquals(16f, OcrAcceptancePolicy.MIN_FRAME_CONTRAST, 0f)
        assertEquals(5f, OcrAcceptancePolicy.MIN_FRAME_EDGE_STRENGTH, 0f)
    }
}
