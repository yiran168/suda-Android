package com.qrint.studio.render

import android.graphics.Bitmap
import android.graphics.Color

data class RasterData(
    val widthDots: Int,
    val widthBytes: Int,
    val heightDots: Int,
    val bytes: ByteArray,
)

object RasterEncoder {
    const val MAX_RASTER_HEIGHT_DOTS = 0xFFFF

    fun encode(monochrome: Bitmap): RasterData {
        val width = monochrome.width
        val height = monochrome.height
        val widthBytes = (width + 7) / 8
        val output = ByteArray(widthBytes * height)
        val row = IntArray(width)
        for (y in 0 until height) {
            monochrome.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val color = row[x]
                val luminance = (Color.red(color) * 54 + Color.green(color) * 183 + Color.blue(color) * 19) shr 8
                if (Color.alpha(color) > 16 && luminance < 128) {
                    val index = y * widthBytes + x / 8
                    output[index] = (output[index].toInt() or (0x80 ushr (x and 7))).toByte()
                }
            }
        }
        return RasterData(width, widthBytes, height, output)
    }

    fun rasterHeader(data: RasterData, mode: Int = 0): ByteArray = byteArrayOf(
        0x1D, 0x76, 0x30, (mode and 0x03).toByte(),
        (data.widthBytes and 0xFF).toByte(), ((data.widthBytes ushr 8) and 0xFF).toByte(),
        (data.heightDots and 0xFF).toByte(), ((data.heightDots ushr 8) and 0xFF).toByte(),
    )

    /** Some thermal-printer firmware silently ignores an all-white raster. */
    fun hasInk(data: RasterData): Boolean = data.bytes.any { it.toInt() != 0 }

    /**
     * Extends an existing printable raster with blank motor rows.
     *
     * A few Qring firmware versions ignore a standalone ESC J command immediately before STOP.
     * Keeping the blank rows inside the same non-empty GS v 0 job makes the requested movement part
     * of the declared raster height, without adding marks or changing the editor/preview bitmap.
     */
    fun appendBlankRows(data: RasterData, rows: Int): RasterData {
        if (rows <= 0 || data.widthBytes <= 0) return data
        val appendable = (MAX_RASTER_HEIGHT_DOTS - data.heightDots).coerceAtLeast(0)
        val appended = rows.coerceAtMost(appendable)
        if (appended == 0) return data
        return data.copy(
            heightDots = data.heightDots + appended,
            bytes = data.bytes.copyOf(data.bytes.size + data.widthBytes * appended),
        )
    }

    /**
     * Returns the remaining complete raster rows starting at [startRow].
     *
     * Qring firmware does not expose an exact printed-row counter when thermal protection stops a
     * job. Recovery is therefore performed at row boundaries and deliberately rewinds a protected
     * overlap before calling this function. Keeping row slicing here avoids duplicating byte-offset
     * arithmetic in the transport layer and guarantees that width/stride stay unchanged.
     */
    fun sliceRows(data: RasterData, startRow: Int): RasterData {
        require(startRow in 0 until data.heightDots) {
            "startRow must be within 0 until ${data.heightDots}, was $startRow"
        }
        if (startRow == 0) return data
        val byteOffset = startRow * data.widthBytes
        return data.copy(
            heightDots = data.heightDots - startRow,
            bytes = data.bytes.copyOfRange(byteOffset, data.bytes.size),
        )
    }

    fun feedCommands(dots: Int): List<ByteArray> {
        val commands = mutableListOf<ByteArray>()
        var remaining = dots.coerceAtLeast(0)
        while (remaining > 0) {
            val amount = minOf(remaining, 255)
            commands += byteArrayOf(0x1B, 0x4A, amount.toByte())
            remaining -= amount
        }
        return commands
    }
}
