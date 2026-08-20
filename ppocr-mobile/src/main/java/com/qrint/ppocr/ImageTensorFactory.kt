package com.qrint.ppocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

internal data class DetectionTensor(
    val values: FloatArray,
    val shape: LongArray,
    val width: Int,
    val height: Int,
)

internal data class RecognitionTensor(
    val values: FloatArray,
    val shape: LongArray,
)

internal object ImageTensorFactory {
    private val detectionMean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val detectionStd = floatArrayOf(0.229f, 0.224f, 0.225f)

    fun detection(source: Bitmap, longSide: Int): DetectionTensor {
        val dimensions = detectionDimensions(source.width, source.height, longSide)
        val scaled = renderScaled(source, dimensions.first, dimensions.second)
        try {
            val pixelCount = scaled.width * scaled.height
            val pixels = IntArray(pixelCount)
            scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            val output = FloatArray(pixelCount * 3)
            repeat(3) { channel ->
                val outputOffset = channel * pixelCount
                val shift = bgrChannelShift(channel)
                for (index in pixels.indices) {
                    val value = pixels[index] ushr shift and 0xFF
                    output[outputOffset + index] = (value / 255f - detectionMean[channel]) / detectionStd[channel]
                }
            }
            return DetectionTensor(
                values = output,
                shape = longArrayOf(1, 3, scaled.height.toLong(), scaled.width.toLong()),
                width = scaled.width,
                height = scaled.height,
            )
        } finally {
            scaled.recycle()
        }
    }

    fun recognition(crop: Bitmap, maxWidth: Int): RecognitionTensor {
        val aspectRatio = crop.width.toFloat() / crop.height.coerceAtLeast(1)
        val proportionalWidth = ceil(PpOcrModelSpec.RECOGNITION_HEIGHT * aspectRatio).toInt()
        val width = roundUpToMultiple(proportionalWidth.coerceIn(8, maxWidth), 8)
            .coerceAtMost(maxWidth)
        val scaled = renderScaled(crop, width, PpOcrModelSpec.RECOGNITION_HEIGHT)
        try {
            val pixelCount = width * PpOcrModelSpec.RECOGNITION_HEIGHT
            val pixels = IntArray(pixelCount)
            scaled.getPixels(pixels, 0, width, 0, 0, width, PpOcrModelSpec.RECOGNITION_HEIGHT)
            val output = FloatArray(pixelCount * 3)
            // PP-OCRv6 Small declares img_mode=BGR and normalization to [-1, 1].
            repeat(3) { channel ->
                val outputOffset = channel * pixelCount
                val shift = bgrChannelShift(channel)
                for (index in pixels.indices) {
                    val value = pixels[index] ushr shift and 0xFF
                    output[outputOffset + index] = value / 127.5f - 1f
                }
            }
            return RecognitionTensor(
                values = output,
                shape = longArrayOf(1, 3, PpOcrModelSpec.RECOGNITION_HEIGHT.toLong(), width.toLong()),
            )
        } finally {
            scaled.recycle()
        }
    }

    internal fun detectionDimensions(width: Int, height: Int, longSide: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && longSide > 0)
        val scale = longSide.toFloat() / max(width, height)
        val scaledWidth = roundToMultiple(width * scale, 32).coerceAtLeast(32)
        val scaledHeight = roundToMultiple(height * scale, 32).coerceAtLeast(32)
        return scaledWidth to scaledHeight
    }

    private fun roundToMultiple(value: Float, multiple: Int): Int =
        (value / multiple).roundToInt() * multiple

    private fun roundUpToMultiple(value: Int, multiple: Int): Int =
        ((value + multiple - 1) / multiple) * multiple

    /** BGR channel order shared by detection and recognition preprocessing. */
    internal fun bgrChannelShift(channel: Int): Int = when (channel) {
        0 -> 0
        1 -> 8
        2 -> 16
        else -> throw IllegalArgumentException("BGR channel must be 0..2")
    }

    /** Rendering through Canvas always produces a readable software ARGB bitmap, even for HARDWARE sources. */
    private fun renderScaled(source: Bitmap, width: Int, height: Int): Bitmap {
        val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(source, null, Rect(0, 0, width, height), paint)
        return target
    }
}
