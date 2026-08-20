package com.qrint.ppocr

import java.nio.FloatBuffer
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal data class DetectedTextBox(val quad: OcrQuad, val score: Float)

/** Pure Kotlin DB post-processing; deliberately contains no OpenCV/RapidOCR dependency. */
internal object DbPostProcessor {
    private const val MAX_BOUNDARY_POINTS = 65_536

    fun process(
        output: FloatBuffer,
        shape: LongArray,
        originalWidth: Int,
        originalHeight: Int,
        options: PpOcrOptions,
    ): List<DetectedTextBox> {
        require(shape.size == 4 && shape[0] == 1L && shape[1] == 1L) {
            "Unexpected detection output: ${shape.contentToString()}"
        }
        val mapHeight = shape[2].toInt()
        val mapWidth = shape[3].toInt()
        require(mapWidth > 0 && mapHeight > 0)
        val pixelCount = mapWidth * mapHeight
        val probabilities = FloatArray(pixelCount)
        val values = output.duplicate()
        values.rewind()
        require(values.remaining() >= pixelCount)
        values.get(probabilities)
        return processMap(probabilities, mapWidth, mapHeight, originalWidth, originalHeight, options)
    }

    internal fun processMap(
        probabilities: FloatArray,
        mapWidth: Int,
        mapHeight: Int,
        originalWidth: Int,
        originalHeight: Int,
        options: PpOcrOptions,
    ): List<DetectedTextBox> {
        require(probabilities.size >= mapWidth * mapHeight)
        val mask = ByteArray(mapWidth * mapHeight)
        for (index in mask.indices) {
            if (probabilities[index] >= options.detectionPixelThreshold) mask[index] = 1
        }
        val queue = IntArray(mask.size)
        val boxes = ArrayList<DetectedTextBox>()
        var components = 0
        for (seed in mask.indices) {
            if (mask[seed].toInt() != 1 || components >= 1_000) continue
            components++
            var head = 0
            var tail = 0
            queue[tail++] = seed
            mask[seed] = 2
            val boundary = PackedPointBuffer()
            var componentPixels = 0
            while (head < tail) {
                val index = queue[head++]
                val x = index % mapWidth
                val y = index / mapWidth
                componentPixels++
                var isBoundary = false
                for (deltaY in -1..1) for (deltaX in -1..1) {
                    if (deltaX == 0 && deltaY == 0) continue
                    val neighborX = x + deltaX
                    val neighborY = y + deltaY
                    if (neighborX !in 0 until mapWidth || neighborY !in 0 until mapHeight) {
                        isBoundary = true
                        continue
                    }
                    val neighbor = neighborY * mapWidth + neighborX
                    when (mask[neighbor].toInt()) {
                        0 -> isBoundary = true
                        1 -> {
                            mask[neighbor] = 2
                            queue[tail++] = neighbor
                        }
                    }
                }
                if (isBoundary) boundary.add(x, y)
            }
            if (componentPixels < 8 || boundary.size < 3) continue
            val hull = convexHull(boundary.toPoints())
            if (hull.size < 3) continue
            val rectangle = minimumAreaRectangle(hull) ?: continue
            if (min(rectangle.width, rectangle.height) < 3f) continue
            val score = polygonScore(probabilities, mapWidth, mapHeight, rectangle.corners)
            if (score < options.detectionBoxThreshold) continue
            val distance = rectangle.width * rectangle.height * options.unclipRatio /
                (2f * (rectangle.width + rectangle.height)).coerceAtLeast(1f)
            val expanded = rectangle.copy(
                width = rectangle.width + distance * 2f,
                height = rectangle.height + distance * 2f,
            )
            val scaled = expanded.corners.map { point ->
                OcrPoint(
                    x = point.x * originalWidth / mapWidth,
                    y = point.y * originalHeight / mapHeight,
                )
            }
            val quad = orderQuad(scaled).clamped(originalWidth, originalHeight)
            val edgeWidth = hypot(
                (quad.points[1].x - quad.points[0].x).toDouble(),
                (quad.points[1].y - quad.points[0].y).toDouble(),
            )
            val edgeHeight = hypot(
                (quad.points[3].x - quad.points[0].x).toDouble(),
                (quad.points[3].y - quad.points[0].y).toDouble(),
            )
            if (edgeWidth <= 3.0 || edgeHeight <= 3.0) continue
            boxes += DetectedTextBox(quad, score)
        }
        return sortReadingOrder(boxes).take(options.maxTextLines)
    }

    private fun sortReadingOrder(input: List<DetectedTextBox>): List<DetectedTextBox> {
        if (input.size <= 1) return input
        val sorted = input.sortedWith(compareBy({ it.quad.top }, { it.quad.left })).toMutableList()
        for (index in 0 until sorted.lastIndex) {
            var cursor = index
            while (cursor >= 0) {
                val left = sorted[cursor]
                val right = sorted[cursor + 1]
                val rowTolerance = max(10f, min(left.quad.height, right.quad.height) * 0.45f)
                if (kotlin.math.abs(right.quad.top - left.quad.top) < rowTolerance &&
                    right.quad.left < left.quad.left
                ) {
                    sorted[cursor] = right
                    sorted[cursor + 1] = left
                    cursor--
                } else break
            }
        }
        return sorted
    }

    internal fun convexHull(points: List<OcrPoint>): List<OcrPoint> {
        if (points.size <= 2) return points
        val sorted = points.distinct().sortedWith(compareBy(OcrPoint::x, OcrPoint::y))
        if (sorted.size <= 2) return sorted
        val lower = ArrayList<OcrPoint>()
        for (point in sorted) {
            while (lower.size >= 2 && cross(lower[lower.lastIndex - 1], lower.last(), point) <= 0f) {
                lower.removeAt(lower.lastIndex)
            }
            lower += point
        }
        val upper = ArrayList<OcrPoint>()
        for (index in sorted.indices.reversed()) {
            val point = sorted[index]
            while (upper.size >= 2 && cross(upper[upper.lastIndex - 1], upper.last(), point) <= 0f) {
                upper.removeAt(upper.lastIndex)
            }
            upper += point
        }
        lower.removeAt(lower.lastIndex)
        upper.removeAt(upper.lastIndex)
        return lower + upper
    }

    private fun cross(origin: OcrPoint, first: OcrPoint, second: OcrPoint): Float =
        (first.x - origin.x) * (second.y - origin.y) -
            (first.y - origin.y) * (second.x - origin.x)

    private fun minimumAreaRectangle(hull: List<OcrPoint>): OrientedRectangle? {
        if (hull.size < 3) return null
        var best: OrientedRectangle? = null
        var bestArea = Float.POSITIVE_INFINITY
        for (index in hull.indices) {
            val first = hull[index]
            val second = hull[(index + 1) % hull.size]
            val angle = atan2((second.y - first.y).toDouble(), (second.x - first.x).toDouble()).toFloat()
            val cosine = cos(angle)
            val sine = sin(angle)
            var minX = Float.POSITIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            for (point in hull) {
                val rotatedX = point.x * cosine + point.y * sine
                val rotatedY = -point.x * sine + point.y * cosine
                minX = min(minX, rotatedX)
                maxX = max(maxX, rotatedX)
                minY = min(minY, rotatedY)
                maxY = max(maxY, rotatedY)
            }
            val width = maxX - minX
            val height = maxY - minY
            val area = width * height
            if (area < bestArea) {
                val rotatedCenterX = (minX + maxX) / 2f
                val rotatedCenterY = (minY + maxY) / 2f
                bestArea = area
                best = OrientedRectangle(
                    centerX = rotatedCenterX * cosine - rotatedCenterY * sine,
                    centerY = rotatedCenterX * sine + rotatedCenterY * cosine,
                    width = width,
                    height = height,
                    angle = angle,
                )
            }
        }
        return best
    }

    private fun polygonScore(
        values: FloatArray,
        width: Int,
        height: Int,
        polygon: List<OcrPoint>,
    ): Float {
        val left = floor(polygon.minOf(OcrPoint::x)).toInt().coerceIn(0, width - 1)
        val right = ceil(polygon.maxOf(OcrPoint::x)).toInt().coerceIn(0, width - 1)
        val top = floor(polygon.minOf(OcrPoint::y)).toInt().coerceIn(0, height - 1)
        val bottom = ceil(polygon.maxOf(OcrPoint::y)).toInt().coerceIn(0, height - 1)
        var sum = 0.0
        var count = 0
        for (y in top..bottom) for (x in left..right) {
            if (insidePolygon(x + 0.5f, y + 0.5f, polygon)) {
                sum += values[y * width + x]
                count++
            }
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }

    private fun insidePolygon(x: Float, y: Float, polygon: List<OcrPoint>): Boolean {
        var inside = false
        var previous = polygon.last()
        for (current in polygon) {
            if ((current.y > y) != (previous.y > y)) {
                val crossing = (previous.x - current.x) * (y - current.y) /
                    (previous.y - current.y).coerceAwayFromZero() + current.x
                if (x < crossing) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun Float.coerceAwayFromZero(): Float = when {
        this > 0f -> max(this, 1e-6f)
        else -> min(this, -1e-6f)
    }

    private fun orderQuad(points: List<OcrPoint>): OcrQuad {
        require(points.size == 4)
        val topLeft = points.minBy { it.x + it.y }
        val bottomRight = points.maxBy { it.x + it.y }
        val topRight = points.maxBy { it.x - it.y }
        val bottomLeft = points.minBy { it.x - it.y }
        return OcrQuad(listOf(topLeft, topRight, bottomRight, bottomLeft))
    }

    private data class OrientedRectangle(
        val centerX: Float,
        val centerY: Float,
        val width: Float,
        val height: Float,
        val angle: Float,
    ) {
        val corners: List<OcrPoint>
            get() {
                val cosine = cos(angle)
                val sine = sin(angle)
                val halfWidth = width / 2f
                val halfHeight = height / 2f
                return listOf(
                    rotate(-halfWidth, -halfHeight, cosine, sine),
                    rotate(halfWidth, -halfHeight, cosine, sine),
                    rotate(halfWidth, halfHeight, cosine, sine),
                    rotate(-halfWidth, halfHeight, cosine, sine),
                )
            }

        private fun rotate(x: Float, y: Float, cosine: Float, sine: Float) = OcrPoint(
            centerX + x * cosine - y * sine,
            centerY + x * sine + y * cosine,
        )
    }

    private class PackedPointBuffer {
        private var values = LongArray(128)
        var size: Int = 0
            private set

        fun add(x: Int, y: Int) {
            if (size >= MAX_BOUNDARY_POINTS) return
            if (size == values.size) values = values.copyOf((values.size * 2).coerceAtMost(MAX_BOUNDARY_POINTS))
            values[size++] = (x.toLong() shl 32) or (y.toLong() and 0xFFFF_FFFFL)
        }

        fun toPoints(): List<OcrPoint> = List(size) { index ->
            val packed = values[index]
            OcrPoint((packed shr 32).toFloat(), packed.toInt().toFloat())
        }
    }
}
