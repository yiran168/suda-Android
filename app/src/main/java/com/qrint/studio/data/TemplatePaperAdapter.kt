package com.qrint.studio.data

import com.qrint.studio.model.LabelDocument
import com.qrint.studio.model.PaperMode
import com.qrint.studio.model.PaperSettings
import kotlin.math.min
import kotlin.math.roundToInt

/** Fits a built-in design to the media selected at template entry without clipping any element. */
fun LabelDocument.fittedToPaper(target: PaperSettings): LabelDocument {
    val sourcePaper = paper.normalized()
    val safeTarget = target.normalized()
    val sourceWidth = sourcePaper.contentWidthDots().coerceAtLeast(1)
    val sourceHeight = sourcePaper.fixedHeightDots().coerceAtLeast(1)
    val targetWidth = safeTarget.contentWidthDots().coerceAtLeast(1)
    val targetHeight = safeTarget.fixedHeightDots().coerceAtLeast(1)
    val widthScale = targetWidth.toFloat() / sourceWidth
    val scale = if (safeTarget.mode == PaperMode.LABEL) {
        min(widthScale, targetHeight.toFloat() / sourceHeight)
    } else {
        widthScale
    }.coerceAtLeast(0.01f)
    val scaledWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
    val scaledHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
    val offsetX = safeTarget.contentStartX() + (targetWidth - scaledWidth) / 2
    val offsetY = if (safeTarget.mode == PaperMode.LABEL) (targetHeight - scaledHeight) / 2 else 0
    val sourceStartX = sourcePaper.contentStartX()
    val mapped = elements.map { element ->
        element.copy(
            x = offsetX + ((element.x - sourceStartX) * scale).roundToInt(),
            y = offsetY + (element.y * scale).roundToInt(),
            width = (element.width * scale).roundToInt().coerceAtLeast(4),
            height = (element.height * scale).roundToInt().coerceAtLeast(4),
            fontSizeDots = (element.fontSizeDots * scale).coerceAtLeast(6f),
            letterSpacingDots = element.letterSpacingDots * scale,
            lineSpacingDots = element.lineSpacingDots * scale,
            strokeWidthDots = (element.strokeWidthDots * scale).coerceAtLeast(1f),
            cornerRadiusDots = element.cornerRadiusDots * scale,
        )
    }
    return copy(
        paper = safeTarget,
        elements = mapped,
        updatedAt = System.currentTimeMillis(),
        builtIn = true,
    ).normalized()
}
