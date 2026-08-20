package com.qrint.ppocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImageTensorFactoryTest {
    @Test
    fun detectionSizeUsesConfiguredLongEdgeAndMultiplesOf32() {
        assertEquals(960 to 544, ImageTensorFactory.detectionDimensions(1920, 1080, 960))
        assertEquals(544 to 960, ImageTensorFactory.detectionDimensions(1080, 1920, 960))
        assertEquals(960 to 960, ImageTensorFactory.detectionDimensions(600, 600, 960))
    }

    @Test
    fun preprocessingUsesTheModelDeclaredBgrChannelOrder() {
        assertEquals(0, ImageTensorFactory.bgrChannelShift(0))
        assertEquals(8, ImageTensorFactory.bgrChannelShift(1))
        assertEquals(16, ImageTensorFactory.bgrChannelShift(2))
        assertThrows(IllegalArgumentException::class.java) {
            ImageTensorFactory.bgrChannelShift(3)
        }
    }
}
