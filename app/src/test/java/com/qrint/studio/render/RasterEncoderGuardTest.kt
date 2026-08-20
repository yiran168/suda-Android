package com.qrint.studio.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RasterEncoderGuardTest {
    @Test
    fun allWhiteRasterIsRejectedBeforePrinterTransport() {
        assertFalse(RasterEncoder.hasInk(RasterData(384, 48, 2, ByteArray(96))))
    }

    @Test
    fun anyHeatedDotMakesRasterPrintable() {
        val bytes = ByteArray(96).also { it[47] = 0x01 }
        assertTrue(RasterEncoder.hasInk(RasterData(384, 48, 2, bytes)))
    }

    @Test
    fun blankMotorRowsExtendHeightWithoutChangingPrintedDots() {
        val sourceBytes = byteArrayOf(0x40, 0x00, 0x00, 0x01)
        val source = RasterData(widthDots = 16, widthBytes = 2, heightDots = 2, bytes = sourceBytes)

        val extended = RasterEncoder.appendBlankRows(source, 3)

        assertEquals(5, extended.heightDots)
        assertEquals(10, extended.bytes.size)
        assertArrayEquals(sourceBytes, extended.bytes.copyOfRange(0, sourceBytes.size))
        assertTrue(extended.bytes.drop(sourceBytes.size).all { it == 0.toByte() })
        assertTrue(RasterEncoder.hasInk(extended))
    }

    @Test
    fun zeroMotorRowsReuseOriginalRaster() {
        val source = RasterData(16, 2, 2, ByteArray(4))

        assertSame(source, RasterEncoder.appendBlankRows(source, 0))
    }

}
