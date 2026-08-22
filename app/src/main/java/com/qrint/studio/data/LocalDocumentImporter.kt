package com.qrint.studio.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.qrint.studio.model.DitherMode
import com.qrint.studio.model.EditorFactories
import com.qrint.studio.model.ImageFit
import com.qrint.studio.model.LabelDocument
import com.qrint.studio.model.MAX_DOCUMENT_HEIGHT_DOTS
import com.qrint.studio.model.PaperMode
import com.qrint.studio.model.PaperSettings
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import kotlin.math.roundToInt

data class ImportedDocumentBatch(
    val sourceName: String,
    val documents: List<LabelDocument>,
) {
    init {
        require(documents.isNotEmpty()) { "文档没有可打印页面" }
    }
}

/** Bounded, offline PDF/text importer. Each page becomes a normal editable label document. */
object LocalDocumentImporter {
    private const val MAX_PDF_BYTES = 96L * 1024L * 1024L
    private const val MAX_PDF_PAGES = 200
    private const val MAX_TEXT_BYTES = 8 * 1024 * 1024
    private const val TEXT_FONT_DOTS = 24f
    private const val TEXT_LINE_DOTS = 30

    /** Routes every supported local document through one format-aware entry point. */
    fun importDocument(
        context: Context,
        uri: Uri,
        sourceName: String,
        mimeType: String?,
        paper: PaperSettings,
    ): Result<ImportedDocumentBatch> {
        return when (LocalDocumentFormatDetector.inspect(context, uri, sourceName, mimeType)) {
            LocalDocumentKind.PDF ->
                importPdf(context, uri, sourceName, paper)
            LocalDocumentKind.DOCX ->
                OfficeDocumentImporter.importDocx(context, uri, sourceName, paper)
            LocalDocumentKind.PPTX ->
                OfficeDocumentImporter.importPptx(context, uri, sourceName, paper)
            LocalDocumentKind.SPREADSHEET ->
                OfficeDocumentImporter.importSpreadsheet(context, uri, sourceName, paper)
            LocalDocumentKind.TEXT ->
                importPlainText(context, uri, sourceName, paper)
            LocalDocumentKind.LEGACY_WORD ->
                OfficeDocumentImporter.importLegacyWord(context, uri, sourceName, paper)
            LocalDocumentKind.LEGACY_POWERPOINT ->
                OfficeDocumentImporter.importLegacyPowerPoint(context, uri, sourceName, paper)
            LocalDocumentKind.ENCRYPTED_OFFICE -> Result.failure(
                IllegalArgumentException("该 Office/WPS 文档已加密，请先在原应用中解除密码保护后再导入。"),
            )
            LocalDocumentKind.UNKNOWN -> Result.failure(
                IllegalArgumentException("无法识别文档格式：$sourceName；请确认文件未损坏且扩展名正确。"),
            )
        }
    }

    fun importPdf(
        context: Context,
        uri: Uri,
        sourceName: String,
        paper: PaperSettings,
    ): Result<ImportedDocumentBatch> = runCatching {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("无法打开 PDF 文件")
        val savedPages = mutableListOf<Uri>()
        try {
            if (descriptor.statSize >= 0) require(descriptor.statSize <= MAX_PDF_BYTES) { "PDF 超过 96 MB 限制" }
            descriptor.use { pdfDescriptor ->
                PdfRenderer(pdfDescriptor).use { renderer ->
                    require(renderer.pageCount in 1..MAX_PDF_PAGES) { "PDF 页数必须在 1–$MAX_PDF_PAGES 页" }
                    val documents = (0 until renderer.pageCount).map { pageIndex ->
                        renderer.openPage(pageIndex).use { page ->
                        val contentWidth = paper.contentWidthDots().coerceAtLeast(64)
                        val naturalHeight = (contentWidth * page.height.toFloat() / page.width.coerceAtLeast(1))
                            .roundToInt().coerceAtLeast(64)
                        val targetHeight = if (paper.mode == PaperMode.LABEL) {
                            naturalHeight.coerceAtMost(paper.fixedHeightDots().coerceAtLeast(64))
                        } else naturalHeight.coerceAtMost(MAX_DOCUMENT_HEIGHT_DOTS - 16)
                        val targetWidth = if (targetHeight < naturalHeight) {
                            (contentWidth * targetHeight.toFloat() / naturalHeight).roundToInt().coerceAtLeast(64)
                        } else contentWidth
                        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                        try {
                            Canvas(bitmap).drawColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val pageUri = CapturedMediaStore.saveBitmap(context, bitmap, "pdf-page").getOrThrow()
                            savedPages += pageUri
                            pdfPageDocument(
                                sourceName = sourceName,
                                pageNumber = pageIndex + 1,
                                pageCount = renderer.pageCount,
                                paper = paper,
                                pageUri = pageUri,
                                naturalHeight = naturalHeight,
                            )
                        } finally {
                            if (!bitmap.isRecycled) bitmap.recycle()
                        }
                        }
                    }
                    ImportedDocumentBatch(sourceName, documents)
                }
            }
        } catch (error: Throwable) {
            savedPages.forEach { CapturedMediaStore.delete(context, it) }
            throw error
        }
    }

    fun importPlainText(
        context: Context,
        uri: Uri,
        sourceName: String,
        paper: PaperSettings,
    ): Result<ImportedDocumentBatch> = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_TEXT_BYTES) { "文本超过 8 MB 限制" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: error("无法读取文本文件")
        val text = decodeText(bytes).replace("\u0000", "").trim()
        require(text.isNotBlank()) { "文本文件为空" }
        val contentWidth = paper.contentWidthDots().coerceAtLeast(64)
        val unitsPerLine = (contentWidth / (TEXT_FONT_DOTS * 0.5f)).toInt().coerceAtLeast(4)
        val lines = wrapTextForPrint(text, unitsPerLine)
        val availableHeight = if (paper.mode == PaperMode.LABEL) {
            paper.fixedHeightDots() - paper.mmToDots(paper.topPaddingMm + paper.bottomPaddingMm)
        } else MAX_DOCUMENT_HEIGHT_DOTS - paper.mmToDots(paper.topPaddingMm + paper.bottomPaddingMm)
        val linesPerPage = (availableHeight / TEXT_LINE_DOTS).coerceIn(1, 240)
        val chunks = lines.chunked(linesPerPage)
        require(chunks.size <= MAX_PDF_PAGES) { "文本分页超过 $MAX_PDF_PAGES 页，请拆分文件" }
        ImportedDocumentBatch(
            sourceName,
            chunks.mapIndexed { index, pageLines ->
                textPageDocument(sourceName, index + 1, chunks.size, pageLines, paper)
            },
        )
    }

    private fun pdfPageDocument(
        sourceName: String,
        pageNumber: Int,
        pageCount: Int,
        paper: PaperSettings,
        pageUri: Uri,
        naturalHeight: Int,
    ): LabelDocument {
        val contentWidth = paper.contentWidthDots()
        val elementHeight = if (paper.mode == PaperMode.LABEL) paper.fixedHeightDots() else naturalHeight
        return EditorFactories.blankDocument("${sourceName.baseName()} · $pageNumber/$pageCount", paper).copy(
            category = "办公管理",
            elements = listOf(
                EditorFactories.imageElement(pageUri.toString(), x = paper.printableStartX(), y = 0).copy(
                    width = contentWidth,
                    height = elementHeight.coerceAtLeast(64),
                    imageFit = ImageFit.FIT,
                    ditherMode = DitherMode.FLOYD_STEINBERG,
                ),
            ),
        ).normalized()
    }

    private fun textPageDocument(
        sourceName: String,
        pageNumber: Int,
        pageCount: Int,
        lines: List<String>,
        paper: PaperSettings,
    ): LabelDocument {
        val top = paper.mmToDots(paper.topPaddingMm)
        return EditorFactories.blankDocument("${sourceName.baseName()} · $pageNumber/$pageCount", paper).copy(
            category = "办公管理",
            elements = listOf(
                EditorFactories.textElement(x = paper.printableStartX(), y = top).copy(
                    width = paper.contentWidthDots(),
                    height = (lines.size * TEXT_LINE_DOTS).coerceAtLeast(TEXT_LINE_DOTS),
                    text = lines.joinToString("\n"),
                    fontSizeDots = TEXT_FONT_DOTS,
                    lineSpacingDots = (TEXT_LINE_DOTS - TEXT_FONT_DOTS),
                ),
            ),
        ).normalized()
    }

    private fun decodeText(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> String(bytes, Charsets.UTF_8).let { utf8 ->
            if (utf8.count { it == '\uFFFD' } > utf8.length / 100) String(bytes, Charset.forName("GB18030")) else utf8
        }
    }

}

internal fun wrapTextForPrint(text: String, maximumUnits: Int): List<String> {
    require(maximumUnits >= 1)
    val output = mutableListOf<String>()
    val line = StringBuilder()
    var units = 0
    fun flush() {
        output += line.toString().trimEnd()
        line.clear()
        units = 0
    }
    text.forEach { character ->
        if (character == '\r') return@forEach
        if (character == '\n') {
            flush()
            return@forEach
        }
        val characterUnits = if (character.code <= 0x7F) 1 else 2
        if (units + characterUnits > maximumUnits && line.isNotEmpty()) flush()
        line.append(character)
        units += characterUnits
    }
    if (line.isNotEmpty() || output.isEmpty()) flush()
    return output
}

private fun String.baseName(): String = substringBeforeLast('.', this).ifBlank { "导入文档" }
