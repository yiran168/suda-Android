package com.qrint.studio.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PathEffect
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.DashPathEffect
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.qrint.studio.R
import com.qrint.studio.data.UserFontStore
import com.qrint.studio.model.ElementKind
import com.qrint.studio.model.ImageFit
import com.qrint.studio.model.LabelDocument
import com.qrint.studio.model.LabelElement
import com.qrint.studio.model.MAX_DOCUMENT_HEIGHT_DOTS
import com.qrint.studio.model.MIN_ELEMENT_DOTS
import com.qrint.studio.model.PaperMode
import com.qrint.studio.model.PrintFontCatalog
import com.qrint.studio.model.ShapeKind
import com.qrint.studio.model.TextAlignment
import com.qrint.studio.model.TextEnhancementMode
import com.qrint.studio.model.toJson
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ceil
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class RenderedLabel(
    val bitmap: Bitmap,
    val widthDots: Int,
    val heightDots: Int,
    val warnings: List<String>,
    val documentHash: Int,
)

/** One rendering path feeds both screen preview and printer raster encoding. */
object LabelRenderer {
    private val AUTO_HEIGHT_TEXT_KINDS = setOf(ElementKind.TEXT, ElementKind.DATE_TIME, ElementKind.SEQUENCE)

    /**
     * Expands continuous-paper text boxes with the very same StaticLayout/typeface path used by
     * [render]. This keeps auto paper length, the blue editor frame, screen dots, and printer dots
     * derived from one layout calculation instead of four approximations.
     */
    fun prepareDocument(context: Context, document: LabelDocument, sequenceIndex: Long = 0): LabelDocument {
        val normalized = document.normalized()
        if (normalized.paper.mode != PaperMode.CONTINUOUS) return normalized
        var changed = false
        val fitted = normalized.elements.map { element ->
            if (element.kind !in AUTO_HEIGHT_TEXT_KINDS) return@map element
            val required = prepareText(context, element, element.runtimeText(sequenceIndex)).requiredHeight
            val height = fittedContinuousTextHeight(element.height, required, element.y)
            if (height != element.height) {
                changed = true
                element.copy(height = height)
            } else element
        }
        return if (changed) normalized.copy(elements = fitted) else normalized
    }

    data class ContentSize(val width: Int, val height: Int)

    /** Exact text layout bounds from the same typeface/StaticLayout path used for printing. */
    fun measureTextContent(context: Context, element: LabelElement, value: String = element.runtimeText()): ContentSize {
        require(element.kind in AUTO_HEIGHT_TEXT_KINDS) { "Only text elements can be measured" }
        val prepared = prepareText(context, element, value)
        val measuredWidth = if (element.verticalText) {
            value.maxOfOrNull { prepared.paint.measureText(it.toString()) } ?: 0f
        } else {
            val layout = prepared.layout
            if (layout == null || layout.lineCount == 0) 0f
            else (0 until layout.lineCount).maxOf { layout.getLineWidth(it) }
        }
        // Two printer dots protect italic overhang/rounding without retaining a visibly loose box.
        return ContentSize(
            width = (kotlin.math.ceil(measuredWidth).toInt() + 2).coerceAtLeast(MIN_ELEMENT_DOTS),
            height = prepared.requiredHeight.coerceAtLeast(MIN_ELEMENT_DOTS),
        )
    }

    fun render(context: Context, document: LabelDocument, sequenceIndex: Long = 0): RenderedLabel {
        val doc = prepareDocument(context, document, sequenceIndex)
        val width = doc.paper.headDots.coerceIn(128, 2048)
        val height = doc.outputHeightDots()
        // The final thermal raster contains only black and white. RGB_565 halves peak memory versus
        // ARGB_8888 is fully supported by Canvas/Compose across the supported API range.
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val warnings = mutableListOf<String>()
        val yOffset = doc.paper.mmToDots(doc.paper.offsetYmm)
        val xOffset = doc.paper.horizontalCalibrationDots()

        canvas.save()
        // A narrow roll only covers part of the fixed thermal head. Keeping the white columns in
        // the raster preserves the left/right physical placement, while this clip guarantees that
        // a calibration offset can never heat dots outside the configured paper area.
        canvas.clipRect(
            doc.paper.printableStartX(),
            0,
            doc.paper.printableEndX(),
            height,
        )
        canvas.translate(xOffset.toFloat(), 0f)
        doc.elements.forEach { element ->
            if (element.width <= 0 || element.height <= 0) return@forEach
            canvas.save()
            val centerX = element.x + element.width / 2f
            val centerY = element.y + yOffset + element.height / 2f
            canvas.rotate(element.rotation, centerX, centerY)
            if (element.invert && element.kind != ElementKind.IMAGE) {
                drawElementBackground(canvas, element, yOffset)
            }
            when (element.kind) {
                ElementKind.TEXT, ElementKind.DATE_TIME, ElementKind.SEQUENCE ->
                    drawText(context, canvas, element, element.runtimeText(sequenceIndex), yOffset, warnings)
                ElementKind.IMAGE -> drawImage(context, canvas, element, yOffset, warnings)
                ElementKind.BARCODE -> drawBarcode(canvas, element, yOffset, warnings)
                ElementKind.SHAPE -> drawShape(canvas, element, yOffset)
                ElementKind.TABLE -> drawTable(context, canvas, element, yOffset, warnings)
                ElementKind.DRAWING -> drawDrawing(canvas, element, yOffset)
            }
            canvas.restore()
        }
        canvas.restore()

        // Resolve anti-aliased edges in-place. Row processing avoids several full-height arrays,
        // which is important on low-memory devices with long continuous receipts.
        thresholdInPlace(bitmap, 220)
        return RenderedLabel(bitmap, width, height, warnings.distinct(), doc.toJson().toString().hashCode())
    }

    private fun drawText(
        context: Context,
        canvas: Canvas,
        element: LabelElement,
        value: String,
        yOffset: Int,
        warnings: MutableList<String>,
    ) {
        val x = element.x.toFloat()
        val y = (element.y + yOffset).toFloat()
        val height = element.height.coerceAtLeast(1)
        val prepared = prepareText(context, element, value)
        prepared.warning?.let(warnings::add)
        if (element.textEnhancement != TextEnhancementMode.NONE) {
            drawEnhancedText(canvas, element, value, yOffset, prepared)
            return
        }
        canvas.save()
        canvas.translate(x, y)
        drawPreparedText(canvas, element, value, prepared, height)
        canvas.restore()
    }

    private fun drawPreparedText(
        canvas: Canvas,
        element: LabelElement,
        value: String,
        prepared: PreparedText,
        height: Int,
    ) {
        val paint = prepared.paint
        canvas.save()
        canvas.clipRect(0f, 0f, element.width.coerceAtLeast(1).toFloat(), height.toFloat())
        if (element.verticalText) {
            var baseline = -paint.ascent()
            value.forEach { char ->
                if (baseline + paint.descent() <= height) {
                    val glyph = char.toString()
                    canvas.drawText(glyph, (element.width - paint.measureText(glyph)) / 2f, baseline, paint)
                }
                baseline += prepared.lineHeight
            }
        } else {
            prepared.layout?.draw(canvas)
        }
        canvas.restore()
    }

    /**
     * Processes text in bounded strips instead of allocating a second full receipt bitmap. The
     * resulting binary mask is drawn back onto the same canvas used by preview and printing.
     */
    private fun drawEnhancedText(
        canvas: Canvas,
        element: LabelElement,
        value: String,
        yOffset: Int,
        prepared: PreparedText,
    ) {
        val width = element.width.coerceAtLeast(1)
        val height = element.height.coerceAtLeast(1)
        val foreground = foregroundColor(element)
        var stripTop = 0
        while (stripTop < height) {
            val stripHeight = min(TEXT_ENHANCEMENT_STRIP_DOTS, height - stripTop)
            val strip = Bitmap.createBitmap(width, stripHeight, Bitmap.Config.ARGB_8888)
            val stripCanvas = Canvas(strip)
            stripCanvas.drawColor(Color.TRANSPARENT)
            stripCanvas.save()
            stripCanvas.translate(0f, -stripTop.toFloat())
            drawPreparedText(stripCanvas, element, value, prepared, height)
            stripCanvas.restore()
            applyTextEnhancement(strip, element.textEnhancement, foreground)
            canvas.drawBitmap(
                strip,
                element.x.toFloat(),
                (element.y + yOffset + stripTop).toFloat(),
                null,
            )
            strip.recycle()
            stripTop += stripHeight
        }
    }

    private data class PreparedText(
        val paint: TextPaint,
        val layout: StaticLayout?,
        val lineHeight: Float,
        val requiredHeight: Int,
        val warning: String?,
    )

    private fun prepareText(context: Context, element: LabelElement, value: String): PreparedText {
        val width = element.width.coerceAtLeast(1)
        val weight = PrintFontCatalog.normalizeWeight(element.fontWeight)
        val legacyStyle = when {
            element.fontWeight >= 600 && element.italic -> Typeface.BOLD_ITALIC
            element.fontWeight >= 600 -> Typeface.BOLD
            element.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val resolvedTypeface = resolveTypeface(context, element.fontFamily, weight, element.italic, legacyStyle)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = foregroundColor(element)
            textSize = element.fontSizeDots.coerceIn(8f, 240f)
            typeface = resolvedTypeface.typeface
            if (Build.VERSION.SDK_INT < 28 && weight > 400) {
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = ((weight - 400) / 250f).coerceIn(0.35f, 2f)
            }
            isUnderlineText = element.underline
            isStrikeThruText = element.strikeThrough
            letterSpacing = (element.letterSpacingDots / max(1f, textSize)).coerceIn(-0.05f, 1f)
        }
        val lineHeight = (paint.fontSpacing + element.lineSpacingDots).coerceAtLeast(1f)
        if (element.verticalText) {
            val chars = value.toCharArray()
            val required = if (chars.isEmpty()) MIN_ELEMENT_DOTS else ceil(
                -paint.ascent() + (chars.size - 1) * lineHeight + paint.descent(),
            ).toInt().coerceAtLeast(MIN_ELEMENT_DOTS)
            return PreparedText(paint, null, lineHeight, required, resolvedTypeface.warning)
        }
        val alignment = when (element.textAlignment) {
            TextAlignment.LEFT -> Layout.Alignment.ALIGN_NORMAL
            TextAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
        val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setLineSpacing(element.lineSpacingDots, 1f)
            .build()
        return PreparedText(
            paint = paint,
            layout = layout,
            lineHeight = lineHeight,
            requiredHeight = layout.height.coerceAtLeast(MIN_ELEMENT_DOTS),
            warning = resolvedTypeface.warning,
        )
    }

    private fun drawImage(
        context: Context,
        canvas: Canvas,
        element: LabelElement,
        yOffset: Int,
        warnings: MutableList<String>,
    ) {
        val source = ImageLoader.load(context, element.imageUri, element.width, element.height)
        if (source == null) {
            warnings += "图片 ${element.id.take(6)} 无法读取，请重新选择"
            drawMissingImage(canvas, element, yOffset)
            return
        }
        val box = Bitmap.createBitmap(element.width, element.height, Bitmap.Config.ARGB_8888)
        val boxCanvas = Canvas(box)
        boxCanvas.drawColor(Color.WHITE)
        val src = sourceRect(source.width, source.height, element.width, element.height, element.imageFit)
        val dst = destinationRect(source.width, source.height, element.width, element.height, element.imageFit)
        boxCanvas.drawBitmap(source, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        source.recycle()
        val mono = Dither.processBitmap(
            box, element.width, element.height, element.ditherMode, element.threshold,
            element.brightness, element.contrast, element.invert,
        )
        box.recycle()
        val alpha = blackOnly(mono)
        mono.recycle()
        canvas.drawBitmap(alpha, element.x.toFloat(), (element.y + yOffset).toFloat(), null)
        alpha.recycle()
    }

    private fun drawBarcode(canvas: Canvas, element: LabelElement, yOffset: Int, warnings: MutableList<String>) {
        val result = BarcodeRenderer.render(
            element.barcodeType,
            element.barcodeContent,
            element.width,
            element.height,
            element.barcodeCaption,
            element.qrErrorCorrection,
        )
        result.warning?.let(warnings::add)
        if (result.normalized.changed) warnings += result.normalized.notice
        val alpha = blackOnly(result.bitmap, foregroundColor(element))
        result.bitmap.recycle()
        canvas.drawBitmap(alpha, element.x.toFloat(), (element.y + yOffset).toFloat(), null)
        alpha.recycle()
    }

    private fun drawShape(canvas: Canvas, element: LabelElement, yOffset: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = foregroundColor(element)
            style = if (element.filled) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = element.strokeWidthDots.coerceIn(1f, 32f)
        }
        val rect = RectF(
            element.x.toFloat(),
            (element.y + yOffset).toFloat(),
            (element.x + element.width).toFloat(),
            (element.y + yOffset + element.height).toFloat(),
        )
        when (element.shapeKind) {
            ShapeKind.RECTANGLE -> canvas.drawRect(rect, paint)
            ShapeKind.ROUNDED_RECTANGLE -> canvas.drawRoundRect(rect, element.cornerRadiusDots, element.cornerRadiusDots, paint)
            ShapeKind.ELLIPSE -> canvas.drawOval(rect, paint)
            ShapeKind.LINE -> canvas.drawLine(rect.left, rect.centerY(), rect.right, rect.centerY(), paint)
            ShapeKind.VERTICAL_LINE -> canvas.drawLine(rect.centerX(), rect.top, rect.centerX(), rect.bottom, paint)
            ShapeKind.DASHED_LINE -> {
                val previous: PathEffect? = paint.pathEffect
                paint.pathEffect = DashPathEffect(floatArrayOf(10f, 7f), 0f)
                canvas.drawLine(rect.left, rect.centerY(), rect.right, rect.centerY(), paint)
                paint.pathEffect = previous
            }
            ShapeKind.DASHED_VERTICAL_LINE -> {
                val previous: PathEffect? = paint.pathEffect
                paint.pathEffect = DashPathEffect(floatArrayOf(10f, 7f), 0f)
                canvas.drawLine(rect.centerX(), rect.top, rect.centerX(), rect.bottom, paint)
                paint.pathEffect = previous
            }
            ShapeKind.TRIANGLE -> canvas.drawPath(android.graphics.Path().apply {
                moveTo(rect.centerX(), rect.top)
                lineTo(rect.right, rect.bottom)
                lineTo(rect.left, rect.bottom)
                close()
            }, paint)
            ShapeKind.PENTAGON -> canvas.drawPath(regularPolygon(rect, 5), paint)
            ShapeKind.HEXAGON -> canvas.drawPath(regularPolygon(rect, 6), paint)
            ShapeKind.DIAMOND -> canvas.drawPath(android.graphics.Path().apply {
                moveTo(rect.centerX(), rect.top)
                lineTo(rect.right, rect.centerY())
                lineTo(rect.centerX(), rect.bottom)
                lineTo(rect.left, rect.centerY())
                close()
            }, paint)
            ShapeKind.STAR -> canvas.drawPath(android.graphics.Path().apply {
                val outer = min(rect.width(), rect.height()) / 2f
                val inner = outer * 0.43f
                for (index in 0 until 10) {
                    val radius = if (index % 2 == 0) outer else inner
                    val angle = -PI / 2.0 + index * PI / 5.0
                    val px = rect.centerX() + cos(angle).toFloat() * radius
                    val py = rect.centerY() + sin(angle).toFloat() * radius
                    if (index == 0) moveTo(px, py) else lineTo(px, py)
                }
                close()
            }, paint)
            ShapeKind.HEART -> canvas.drawPath(android.graphics.Path().apply {
                moveTo(rect.centerX(), rect.bottom)
                cubicTo(rect.left - rect.width() * 0.08f, rect.centerY(), rect.left + rect.width() * 0.08f, rect.top, rect.centerX(), rect.top + rect.height() * 0.28f)
                cubicTo(rect.right - rect.width() * 0.08f, rect.top, rect.right + rect.width() * 0.08f, rect.centerY(), rect.centerX(), rect.bottom)
                close()
            }, paint)
            ShapeKind.PLUS -> {
                canvas.drawLine(rect.centerX(), rect.top, rect.centerX(), rect.bottom, paint)
                canvas.drawLine(rect.left, rect.centerY(), rect.right, rect.centerY(), paint)
            }
            ShapeKind.CHECKMARK -> canvas.drawPath(android.graphics.Path().apply {
                moveTo(rect.left, rect.centerY())
                lineTo(rect.left + rect.width() * 0.38f, rect.bottom)
                lineTo(rect.right, rect.top)
            }, paint)
            ShapeKind.ARROW_RIGHT, ShapeKind.ARROW_LEFT, ShapeKind.ARROW_UP, ShapeKind.ARROW_DOWN -> {
                canvas.drawPath(arrowPath(rect, element.shapeKind), paint)
            }
            ShapeKind.SPEECH_BUBBLE -> {
                val bubbleBottom = rect.bottom - rect.height() * 0.2f
                canvas.drawRoundRect(RectF(rect.left, rect.top, rect.right, bubbleBottom), element.cornerRadiusDots, element.cornerRadiusDots, paint)
                canvas.drawPath(android.graphics.Path().apply {
                    moveTo(rect.left + rect.width() * 0.62f, bubbleBottom)
                    lineTo(rect.left + rect.width() * 0.76f, rect.bottom)
                    lineTo(rect.left + rect.width() * 0.82f, bubbleBottom)
                    close()
                }, paint)
            }
            ShapeKind.CROSS -> {
                canvas.drawLine(rect.left, rect.top, rect.right, rect.bottom, paint)
                canvas.drawLine(rect.right, rect.top, rect.left, rect.bottom, paint)
            }
        }
    }

    private fun drawDrawing(canvas: Canvas, element: LabelElement, yOffset: Int) {
        if (element.drawingPoints.size < 4) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = foregroundColor(element)
            style = Paint.Style.STROKE
            strokeWidth = element.strokeWidthDots.coerceIn(1f, 32f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = android.graphics.Path()
        var drawing = false
        var index = 0
        while (index + 1 < element.drawingPoints.size) {
            val nx = element.drawingPoints[index]
            val ny = element.drawingPoints[index + 1]
            if (nx < 0f || ny < 0f) {
                drawing = false
            } else {
                val px = element.x + nx.coerceIn(0f, 1f) * element.width
                val py = element.y + yOffset + ny.coerceIn(0f, 1f) * element.height
                if (drawing) path.lineTo(px, py) else { path.moveTo(px, py); drawing = true }
            }
            index += 2
        }
        canvas.drawPath(path, paint)
    }

    private fun regularPolygon(rect: RectF, sides: Int): android.graphics.Path = android.graphics.Path().apply {
        val radiusX = rect.width() / 2f
        val radiusY = rect.height() / 2f
        repeat(sides) { index ->
            val angle = -PI / 2.0 + index * 2.0 * PI / sides
            val x = rect.centerX() + cos(angle).toFloat() * radiusX
            val y = rect.centerY() + sin(angle).toFloat() * radiusY
            if (index == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    /** Direction-specific normalized geometry keeps arrows inside non-square element bounds. */
    private fun arrowPath(rect: RectF, kind: ShapeKind): android.graphics.Path {
        val points = when (kind) {
            ShapeKind.ARROW_LEFT -> listOf(
                1f to 0.28f, 0.42f to 0.28f, 0.42f to 0f, 0f to 0.5f,
                0.42f to 1f, 0.42f to 0.72f, 1f to 0.72f,
            )
            ShapeKind.ARROW_UP -> listOf(
                0.28f to 1f, 0.28f to 0.42f, 0f to 0.42f, 0.5f to 0f,
                1f to 0.42f, 0.72f to 0.42f, 0.72f to 1f,
            )
            ShapeKind.ARROW_DOWN -> listOf(
                0.28f to 0f, 0.28f to 0.58f, 0f to 0.58f, 0.5f to 1f,
                1f to 0.58f, 0.72f to 0.58f, 0.72f to 0f,
            )
            else -> listOf(
                0f to 0.28f, 0.58f to 0.28f, 0.58f to 0f, 1f to 0.5f,
                0.58f to 1f, 0.58f to 0.72f, 0f to 0.72f,
            )
        }
        return android.graphics.Path().apply {
            points.forEachIndexed { index, (x, y) ->
                val px = rect.left + rect.width() * x
                val py = rect.top + rect.height() * y
                if (index == 0) moveTo(px, py) else lineTo(px, py)
            }
            close()
        }
    }

    private fun drawTable(
        context: Context,
        canvas: Canvas,
        element: LabelElement,
        yOffset: Int,
        warnings: MutableList<String>,
    ) {
        val rows = element.tableRows.coerceIn(1, 12)
        val columns = element.tableColumns.coerceIn(1, 8)
        val left = element.x.toFloat()
        val top = (element.y + yOffset).toFloat()
        val cellWidth = element.width.toFloat() / columns
        val cellHeight = element.height.toFloat() / rows
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = foregroundColor(element)
            style = Paint.Style.STROKE
            strokeWidth = element.strokeWidthDots.coerceIn(1f, 16f)
        }
        canvas.drawRect(left, top, left + element.width, top + element.height, linePaint)
        for (column in 1 until columns) {
            val x = left + cellWidth * column
            canvas.drawLine(x, top, x, top + element.height, linePaint)
        }
        for (row in 1 until rows) {
            val y = top + cellHeight * row
            canvas.drawLine(left, y, left + element.width, y, linePaint)
        }

        val data = element.tableData.lines().map { line -> line.split('|') }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = foregroundColor(element)
            textSize = element.fontSizeDots.coerceIn(8f, max(9f, cellHeight * 0.58f))
        }
        val bodyTypeface = resolveTypeface(
            context,
            element.fontFamily,
            element.fontWeight,
            element.italic,
            if (element.italic) Typeface.ITALIC else Typeface.NORMAL,
        )
        val headerTypeface = resolveTypeface(
            context,
            element.fontFamily,
            700,
            element.italic,
            if (element.italic) Typeface.BOLD_ITALIC else Typeface.BOLD,
        )
        bodyTypeface.warning?.let(warnings::add)
        headerTypeface.warning?.let(warnings::add)
        for (row in 0 until rows) for (column in 0 until columns) {
            val value = data.getOrNull(row)?.getOrNull(column).orEmpty()
            if (value.isBlank()) continue
            textPaint.typeface = if (element.tableHeader && row == 0) headerTypeface.typeface else bodyTypeface.typeface
            val cellLeft = left + column * cellWidth
            val cellTop = top + row * cellHeight
            canvas.save()
            canvas.clipRect(cellLeft + 2f, cellTop + 1f, cellLeft + cellWidth - 2f, cellTop + cellHeight - 1f)
            val available = (cellWidth - 8f).coerceAtLeast(1f)
            var shown = value
            while (shown.length > 1 && textPaint.measureText(shown) > available) shown = shown.dropLast(1)
            if (shown != value && shown.length > 1) shown = shown.dropLast(1) + "…"
            val baseline = cellTop + (cellHeight - (textPaint.descent() + textPaint.ascent())) / 2f
            canvas.drawText(shown, cellLeft + 4f, baseline, textPaint)
            canvas.restore()
        }
    }

    private fun drawMissingImage(canvas: Canvas, element: LabelElement, yOffset: Int) {
        val rect = RectF(element.x.toFloat(), (element.y + yOffset).toFloat(), element.right().toFloat(), (element.bottom() + yOffset).toFloat())
        if (element.invert) canvas.drawRect(rect, Paint().apply { color = Color.BLACK; style = Paint.Style.FILL })
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = foregroundColor(element); style = Paint.Style.STROKE; strokeWidth = 2f }
        canvas.drawRect(rect, paint)
        canvas.drawLine(rect.left, rect.top, rect.right, rect.bottom, paint)
        canvas.drawLine(rect.right, rect.top, rect.left, rect.bottom, paint)
    }

    private data class TypefaceResolution(val typeface: Typeface, val warning: String? = null)

    private fun resolveTypeface(
        context: Context,
        familyKey: String,
        weight: Int,
        italic: Boolean,
        legacyStyle: Int,
    ): TypefaceResolution {
        val bundledId = when (familyKey) {
            PrintFontCatalog.MA_SHAN_ZHENG -> R.font.ma_shan_zheng
            PrintFontCatalog.LONG_CANG -> R.font.long_cang
            PrintFontCatalog.ZHI_MANG_XING -> R.font.zhi_mang_xing
            PrintFontCatalog.LIU_JIAN_MAO_CAO -> R.font.liu_jian_mao_cao
            else -> null
        }
        val local = familyKey.startsWith(UserFontStore.KEY_PREFIX)
        val localFile = if (local) UserFontStore.resolveFile(context, familyKey) else null
        val warning = when {
            local && localFile == null -> "本地字体文件缺失，已使用系统黑体；请重新导入"
            else -> null
        }
        val localTypeface = localFile?.let { file -> runCatching { Typeface.createFromFile(file) } }
        val base = when {
            localTypeface != null -> localTypeface.getOrNull()
            bundledId != null -> runCatching { ResourcesCompat.getFont(context, bundledId) }.getOrNull()
            local -> null
            else -> Typeface.create(familyKey.ifBlank { "sans-serif" }, Typeface.NORMAL)
        } ?: Typeface.create("sans-serif", Typeface.NORMAL)
        val styled = if (Build.VERSION.SDK_INT >= 28) {
            Typeface.create(base, PrintFontCatalog.normalizeWeight(weight), italic)
        } else {
            Typeface.create(base, legacyStyle)
        }
        val parseWarning = if (localTypeface?.isFailure == true) {
            "本地字体损坏，已使用系统黑体；请删除后重新导入"
        } else null
        return TypefaceResolution(styled, warning ?: parseWarning)
    }

    private fun destinationRect(sourceW: Int, sourceH: Int, targetW: Int, targetH: Int, fit: ImageFit): Rect {
        if (fit == ImageFit.STRETCH) return Rect(0, 0, targetW, targetH)
        val sourceRatio = sourceW.toFloat() / sourceH
        val targetRatio = targetW.toFloat() / targetH
        return if (fit == ImageFit.FIT) {
            if (sourceRatio > targetRatio) {
                val h = (targetW / sourceRatio).toInt()
                Rect(0, (targetH - h) / 2, targetW, (targetH + h) / 2)
            } else {
                val w = (targetH * sourceRatio).toInt()
                Rect((targetW - w) / 2, 0, (targetW + w) / 2, targetH)
            }
        } else {
            // CROP fills the destination; Canvas receives a centered source crop in a pre-sized box.
            Rect(0, 0, targetW, targetH)
        }
    }

    private fun sourceRect(sourceW: Int, sourceH: Int, targetW: Int, targetH: Int, fit: ImageFit): Rect {
        if (fit != ImageFit.CROP) return Rect(0, 0, sourceW, sourceH)
        val sourceRatio = sourceW.toFloat() / sourceH
        val targetRatio = targetW.toFloat() / targetH
        return if (sourceRatio > targetRatio) {
            val croppedW = (sourceH * targetRatio).toInt().coerceAtLeast(1)
            val left = (sourceW - croppedW) / 2
            Rect(left, 0, left + croppedW, sourceH)
        } else {
            val croppedH = (sourceW / targetRatio).toInt().coerceAtLeast(1)
            val top = (sourceH - croppedH) / 2
            Rect(0, top, sourceW, top + croppedH)
        }
    }

    private fun blackOnly(source: Bitmap, foreground: Int = Color.BLACK): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        for (index in pixels.indices) {
            val color = pixels[index]
            val gray = (Color.red(color) * 54 + Color.green(color) * 183 + Color.blue(color) * 19) shr 8
            pixels[index] = if (gray < 128 && Color.alpha(color) > 16) foreground else Color.TRANSPARENT
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun thresholdInPlace(bitmap: Bitmap, threshold: Int) {
        val width = bitmap.width
        val row = IntArray(width)
        val pivot = threshold.coerceIn(1, 254)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in row.indices) {
                val color = row[x]
                val gray = (Color.red(color) * 54 + Color.green(color) * 183 + Color.blue(color) * 19) shr 8
                row[x] = if (Color.alpha(color) > 16 && gray < pivot) Color.BLACK else Color.WHITE
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
    }

    private fun drawElementBackground(canvas: Canvas, element: LabelElement, yOffset: Int) {
        canvas.drawRect(
            element.x.toFloat(),
            (element.y + yOffset).toFloat(),
            element.right().toFloat(),
            (element.bottom() + yOffset).toFloat(),
            Paint().apply { color = Color.BLACK; style = Paint.Style.FILL },
        )
    }

    private fun foregroundColor(element: LabelElement): Int = if (element.invert) Color.WHITE else Color.BLACK

    private fun applyTextEnhancement(bitmap: Bitmap, mode: TextEnhancementMode, foreground: Int) {
        if (mode == TextEnhancementMode.NONE) return
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val threshold = when (mode) {
            TextEnhancementMode.PIXEL_CRISP -> 128
            TextEnhancementMode.EDGE_CLEAN -> 152
            TextEnhancementMode.STROKE_BALANCE -> 120
            TextEnhancementMode.THIN_TEXT_RESCUE -> 136
            TextEnhancementMode.MAX_CLARITY -> 112
            TextEnhancementMode.NONE -> 1
        }
        var mask = BooleanArray(pixels.size) { Color.alpha(pixels[it]) >= threshold }
        mask = when (mode) {
            TextEnhancementMode.PIXEL_CRISP -> mask
            TextEnhancementMode.EDGE_CLEAN -> removeIsolated(mask, width, height, 3)
            TextEnhancementMode.STROKE_BALANCE -> erode(dilate(mask, width, height), width, height)
            TextEnhancementMode.THIN_TEXT_RESCUE -> dilate(mask, width, height)
            TextEnhancementMode.MAX_CLARITY -> removeIsolated(
                erode(dilate(mask, width, height), width, height),
                width,
                height,
                2,
            )
            TextEnhancementMode.NONE -> mask
        }
        for (index in pixels.indices) pixels[index] = if (mask[index]) foreground else Color.TRANSPARENT
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun dilate(source: BooleanArray, width: Int, height: Int): BooleanArray =
        BooleanArray(source.size) { index ->
            val x = index % width
            val y = index / width
            neighborCount(source, x, y, width, height) > 0
        }

    private fun erode(source: BooleanArray, width: Int, height: Int): BooleanArray =
        BooleanArray(source.size) { index ->
            val x = index % width
            val y = index / width
            neighborCount(source, x, y, width, height) == neighborCellCount(x, y, width, height)
        }

    private fun removeIsolated(
        source: BooleanArray,
        width: Int,
        height: Int,
        minimumNeighbors: Int,
    ): BooleanArray = BooleanArray(source.size) { index ->
        if (!source[index]) false else {
            val x = index % width
            val y = index / width
            neighborCount(source, x, y, width, height) >= minimumNeighbors
        }
    }

    private fun neighborCount(source: BooleanArray, x: Int, y: Int, width: Int, height: Int): Int {
        var count = 0
        val startX = (x - 1).coerceAtLeast(0)
        val endX = (x + 1).coerceAtMost(width - 1)
        val startY = (y - 1).coerceAtLeast(0)
        val endY = (y + 1).coerceAtMost(height - 1)
        for (ny in startY..endY) for (nx in startX..endX) {
            if (source[ny * width + nx]) count++
        }
        return count
    }

    private fun neighborCellCount(x: Int, y: Int, width: Int, height: Int): Int =
        ((x + 1).coerceAtMost(width - 1) - (x - 1).coerceAtLeast(0) + 1) *
            ((y + 1).coerceAtMost(height - 1) - (y - 1).coerceAtLeast(0) + 1)

    private const val TEXT_ENHANCEMENT_STRIP_DOTS = 512
}

internal fun fittedContinuousTextHeight(currentHeight: Int, measuredHeight: Int, y: Int): Int {
    val available = (MAX_DOCUMENT_HEIGHT_DOTS - y.coerceAtLeast(0)).coerceAtLeast(MIN_ELEMENT_DOTS)
    return max(currentHeight, measuredHeight).coerceIn(MIN_ELEMENT_DOTS, available)
}
