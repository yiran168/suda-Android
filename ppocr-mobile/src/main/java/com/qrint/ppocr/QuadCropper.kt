package com.qrint.ppocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

internal object QuadCropper {
    fun crop(source: Bitmap, input: OcrQuad): Bitmap {
        val quad = input.clamped(source.width, source.height)
        val points = quad.points
        val width = max(
            distance(points[0], points[1]),
            distance(points[3], points[2]),
        ).roundToInt().coerceAtLeast(1)
        val height = max(
            distance(points[0], points[3]),
            distance(points[1], points[2]),
        ).roundToInt().coerceAtLeast(1)
        val sourcePoints = floatArrayOf(
            points[0].x, points[0].y,
            points[1].x, points[1].y,
            points[2].x, points[2].y,
            points[3].x, points[3].y,
        )
        val destinationPoints = floatArrayOf(
            0f, 0f,
            (width - 1).toFloat(), 0f,
            (width - 1).toFloat(), (height - 1).toFloat(),
            0f, (height - 1).toFloat(),
        )
        val transform = Matrix()
        if (!transform.setPolyToPoly(sourcePoints, 0, destinationPoints, 0, 4)) {
            throw PpOcrException.InvalidImage("无法校正文字区域的透视角度")
        }
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawColor(Color.WHITE)
            drawBitmap(
                source,
                transform,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
            )
        }
        if (height.toFloat() / width.coerceAtLeast(1) < 1.5f) return output
        val rotation = Matrix().apply { postRotate(-90f) }
        val rotated = Bitmap.createBitmap(output, 0, 0, output.width, output.height, rotation, true)
        if (rotated !== output) output.recycle()
        return rotated
    }

    private fun distance(first: OcrPoint, second: OcrPoint): Float = hypot(
        (first.x - second.x).toDouble(),
        (first.y - second.y).toDouble(),
    ).toFloat()
}
