package com.qrint.studio.model

import kotlin.math.roundToInt

/** Exact physical mapping used by the editor, preview, calibration sheet and raster renderer. */
data class PaperPlacement(
    val headDots: Int,
    val paperDots: Int,
    val paperStartDot: Int,
    val paperEndDotExclusive: Int,
    val calibrationOffsetDots: Int,
) {
    val printableDots: Int get() = paperEndDotExclusive - paperStartDot
    val unusedBeforeDots: Int get() = paperStartDot
    val unusedAfterDots: Int get() = headDots - paperEndDotExclusive
}

fun PaperSettings.placement(): PaperPlacement = PaperPlacement(
    headDots = headDots.coerceAtLeast(8),
    paperDots = paperWidthDots(),
    paperStartDot = printableStartX(),
    paperEndDotExclusive = printableEndX(),
    calibrationOffsetDots = horizontalCalibrationDots(),
)

/**
 * Creates a short physical calibration label. The outer frame should be fully visible and the
 * centre ruler should land on the configured side of the loaded paper. The same document travels
 * through [LabelRenderer] as every user label, so a successful calibration covers the whole path.
 */
fun createPaperCalibrationDocument(settings: PaperSettings): LabelDocument {
    val paper = settings.copy(
        mode = PaperMode.LABEL,
        shape = PaperShape.RECTANGLE,
        mediaWidthMm = settings.contentWidthMm.coerceIn(MIN_PAPER_WIDTH_MM, MAX_PAPER_WIDTH_MM),
        contentWidthMm = settings.contentWidthMm.coerceIn(MIN_PAPER_WIDTH_MM, MAX_PAPER_WIDTH_MM),
        labelHeightMm = 34f,
        topPaddingMm = 0f,
        bottomPaddingMm = 0f,
        labelGapMm = 3f,
    )
    val start = paper.printableStartX()
    val width = paper.contentWidthDots()
    val inset = 4.coerceAtMost((width / 8).coerceAtLeast(1))
    val insideWidth = (width - inset * 2).coerceAtLeast(MIN_ELEMENT_DOTS)
    val height = paper.fixedHeightDots()
    val anchorTitle = when (paper.horizontalAnchor) {
        HorizontalAnchor.LEFT -> "纸张靠左"
        HorizontalAnchor.CENTER -> "纸张居中"
        HorizontalAnchor.RIGHT -> "纸张靠右"
    }
    val widthText = "${formatCalibrationMm(paper.contentWidthMm)} mm"
    val elements = buildList {
        add(
            LabelElement(
                kind = ElementKind.SHAPE,
                x = start + inset,
                y = 4,
                width = insideWidth,
                height = (height - 8).coerceAtLeast(MIN_ELEMENT_DOTS),
                shapeKind = ShapeKind.RECTANGLE,
                strokeWidthDots = 3f,
            ),
        )
        add(
            LabelElement(
                kind = ElementKind.TEXT,
                x = start + inset + 5,
                y = 18,
                width = (insideWidth - 10).coerceAtLeast(MIN_ELEMENT_DOTS),
                height = 42,
                text = "纸宽校准  $widthText",
                fontSizeDots = if (width < 120) 18f else 24f,
                fontWeight = 700,
                textAlignment = TextAlignment.CENTER,
            ),
        )
        add(
            LabelElement(
                kind = ElementKind.TEXT,
                x = start + inset + 5,
                y = 61,
                width = (insideWidth - 10).coerceAtLeast(MIN_ELEMENT_DOTS),
                height = 34,
                text = "$anchorTitle  偏移 ${formatSignedMm(paper.offsetXmm)} mm",
                fontSizeDots = if (width < 120) 14f else 19f,
                textAlignment = TextAlignment.CENTER,
            ),
        )

        // Millimetre ruler. Five-millimetre ticks are long and one-millimetre ticks are short.
        val wholeMillimetres = paper.contentWidthMm.roundToInt().coerceAtLeast(1)
        for (millimetre in 0..wholeMillimetres) {
            val x = start + (millimetre * paper.dpi / 25.4f).roundToInt()
            if (x >= start + width) break
            val major = millimetre % 5 == 0
            add(
                LabelElement(
                    kind = ElementKind.SHAPE,
                    x = (x - MIN_ELEMENT_DOTS / 2).coerceIn(start, (start + width - MIN_ELEMENT_DOTS).coerceAtLeast(start)),
                    y = if (major) 110 else 122,
                    width = MIN_ELEMENT_DOTS,
                    height = if (major) 46 else 30,
                    shapeKind = ShapeKind.VERTICAL_LINE,
                    strokeWidthDots = if (major) 3f else 2f,
                ),
            )
        }
        add(
            LabelElement(
                kind = ElementKind.SHAPE,
                x = start + inset + 5,
                y = 146,
                width = (insideWidth - 10).coerceAtLeast(MIN_ELEMENT_DOTS),
                height = MIN_ELEMENT_DOTS,
                shapeKind = ShapeKind.LINE,
                strokeWidthDots = 3f,
            ),
        )
        add(
            LabelElement(
                kind = ElementKind.TEXT,
                x = start + inset + 5,
                y = 168,
                width = (insideWidth - 10).coerceAtLeast(MIN_ELEMENT_DOTS),
                height = 54,
                text = "边框完整＝宽度与装纸方向正确\n裁边时每次调整 0.1 mm 后重打",
                fontSizeDots = if (width < 120) 13f else 17f,
                textAlignment = TextAlignment.CENTER,
                lineSpacingDots = 2f,
            ),
        )
    }
    return LabelDocument(
        title = "${widthText} 纸宽校准",
        category = "校准",
        paper = paper,
        elements = elements,
    ).normalized()
}

private fun formatCalibrationMm(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else "%.1f".format(value)

private fun formatSignedMm(value: Float): String = "%+.1f".format(value)
