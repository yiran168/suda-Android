package com.qrint.studio.render

import android.graphics.Bitmap
import android.graphics.Color
import com.qrint.studio.model.DitherMode
import kotlin.math.max
import kotlin.math.min

object Dither {
    private data class Tap(val dx: Int, val dy: Int, val weight: Float)

    private val floyd = arrayOf(
        Tap(1, 0, 7f / 16f), Tap(-1, 1, 3f / 16f), Tap(0, 1, 5f / 16f), Tap(1, 1, 1f / 16f),
    )
    private val atkinson = arrayOf(
        Tap(1, 0, 1f / 8f), Tap(2, 0, 1f / 8f), Tap(-1, 1, 1f / 8f),
        Tap(0, 1, 1f / 8f), Tap(1, 1, 1f / 8f), Tap(0, 2, 1f / 8f),
    )
    private val jjn = arrayOf(
        Tap(1, 0, 7f / 48f), Tap(2, 0, 5f / 48f),
        Tap(-2, 1, 3f / 48f), Tap(-1, 1, 5f / 48f), Tap(0, 1, 7f / 48f), Tap(1, 1, 5f / 48f), Tap(2, 1, 3f / 48f),
        Tap(-2, 2, 1f / 48f), Tap(-1, 2, 3f / 48f), Tap(0, 2, 5f / 48f), Tap(1, 2, 3f / 48f), Tap(2, 2, 1f / 48f),
    )
    private val stucki = arrayOf(
        Tap(1, 0, 8f / 42f), Tap(2, 0, 4f / 42f),
        Tap(-2, 1, 2f / 42f), Tap(-1, 1, 4f / 42f), Tap(0, 1, 8f / 42f), Tap(1, 1, 4f / 42f), Tap(2, 1, 2f / 42f),
        Tap(-2, 2, 1f / 42f), Tap(-1, 2, 2f / 42f), Tap(0, 2, 4f / 42f), Tap(1, 2, 2f / 42f), Tap(2, 2, 1f / 42f),
    )
    private val sierraLite = arrayOf(
        Tap(1, 0, 2f / 4f), Tap(-1, 1, 1f / 4f), Tap(0, 1, 1f / 4f),
    )

    private val bayer4 = arrayOf(
        intArrayOf(0, 8, 2, 10), intArrayOf(12, 4, 14, 6),
        intArrayOf(3, 11, 1, 9), intArrayOf(15, 7, 13, 5),
    )
    private val bayer8 = arrayOf(
        intArrayOf(0, 32, 8, 40, 2, 34, 10, 42), intArrayOf(48, 16, 56, 24, 50, 18, 58, 26),
        intArrayOf(12, 44, 4, 36, 14, 46, 6, 38), intArrayOf(60, 28, 52, 20, 62, 30, 54, 22),
        intArrayOf(3, 35, 11, 43, 1, 33, 9, 41), intArrayOf(51, 19, 59, 27, 49, 17, 57, 25),
        intArrayOf(15, 47, 7, 39, 13, 45, 5, 37), intArrayOf(63, 31, 55, 23, 61, 29, 53, 21),
    )

    fun grayscale(
        pixels: IntArray,
        brightness: Float = 0f,
        contrast: Float = 1f,
        invert: Boolean = false,
    ): IntArray = IntArray(pixels.size) { index ->
        val color = pixels[index]
        val alpha = Color.alpha(color) / 255f
        val source = if (alpha <= 0f) 255f else {
            val r = Color.red(color).toFloat()
            val g = Color.green(color).toFloat()
            val b = Color.blue(color).toFloat()
            (0.2126f * r + 0.7152f * g + 0.0722f * b) * alpha + 255f * (1f - alpha)
        }
        var value = (source - 127.5f) * contrast.coerceIn(0.2f, 3f) + 127.5f + brightness.coerceIn(-1f, 1f) * 127f
        if (invert) value = 255f - value
        value.toInt().coerceIn(0, 255)
    }

    fun apply(gray: IntArray, width: Int, height: Int, mode: DitherMode, threshold: Int = 160): ByteArray {
        require(width > 0 && height > 0 && gray.size >= width * height)
        return when (mode) {
            DitherMode.THRESHOLD -> threshold(gray, width, height, threshold)
            DitherMode.BAYER_4 -> ordered(gray, width, height, bayer4, threshold)
            DitherMode.BAYER_8 -> ordered(gray, width, height, bayer8, threshold)
            DitherMode.FLOYD_STEINBERG -> diffuse(gray, width, height, floyd)
            DitherMode.ATKINSON -> diffuse(gray, width, height, atkinson)
            DitherMode.JARVIS_JUDICE_NINKE -> diffuse(gray, width, height, jjn)
            DitherMode.STUCKI -> diffuse(gray, width, height, stucki)
            DitherMode.SIERRA_LITE -> diffuse(gray, width, height, sierraLite)
        }
    }

    private fun threshold(gray: IntArray, width: Int, height: Int, threshold: Int): ByteArray {
        val pivot = threshold.coerceIn(1, 254)
        return ByteArray(width * height) { if (gray[it] < pivot) 1 else 0 }
    }

    private fun ordered(gray: IntArray, width: Int, height: Int, matrix: Array<IntArray>, threshold: Int): ByteArray {
        val size = matrix.size
        val total = size * size
        val bias = threshold.coerceIn(1, 254) - 128
        return ByteArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val matrixPivot = ((matrix[y % size][x % size] + 0.5f) * 255f / total).toInt()
            if ((gray[index] - bias) < matrixPivot) 1 else 0
        }
    }

    private fun diffuse(gray: IntArray, width: Int, height: Int, taps: Array<Tap>): ByteArray {
        val values = FloatArray(width * height) { gray[it].toFloat() }
        val output = ByteArray(width * height)
        for (y in 0 until height) {
            val reverse = y and 1 == 1
            for (step in 0 until width) {
                val x = if (reverse) width - 1 - step else step
                val index = y * width + x
                val old = values[index]
                val quantized = if (old < 128f) 0f else 255f
                output[index] = if (quantized == 0f) 1 else 0
                val error = old - quantized
                taps.forEach { tap ->
                    val dx = if (reverse) -tap.dx else tap.dx
                    val nx = x + dx
                    val ny = y + tap.dy
                    if (nx in 0 until width && ny in 0 until height) {
                        val target = ny * width + nx
                        values[target] = max(-255f, min(510f, values[target] + error * tap.weight))
                    }
                }
            }
        }
        return output
    }

    fun toBitmap(binary: ByteArray, width: Int, height: Int): Bitmap {
        require(binary.size >= width * height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) row[x] = if (binary[offset + x].toInt() == 1) Color.BLACK else Color.WHITE
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        return bitmap
    }

    fun processBitmap(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        mode: DitherMode,
        threshold: Int,
        brightness: Float = 0f,
        contrast: Float = 1f,
        invert: Boolean = false,
    ): Bitmap {
        val scaled = if (bitmap.width == width && bitmap.height == height) bitmap else
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        val binary = apply(grayscale(pixels, brightness, contrast, invert), width, height, mode, threshold)
        val result = toBitmap(binary, width, height)
        if (scaled !== bitmap) scaled.recycle()
        return result
    }
}
