package com.qrint.studio.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.w3c.dom.Element
import org.w3c.dom.Node

/** Offline structured Office importer. Output is normal editable canvas elements, not screenshots. */
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
        val documents = slideEntries.mapIndexed { pageIndex, slidePath ->
            val slideRoot = parseXml(zip.getValue(slidePath)).documentElement
            val relPath = slidePath.replace("slides/", "slides/_rels/") + ".rels"
            val relationships = relationshipMap(zip[relPath], "ppt/slides")
            pptSlideDocument(
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
        }
        ImportedDocumentBatch(sourceName, documents)
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
        root.descendants("sp").forEach { shape ->
            val box = pptBox(shape, scale, startX) ?: return@forEach
            val text = collectOfficeText(shape).trim()
            if (text.isNotBlank()) {
                val runProperties = shape.firstDescendant("rPr") ?: shape.firstDescendant("defRPr")
                val pointSize = runProperties?.attribute("sz")?.toFloatOrNull()?.div(100f)
                val alignment = when (shape.firstDescendant("pPr")?.attribute("algn")) {
                    "ctr" -> TextAlignment.CENTER
                    "r" -> TextAlignment.RIGHT
                    else -> TextAlignment.LEFT
                }
                elements += EditorFactories.textElement(box.left, box.top).copy(
                    width = box.width,
                    height = box.height,
                    text = text,
                    fontSizeDots = ((pointSize ?: 16f) * paper.dpi / 72f).coerceIn(10f, 96f),
                    fontWeight = if (runProperties?.attribute("b") in setOf("1", "true")) 700 else 400,
                    italic = runProperties?.attribute("i") in setOf("1", "true"),
                    underline = runProperties?.attribute("u").orEmpty().let { it.isNotBlank() && it != "none" },
                    textAlignment = alignment,
                )
            } else {
                val preset = shape.firstDescendant("prstGeom")?.attribute("prst")
                val shapeKind = when (preset) {
                    "ellipse" -> ShapeKind.ELLIPSE
                    "roundRect" -> ShapeKind.ROUNDED_RECTANGLE
                    "triangle" -> ShapeKind.TRIANGLE
                    "diamond" -> ShapeKind.DIAMOND
                    "line" -> ShapeKind.LINE
                    else -> null
                }
                if (shapeKind != null) elements += EditorFactories.shapeElement(shapeKind, box.left, box.top).copy(width = box.width, height = box.height)
            }
        }
        root.descendants("pic").forEach { picture ->
            val relationId = picture.firstDescendant("blip")?.attribute("embed").orEmpty()
            val target = relationships[relationId] ?: return@forEach
            val bytes = zip[target] ?: return@forEach
            val box = pptBox(picture, scale, startX) ?: return@forEach
            val uri = CapturedMediaStore.saveImageBytes(context, bytes, target.substringAfterLast('.', "png"), "pptx-media")
                .getOrNull() ?: return@forEach
            elements += EditorFactories.imageElement(uri.toString(), box.left, box.top).copy(
                width = box.width,
                height = box.height,
                imageFit = ImageFit.FIT,
            )
        }
        return EditorFactories.blankDocument("${sourceName.baseName()} · 幻灯片 $page/$pages", paper).copy(
            category = "办公管理",
            elements = elements.ifEmpty {
                listOf(EditorFactories.textElement(startX, paper.mmToDots(paper.topPaddingMm)).copy(text = "（空白幻灯片）", width = contentWidth))
            },
        ).normalized()
    }

    private data class PptBox(val left: Int, val top: Int, val width: Int, val height: Int)

    private fun pptBox(element: Element, scale: Double, startX: Int): PptBox? {
        val transform = element.firstDescendant("xfrm") ?: return null
        val offset = transform.firstDescendant("off") ?: return null
        val extent = transform.firstDescendant("ext") ?: return null
        val x = offset.attribute("x").toLongOrNull() ?: return null
        val y = offset.attribute("y").toLongOrNull() ?: return null
        val width = extent.attribute("cx").toLongOrNull() ?: return null
        val height = extent.attribute("cy").toLongOrNull() ?: return null
        return PptBox(
            left = startX + (x * scale).roundToInt(),
            top = (y * scale).roundToInt(),
            width = (width * scale).roundToInt().coerceAtLeast(16),
            height = (height * scale).roundToInt().coerceAtLeast(16),
        )
    }

    private fun presentationSize(bytes: ByteArray?): Pair<Long, Long> {
        if (bytes == null) return 12_192_000L to 6_858_000L
        val size = parseXml(bytes).documentElement.firstDescendant("sldSz") ?: return 12_192_000L to 6_858_000L
        return (size.attribute("cx").toLongOrNull() ?: 12_192_000L) to
            (size.attribute("cy").toLongOrNull() ?: 6_858_000L)
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

    private fun parseXml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

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

    private fun Element.firstDescendant(localName: String): Element? = descendants(localName).firstOrNull()

    private fun sanitizeCell(value: String): String = value.replace('|', '¦').replace('\r', ' ').replace('\n', ' ')
}

private fun String.baseName(): String = substringBeforeLast('.', this).ifBlank { "导入文档" }
