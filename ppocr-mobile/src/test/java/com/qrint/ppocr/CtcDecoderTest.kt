package com.qrint.ppocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CtcDecoderTest {
    @Test
    fun collapsesRepeatedClassesAndRemovesBlank() {
        val characters = listOf("你", "好", " ")
        val classes = characters.size + 1
        val output = FloatArray(6 * classes)
        setWinner(output, 0, classes, 1, 0.91f)
        setWinner(output, 1, classes, 1, 0.88f)
        setWinner(output, 2, classes, 0, 0.96f)
        setWinner(output, 3, classes, 2, 0.84f)
        setWinner(output, 4, classes, 2, 0.82f)
        setWinner(output, 5, classes, 0, 0.98f)

        val decoded = CtcDecoder.decode(output, 6, classes, characters)

        assertEquals("你好", decoded.text)
        assertEquals((0.91f + 0.84f) / 2f, decoded.confidence, 0.0001f)
    }

    @Test
    fun returnsEmptyForAllBlankSequence() {
        val output = FloatArray(12)
        repeat(3) { setWinner(output, it, 4, 0, 1f) }
        val decoded = CtcDecoder.decode(output, 3, 4, listOf("A", "B", " "))
        assertTrue(decoded.text.isEmpty())
        assertEquals(0f, decoded.confidence, 0f)
    }

    private fun setWinner(values: FloatArray, time: Int, classes: Int, winner: Int, score: Float) {
        val offset = time * classes
        repeat(classes) { values[offset + it] = 0.01f }
        values[offset + winner] = score
    }
}
