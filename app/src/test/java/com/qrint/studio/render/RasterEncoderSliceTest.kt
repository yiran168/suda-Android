package com.qrint.studio.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RasterEncoderSliceTest {
    @Test fun sliceRowsKeepsStrideAndCopiesOnlyWholeRemainingRows() {
        val source = RasterData(
            widthDots = 16,
            widthBytes = 2,
            heightDots = 4,
            bytes = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7),
        )

        val sliced = RasterEncoder.sliceRows(source, 2)

        assertEquals(16, sliced.widthDots)
        assertEquals(2, sliced.widthBytes)
        assertEquals(2, sliced.heightDots)
        assertArrayEquals(byteArrayOf(4, 5, 6, 7), sliced.bytes)
    }
}
