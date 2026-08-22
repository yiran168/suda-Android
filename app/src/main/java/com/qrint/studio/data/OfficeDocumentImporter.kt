package com.qrint.studio.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import com.qrint.studio.model.DitherMode
import com.qrint.studio.model.EditorFactories
import com.qrint.studio.model.ElementKind
import com.qrint.studio.model.ImageFit
import com.qrint.studio.model.LabelDocument
import com.qrint.studio.model.LabelElement
import com.qrint.studio.model.MAX_DOCUMENT_HEIGHT_DOTS
import com.qrint.studio.model.PaperMode
import com.qrint.studio.model.PaperSettings
import com.qrint.studio.model.ShapeKind
import com.qrint.studio.model.TextAlignment
import com.qrint.studio.render.LabelRenderer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.apache.poi.hslf.usermodel.HSLFGroupShape
import org.apache.poi.hslf.usermodel.HSLFPictureShape
import org.apache.poi.hslf.usermodel.HSLFShape
import org.apache.poi.hslf.usermodel.HSLFSlideShow
import org.apache.poi.hslf.usermodel.HSLFTextShape
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.usermodel.Paragraph
import org.apache.poi.hwpf.usermodel.Table
import org.w3c.dom.Element
import org.w3c.dom.Node

/** Offline Office importer. Word/Excel stay editable; PowerPoint is flattened per slide for layout fidelity. */
object OfficeDocumentImporter {
    private const val MAX_FILE_BYTES = 96 * 1024 * 1024
    private const val MAX_UNCOMPRESSED_BYTES = 160 * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 4_096
    private const val MAX_PAGES = 200

    fun importDocx(
        context: Context,
        uri: Uri,
        sourceName: String,
        paper: PaperSettings,
    ): Result<ImportedDocumentBatch> = runCatching {
        val zip = readZip(readUri(context, uri))
        val documentXml = zip["word/document.xml"] ?: error("不是有效的 Word DOCX（缺少 word/document.xml）")
        val relationships = relationshipMap(zip["word/_rels/document.xml.rels"], "word")
        val body = parseXml(documentXml).documentElement.firstDescendant("body") ?: error("Word 正文为空")
        val builder = FlowDocumentBuilder(sourceName, paper)
        body.childElements().forEach { block ->
            when (block.local()) {
                "p" -> {
                    if (block.firstDescendant("pageBreakBefore") != null) builder.forcePageBreak()
                    addWordParagraph(context, zip, relationships, block, builder)
                    val explicitPageBreak = block.descendants("br").any { it.attribute("type") == "page" } ||
                        block.firstDescendant("lastRenderedPageBreak") != null
                    if (explicitPageBreak) builder.forcePageBreak()
                }
                "tbl" -> addWordTable(block, builder)
            }
        }
        ImportedDocumentBatch(sourceName, builder.finish())
    }

    fun importPptx(
        context: Context,
        uri: Uri,
        sourceName: String,
        paper: PaperSettings,
    ): Result<ImportedDocumentBatch> = runCatching {
        val zip = readZip(readUri(context, uri))
        val slideEntries = zip.keys.filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
            .sortedBy { it.substringAfterLast("slide").substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE }
            .take(MAX_PAGES)
        require(slideEntries.isNotEmpty()) { "PPTX 中没有幻灯片" }
        val (slideWidth, slideHeight) = presentationSize(zip["ppt/presentation.xml"])
        val savedPages = mutableListOf<Uri>()
        try {
            val documents = slideEntries.mapIndexed { pageIndex, slidePath ->
                val slideRoot = parseXml(zip.getValue(slidePath)).documentElement
                val relPath = slidePath.replace("slides/", "slides/_rels/") + ".rels"
                val relationships = relationshipMap(zip[relPath], "ppt/slides")
                val editableSlide = pptSlideDocument(
                    context,
                    zip,
                    relationships,
                    slideRoot,
                    sourceName,
                    pageIndex + 1,
                    slideEntries.size,
                    slideWidth,
                    slideHeight,
                    paper,
                )
                flattenPowerPointSlide(
                    context = context,
                    editableSlide = editableSlide,
                    targetHeight = pptTargetPageHeight(slideWidth, slideHeight, paper),
                ).also { document -> document.singleImageUri()?.let(savedPages::add) }
            }
            ImportedDocumentBatch(sourceName, documents)
        } catch (error: Throwable) {
            savedPages.forEach { CapturedMediaStore.delete(context, it) }
            throw error
        }
    }

    /**
     * Imports binary Word 97-2003/WPS documents with the Android-adapted Apache POI reader.
     * Paragraphs, tables, page breaks, basic run styling and supported raster pictures remain
     * separate editable elements. Unsupported OLE objects are deliberately ignored instead of
     * crashing the whole import.
     */
    fun importLegacyWord(
        context: Context,
        uri: Uri,
        sourceName: String,
        paper: PaperSettings,
    ): Result<ImportedDocumentBatch> = runCatching {
        ByteArrayInputStream(readUri(context, uri)).use { stream ->
            HWPFDocument(stream).use { document ->
                val range = document.range
                val pictures = document.picturesTable
                val builder = FlowDocumentBuilder(sourceName, paper)
                var paragraphIndex = 0
                while (paragraphIndex < range.numParagraphs()) {
                    val paragraph = range.getParagraph(paragraphIndex)
                    if (paragraph.pageBreakBefore()) builder.forcePageBreak()
                    if (paragraph.isInTable && paragraph.tableLevel == 1) {
                        val table = range.getTable(paragraph)
                        addLegacyWordTable(table, builder)
                        val tableEnd = table.endOffset
                        do {
                            paragraphIndex++
                        } while (
                            paragraphIndex < range.numParagraphs() &&
                            range.getParagraph(paragraphIndex).startOffset < tableEnd
                        )
                        continue
                    }

                    addLegacyWordParagraph(paragraph, builder)
                    for (runIndex in 0 until paragraph.numCharacterRuns()) {
                        val run = paragraph.getCharacterRun(runIndex)
                        if (!pictures.hasPicture(run)) continue
                        val picture = runCatching { pictures.extractPicture(run, true) }.getOrNull() ?: continue
                        val bytes = picture.content ?: continue
                        val extension = picture.suggestFileExtension().orEmpty().lowercase()
                        if (extension !in SUPPORTED_RASTER_EXTENSIONS) continue
                        val imageUri = CapturedMediaStore.saveImageBytes(
                            context,
                            bytes,
                            extension,
                            "legacy-word-media",
                        ).getOrNull() ?: continue
                        builder.addImage(imageUri, bytes)
                    }
                    if (paragraph.text().contains('\u000c')) builder.forcePageBreak()
                    paragraphIndex++
                }
                ImportedDocumentBatch(sourceName, builder.finish())
            }
        }
    }

    /** One editable document per slide (or continuation page on very short labels). */
    fun importLegacyPowerPoint(
        context: Context,
        uri: Uri,
        sourceName: String,
        paper: PaperSettings,
    ): Result<ImportedDocumentBatch> = runCatching {
        val savedPages = mutableListOf<Uri>()
        try {
            ByteArrayInputStream(readUri(context, uri)).use { stream ->
                HSLFSlideShow(stream).use { show ->
                val slides = show.slides.take(MAX_PAGES)
                require(slides.isNotEmpty()) { "旧版 PowerPoint/WPS 文稿中没有幻灯片" }
                val legacyPageSize = show.pageSize
                val targetPageHeight = fixedLayoutTargetPageHeight(
                    legacyPageSize.width.toDouble(),
                    legacyPageSize.height.toDouble(),
                    paper,
                )
                val imported = slides.flatMapIndexed { slideIndex, slide ->
                    val slideTitle = "$sourceName · 幻灯片 ${slideIndex + 1}/${slides.size}"
                    val builder = FlowDocumentBuilder(slideTitle, paper)
                    var added = false
                    slide.shapes.forEach { shape ->
                        if (addLegacyPowerPointShape(context, shape, builder)) added = true
                    }
                    if (!added) {
                        builder.addParagraph(
                            text = "（空白幻灯片）",
                            fontSize = 24f,
                            weight = 400,
                            italic = false,
                            underline = false,
                            alignment = TextAlignment.CENTER,
                            afterDots = 0,
                        )
                    }
                    builder.finish().mapIndexed { continuation, document ->
                        val titled = document.copy(
                            title = if (continuation == 0) slideTitle else "$slideTitle · 续 ${continuation + 1}",
                        )
                        flattenPowerPointSlide(context, titled, targetPageHeight)
                            .also { document -> document.singleImageUri()?.let(savedPages::add) }
                    }
                }
                require(imported.size <= MAX_PAGES) { "PowerPoint 分页超过 $MAX_PAGES 页，请拆分文稿" }
                ImportedDocumentBatch(sourceName, imported)
                }
            }
        } catch (error: Throwable) {
            savedPages.forEach { CapturedMediaStore.delete(context, it) }
            throw error
        }
    }

    fun importSpreadsheet(
        context: Context,
        uri: Uri,
        sourceName: String,
        paper: PaperSettings,
    ): Result<ImportedDocumentBatch> = runCatching {
        val bytes = readUri(context, uri)
        val workbook = VariableDataParser.parseWorkbook(sourceName, ByteArrayInputStream(bytes))
        val documents = mutableListOf<LabelDocument>()
        val contentWidth = paper.contentWidthDots()
        val top = paper.mmToDots(paper.topPaddingMm)
        val usableHeight = if (paper.mode == PaperMode.LABEL) {
            (paper.fixedHeightDots() - top - paper.mmToDots(paper.bottomPaddingMm)).coerceAtLeast(36)
        } else MAX_DOCUMENT_HEIGHT_DOTS - top - paper.mmToDots(paper.bottomPaddingMm)
        val rowsPerPage = (usableHeight / 38).coerceIn(2, 12) - 1
        workbook.sheets.forEach { sheet ->
            sheet.headers.chunked(8).forEachIndexed { columnPage, headers ->
                sheet.rows.chunked(rowsPerPage.coerceAtLeast(1)).forEachIndexed { rowPage, rows ->
                    if (documents.size >= MAX_PAGES) error("Excel 分页超过 $MAX_PAGES 页，请拆分工作簿")
                    val tableRows = listOf(headers) + rows.map { row -> headers.map { row[it].orEmpty() } }
                    val data = tableRows.joinToString("\n") { cells -> cells.joinToString("|") { sanitizeCell(it) } }
                    val table = EditorFactories.tableElement(x = paper.printableStartX(), y = top).copy(
                        width = contentWidth,
                        height = min(usableHeight, tableRows.size * 38),
                        tableRows = tableRows.size,
                        tableColumns = headers.size,
                        tableData = data,
                        tableHeader = true,
                        fontSizeDots = 18f,
                    )
                    val suffix = buildString {
                        append(sheet.sheetName)
                        if (sheet.headers.size > 8) append(" · 列组 ${columnPage + 1}")
                        if (sheet.rows.size > rowsPerPage) append(" · 第 ${rowPage + 1} 页")
                    }
                    documents += EditorFactories.blankDocument("${sourceName.baseName()} · $suffix", paper).copy(
                        category = "办公管理",
                        elements = listOf(table),
                    ).normalized()
                }
            }
        }
        require(documents.isNotEmpty()) { "Excel / WPS 表格没有可打印内容" }
        ImportedDocumentBatch(sourceName, documents)
    }

    private fun addLegacyWordParagraph(paragraph: Paragraph, builder: FlowDocumentBuilder) {
        val text = paragraph.text().cleanLegacyOfficeText()
        val firstRun = (0 until paragraph.numCharacterRuns())
            .asSequence()
            .map(paragraph::getCharacterRun)
            .firstOrNull { it.text().cleanLegacyOfficeText().isNotBlank() }
        val fontSize = firstRun?.fontSize
            ?.takeIf { it > 0 }
            ?.let { halfPoints -> halfPoints / 2f * builder.paper.dpi / 72f }
            ?.coerceIn(12f, 96f)
            ?: 28f
        if (text.isBlank()) {
            builder.addVerticalSpace((fontSize * 0.55f).roundToInt())
            return
        }
        val alignment = when (paragraph.justification) {
            1 -> TextAlignment.CENTER
            2 -> TextAlignment.RIGHT
            else -> TextAlignment.LEFT
        }
        val afterDots = (paragraph.spacingAfter / 20f * builder.paper.dpi / 72f)
            .roundToInt().coerceIn(4, 36)
        builder.addParagraph(
            text = if (paragraph.isInList) "• $text" else text,
            fontSize = fontSize,
            weight = if (firstRun?.isBold == true) 700 else 400,
            italic = firstRun?.isItalic == true,
            underline = (firstRun?.underlineCode ?: 0) != 0,
            alignment = alignment,
            fontFamily = firstRun?.fontName.orEmpty().ifBlank { "sans-serif" },
            afterDots = afterDots,
        )
    }

    private fun addLegacyWordTable(table: Table, builder: FlowDocumentBuilder) {
        val rows = (0 until table.numRows()).map { rowIndex ->
            val row = table.getRow(rowIndex)
            (0 until row.numCells()).map { cellIndex ->
                row.getCell(cellIndex).text().cleanLegacyOfficeText()
            }
        }.filter { row -> row.any(String::isNotBlank) }
        if (rows.isEmpty()) return
        val columnCount = rows.maxOf { it.size }.coerceIn(1, 8)
        rows.chunked(builder.tableRowCapacity()).forEach { pageRows ->
            val data = pageRows.joinToString("\n") { row ->
                List(columnCount) { index -> sanitizeCell(row.getOrElse(index) { "" }) }.joinToString("|")
            }
            builder.addTable(pageRows.size, columnCount, data)
        }
    }

    private fun addLegacyPowerPointShape(
        context: Context,
        shape: HSLFShape,
        builder: FlowDocumentBuilder,
    ): Boolean = when (shape) {
        is HSLFGroupShape -> shape.shapes.fold(false) { added, child ->
            addLegacyPowerPointShape(context, child, builder) || added
        }
        is HSLFTextShape -> {
            var added = false
            shape.textParagraphs.forEach { paragraph ->
                val text = paragraph.textRuns.joinToString("") { it.rawText }.cleanLegacyOfficeText()
                if (text.isBlank()) return@forEach
                val firstRun = paragraph.textRuns.firstOrNull { it.rawText.isNotBlank() }
                val fontSize = ((firstRun?.fontSize ?: paragraph.defaultFontSize ?: 16.0) * builder.paper.dpi / 72.0)
                    .toFloat().coerceIn(12f, 96f)
                val alignment = when (paragraph.textAlign?.name) {
                    "CENTER" -> TextAlignment.CENTER
                    "RIGHT" -> TextAlignment.RIGHT
                    else -> TextAlignment.LEFT
                }
                val bullet = paragraph.bulletChar?.let { "$it " }.orEmpty()
                builder.addParagraph(
                    text = bullet + text,
                    fontSize = fontSize,
                    weight = if (firstRun?.isBold == true) 700 else 400,
                    italic = firstRun?.isItalic == true,
                    underline = firstRun?.isUnderlined == true,
                    alignment = alignment,
                    fontFamily = firstRun?.fontFamily.orEmpty().ifBlank { "sans-serif" },
                    afterDots = ((paragraph.spaceAfter ?: 4.0) * builder.paper.dpi / 72.0)
                        .roundToInt().coerceIn(4, 36),
                )
                added = true
            }
            added
        }
        is HSLFPictureShape -> {
            val picture = runCatching { shape.pictureData }.getOrNull() ?: return false
            val extension = picture.contentType.toRasterExtension() ?: return false
            val bytes = runCatching { picture.data }.getOrNull() ?: return false
            val imageUri = CapturedMediaStore.saveImageBytes(
                context,
                bytes,
                extension,
                "legacy-ppt-media",
            ).getOrNull() ?: return false
            builder.addImage(imageUri, bytes)
            true
        }
        else -> false
    }

    private fun addWordParagraph(
        context: Context,
        zip: Map<String, ByteArray>,
        relationships: Map<String, String>,
        paragraph: Element,
        builder: FlowDocumentBuilder,
    ) {
        val rawText = collectOfficeText(paragraph).trimEnd()
        val paragraphProperties = paragraph.firstDescendant("pPr")
        val styleName = paragraphProperties?.firstDescendant("pStyle")?.attribute("val").orEmpty()
        val firstRun = paragraph.firstDescendant("r")
        val runProperties = firstRun?.firstDescendant("rPr")
        val sizeHalfPoints = runProperties?.firstDescendant("sz")?.attribute("val")?.toFloatOrNull()
        val headingLevel = Regex("(?:Heading|标题)\\s*([1-6])", RegexOption.IGNORE_CASE)
            .find(styleName)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val fontSize = when {
            sizeHalfPoints != null -> sizeHalfPoints / 2f * builder.paper.dpi / 72f
            headingLevel != null -> (40f - headingLevel * 3f).coerceAtLeast(24f)
            else -> 28f
        }.coerceIn(12f, 96f)
        val bold = runProperties?.firstDescendant("b") != null || headingLevel != null
        val italic = runProperties?.firstDescendant("i") != null
        val underline = runProperties?.firstDescendant("u") != null
        val alignment = when (paragraphProperties?.firstDescendant("jc")?.attribute("val")) {
            "center" -> TextAlignment.CENTER
            "right", "end" -> TextAlignment.RIGHT
            else -> TextAlignment.LEFT
        }
        val numbered = paragraphProperties?.firstDescendant("numPr") != null
        if (rawText.isNotBlank()) {
            builder.addParagraph(
                text = if (numbered) "• $rawText" else rawText,
                fontSize = fontSize,
                weight = if (bold) 700 else 400,
                italic = italic,
                underline = underline,
                alignment = alignment,
                afterDots = if (headingLevel != null) 10 else 5,
            )
        } else if (paragraph.descendants("blip").isEmpty()) {
            builder.addVerticalSpace((fontSize * 0.55f).roundToInt())
        }
        paragraph.descendants("blip").forEach { blip ->
            val relationId = blip.attribute("embed")
            val target = relationships[relationId] ?: return@forEach
            val bytes = zip[target] ?: return@forEach
            val uri = CapturedMediaStore.saveImageBytes(context, bytes, target.substringAfterLast('.', "png"), "docx-media")
                .getOrNull() ?: return@forEach
            builder.addImage(uri, bytes)
        }
    }

    private fun addWordTable(table: Element, builder: FlowDocumentBuilder) {
        val rows = table.childElements().filter { it.local() == "tr" }.map { row ->
            row.childElements().filter { it.local() == "tc" }.map { collectOfficeText(it).trim() }
        }.filter { row -> row.any(String::isNotBlank) }
        if (rows.isEmpty()) return
        val columnCount = rows.maxOf { it.size }.coerceIn(1, 8)
        rows.chunked(builder.tableRowCapacity()).forEach { pageRows ->
            val data = pageRows.joinToString("\n") { row ->
                List(columnCount) { index -> sanitizeCell(row.getOrElse(index) { "" }) }.joinToString("|")
            }
            builder.addTable(pageRows.size, columnCount, data)
        }
    }

    private fun pptSlideDocument(
        context: Context,
        zip: Map<String, ByteArray>,
        relationships: Map<String, String>,
        root: Element,
        sourceName: String,
        page: Int,
        pages: Int,
        slideWidth: Long,
        slideHeight: Long,
        paper: PaperSettings,
    ): LabelDocument {
        val contentWidth = paper.contentWidthDots().coerceAtLeast(32)
        val availableHeight = if (paper.mode == PaperMode.LABEL) paper.fixedHeightDots() else MAX_DOCUMENT_HEIGHT_DOTS
        val scale = min(contentWidth.toDouble() / slideWidth, availableHeight.toDouble() / slideHeight)
        val startX = paper.printableStartX()
        val elements = mutableListOf<LabelElement>()
        root.descendantsInDocumentOrder(setOf("sp", "pic")).forEach { item ->
            when (item.local()) {
                "sp" -> {
                    val box = pptBox(item, scale, startX) ?: return@forEach
                    val text = collectOfficeText(item).trim()
                    if (text.isNotBlank()) {
                        val runProperties = item.firstDescendant("rPr") ?: item.firstDescendant("defRPr")
                        val pointSize = runProperties?.attribute("sz")?.toFloatOrNull()?.div(100f)
                        val alignment = when (item.firstDescendant("pPr")?.attribute("algn")) {
                            "ctr" -> TextAlignment.CENTER
                            "r" -> TextAlignment.RIGHT
                            else -> TextAlignment.LEFT
                        }
                        elements += EditorFactories.textElement(box.left, box.top).copy(
                            width = box.width,
                            height = box.height,
                            rotation = box.rotation,
                            text = text,
                            fontSizeDots = pptPointSizeToOutputDots(pointSize ?: 16f, scale),
                            fontWeight = if (runProperties?.attribute("b") in setOf("1", "true")) 700 else 400,
                            italic = runProperties?.attribute("i") in setOf("1", "true"),
                            underline = runProperties?.attribute("u").orEmpty().let { it.isNotBlank() && it != "none" },
                            textAlignment = alignment,
                        )
                    } else {
                        val preset = item.firstDescendant("prstGeom")?.attribute("prst")
                        val shapeKind = when (preset) {
                            "ellipse" -> ShapeKind.ELLIPSE
                            "roundRect" -> ShapeKind.ROUNDED_RECTANGLE
                            "triangle" -> ShapeKind.TRIANGLE
                            "diamond" -> ShapeKind.DIAMOND
                            "line" -> ShapeKind.LINE
                            else -> null
                        }
                        if (shapeKind != null) {
                            elements += EditorFactories.shapeElement(shapeKind, box.left, box.top).copy(
                                width = box.width,
                                height = box.height,
                                rotation = box.rotation,
                            )
                        }
                    }
                }
                "pic" -> {
                    val relationId = item.firstDescendant("blip")?.attribute("embed").orEmpty()
                    val target = relationships[relationId] ?: return@forEach
                    val bytes = zip[target] ?: return@forEach
                    val box = pptBox(item, scale, startX) ?: return@forEach
                    val uri = CapturedMediaStore.saveImageBytes(
                        context,
                        bytes,
                        target.substringAfterLast('.', "png"),
                        "pptx-media",
                    ).getOrNull() ?: return@forEach
                    elements += EditorFactories.imageElement(uri.toString(), box.left, box.top).copy(
                        width = box.width,
                        height = box.height,
                        rotation = box.rotation,
                        imageFit = ImageFit.STRETCH,
                    )
                }
            }
        }
        return EditorFactories.blankDocument("${sourceName.baseName()} · 幻灯片 $page/$pages", paper).copy(
            category = "办公管理",
            elements = elements.ifEmpty {
                listOf(EditorFactories.textElement(startX, paper.mmToDots(paper.topPaddingMm)).copy(text = "（空白幻灯片）", width = contentWidth))
            },
        ).normalized()
    }

    private data class PptBox(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val rotation: Float,
    )

    private data class PptRawBox(
        val left: Double,
        val top: Double,
        val width: Double,
        val height: Double,
    )

    private fun pptBox(element: Element, scale: Double, startX: Int): PptBox? {
        val transform = element.firstDescendant("xfrm") ?: return null
        val offset = transform.firstDescendant("off") ?: return null
        val extent = transform.firstDescendant("ext") ?: return null
        val x = offset.attribute("x").toLongOrNull() ?: return null
        val y = offset.attribute("y").toLongOrNull() ?: return null
        val width = extent.attribute("cx").toLongOrNull() ?: return null
        val height = extent.attribute("cy").toLongOrNull() ?: return null
        var raw = PptRawBox(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())
        var ancestor = element.parentNode
        while (ancestor is Element) {
            if (ancestor.local() == "grpSp") raw = mapPptGroupBox(raw, ancestor)
            ancestor = ancestor.parentNode
        }
        return PptBox(
            left = startX + (raw.left * scale).roundToInt(),
            top = (raw.top * scale).roundToInt(),
            width = (raw.width * scale).roundToInt().coerceAtLeast(16),
            height = (raw.height * scale).roundToInt().coerceAtLeast(16),
            rotation = transform.attribute("rot").toFloatOrNull()?.div(60_000f) ?: 0f,
        )
    }

    /** Maps child coordinates through nested PPTX group transforms into slide coordinates. */
    private fun mapPptGroupBox(child: PptRawBox, group: Element): PptRawBox {
        val properties = group.childElements().firstOrNull { it.local() == "grpSpPr" } ?: return child
        val transform = properties.firstDescendant("xfrm") ?: return child
        val offset = transform.childElements().firstOrNull { it.local() == "off" } ?: return child
        val extent = transform.childElements().firstOrNull { it.local() == "ext" } ?: return child
        val childOffset = transform.childElements().firstOrNull { it.local() == "chOff" }
        val childExtent = transform.childElements().firstOrNull { it.local() == "chExt" }
        val offX = offset.attribute("x").toDoubleOrNull() ?: return child
        val offY = offset.attribute("y").toDoubleOrNull() ?: return child
        val extX = extent.attribute("cx").toDoubleOrNull() ?: return child
        val extY = extent.attribute("cy").toDoubleOrNull() ?: return child
        val childX = childOffset?.attribute("x")?.toDoubleOrNull() ?: 0.0
        val childY = childOffset?.attribute("y")?.toDoubleOrNull() ?: 0.0
        val childWidth = childExtent?.attribute("cx")?.toDoubleOrNull()?.takeIf { it != 0.0 } ?: extX
        val childHeight = childExtent?.attribute("cy")?.toDoubleOrNull()?.takeIf { it != 0.0 } ?: extY
        val scaleX = extX / childWidth
        val scaleY = extY / childHeight
        return PptRawBox(
            left = offX + (child.left - childX) * scaleX,
            top = offY + (child.top - childY) * scaleY,
            width = child.width * scaleX,
            height = child.height * scaleY,
        )
    }

    private fun presentationSize(bytes: ByteArray?): Pair<Long, Long> {
        if (bytes == null) return 12_192_000L to 6_858_000L
        val size = parseXml(bytes).documentElement.firstDescendant("sldSz") ?: return 12_192_000L to 6_858_000L
        return (size.attribute("cx").toLongOrNull() ?: 12_192_000L) to
            (size.attribute("cy").toLongOrNull() ?: 6_858_000L)
    }

    private fun pptTargetPageHeight(slideWidth: Long, slideHeight: Long, paper: PaperSettings): Int {
        return fixedLayoutTargetPageHeight(slideWidth.toDouble(), slideHeight.toDouble(), paper)
    }

    private fun fixedLayoutTargetPageHeight(
        sourceWidth: Double,
        sourceHeight: Double,
        paper: PaperSettings,
    ): Int {
        require(sourceWidth > 0.0 && sourceHeight > 0.0) { "演示文稿页面尺寸无效" }
        val contentWidth = paper.contentWidthDots().coerceAtLeast(32)
        val heightLimit = if (paper.mode == PaperMode.LABEL) paper.fixedHeightDots() else MAX_DOCUMENT_HEIGHT_DOTS
        val scale = min(contentWidth / sourceWidth, heightLimit / sourceHeight)
        return if (paper.mode == PaperMode.LABEL) {
            paper.fixedHeightDots()
        } else {
            (sourceHeight * scale).roundToInt().coerceIn(64, MAX_DOCUMENT_HEIGHT_DOTS)
        }
    }

    /**
     * PowerPoint is a fixed-layout format. Flattening the reconstructed slide into one bitmap keeps
     * all text, pictures and shapes in the same coordinate system when the editor is reopened.
     */
    private fun flattenPowerPointSlide(
        context: Context,
        editableSlide: LabelDocument,
        targetHeight: Int,
    ): LabelDocument {
        val rendered = LabelRenderer.render(context, editableSlide)
        val paper = editableSlide.paper
        val contentLeft = paper.printableStartX().coerceIn(0, rendered.bitmap.width - 1)
        val contentWidth = paper.contentWidthDots()
            .coerceAtMost(rendered.bitmap.width - contentLeft)
            .coerceAtLeast(1)
        val pageHeight = targetHeight.coerceIn(1, MAX_DOCUMENT_HEIGHT_DOTS)
        val flattened = Bitmap.createBitmap(contentWidth, pageHeight, Bitmap.Config.RGB_565)
        val intermediateMedia = editableSlide.elements
            .asSequence()
            .filter { it.kind == ElementKind.IMAGE && it.imageUri.isNotBlank() }
            .mapNotNull { runCatching { Uri.parse(it.imageUri) }.getOrNull() }
            .toList()
        try {
            val canvas = Canvas(flattened)
            canvas.drawColor(Color.WHITE)
            val copiedHeight = min(pageHeight, rendered.bitmap.height)
            canvas.drawBitmap(
                rendered.bitmap,
                Rect(contentLeft, 0, contentLeft + contentWidth, copiedHeight),
                Rect(0, 0, contentWidth, copiedHeight),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            val pageUri = CapturedMediaStore.saveBitmap(context, flattened, "powerpoint-page").getOrThrow()
            return EditorFactories.blankDocument(editableSlide.title, paper).copy(
                category = "办公管理",
                elements = listOf(
                    EditorFactories.imageElement(pageUri.toString(), paper.printableStartX(), 0).copy(
                        width = contentWidth,
                        height = pageHeight,
                        imageFit = ImageFit.STRETCH,
                        ditherMode = DitherMode.THRESHOLD,
                    ),
                ),
            ).normalized()
        } finally {
            intermediateMedia.forEach { CapturedMediaStore.delete(context, it) }
            if (!flattened.isRecycled) flattened.recycle()
            if (!rendered.bitmap.isRecycled) rendered.bitmap.recycle()
        }
    }

    private class FlowDocumentBuilder(private val sourceName: String, val paper: PaperSettings) {
        private val finished = mutableListOf<LabelDocument>()
        private var elements = mutableListOf<LabelElement>()
        private val startX = paper.printableStartX()
        private val width = paper.contentWidthDots()
        private val topPadding = paper.mmToDots(paper.topPaddingMm)
        private val bottomPadding = paper.mmToDots(paper.bottomPaddingMm)
        private val limit = if (paper.mode == PaperMode.LABEL) paper.fixedHeightDots() else MAX_DOCUMENT_HEIGHT_DOTS
        private var y = topPadding

        fun addParagraph(
            text: String,
            fontSize: Float,
            weight: Int,
            italic: Boolean,
            underline: Boolean,
            alignment: TextAlignment,
            fontFamily: String = "sans-serif",
            afterDots: Int,
        ) {
            val units = (width / (fontSize * 0.52f)).toInt().coerceAtLeast(2)
            val lines = wrapTextForPrint(text, units)
            val lineHeight = ceil(fontSize * 1.24f).toInt().coerceAtLeast(16)
            var cursor = 0
            while (cursor < lines.size) {
                ensureRoom(lineHeight)
                val availableLines = ((limit - bottomPadding - y) / lineHeight).coerceAtLeast(1)
                val chunk = lines.subList(cursor, min(lines.size, cursor + availableLines))
                val height = (chunk.size * lineHeight).coerceAtLeast(16)
                elements += EditorFactories.textElement(startX, y).copy(
                    width = width,
                    height = height,
                    text = chunk.joinToString("\n"),
                    fontFamily = fontFamily,
                    fontSizeDots = fontSize,
                    fontWeight = weight,
                    italic = italic,
                    underline = underline,
                    textAlignment = alignment,
                    lineSpacingDots = (lineHeight - fontSize).coerceAtLeast(0f),
                )
                y += height + afterDots
                cursor += chunk.size
                if (cursor < lines.size) flush()
            }
        }

        fun addTable(rows: Int, columns: Int, data: String) {
            val height = (rows * 38).coerceAtLeast(38)
            ensureRoom(height)
            elements += EditorFactories.tableElement(startX, y).copy(
                width = width,
                height = min(height, limit - bottomPadding - y),
                tableRows = rows,
                tableColumns = columns,
                tableData = data,
                fontSizeDots = 18f,
            )
            y += height + 8
        }

        fun tableRowCapacity(): Int =
            ((limit - topPadding - bottomPadding) / 38).coerceIn(1, 12)

        fun addImage(uri: Uri, bytes: ByteArray) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            val naturalHeight = if (options.outWidth > 0) width * options.outHeight / options.outWidth else width * 3 / 4
            ensureRoom(48)
            val height = naturalHeight.coerceIn(48, (limit - bottomPadding - y).coerceAtLeast(48))
            elements += EditorFactories.imageElement(uri.toString(), startX, y).copy(width = width, height = height, imageFit = ImageFit.FIT)
            y += height + 8
        }

        fun addVerticalSpace(dots: Int) {
            if (y + dots >= limit - bottomPadding) flush() else y += dots.coerceAtLeast(0)
        }

        /** Preserves explicit Word page boundaries in addition to thermal-paper pagination. */
        fun forcePageBreak() = flush()

        fun finish(): List<LabelDocument> {
            flush()
            require(finished.isNotEmpty()) { "Word 文档没有可打印内容" }
            require(finished.size <= MAX_PAGES) { "Word 分页超过 $MAX_PAGES 页，请拆分文档" }
            return finished.mapIndexed { index, document ->
                document.copy(title = "${sourceName.baseName()} · ${index + 1}/${finished.size}")
            }
        }

        private fun ensureRoom(required: Int) {
            if (elements.isNotEmpty() && y + required > limit - bottomPadding) flush()
        }

        private fun flush() {
            if (elements.isEmpty()) {
                y = topPadding
                return
            }
            finished += EditorFactories.blankDocument(sourceName.baseName(), paper).copy(
                category = "办公管理",
                elements = elements,
            ).normalized()
            elements = mutableListOf()
            y = topPadding
        }
    }

    private fun readUri(context: Context, uri: Uri): ByteArray {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_FILE_BYTES) { "Office 文档超过 96 MB 限制" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: error("无法读取 Office 文档")
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                require(entries.size < MAX_ZIP_ENTRIES) { "Office 文档包含过多文件" }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_UNCOMPRESSED_BYTES) { "Office 文档解压后过大" }
                    output.write(buffer, 0, read)
                }
                entries[normalizePath(entry.name)] = output.toByteArray()
            }
        }
        return entries
    }

    private fun relationshipMap(bytes: ByteArray?, baseDirectory: String): Map<String, String> {
        if (bytes == null) return emptyMap()
        val root = parseXml(bytes).documentElement
        return root.descendants("Relationship").mapNotNull { relation ->
            val id = relation.attribute("Id")
            val target = relation.attribute("Target")
            if (id.isBlank() || target.isBlank() || target.startsWith("http", true)) null
            else id to resolvePath(baseDirectory, target)
        }.toMap()
    }

    private fun resolvePath(baseDirectory: String, target: String): String =
        normalizePath("$baseDirectory/$target")

    private fun normalizePath(path: String): String {
        val stack = ArrayDeque<String>()
        path.replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        return stack.joinToString("/")
    }

    private fun parseXml(bytes: ByteArray) = SafeXmlParser.parse(bytes, namespaceAware = true)

    private fun collectOfficeText(element: Element): String = buildString { appendOfficeText(element, this) }

    private fun appendOfficeText(node: Node, output: StringBuilder) {
        when (node.local()) {
            "t" -> output.append(node.textContent)
            "tab" -> output.append('\t')
            "br", "cr" -> output.append('\n')
            else -> for (index in 0 until node.childNodes.length) appendOfficeText(node.childNodes.item(index), output)
        }
    }

    private fun Node.local(): String = localName ?: nodeName.substringAfter(':')

    private fun Element.attribute(name: String): String {
        if (hasAttribute(name)) return getAttribute(name)
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            if ((attribute.localName ?: attribute.nodeName.substringAfter(':')) == name) return attribute.nodeValue.orEmpty()
        }
        return ""
    }

    private fun Element.childElements(): List<Element> = buildList {
        for (index in 0 until childNodes.length) (childNodes.item(index) as? Element)?.let(::add)
    }

    private fun Element.descendants(localName: String): List<Element> = buildList {
        val namespaced = getElementsByTagNameNS("*", localName)
        for (index in 0 until namespaced.length) (namespaced.item(index) as? Element)?.let(::add)
        if (isEmpty()) {
            val plain = getElementsByTagName(localName)
            for (index in 0 until plain.length) (plain.item(index) as? Element)?.let(::add)
        }
    }

    private fun Element.descendantsInDocumentOrder(localNames: Set<String>): List<Element> = buildList {
        fun visit(parent: Element) {
            parent.childElements().forEach { child ->
                if (child.local() in localNames) add(child)
                visit(child)
            }
        }
        visit(this@descendantsInDocumentOrder)
    }

    private fun Element.firstDescendant(localName: String): Element? = descendants(localName).firstOrNull()

    private fun LabelDocument.singleImageUri(): Uri? = elements.singleOrNull()
        ?.takeIf { it.kind == ElementKind.IMAGE && it.imageUri.isNotBlank() }
        ?.let { runCatching { Uri.parse(it.imageUri) }.getOrNull() }

    private fun sanitizeCell(value: String): String = value.replace('|', '¦').replace('\r', ' ').replace('\n', ' ')

    private fun String.cleanLegacyOfficeText(): String =
        replace('\u0007', ' ')
            .replace('\u000b', '\n')
            .replace('\u000c', '\n')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()

    private fun String.toRasterExtension(): String? = when (lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/gif" -> "gif"
        "image/bmp", "image/x-ms-bmp" -> "bmp"
        "image/webp" -> "webp"
        else -> null
    }

    private val SUPPORTED_RASTER_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
}

internal fun pptPointSizeToOutputDots(pointSize: Float, emuToDotsScale: Double): Float =
    (pointSize.coerceAtLeast(1f) * 12_700.0 * emuToDotsScale).toFloat().coerceIn(8f, 96f)

private fun String.baseName(): String = substringBeforeLast('.', this).ifBlank { "导入文档" }
