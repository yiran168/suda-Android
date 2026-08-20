package com.qrint.studio.data

import com.qrint.studio.model.LabelDocument
import com.qrint.studio.model.LabelElement
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.Charset
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import jxl.Workbook
import jxl.WorkbookSettings
import org.w3c.dom.Element

data class VariableDataTable(
    val sourceName: String,
    val headers: List<String>,
    val rows: List<Map<String, String>>,
    val sheetName: String = "数据",
) {
    init {
        require(headers.isNotEmpty()) { "数据表缺少字段名" }
        require(rows.isNotEmpty()) { "数据表没有可打印记录" }
    }

    fun normalizeRange(requested: IntRange): IntRange {
        val first = requested.first.coerceIn(0, rows.lastIndex)
        val last = requested.last.coerceIn(first, rows.lastIndex)
        return first..last
    }

    fun rowsIn(requested: IntRange): List<Map<String, String>> {
        val safe = normalizeRange(requested)
        return rows.subList(safe.first, safe.last + 1)
    }
}

data class VariableDataWorkbook(
    val sourceName: String,
    val sheets: List<VariableDataTable>,
) {
    init {
        require(sheets.isNotEmpty()) { "工作簿没有可打印的工作表" }
    }

    val defaultSheet: VariableDataTable get() = sheets.first()
}

/** One shared view function drives row preview, range selection and the final batch order. */
fun queryVariableRows(
    table: VariableDataTable,
    query: String,
    sortField: String?,
    ascending: Boolean,
): List<Map<String, String>> {
    val needle = query.trim().lowercase(Locale.ROOT)
    val filtered = if (needle.isBlank()) table.rows else table.rows.filter { row ->
        row.values.any { value -> value.lowercase(Locale.ROOT).contains(needle) }
    }
    val field = sortField?.takeIf { it in table.headers } ?: return filtered
    val indexed = filtered.withIndex().toList()
    val comparator = Comparator<IndexedValue<Map<String, String>>> { left, right ->
        val leftValue = left.value[field].orEmpty().trim()
        val rightValue = right.value[field].orEmpty().trim()
        val leftNumber = leftValue.replace(",", "").toBigDecimalOrNull()
        val rightNumber = rightValue.replace(",", "").toBigDecimalOrNull()
        val compared = if (leftNumber != null && rightNumber != null) {
            leftNumber.compareTo(rightNumber)
        } else leftValue.compareTo(rightValue, ignoreCase = true)
        val directed = if (ascending) compared else -compared
        if (directed != 0) directed else left.index.compareTo(right.index)
    }
    return indexed.sortedWith(comparator).map { it.value }
}

fun normalizeVariableRange(rowCount: Int, requested: IntRange): IntRange {
    if (rowCount <= 0) return IntRange.EMPTY
    val first = requested.first.coerceIn(0, rowCount - 1)
    val last = requested.last.coerceIn(first, rowCount - 1)
    return first..last
}

fun variableRowsIn(rows: List<Map<String, String>>, requested: IntRange): List<Map<String, String>> {
    val range = normalizeVariableRange(rows.size, requested)
    return if (range.isEmpty()) emptyList() else rows.subList(range.first, range.last + 1)
}

/** Local-only CSV/TSV/XLSX reader used by variable-data printing. */
object VariableDataParser {
    private const val MAX_FILE_BYTES = 24 * 1024 * 1024
    private const val MAX_ROWS = 5_000
    private const val MAX_COLUMNS = 128

    fun parse(sourceName: String, input: InputStream): VariableDataTable {
        return parseWorkbook(sourceName, input).defaultSheet
    }

    fun parseWorkbook(sourceName: String, input: InputStream): VariableDataWorkbook {
        val bytes = input.readBounded(MAX_FILE_BYTES)
        val lower = sourceName.lowercase()
        return when {
            bytes.isZipArchive() -> parseXlsxWorkbook(sourceName, bytes)
            bytes.isOle2Archive() -> parseLegacyWorkbook(sourceName, bytes)
            lower.endsWith(".xls") || lower.endsWith(".et") ->
                error("文件扩展名是 ${lower.substringAfterLast('.', "xls")}，但内容不是可识别的 Excel/WPS 工作簿")
            else -> VariableDataWorkbook(sourceName, listOf(parseDelimited(sourceName, decodeText(bytes))))
        }
    }

    fun parseDelimited(sourceName: String, text: String): VariableDataTable {
        val delimiter = detectDelimiter(text)
        val matrix = parseCsv(text, delimiter)
            .filterNot { row -> row.all(String::isBlank) }
            .take(MAX_ROWS + 1)
        return tableFromMatrix(sourceName, matrix, "数据至少需要一行字段名和一行记录")
    }

    private fun parseXlsxWorkbook(sourceName: String, bytes: ByteArray): VariableDataWorkbook {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val normalized = entry.name.replace('\\', '/')
                if (!entry.isDirectory && (
                        normalized == "xl/sharedStrings.xml" ||
                            normalized == "xl/styles.xml" ||
                            normalized == "xl/workbook.xml" ||
                            normalized == "xl/_rels/workbook.xml.rels" ||
                            normalized.startsWith("xl/worksheets/") && normalized.endsWith(".xml")
                        )
                ) {
                    entries[normalized] = zip.readBounded(MAX_FILE_BYTES)
                }
                zip.closeEntry()
            }
        }
        val worksheetPaths = entries.keys
            .filter { it.startsWith("xl/worksheets/") }
            .sortedBy(::naturalSheetNumber)
        require(worksheetPaths.isNotEmpty()) { "文件不是可识别的 XLSX/WPS 表格" }
        val shared = entries["xl/sharedStrings.xml"]?.let(::sharedStrings).orEmpty()
        val styles = entries["xl/styles.xml"]?.let(::xlsxStyles) ?: XlsxStyles.EMPTY
        val date1904 = entries["xl/workbook.xml"]?.let(::usesExcel1904DateSystem) == true
        val descriptors = xlsxSheetDescriptors(entries).ifEmpty {
            worksheetPaths.mapIndexed { index, path -> SheetDescriptor("工作表 ${index + 1}", path) }
        }
        val validSheets = descriptors.mapNotNull { descriptor ->
            val xml = entries[descriptor.path] ?: return@mapNotNull null
            val matrix = worksheetRows(xml, shared, styles, date1904).take(MAX_ROWS + 1)
            if (matrix.size >= 2) {
                tableFromMatrix(
                    sourceName = sourceName,
                    matrix = matrix,
                    insufficientMessage = "工作表至少需要一行字段名和一行记录",
                    sheetName = descriptor.name,
                )
            } else null
        }
        require(validSheets.isNotEmpty()) { "所有工作表都缺少字段名或可打印记录" }
        return VariableDataWorkbook(sourceName, validSheets)
    }

    /** Reads the old OLE2/BIFF format without sending the workbook to any cloud service. */
    private fun parseLegacyWorkbook(sourceName: String, bytes: ByteArray): VariableDataWorkbook {
        val settings = WorkbookSettings().apply {
            encoding = "ISO-8859-1"
            setSuppressWarnings(true)
        }
        val workbook = runCatching {
            Workbook.getWorkbook(ByteArrayInputStream(bytes), settings)
        }.getOrElse { error ->
            throw IllegalArgumentException(
                "无法读取旧式 Excel/WPS 文件；请确认文件未加密，或在 WPS/Excel 中另存为 .xlsx/CSV",
                error,
            )
        }
        try {
            val sheets = workbook.sheets.mapIndexedNotNull { sheetIndex, sheet ->
                if (sheet.rows < 2 || sheet.columns <= 0) return@mapIndexedNotNull null
                val rowCount = sheet.rows.coerceAtMost(MAX_ROWS + 1)
                val columnCount = sheet.columns.coerceAtMost(MAX_COLUMNS)
                val matrix = (0 until rowCount).map { row ->
                    (0 until columnCount).map { column -> sheet.getCell(column, row).contents.orEmpty() }
                }.filterNot { row -> row.all(String::isBlank) }
                if (matrix.size < 2) null else tableFromMatrix(
                    sourceName = sourceName,
                    matrix = matrix,
                    insufficientMessage = "工作表至少需要一行字段名和一行记录",
                    sheetName = sheet.name.ifBlank { "工作表 ${sheetIndex + 1}" },
                )
            }
            require(sheets.isNotEmpty()) { "工作簿中没有包含字段名和记录的工作表" }
            return VariableDataWorkbook(sourceName, sheets)
        } finally {
            workbook.close()
        }
    }

    private fun tableFromMatrix(
        sourceName: String,
        matrix: List<List<String>>,
        insufficientMessage: String,
        sheetName: String = "数据",
    ): VariableDataTable {
        require(matrix.size >= 2) { insufficientMessage }
        val width = matrix.maxOf { it.size }.coerceAtMost(MAX_COLUMNS)
        val headers = uniqueHeaders((0 until width).map { matrix.first().getOrNull(it).orEmpty() })
        val rows = matrix.drop(1).take(MAX_ROWS).map { cells ->
            headers.mapIndexed { index, header -> header to cells.getOrNull(index).orEmpty() }.toMap()
        }.filter { row -> row.values.any(String::isNotBlank) }
        return VariableDataTable(sourceName, headers, rows, sheetName)
    }

    private fun xlsxSheetDescriptors(entries: Map<String, ByteArray>): List<SheetDescriptor> {
        val workbookXml = entries["xl/workbook.xml"] ?: return emptyList()
        val relationshipsXml = entries["xl/_rels/workbook.xml.rels"] ?: return emptyList()
        val relationships = parseXml(relationshipsXml).getElementsByTagName("Relationship").let { nodes ->
            buildMap {
                for (index in 0 until nodes.length) {
                    val relationship = nodes.item(index) as? Element ?: continue
                    val id = relationship.getAttribute("Id")
                    val target = relationship.getAttribute("Target")
                    if (id.isNotBlank() && target.isNotBlank()) put(id, normalizeOoxmlPath(target))
                }
            }
        }
        val sheets = parseXml(workbookXml).getElementsByTagName("sheet")
        return buildList {
            for (index in 0 until sheets.length) {
                val sheet = sheets.item(index) as? Element ?: continue
                val relationId = sheet.getAttribute("r:id").ifBlank { sheet.getAttribute("id") }
                val path = relationships[relationId] ?: continue
                if (path.startsWith("xl/worksheets/") && entries.containsKey(path)) {
                    add(SheetDescriptor(sheet.getAttribute("name").ifBlank { "工作表 ${index + 1}" }, path))
                }
            }
        }
    }

    private fun normalizeOoxmlPath(target: String): String {
        val raw = if (target.startsWith('/')) target.drop(1) else "xl/$target"
        val segments = mutableListOf<String>()
        raw.replace('\\', '/').split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        return segments.joinToString("/")
    }

    private fun sharedStrings(xml: ByteArray): List<String> {
        val document = parseXml(xml)
        val items = document.getElementsByTagName("si")
        return buildList {
            for (index in 0 until items.length) {
                val item = items.item(index) as? Element ?: continue
                val texts = item.getElementsByTagName("t")
                add(buildString { for (textIndex in 0 until texts.length) append(texts.item(textIndex).textContent.orEmpty()) })
            }
        }
    }

    private fun worksheetRows(
        xml: ByteArray,
        shared: List<String>,
        styles: XlsxStyles,
        date1904: Boolean,
    ): List<List<String>> {
        val document = parseXml(xml)
        val rows = document.getElementsByTagName("row")
        return buildList {
            for (rowIndex in 0 until rows.length) {
                val row = rows.item(rowIndex) as? Element ?: continue
                val cells = row.getElementsByTagName("c")
                val values = sortedMapOf<Int, String>()
                for (cellIndex in 0 until cells.length) {
                    val cell = cells.item(cellIndex) as? Element ?: continue
                    val column = columnIndex(cell.getAttribute("r")).coerceIn(0, MAX_COLUMNS - 1)
                    val type = cell.getAttribute("t")
                    val styleIndex = cell.getAttribute("s").toIntOrNull()
                    val raw = when (type) {
                        "inlineStr" -> cell.getElementsByTagName("t").let { nodes ->
                            buildString { for (i in 0 until nodes.length) append(nodes.item(i).textContent.orEmpty()) }
                        }
                        else -> cell.getElementsByTagName("v").item(0)?.textContent.orEmpty()
                    }
                    values[column] = when (type) {
                        "s" -> raw.toIntOrNull()?.let(shared::getOrNull).orEmpty()
                        "b" -> if (raw == "1") "TRUE" else "FALSE"
                        "str", "inlineStr" -> raw
                        else -> styles.format(raw, styleIndex, date1904)
                    }
                }
                if (values.isNotEmpty()) {
                    val last = values.lastKey().coerceAtMost(MAX_COLUMNS - 1)
                    add((0..last).map { values[it].orEmpty() })
                }
            }
        }
    }

    private fun parseXml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun xlsxStyles(xml: ByteArray): XlsxStyles {
        val document = parseXml(xml)
        val customFormats = buildMap {
            val formats = document.getElementsByTagName("numFmt")
            for (index in 0 until formats.length) {
                val format = formats.item(index) as? Element ?: continue
                val id = format.getAttribute("numFmtId").toIntOrNull() ?: continue
                val code = format.getAttribute("formatCode")
                if (code.isNotBlank()) put(id, code)
            }
        }
        val cellXfs = document.getElementsByTagName("cellXfs").item(0) as? Element
        val xfs = cellXfs?.getElementsByTagName("xf")?.let { nodes ->
            buildList {
                for (index in 0 until nodes.length) {
                    val xf = nodes.item(index) as? Element ?: continue
                    add(xf.getAttribute("numFmtId").toIntOrNull() ?: 0)
                }
            }
        }.orEmpty()
        return XlsxStyles(xfs, customFormats)
    }

    private fun usesExcel1904DateSystem(workbookXml: ByteArray): Boolean {
        val workbookProperties = parseXml(workbookXml).getElementsByTagName("workbookPr").item(0) as? Element
            ?: return false
        return workbookProperties.getAttribute("date1904") in setOf("1", "true", "TRUE")
    }

    private fun uniqueHeaders(raw: List<String>): List<String> {
        val counts = mutableMapOf<String, Int>()
        return raw.mapIndexed { index, value ->
            val base = value.trim().ifBlank { "字段${index + 1}" }
            val count = (counts[base] ?: 0) + 1
            counts[base] = count
            if (count == 1) base else "$base-$count"
        }
    }

    private fun parseCsv(text: String, delimiter: Char): List<List<String>> {
        val output = mutableListOf<MutableList<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' && quoted && index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"'); index++
                }
                char == '"' -> quoted = !quoted
                char == delimiter && !quoted -> { row += field.toString(); field.clear() }
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    row += field.toString(); field.clear(); output += row; row = mutableListOf()
                }
                else -> field.append(char)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); output += row }
        return output
    }

    private fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        return listOf(',', '\t', ';').maxBy { candidate -> sample.count { it == candidate } }
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        return if (utf8.count { it == '\uFFFD' } > utf8.length / 100) String(bytes, Charset.forName("GB18030")) else utf8
    }

    private fun ByteArray.isZipArchive(): Boolean = size >= 4 && this[0] == 0x50.toByte() && this[1] == 0x4B.toByte()

    private fun ByteArray.isOle2Archive(): Boolean {
        val signature = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
        )
        return size >= signature.size && signature.indices.all { this[it] == signature[it] }
    }

    private fun InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "文件超过 ${maxBytes / 1024 / 1024} MB 限制" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private data class SheetDescriptor(val name: String, val path: String)

    private data class XlsxStyles(
        val styleNumberFormats: List<Int>,
        val customNumberFormats: Map<Int, String>,
    ) {
        fun format(raw: String, styleIndex: Int?, date1904: Boolean): String {
            if (raw.isBlank()) return raw
            val number = raw.toDoubleOrNull() ?: return raw
            val formatId = styleIndex?.let(styleNumberFormats::getOrNull) ?: 0
            val formatCode = customNumberFormats[formatId] ?: BUILT_IN_NUMBER_FORMATS[formatId].orEmpty()
            return when {
                isDateFormat(formatId, formatCode) -> formatExcelDate(number, formatCode, date1904)
                formatCode.contains('%') -> formatDecimal(number * 100.0, formatCode) + "%"
                formatId == 0 || formatCode.isBlank() -> generalNumber(raw)
                else -> formatDecimal(number, formatCode)
            }
        }

        companion object {
            val EMPTY = XlsxStyles(emptyList(), emptyMap())
        }
    }

    private fun isDateFormat(formatId: Int, code: String): Boolean {
        if (formatId in 14..22 || formatId in 45..47) return true
        val clean = cleanExcelFormat(code).lowercase(Locale.ROOT)
        return clean.contains('y') || clean.contains('d') || clean.contains('h') || clean.contains('s')
    }

    private fun formatExcelDate(serial: Double, code: String, date1904: Boolean): String {
        val base = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
            clear()
            if (date1904) set(1904, Calendar.JANUARY, 1) else set(1899, Calendar.DECEMBER, 30)
        }
        val milliseconds = (serial * MILLIS_PER_DAY).toLong()
        base.timeInMillis += milliseconds
        val clean = cleanExcelFormat(code).lowercase(Locale.ROOT)
        val hasDate = clean.any { it == 'y' || it == 'd' }
        val hasSeconds = clean.contains('s')
        val hasTime = clean.any { it == 'h' || it == 's' } || (!hasDate && clean.contains('m'))
        val pattern = when {
            hasDate && hasTime && hasSeconds -> "yyyy-MM-dd HH:mm:ss"
            hasDate && hasTime -> "yyyy-MM-dd HH:mm"
            hasDate -> "yyyy-MM-dd"
            hasSeconds -> "HH:mm:ss"
            else -> "HH:mm"
        }
        return SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(base.time)
    }

    private fun formatDecimal(number: Double, code: String): String {
        val clean = cleanExcelFormat(code)
        if (clean.contains('E', ignoreCase = true)) {
            return DecimalFormat("0.#####E0", DecimalFormatSymbols(Locale.ROOT)).apply {
                roundingMode = RoundingMode.HALF_UP
            }.format(number)
        }
        val fractionalPattern = clean.substringAfter('.', "")
            .takeWhile { it == '0' || it == '#' }
        val minimumDecimals = fractionalPattern.count { it == '0' }
        val maximumDecimals = fractionalPattern.length.coerceAtMost(12)
        val integerPattern = if (clean.substringBefore('.').contains(',')) "#,##0" else "0"
        val pattern = buildString {
            append(integerPattern)
            if (maximumDecimals > 0) {
                append('.')
                repeat(minimumDecimals) { append('0') }
                repeat(maximumDecimals - minimumDecimals) { append('#') }
            }
        }
        return DecimalFormat(pattern, DecimalFormatSymbols(Locale.ROOT)).apply {
            roundingMode = RoundingMode.HALF_UP
        }.format(number)
    }

    private fun generalNumber(raw: String): String = runCatching {
        BigDecimal(raw).stripTrailingZeros().toPlainString()
    }.getOrDefault(raw)

    private fun cleanExcelFormat(code: String): String = code
        .replace(QUOTED_EXCEL_FORMAT, "")
        .replace(BRACKETED_EXCEL_FORMAT, "")
        .replace(ESCAPED_EXCEL_FORMAT, "")
        .substringBefore(';')

    private val BUILT_IN_NUMBER_FORMATS = mapOf(
        1 to "0",
        2 to "0.00",
        3 to "#,##0",
        4 to "#,##0.00",
        9 to "0%",
        10 to "0.00%",
        11 to "0.00E+00",
        14 to "yyyy-mm-dd",
        15 to "dd-mmm-yy",
        16 to "dd-mmm",
        17 to "mmm-yy",
        18 to "h:mm AM/PM",
        19 to "h:mm:ss AM/PM",
        20 to "h:mm",
        21 to "h:mm:ss",
        22 to "yyyy-mm-dd h:mm",
        45 to "mm:ss",
        46 to "[h]:mm:ss",
        47 to "mm:ss.0",
    )

    private val QUOTED_EXCEL_FORMAT = Regex("\"[^\"]*\"")
    private val BRACKETED_EXCEL_FORMAT = Regex("\\[[^]]*]")
    private val ESCAPED_EXCEL_FORMAT = Regex("\\\\.")
    private const val MILLIS_PER_DAY = 86_400_000.0

    private fun naturalSheetNumber(path: String): Int = Regex("sheet(\\d+)").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

    private fun columnIndex(reference: String): Int {
        var value = 0
        reference.takeWhile(Char::isLetter).uppercase().forEach { char -> value = value * 26 + (char - 'A' + 1) }
        return (value - 1).coerceAtLeast(0)
    }
}

private data class VariablePlaceholder(val start: Int, val endExclusive: Int, val key: String)

/**
 * Parses {{field}} tokens without java.util.regex. Android 16's ICU engine rejects unescaped
 * closing braces that the desktop JVM historically accepted; a small deterministic scanner keeps
 * template parsing identical across supported Android versions and cannot fail during class initialization.
 */
private object VariablePlaceholders {
    fun findAll(value: String): List<VariablePlaceholder> {
        val result = mutableListOf<VariablePlaceholder>()
        var cursor = 0
        while (cursor < value.length) {
            val start = value.indexOf("{{", cursor)
            if (start < 0) break
            val close = value.indexOf("}}", start + 2)
            if (close < 0) break
            val key = value.substring(start + 2, close).trim()
            if (key.isNotEmpty() && '{' !in key && '}' !in key) {
                result += VariablePlaceholder(start, close + 2, key)
                cursor = close + 2
            } else {
                cursor = close + 2
            }
        }
        return result
    }

    fun resolve(value: String, values: Map<String, String>): String {
        val placeholders = findAll(value)
        if (placeholders.isEmpty()) return value
        return buildString(value.length) {
            var cursor = 0
            placeholders.forEach { placeholder ->
                append(value, cursor, placeholder.start)
                append(values[placeholder.key] ?: value.substring(placeholder.start, placeholder.endExclusive))
                cursor = placeholder.endExclusive
            }
            append(value, cursor, value.length)
        }
    }
}

fun String.resolveVariables(values: Map<String, String>): String = VariablePlaceholders.resolve(this, values)

internal fun String.variableFields(): Set<String> = VariablePlaceholders.findAll(this).mapTo(linkedSetOf()) { it.key }

fun LabelElement.resolveVariables(values: Map<String, String>): LabelElement = copy(
    text = text.resolveVariables(values),
    barcodeContent = barcodeContent.resolveVariables(values),
    tableData = tableData.resolveVariables(values),
    sequencePrefix = sequencePrefix.resolveVariables(values),
    sequenceSuffix = sequenceSuffix.resolveVariables(values),
)

fun LabelDocument.resolveVariables(values: Map<String, String>): LabelDocument = copy(
    title = title.resolveVariables(values),
    elements = elements.map { it.resolveVariables(values) },
)

fun LabelDocument.variableFields(): Set<String> = buildSet {
    fun collect(value: String) { VariablePlaceholders.findAll(value).forEach { add(it.key) } }
    collect(title)
    elements.forEach { element ->
        collect(element.text); collect(element.barcodeContent); collect(element.tableData)
        collect(element.sequencePrefix); collect(element.sequenceSuffix)
    }
}
