package com.qrint.studio.render

import com.qrint.studio.model.DitherMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DitherTest {
    private val gradient = IntArray(64) { it * 255 / 63 }

    @Test fun everyAlgorithmReturnsOneBitPerPixel() {
        DitherMode.entries.forEach { mode ->
            val result = Dither.apply(gradient, 8, 8, mode, 128)
            assertEquals(64, result.size)
            assertTrue(result.all { it.toInt() == 0 || it.toInt() == 1 })
        }
    }

    @Test fun algorithmsAreDeterministic() {
        val first = Dither.apply(gradient, 8, 8, DitherMode.FLOYD_STEINBERG, 128)
        val second = Dither.apply(gradient, 8, 8, DitherMode.FLOYD_STEINBERG, 128)
        assertArrayEquals(first, second)
    }
}
