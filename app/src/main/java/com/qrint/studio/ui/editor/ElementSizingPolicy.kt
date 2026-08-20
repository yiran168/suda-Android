package com.qrint.studio.ui.editor

import com.qrint.studio.model.ElementKind
import com.qrint.studio.model.LabelElement
import com.qrint.studio.model.MIN_ELEMENT_DOTS
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shared geometry used by touch handles, the size slider and numeric size input.
 * Keeping the calculation here prevents each control from producing a slightly different print box.
 */
internal object ElementSizingPolicy {
    fun scaleToWidth(
        element: LabelElement,
        targetWidthDots: Int,
        contentStart: Int,
        contentEnd: Int,
        heightLimit: Int,
    ): LabelElement {
        val availableWidth = (contentEnd - contentStart).coerceAtLeast(MIN_ELEMENT_DOTS)
        val availableHeight = heightLimit.coerceAtLeast(MIN_ELEMENT_DOTS)
        val requestedScale = targetWidthDots.toFloat() / element.width.coerceAtLeast(1)
        val minimumScale = max(
            MIN_ELEMENT_DOTS / element.width.coerceAtLeast(1).toFloat(),
            MIN_ELEMENT_DOTS / element.height.coerceAtLeast(1).toFloat(),
        )
        val maximumScale = min(
            availableWidth / element.width.coerceAtLeast(1).toFloat(),
            availableHeight / element.height.coerceAtLeast(1).toFloat(),
        ).coerceAtLeast(minimumScale)
        val scale = requestedScale.coerceIn(minimumScale, maximumScale)
        return resize(
            element = element.scaleTypography(scale),
            width = (element.width * scale).roundToInt(),
            height = (element.height * scale).roundToInt(),
            contentStart = contentStart,
            contentEnd = contentEnd,
            heightLimit = heightLimit,
            horizontalBias = 0.5f,
            verticalBias = 0.5f,
        )
    }

    fun fitToContent(
        element: LabelElement,
        contentWidthDots: Int,
        contentHeightDots: Int,
        contentStart: Int,
        contentEnd: Int,
        heightLimit: Int,
        horizontalBias: Float = 0.5f,
        verticalBias: Float = 0f,
    ): LabelElement = resize(
        element = element,
        width = contentWidthDots,
        height = contentHeightDots,
        contentStart = contentStart,
        contentEnd = contentEnd,
        heightLimit = heightLimit,
        horizontalBias = horizontalBias,
        verticalBias = verticalBias,
    )

    private fun resize(
        element: LabelElement,
        width: Int,
        height: Int,
        contentStart: Int,
        contentEnd: Int,
        heightLimit: Int,
        horizontalBias: Float,
        verticalBias: Float,
    ): LabelElement {
        val safeStart = min(contentStart, contentEnd - MIN_ELEMENT_DOTS)
        val safeEnd = max(contentEnd, safeStart + MIN_ELEMENT_DOTS)
        val safeHeightLimit = heightLimit.coerceAtLeast(MIN_ELEMENT_DOTS)
        val safeWidth = width.coerceIn(MIN_ELEMENT_DOTS, safeEnd - safeStart)
        val safeHeight = height.coerceIn(MIN_ELEMENT_DOTS, safeHeightLimit)
        val biasX = horizontalBias.coerceIn(0f, 1f)
        val biasY = verticalBias.coerceIn(0f, 1f)
        val x = (element.x + (element.width - safeWidth) * biasX).roundToInt()
            .coerceIn(safeStart, safeEnd - safeWidth)
        val y = (element.y + (element.height - safeHeight) * biasY).roundToInt()
            .coerceIn(0, safeHeightLimit - safeHeight)
        return element.copy(x = x, y = y, width = safeWidth, height = safeHeight)
    }
}

internal fun LabelElement.scaleTypography(scale: Float): LabelElement {
    if (kind !in TYPOGRAPHY_KINDS || !scale.isFinite() || scale == 1f) return this
    return copy(
        fontSizeDots = (fontSizeDots * scale).coerceIn(8f, 240f),
        letterSpacingDots = (letterSpacingDots * scale).coerceIn(-12f, 64f),
        lineSpacingDots = (lineSpacingDots * scale).coerceIn(-12f, 128f),
    )
}

private val TYPOGRAPHY_KINDS = setOf(ElementKind.TEXT, ElementKind.DATE_TIME, ElementKind.SEQUENCE)
