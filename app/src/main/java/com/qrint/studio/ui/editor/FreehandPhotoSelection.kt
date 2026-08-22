package com.qrint.studio.ui.editor

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** One point in the EXIF-oriented photo, independent of preview size and screen density. */
internal data class NormalizedPhotoPoint(val x: Float, val y: Float) {
    init {
        require(x.isFinite() && y.isFinite())
        require(x in 0f..1f && y in 0f..1f)
    }
}

/** Closed freehand lasso. The first point is not duplicated at the end. */
internal data class FreehandPhotoSelection(val points: List<NormalizedPhotoPoint>) {
    val normalizedArea: Float by lazy {
        if (points.size < 3) return@lazy 0f
        var twiceArea = 0f
        points.indices.forEach { index ->
            val current = points[index]
            val next = points[(index + 1) % points.size]
            twiceArea += current.x * next.y - next.x * current.y
        }
        abs(twiceArea) / 2f
    }

    val isUsable: Boolean get() = points.size >= MINIMUM_POINT_COUNT && normalizedArea >= MINIMUM_AREA

    fun pixelBounds(width: Int, height: Int): CameraPixelCrop {
        require(isUsable) { "请先用手指沿要打印的内容画一圈" }
        require(width > 0 && height > 0)
        val left = floor(points.minOf { it.x } * width).toInt().coerceIn(0, width - 1)
        val top = floor(points.minOf { it.y } * height).toInt().coerceIn(0, height - 1)
        val right = ceil(points.maxOf { it.x } * width).toInt().coerceIn(left + 1, width)
        val bottom = ceil(points.maxOf { it.y } * height).toInt().coerceIn(top + 1, height)
        return CameraPixelCrop(left, top, right, bottom)
    }

    companion object {
        private const val MINIMUM_POINT_COUNT = 3
        private const val MINIMUM_AREA = 0.0005f
    }
}

/**
 * Adds a sampled lasso point only when it materially changes the path. This bounds memory while
 * keeping roughly pixel-level fidelity on a phone-sized preview.
 */
internal fun appendFreehandPhotoPoint(
    points: List<NormalizedPhotoPoint>,
    point: NormalizedPhotoPoint,
    minimumSquaredDistance: Float = 0.000004f,
    maximumPoints: Int = 2_048,
): List<NormalizedPhotoPoint> {
    require(minimumSquaredDistance >= 0f && maximumPoints >= 3)
    return if (shouldAppendFreehandPhotoPoint(points, point, minimumSquaredDistance, maximumPoints)) {
        points + point
    } else points
}

internal fun shouldAppendFreehandPhotoPoint(
    points: List<NormalizedPhotoPoint>,
    point: NormalizedPhotoPoint,
    minimumSquaredDistance: Float = 0.000004f,
    maximumPoints: Int = 2_048,
): Boolean {
    require(minimumSquaredDistance >= 0f && maximumPoints >= 3)
    if (points.size >= maximumPoints) return false
    val previous = points.lastOrNull() ?: return true
    val dx = point.x - previous.x
    val dy = point.y - previous.y
    return dx * dx + dy * dy >= minimumSquaredDistance
}

internal fun finalizeFreehandPhotoSelection(points: List<NormalizedPhotoPoint>): FreehandPhotoSelection {
    if (points.size < 2) return FreehandPhotoSelection(points)
    val first = points.first()
    val last = points.last()
    val dx = first.x - last.x
    val dy = first.y - last.y
    val withoutDuplicateClosingPoint = if (dx * dx + dy * dy < 0.000004f) points.dropLast(1) else points
    return FreehandPhotoSelection(withoutDuplicateClosingPoint.distinctAdjacent())
}

private fun List<NormalizedPhotoPoint>.distinctAdjacent(): List<NormalizedPhotoPoint> = buildList {
    this@distinctAdjacent.forEach { point ->
        if (lastOrNull() != point) add(point)
    }
}
