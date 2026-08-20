package com.qrint.studio.ui.editor

import com.qrint.studio.render.OcrAcceptancePolicy
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Camera selection bounds expressed in oriented-frame coordinates.
 *
 * Keeping the editor overlay and camera analyzer on the same normalized model prevents the
 * visible blue frame from drifting away from the pixels that OCR/barcode recognition receives.
 */
internal data class CameraScanRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(left < right && top < bottom)
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

internal data class CameraPixelCrop(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

internal data class CameraPreviewFrame(val width: Float, val height: Float)

/** Fits the complete 3:4 portrait preview above a reserved, non-overlapping guidance area. */
internal fun fitCameraPreview(
    containerWidth: Float,
    containerHeight: Float,
    reservedBottom: Float,
): CameraPreviewFrame {
    require(containerWidth > 0f && containerHeight > 0f && reservedBottom >= 0f)
    val availableHeight = (containerHeight - reservedBottom).coerceAtLeast(1f)
    val height = (containerWidth * 4f / 3f).coerceAtMost(availableHeight)
    return CameraPreviewFrame(width = height * 3f / 4f, height = height)
}

/** One normalized-to-pixel conversion shared by captured photos and live barcode frames. */
internal fun CameraScanRegion.toPixelCrop(width: Int, height: Int): CameraPixelCrop {
    require(width > 0 && height > 0)
    val cropLeft = floor(left * width).toInt().coerceIn(0, width - 1)
    val cropTop = floor(top * height).toInt().coerceIn(0, height - 1)
    val cropRight = ceil(right * width).toInt().coerceIn(cropLeft + 1, width)
    val cropBottom = ceil(bottom * height).toInt().coerceIn(cropTop + 1, height)
    return CameraPixelCrop(cropLeft, cropTop, cropRight, cropBottom)
}

internal enum class CameraScanHandle {
    MOVE,
    LEFT,
    TOP,
    RIGHT,
    BOTTOM,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

internal fun defaultCameraScanRegion(mode: LiveCameraMode): CameraScanRegion = when (mode) {
    LiveCameraMode.CODE -> CameraScanRegion(0.08f, 0.22f, 0.92f, 0.74f)
    LiveCameraMode.OCR,
    LiveCameraMode.TEMPLATE,
    -> CameraScanRegion(0.07f, 0.10f, 0.93f, 0.90f)
}

/** Returns an edge/corner first, then MOVE for a touch inside the frame. */
internal fun hitCameraScanRegion(
    region: CameraScanRegion,
    x: Float,
    y: Float,
    toleranceX: Float,
    toleranceY: Float,
): CameraScanHandle? {
    val nearLeft = kotlin.math.abs(x - region.left) <= toleranceX
    val nearRight = kotlin.math.abs(x - region.right) <= toleranceX
    val nearTop = kotlin.math.abs(y - region.top) <= toleranceY
    val nearBottom = kotlin.math.abs(y - region.bottom) <= toleranceY
    val withinHorizontal = x in (region.left - toleranceX)..(region.right + toleranceX)
    val withinVertical = y in (region.top - toleranceY)..(region.bottom + toleranceY)

    return when {
        nearLeft && nearTop -> CameraScanHandle.TOP_LEFT
        nearRight && nearTop -> CameraScanHandle.TOP_RIGHT
        nearLeft && nearBottom -> CameraScanHandle.BOTTOM_LEFT
        nearRight && nearBottom -> CameraScanHandle.BOTTOM_RIGHT
        nearLeft && withinVertical -> CameraScanHandle.LEFT
        nearRight && withinVertical -> CameraScanHandle.RIGHT
        nearTop && withinHorizontal -> CameraScanHandle.TOP
        nearBottom && withinHorizontal -> CameraScanHandle.BOTTOM
        x in region.left..region.right && y in region.top..region.bottom -> CameraScanHandle.MOVE
        else -> null
    }
}

internal fun transformCameraScanRegion(
    region: CameraScanRegion,
    handle: CameraScanHandle,
    deltaX: Float,
    deltaY: Float,
    minimumWidth: Float = 0.16f,
    minimumHeight: Float = 0.12f,
): CameraScanRegion {
    require(minimumWidth in 0f..1f && minimumHeight in 0f..1f)
    if (handle == CameraScanHandle.MOVE) {
        val safeX = deltaX.coerceIn(-region.left, 1f - region.right)
        val safeY = deltaY.coerceIn(-region.top, 1f - region.bottom)
        return region.copy(
            left = region.left + safeX,
            right = region.right + safeX,
            top = region.top + safeY,
            bottom = region.bottom + safeY,
        )
    }

    var left = region.left
    var top = region.top
    var right = region.right
    var bottom = region.bottom
    when (handle) {
        CameraScanHandle.LEFT,
        CameraScanHandle.TOP_LEFT,
        CameraScanHandle.BOTTOM_LEFT,
        -> left = (left + deltaX).coerceIn(0f, right - minimumWidth)

        CameraScanHandle.RIGHT,
        CameraScanHandle.TOP_RIGHT,
        CameraScanHandle.BOTTOM_RIGHT,
        -> right = (right + deltaX).coerceIn(left + minimumWidth, 1f)

        else -> Unit
    }
    when (handle) {
        CameraScanHandle.TOP,
        CameraScanHandle.TOP_LEFT,
        CameraScanHandle.TOP_RIGHT,
        -> top = (top + deltaY).coerceIn(0f, bottom - minimumHeight)

        CameraScanHandle.BOTTOM,
        CameraScanHandle.BOTTOM_LEFT,
        CameraScanHandle.BOTTOM_RIGHT,
        -> bottom = (bottom + deltaY).coerceIn(top + minimumHeight, 1f)

        else -> Unit
    }
    return CameraScanRegion(left, top, right, bottom)
}

/** Crops one oriented luminance frame without allocating a full ARGB camera bitmap first. */
internal fun LuminanceFrame.cropTo(region: CameraScanRegion): LuminanceFrame {
    val crop = region.toPixelCrop(width, height)
    val cropped = ByteArray(crop.width * crop.height)
    for (y in 0 until crop.height) {
        bytes.copyInto(
            destination = cropped,
            destinationOffset = y * crop.width,
            startIndex = (crop.top + y) * width + crop.left,
            endIndex = (crop.top + y) * width + crop.right,
        )
    }
    return LuminanceFrame(cropped, crop.width, crop.height)
}

internal data class CameraFrameQuality(
    val meanLuminance: Float,
    val contrast: Float,
    val edgeStrength: Float,
) {
    val guidance: String?
        get() = when {
            meanLuminance < OcrAcceptancePolicy.MIN_FRAME_LUMINANCE -> "画面过暗，请打开补光灯或增加照明"
            meanLuminance > OcrAcceptancePolicy.MAX_FRAME_LUMINANCE -> "画面过亮，请避开反光并调整角度"
            contrast < OcrAcceptancePolicy.MIN_FRAME_CONTRAST -> "文字与背景对比度不足，请靠近或换背景"
            edgeStrength < OcrAcceptancePolicy.MIN_FRAME_EDGE_STRENGTH -> "画面较模糊，请稳住手机并点击文字对焦"
            else -> null
        }
}

/** Lightweight quality gate; samples at most about 80k pixels to keep live analysis responsive. */
internal fun LuminanceFrame.measureQuality(): CameraFrameQuality {
    val stride = qualitySampleStride(width, height)
    var count = 0
    var sum = 0.0
    var sumSquares = 0.0
    var edgeSum = 0.0
    var edgeCount = 0
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val value = bytes[y * width + x].toInt() and 0xFF
            count++
            sum += value
            sumSquares += value * value.toDouble()
            if (x + stride < width) {
                val neighbor = bytes[y * width + x + stride].toInt() and 0xFF
                edgeSum += kotlin.math.abs(value - neighbor)
                edgeCount++
            }
            if (y + stride < height) {
                val neighbor = bytes[(y + stride) * width + x].toInt() and 0xFF
                edgeSum += kotlin.math.abs(value - neighbor)
                edgeCount++
            }
            x += stride
        }
        y += stride
    }
    val mean = if (count == 0) 0.0 else sum / count
    val variance = if (count == 0) 0.0 else max(0.0, sumSquares / count - mean * mean)
    return CameraFrameQuality(
        meanLuminance = mean.toFloat(),
        contrast = kotlin.math.sqrt(variance).toFloat(),
        edgeStrength = if (edgeCount == 0) 0f else (edgeSum / edgeCount).toFloat(),
    )
}

/** Chooses the smallest square sampling stride that stays within the requested pixel budget. */
internal fun qualitySampleStride(width: Int, height: Int, maxSamples: Int = 80_000): Int {
    require(width > 0 && height > 0 && maxSamples > 0)
    val ratio = width.toDouble() * height.toDouble() / maxSamples.toDouble()
    return ceil(kotlin.math.sqrt(ratio)).toInt().coerceAtLeast(1)
}
