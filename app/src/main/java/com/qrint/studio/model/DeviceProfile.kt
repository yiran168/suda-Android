package com.qrint.studio.model

import kotlin.math.roundToInt

/**
 * Applies the current physical printer profile without replacing a job's paper type or dimensions.
 * Dot-based element geometry is rescaled when DPI changes so its millimetre size stays constant.
 */
fun LabelDocument.withDeviceProfile(profile: PaperSettings): LabelDocument {
    val sourcePaper = paper.normalized()
    val targetPaper = sourcePaper.copy(
        protocol = profile.protocol,
        dpi = profile.dpi,
        headDots = profile.headDots,
        offsetXmm = profile.offsetXmm,
        offsetYmm = profile.offsetYmm,
    ).normalized()
    val scale = targetPaper.dpi.toFloat() / sourcePaper.dpi.coerceAtLeast(1)
    val sourceStart = sourcePaper.printableStartX()
    val targetStart = targetPaper.printableStartX()
    val mapped = elements.map { element ->
        element.copy(
            x = targetStart + ((element.x - sourceStart) * scale).roundToInt(),
            y = (element.y * scale).roundToInt(),
            width = (element.width * scale).roundToInt().coerceAtLeast(MIN_ELEMENT_DOTS),
            height = (element.height * scale).roundToInt().coerceAtLeast(MIN_ELEMENT_DOTS),
            fontSizeDots = element.fontSizeDots * scale,
            letterSpacingDots = element.letterSpacingDots * scale,
            lineSpacingDots = element.lineSpacingDots * scale,
            strokeWidthDots = element.strokeWidthDots * scale,
            cornerRadiusDots = element.cornerRadiusDots * scale,
        )
    }
    return copy(paper = targetPaper, elements = mapped).normalized()
}
