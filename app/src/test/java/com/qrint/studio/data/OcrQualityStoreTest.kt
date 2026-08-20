package com.qrint.studio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrQualityStoreTest {
    @Test
    fun validatedSamplesAggregateCharacterErrors() {
        val first = updateOcrQualityStats(OcrQualityStats(), "商品 A001", "商品 A00I")
        val second = updateOcrQualityStats(first, "价格 18", "价格 18")
        assertEquals(2, second.validatedSamples)
        assertEquals(12, second.characters)
        assertEquals(1, second.errors)
        assertTrue(second.accuracy in 0.916f..0.917f)
    }
}
