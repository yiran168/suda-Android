package com.qrint.ppocr

import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

data class OcrPoint(val x: Float, val y: Float)

/** Four points in top-left, top-right, bottom-right, bottom-left order. */
data class OcrQuad(val points: List<OcrPoint>) {
    init { require(points.size == 4) { "OCR quad must contain exactly four points" } }

    val left: Float get() = points.minOf(OcrPoint::x)
    val top: Float get() = points.minOf(OcrPoint::y)
    val right: Float get() = points.maxOf(OcrPoint::x)
    val bottom: Float get() = points.maxOf(OcrPoint::y)
    val width: Float get() = max(1f, right - left)
    val height: Float get() = max(1f, bottom - top)
    val rotationDegrees: Float
        get() = Math.toDegrees(
            atan2(
                (points[1].y - points[0].y).toDouble(),
                (points[1].x - points[0].x).toDouble(),
            ),
        ).toFloat()

    fun clamped(width: Int, height: Int): OcrQuad = OcrQuad(
        points.map { point ->
            OcrPoint(
                point.x.coerceIn(0f, max(0, width - 1).toFloat()),
                point.y.coerceIn(0f, max(0, height - 1).toFloat()),
            )
        },
    )
}

data class PpOcrLine(
    val text: String,
    val confidence: Float,
    val box: OcrQuad,
)

data class PpOcrTimings(
    val modelLoadMs: Long,
    val detectionMs: Long,
    val recognitionMs: Long,
    val totalMs: Long,
)

data class PpOcrResult(
    val imageWidth: Int,
    val imageHeight: Int,
    val lines: List<PpOcrLine>,
    val timings: PpOcrTimings,
    val modelName: String = PpOcrMobile.MODEL_NAME,
) {
    val plainText: String get() = lines.joinToString("\n", transform = PpOcrLine::text)
    val meanConfidence: Float
        get() = lines.takeIf(List<PpOcrLine>::isNotEmpty)
            ?.map(PpOcrLine::confidence)
            ?.average()
            ?.toFloat()
            ?: 0f
}

/** Accuracy-oriented defaults with bounded per-line memory use. */
data class PpOcrOptions(
    val detectionLongSide: Int = 960,
    val detectionPixelThreshold: Float = 0.20f,
    val detectionBoxThreshold: Float = 0.45f,
    val unclipRatio: Float = 1.40f,
    val recognitionScoreThreshold: Float = 0.45f,
    val recognitionMaxWidth: Int = 1_920,
    val maxTextLines: Int = 160,
) {
    init {
        require(detectionLongSide in 320..1_600)
        require(detectionPixelThreshold in 0f..1f)
        require(detectionBoxThreshold in 0f..1f)
        require(unclipRatio in 1f..3f)
        require(recognitionScoreThreshold in 0f..1f)
        require(recognitionMaxWidth in 320..3_200)
        require(maxTextLines in 1..1_000)
    }
}

sealed class PpOcrException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class InvalidImage(message: String) : PpOcrException(message)
    class ModelLoad(cause: Throwable) : PpOcrException("无法加载本地 PP-OCRv6 Small 模型", cause)
    class Inference(stage: String, cause: Throwable) : PpOcrException("PP-OCR $stage 推理失败", cause)
    class ModelConfig(message: String, cause: Throwable? = null) : PpOcrException(message, cause)
}
