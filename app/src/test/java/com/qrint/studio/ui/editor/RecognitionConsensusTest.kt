package com.qrint.studio.ui.editor

import com.qrint.studio.render.canonicalRecognitionText
import com.qrint.studio.render.textAccuracy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionConsensusTest {
    @Test fun canonicalTextNormalizesWidthAndWhitespaceWithoutHidingCharacterErrors() {
        assertEquals("订单 A-01\n数量 2", canonicalRecognitionText("  订单　Ａ-０１\r\n数量   ２ "))
    }

    @Test fun labelledAccuracyUsesCharacterErrorRate() {
        assertEquals(1f, textAccuracy("奶茶 18元", "奶茶 18元"))
        assertTrue(textAccuracy("1234567890", "123456789O") in 0.89f..0.91f)
    }
}
