package com.qrint.studio.data

import com.qrint.studio.model.BarcodeType
import com.qrint.studio.model.DEFAULT_TAIL_FEED_MM
import com.qrint.studio.model.DitherMode
import com.qrint.studio.model.ElementKind
import com.qrint.studio.model.HorizontalAnchor
import com.qrint.studio.model.ImageFit
import com.qrint.studio.model.LabelDocument
import com.qrint.studio.model.LabelElement
import com.qrint.studio.model.PaperMode
import com.qrint.studio.model.PaperSettings
import com.qrint.studio.model.PaperShape
import com.qrint.studio.model.ShapeKind
import com.qrint.studio.model.TemplateSummary
import com.qrint.studio.model.TextAlignment
import kotlin.math.roundToInt

/**
 * One DRY runtime adapter for the generated source catalog.
 *
 * Source artwork is an unlocked image element; recognized text, real decoded codes and detected
 * rules are independent elements. The same document therefore drives card previews, editor
 * previews and the final 203-dpi printer raster without a second layout implementation.
 */
object TemplateCatalog {
    val categories: List<String> = listOf(IndustryCatalog.ALL) + IndustryCatalog.categories.map { it.name }
    val size: Int get() = SOURCE_TEMPLATE_COUNT

    val all: List<TemplateSummary> by lazy {
        sourceTemplateSpecs.mapIndexed(::create)
    }

    fun inCategory(category: String): List<TemplateSummary> =
        if (category == IndustryCatalog.ALL) all else all.filter { it.category == category }

    private fun create(index: Int, spec: SourceTemplateSpec): TemplateSummary {
        val paper = PaperSettings(
            mode = PaperMode.LABEL,
            shape = PaperShape.RECTANGLE,
            mediaWidthMm = spec.widthMm,
            contentWidthMm = spec.widthMm,
            labelHeightMm = spec.heightMm,
            horizontalAnchor = HorizontalAnchor.LEFT,
            labelGapMm = 2f,
            tailFeedMm = DEFAULT_TAIL_FEED_MM,
        )
        val widthDots = paper.contentWidthDots()
        val heightDots = paper.fixedHeightDots()
        val contentX = paper.contentStartX()
        val elements = buildList {
            if (spec.decorResource.isNotBlank()) {
                add(
                    LabelElement(
                        id = "${spec.id}-source-art",
                        kind = ElementKind.IMAGE,
                        x = contentX,
                        y = 0,
                        width = widthDots,
                        height = heightDots,
                        locked = false,
                        imageUri = "android.resource://com.qrint.studio/drawable/${spec.decorResource}",
                        imageFit = ImageFit.STRETCH,
                        ditherMode = DitherMode.FLOYD_STEINBERG,
                        threshold = 170,
                        contrast = 1.06f,
                    ),
                )
            }
            spec.text.forEachIndexed { textIndex, text ->
                val box = pixelBox(text.left, text.top, text.right, text.bottom, contentX, widthDots, heightDots, 18, 18)
                add(
                    LabelElement(
                        id = "${spec.id}-text-$textIndex",
                        kind = ElementKind.TEXT,
                        x = box.left,
                        y = box.top,
                        width = box.width,
                        height = box.height,
                        text = text.text,
                        fontSizeDots = (box.height * 0.76f).coerceIn(10f, 64f),
                        fontWeight = if (text.emphasis) 700 else 400,
                        lineSpacingDots = 1f,
                        textAlignment = runCatching { TextAlignment.valueOf(text.alignment) }.getOrDefault(TextAlignment.LEFT),
                    ),
                )
            }
            spec.codes.forEachIndexed { codeIndex, code ->
                val type = runCatching { BarcodeType.valueOf(code.type) }.getOrDefault(BarcodeType.QR_CODE)
                val minimum = if (type.twoDimensional) 42 else 24
                val box = pixelBox(code.left, code.top, code.right, code.bottom, contentX, widthDots, heightDots, minimum, minimum)
                add(
                    LabelElement(
                        id = "${spec.id}-code-$codeIndex",
                        kind = ElementKind.BARCODE,
                        x = box.left,
                        y = box.top,
                        width = box.width,
                        height = box.height,
                        barcodeType = type,
                        barcodeContent = code.content,
                        barcodeCaption = false,
                    ),
                )
            }
            spec.shapes.forEachIndexed { shapeIndex, shape ->
                val kind = runCatching { ShapeKind.valueOf(shape.kind) }.getOrDefault(ShapeKind.LINE)
                val horizontal = kind == ShapeKind.LINE || kind == ShapeKind.DASHED_LINE
                val vertical = kind == ShapeKind.VERTICAL_LINE || kind == ShapeKind.DASHED_VERTICAL_LINE
                val box = pixelBox(
                    shape.left,
                    shape.top,
                    shape.right,
                    shape.bottom,
                    contentX,
                    widthDots,
                    heightDots,
                    if (vertical) 4 else 16,
                    if (horizontal) 4 else 16,
                )
                add(
                    LabelElement(
                        id = "${spec.id}-shape-$shapeIndex",
                        kind = ElementKind.SHAPE,
                        x = box.left,
                        y = box.top,
                        width = box.width,
                        height = box.height,
                        shapeKind = kind,
                        strokeWidthDots = (shape.strokeWidth * minOf(widthDots, heightDots)).coerceIn(1f, 10f),
                    ),
                )
            }
        }
        val timestamp = 1_786_500_000_000L + index
        val document = LabelDocument(
            id = spec.id,
            title = spec.title,
            category = spec.category,
            paper = paper,
            elements = elements,
            createdAt = timestamp,
            updatedAt = timestamp,
            builtIn = true,
        ).normalized()
        return TemplateSummary(spec.id, spec.title, spec.category, spec.widthMm, spec.heightMm, document)
    }

    private fun pixelBox(
        normalizedLeft: Float,
        normalizedTop: Float,
        normalizedRight: Float,
        normalizedBottom: Float,
        contentX: Int,
        canvasWidth: Int,
        canvasHeight: Int,
        minimumWidth: Int,
        minimumHeight: Int,
    ): PixelBox {
        val safeMinimumWidth = minimumWidth.coerceAtMost(canvasWidth)
        val safeMinimumHeight = minimumHeight.coerceAtMost(canvasHeight)
        var left = contentX + (normalizedLeft.coerceIn(0f, 1f) * canvasWidth).roundToInt()
        var top = (normalizedTop.coerceIn(0f, 1f) * canvasHeight).roundToInt()
        var right = contentX + (normalizedRight.coerceIn(0f, 1f) * canvasWidth).roundToInt()
        var bottom = (normalizedBottom.coerceIn(0f, 1f) * canvasHeight).roundToInt()
        right = maxOf(right, left + safeMinimumWidth)
        bottom = maxOf(bottom, top + safeMinimumHeight)
        if (right > contentX + canvasWidth) {
            left -= right - (contentX + canvasWidth)
            right = contentX + canvasWidth
        }
        if (bottom > canvasHeight) {
            top -= bottom - canvasHeight
            bottom = canvasHeight
        }
        left = left.coerceIn(contentX, contentX + canvasWidth - safeMinimumWidth)
        top = top.coerceIn(0, canvasHeight - safeMinimumHeight)
        return PixelBox(left, top, (right - left).coerceAtLeast(safeMinimumWidth), (bottom - top).coerceAtLeast(safeMinimumHeight))
    }

    private data class PixelBox(val left: Int, val top: Int, val width: Int, val height: Int)
}
