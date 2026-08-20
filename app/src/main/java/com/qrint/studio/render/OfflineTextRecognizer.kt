package com.qrint.studio.render

import android.content.Context
import android.net.Uri
import com.qrint.ppocr.PpOcrMobile
import com.qrint.ppocr.PpOcrResult
import com.qrint.studio.model.ElementKind
import com.qrint.studio.model.LabelElement
import com.qrint.studio.model.MIN_ELEMENT_DOTS
import com.qrint.studio.model.PaperMode
import com.qrint.studio.model.PaperSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

data class RecognizedTextLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val rotationDegrees: Float = 0f,
    val confidence: Float? = null,
) {
    val width: Int get() = (right - left).coerceAtLeast(1)
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

data class OfflineTextScan(
    val sourceUri: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val lines: List<RecognizedTextLine>,
    val modelName: String = PpOcrMobile.MODEL_NAME,
) {
    val plainText: String get() = lines.joinToString("\n") { it.text }
    val meanConfidence: Float?
        get() = lines.mapNotNull { it.confidence }.takeIf { it.isNotEmpty() }?.average()?.toFloat()

    /** Maps OCR coordinates to the same printer-dot coordinate system used by the live canvas. */
    fun toEditableElements(
        paper: PaperSettings,
        startYDots: Int = 0,
        fitInsideFixedLabel: Boolean = paper.mode == PaperMode.LABEL,
    ): List<LabelElement> {
        if (lines.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return emptyList()
        val printableWidth = paper.contentWidthDots().coerceAtLeast(MIN_ELEMENT_DOTS)
        val widthScale = printableWidth.toFloat() / imageWidth
        val heightLimit = if (paper.mode == PaperMode.LABEL) {
            (paper.fixedHeightDots() - startYDots).coerceAtLeast(MIN_ELEMENT_DOTS)
        } else Int.MAX_VALUE
        val scale = if (fitInsideFixedLabel) {
            min(widthScale, heightLimit.toFloat() / imageHeight)
        } else widthScale
        val drawnWidth = imageWidth * scale
        val originX = paper.printableStartX() + (printableWidth - drawnWidth) / 2f
        return lines.sortedWith(compareBy<RecognizedTextLine> { it.top }.thenBy { it.left }).map { line ->
            val rawWidth = max(MIN_ELEMENT_DOTS.toFloat(), line.width * scale)
            val rawHeight = max(MIN_ELEMENT_DOTS.toFloat(), line.height * scale * 1.22f)
            LabelElement(
                kind = ElementKind.TEXT,
                x = (originX + line.left * scale).toInt(),
                y = (startYDots + line.top * scale).toInt(),
                width = rawWidth.toInt(),
                height = rawHeight.toInt(),
                rotation = line.rotationDegrees.coerceIn(-45f, 45f),
                text = line.text,
                fontSizeDots = max(12f, line.height * scale * 0.86f),
                fontWeight = 400,
                lineSpacingDots = 1f,
            )
        }
    }
}

/** One PP-OCR mapping path shared by gallery import, camera OCR and scan-to-template. */
object OfflineTextRecognizer {
    internal fun fromPpOcrResult(sourceUri: String, result: PpOcrResult): OfflineTextScan {
        val lines = result.lines.mapNotNull { line ->
            val text = line.text.trim()
            if (text.isEmpty()) return@mapNotNull null
            val box = line.box
            val left = box.left.toInt().coerceIn(0, result.imageWidth)
            val top = box.top.toInt().coerceIn(0, result.imageHeight)
            val right = box.right.toInt().coerceIn(left + 1, result.imageWidth.coerceAtLeast(left + 1))
            val bottom = box.bottom.toInt().coerceIn(top + 1, result.imageHeight.coerceAtLeast(top + 1))
            RecognizedTextLine(
                text = text,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                rotationDegrees = box.rotationDegrees,
                confidence = line.confidence,
            )
        }
        return OfflineTextScan(
            sourceUri = sourceUri,
            imageWidth = result.imageWidth,
            imageHeight = result.imageHeight,
            lines = lines,
            modelName = result.modelName,
        )
    }

    suspend fun recognize(context: Context, uri: Uri): Result<OfflineTextScan> = withContext(Dispatchers.Default) {
        runCatching {
            val source = ImageLoader.load(context, uri.toString(), 2_400, 2_400)
                ?: error("无法读取扫描图片")
            try {
                val result = PpOcrMobile.recognize(context, source)
                val scan = fromPpOcrResult(uri.toString(), result)
                require(scan.lines.isNotEmpty()) { "PP-OCR 未发现清晰文字，请靠近、对焦并避免反光" }
                val confidence = scan.meanConfidence
                require(confidence == null || confidence >= OcrAcceptancePolicy.MIN_MODEL_CONFIDENCE) {
                    "PP-OCR 模型置信度 ${"%.1f".format((confidence ?: 0f) * 100f)}% 偏低；请改善光线或靠近"
                }
                scan
            } finally {
                if (!source.isRecycled) source.recycle()
            }
        }
    }
}
