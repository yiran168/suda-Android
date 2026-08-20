package com.qrint.studio.model

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

const val DEFAULT_HEAD_DOTS = 384
const val DEFAULT_DPI = 203
const val DEFAULT_TAIL_FEED_MM = 5f
const val MIN_PAPER_WIDTH_MM = 10f
const val MAX_PAPER_WIDTH_MM = 57f
/** Paper below this physical width must explicitly declare which guide rail it touches. */
const val NARROW_LOADING_THRESHOLD_MM = 55f
/**
 * About one metre at 203 dpi. Keeping a hard cap protects low-memory devices from bitmap OOMs while
 * still allowing receipts far longer than a typical portable printer can reliably feed at once.
 */
const val MAX_DOCUMENT_HEIGHT_DOTS = 8_000
const val MAX_DOCUMENT_ELEMENTS = 1_000
const val MIN_ELEMENT_DOTS = 16

enum class PaperMode { CONTINUOUS, LABEL }
enum class PaperShape { RECTANGLE, ROUNDED, OVAL }
enum class HorizontalAnchor { LEFT, CENTER, RIGHT }
enum class PrintProtocol { QRING_SPP, GENERIC_ESC_POS }
enum class ElementKind { TEXT, IMAGE, BARCODE, SHAPE, TABLE, DATE_TIME, SEQUENCE, DRAWING }
enum class TextAlignment { LEFT, CENTER, RIGHT }
enum class TextEnhancementMode(val title: String, val description: String) {
    NONE("关闭增强", "保留字体原始笔画，适合字号较大、字重较粗的内容"),
    PIXEL_CRISP("1 · 点阵锐化", "将抗锯齿边缘收敛到打印点，减少灰边"),
    EDGE_CLEAN("2 · 边缘净化", "清理孤立噪点，适合票据正文和细小数字"),
    STROKE_BALANCE("3 · 笔画均衡", "闭合轻微断笔，同时尽量保持字腔"),
    THIN_TEXT_RESCUE("4 · 细字增强", "补强过细笔画，适合低字重或小字号"),
    MAX_CLARITY("5 · 极清增强", "锐化、闭合与噪点清理组合，清晰度优先"),
}
enum class ShapeKind {
    RECTANGLE,
    ROUNDED_RECTANGLE,
    LINE,
    VERTICAL_LINE,
    DASHED_LINE,
    DASHED_VERTICAL_LINE,
    ELLIPSE,
    TRIANGLE,
    PENTAGON,
    HEXAGON,
    DIAMOND,
    STAR,
    HEART,
    PLUS,
    CHECKMARK,
    ARROW_LEFT,
    ARROW_RIGHT,
    ARROW_UP,
    ARROW_DOWN,
    SPEECH_BUBBLE,
    CROSS,
}
enum class ImageFit { FIT, CROP, STRETCH }

enum class DitherMode(val title: String, val description: String) {
    THRESHOLD("清晰阈值", "文字、线稿与二维码最锐利"),
    FLOYD_STEINBERG("Floyd–Steinberg", "照片层次细腻，通用首选"),
    ATKINSON("Atkinson", "亮部干净、对比更强"),
    JARVIS_JUDICE_NINKE("Jarvis–Judice–Ninke", "渐变柔和，细节丰富"),
    STUCKI("Stucki", "长图稳定，颗粒更均匀"),
    SIERRA_LITE("Sierra Lite", "速度快、边缘清楚"),
    BAYER_4("Bayer 4×4", "规则网点，适合图标与浅灰底"),
    BAYER_8("Bayer 8×8", "更细的规则网点，适合大面积灰阶"),
}

enum class BarcodeType(val label: String, val twoDimensional: Boolean) {
    QR_CODE("QR Code", true),
    GS1_QR("GS1 QR", true),
    DATA_MATRIX("Data Matrix", true),
    GS1_DATA_MATRIX("GS1 Data Matrix", true),
    PDF_417("PDF417", true),
    AZTEC("Aztec", true),
    CODE_128("Code 128", false),
    GS1_128("GS1-128", false),
    CODE_39("Code 39", false),
    CODE_93("Code 93", false),
    CODABAR("Codabar", false),
    EAN_13("EAN-13", false),
    ISBN_13("ISBN-13", false),
    ISSN_13("ISSN-13", false),
    JAN_13("JAN-13", false),
    EAN_8("EAN-8", false),
    UPC_A("UPC-A", false),
    UPC_E("UPC-E", false),
    ITF("ITF", false),
}

data class PaperSettings(
    val mode: PaperMode = PaperMode.CONTINUOUS,
    val shape: PaperShape = PaperShape.RECTANGLE,
    val protocol: PrintProtocol = PrintProtocol.QRING_SPP,
    val dpi: Int = DEFAULT_DPI,
    /** Physical thermal-head width. Qring/BeePrt BY is 384 dots. */
    val headDots: Int = DEFAULT_HEAD_DOTS,
    /** Nominal roll width, including the non-printable side margins. */
    val mediaWidthMm: Float = 57f,
    /** Width of the content/label area. It is aligned inside [headDots]. */
    val contentWidthMm: Float = 48f,
    val labelHeightMm: Float = 30f,
    val horizontalAnchor: HorizontalAnchor = HorizontalAnchor.CENTER,
    val topPaddingMm: Float = 1.5f,
    val bottomPaddingMm: Float = 2f,
    val offsetXmm: Float = 0f,
    val offsetYmm: Float = 0f,
    val tailFeedMm: Float = DEFAULT_TAIL_FEED_MM,
    val labelGapMm: Float = 2f,
    val defaultDither: DitherMode = DitherMode.FLOYD_STEINBERG,
    val threshold: Int = 160,
) {
    /** Signed conversion is intentional: calibration offsets may be negative. */
    fun mmToDots(mm: Float): Int = (mm * dpi / 25.4f).roundToInt()
    fun dotsToMm(dots: Int): Float = dots * 25.4f / dpi

    /** Physical paper width represented at the document DPI, including unprintable side margins. */
    fun paperWidthDots(): Int = max(8, mmToDots(contentWidthMm.coerceIn(MIN_PAPER_WIDTH_MM, MAX_PAPER_WIDTH_MM)))

    fun contentWidthDots(): Int = min(headDots, max(8, mmToDots(contentWidthMm.coerceIn(MIN_PAPER_WIDTH_MM, MAX_PAPER_WIDTH_MM))))

    /** Physical paper position under the print head. Calibration is deliberately not included. */
    fun paperStartX(): Int {
        val free = max(0, headDots - paperWidthDots())
        return when (horizontalAnchor) {
            HorizontalAnchor.LEFT -> 0
            HorizontalAnchor.CENTER -> free / 2
            HorizontalAnchor.RIGHT -> free
        }
    }

    fun contentStartX(): Int {
        val free = max(0, headDots - contentWidthDots())
        return when (horizontalAnchor) {
            HorizontalAnchor.LEFT -> 0
            HorizontalAnchor.CENTER -> free / 2
            HorizontalAnchor.RIGHT -> free
        }
    }

    /** Inclusive start of the physical paper area covered by the thermal head. */
    fun printableStartX(): Int = contentStartX().coerceIn(0, headDots.coerceAtLeast(8) - 8)

    /** Exclusive end of the physical paper area covered by the thermal head. */
    fun printableEndX(): Int =
        (printableStartX() + contentWidthDots()).coerceIn(printableStartX() + 8, headDots.coerceAtLeast(8))

    fun horizontalCalibrationDots(): Int = mmToDots(offsetXmm)

    fun fixedHeightDots(): Int = mmToDots(labelHeightMm).coerceIn(24, MAX_DOCUMENT_HEIGHT_DOTS)

    fun requiresNarrowLoading(): Boolean =
        contentWidthMm.finiteOr(48f) < NARROW_LOADING_THRESHOLD_MM

    /** Normalizes both imported documents and values left by older app versions. */
    fun normalized(): PaperSettings {
        val safeWidth = contentWidthMm.finiteOr(48f).coerceIn(MIN_PAPER_WIDTH_MM, MAX_PAPER_WIDTH_MM)
        val safeAnchor = if (safeWidth < NARROW_LOADING_THRESHOLD_MM && horizontalAnchor == HorizontalAnchor.CENTER) {
            HorizontalAnchor.LEFT
        } else horizontalAnchor
        return copy(
            dpi = dpi.coerceIn(100, 600),
            headDots = headDots.coerceIn(128, 2048),
            mediaWidthMm = mediaWidthMm.finiteOr(57f).coerceIn(MIN_PAPER_WIDTH_MM, MAX_PAPER_WIDTH_MM),
            contentWidthMm = safeWidth,
            horizontalAnchor = safeAnchor,
            labelHeightMm = labelHeightMm.finiteOr(30f).coerceIn(5f, 1_000f),
            topPaddingMm = topPaddingMm.finiteOr(1.5f).coerceIn(0f, 100f),
            bottomPaddingMm = bottomPaddingMm.finiteOr(2f).coerceIn(0f, 100f),
            offsetXmm = offsetXmm.finiteOr(0f).coerceIn(-57f, 57f),
            offsetYmm = offsetYmm.finiteOr(0f).coerceIn(-100f, 100f),
            tailFeedMm = tailFeedMm.finiteOr(DEFAULT_TAIL_FEED_MM).coerceIn(0f, 100f),
            labelGapMm = labelGapMm.finiteOr(2f).coerceIn(0f, 100f),
            threshold = threshold.coerceIn(1, 254),
        )
    }
}

data class LabelElement(
    val id: String = UUID.randomUUID().toString(),
    val kind: ElementKind,
    val x: Int = 8,
    val y: Int = 8,
    val width: Int = 200,
    val height: Int = 56,
    val rotation: Float = 0f,
    val locked: Boolean = false,
    /** Non-empty ids persist an editor group across save/export/import. */
    val groupId: String = "",
    val text: String = "双击编辑文字",
    val fontFamily: String = "sans-serif",
    val fontSizeDots: Float = 28f,
    val fontWeight: Int = 400,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikeThrough: Boolean = false,
    val verticalText: Boolean = false,
    val letterSpacingDots: Float = 0f,
    val lineSpacingDots: Float = 3f,
    val textAlignment: TextAlignment = TextAlignment.LEFT,
    val textEnhancement: TextEnhancementMode = TextEnhancementMode.NONE,
    val imageUri: String = "",
    val imageFit: ImageFit = ImageFit.FIT,
    val ditherMode: DitherMode = DitherMode.FLOYD_STEINBERG,
    val threshold: Int = 160,
    val brightness: Float = 0f,
    val contrast: Float = 1f,
    val invert: Boolean = false,
    val barcodeType: BarcodeType = BarcodeType.QR_CODE,
    val barcodeContent: String = "https://example.com",
    val barcodeCaption: Boolean = true,
    val qrErrorCorrection: String = "M",
    val shapeKind: ShapeKind = ShapeKind.RECTANGLE,
    val strokeWidthDots: Float = 2f,
    val filled: Boolean = false,
    val cornerRadiusDots: Float = 8f,
    val tableRows: Int = 3,
    val tableColumns: Int = 3,
    /** Rows are separated by new lines and cells by the pipe character. */
    val tableData: String = "品名|数量|备注\n示例|1|正常\n|||",
    val tableHeader: Boolean = true,
    val datePattern: String = "yyyy-MM-dd HH:mm",
    val sequenceStart: Long = 1,
    val sequenceStep: Long = 1,
    val sequenceDigits: Int = 4,
    val sequencePrefix: String = "NO.",
    val sequenceSuffix: String = "",
    /** Normalized x/y pairs in 0..1; -1/-1 starts a new stroke. */
    val drawingPoints: List<Float> = emptyList(),
) {
    fun right(): Int = x + width
    fun bottom(): Int = y + height

    /** Bottom of the rotated visual bounds, used by continuous-paper auto length. */
    fun visualBottom(): Int {
        val radians = rotation * PI / 180.0
        val halfExtent = abs(sin(radians)) * width / 2.0 + abs(cos(radians)) * height / 2.0
        val strokeOverflow = if (kind == ElementKind.SHAPE || kind == ElementKind.DRAWING || kind == ElementKind.TABLE) strokeWidthDots / 2.0 else 0.0
        return ceil(y + height / 2.0 + halfExtent + strokeOverflow).toInt()
    }

    fun runtimeText(sequenceIndex: Long = 0, now: Date = Date()): String = when (kind) {
        ElementKind.DATE_TIME -> runCatching {
            SimpleDateFormat(datePattern, Locale.getDefault()).format(now)
        }.getOrDefault(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(now))
        ElementKind.SEQUENCE -> {
            val number = sequenceStart + sequenceIndex * sequenceStep
            sequencePrefix + number.toString().padStart(sequenceDigits.coerceIn(1, 18), '0') + sequenceSuffix
        }
        else -> text
    }
}

data class LabelDocument(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "未命名标签",
    val category: String = "通用",
    val paper: PaperSettings = PaperSettings(),
    val elements: List<LabelElement> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val builtIn: Boolean = false,
) {
    fun continuousHeightDots(): Int {
        val contentBottom = elements.maxOfOrNull { it.visualBottom() } ?: 0
        val offset = paper.mmToDots(paper.offsetYmm)
        val padding = paper.mmToDots(paper.bottomPaddingMm)
        return (contentBottom + offset + padding)
            .coerceIn(24, MAX_DOCUMENT_HEIGHT_DOTS)
    }

    fun outputHeightDots(): Int = when (paper.mode) {
        PaperMode.CONTINUOUS -> continuousHeightDots()
        PaperMode.LABEL -> paper.fixedHeightDots()
    }

    fun normalized(): LabelDocument {
        val safePaper = paper.normalized()
        val usedIds = hashSetOf<String>()
        val primitiveSafe = elements.take(MAX_DOCUMENT_ELEMENTS).map { source ->
            val candidateId = source.id.takeIf { it.isNotBlank() && usedIds.add(it) }
                ?: UUID.randomUUID().toString().also(usedIds::add)
            source.copy(
                id = candidateId,
                x = source.x.coerceIn(-safePaper.headDots * 4, safePaper.headDots * 4),
                y = source.y.coerceIn(-MAX_DOCUMENT_HEIGHT_DOTS, MAX_DOCUMENT_HEIGHT_DOTS),
                width = source.width.coerceIn(MIN_ELEMENT_DOTS, safePaper.headDots * 8),
                height = source.height.coerceIn(MIN_ELEMENT_DOTS, MAX_DOCUMENT_HEIGHT_DOTS),
                rotation = source.rotation.finiteOr(0f).coerceIn(-360f, 360f),
                fontSizeDots = source.fontSizeDots.finiteOr(28f).coerceIn(8f, 240f),
                fontWeight = PrintFontCatalog.normalizeWeight(source.fontWeight),
                letterSpacingDots = source.letterSpacingDots.finiteOr(0f).coerceIn(-12f, 64f),
                lineSpacingDots = source.lineSpacingDots.finiteOr(3f).coerceIn(-12f, 128f),
                threshold = source.threshold.coerceIn(1, 254),
                brightness = source.brightness.finiteOr(0f).coerceIn(-1f, 1f),
                contrast = source.contrast.finiteOr(1f).coerceIn(0.1f, 4f),
                strokeWidthDots = source.strokeWidthDots.finiteOr(2f).coerceIn(1f, 32f),
                cornerRadiusDots = source.cornerRadiusDots.finiteOr(8f).coerceIn(0f, 512f),
                tableRows = source.tableRows.coerceIn(1, 12),
                tableColumns = source.tableColumns.coerceIn(1, 8),
                sequenceDigits = source.sequenceDigits.coerceIn(1, 18),
                drawingPoints = source.drawingPoints.take(20_000).map { it.finiteOr(-1f).coerceIn(-1f, 1f) },
            )
        }
        val base = copy(paper = safePaper, elements = primitiveSafe)
        val contentStart = safePaper.printableStartX()
        val contentEnd = safePaper.printableEndX()
        val maxHeight = base.outputHeightDots()
        val safe = primitiveSafe.map { element ->
            // Built-in decorative layers may deliberately overflow the canvas so a card-preview
            // crop can be magnified to the real label bounds. Canvas clipping keeps them safe.
            if (element.locked && element.kind == ElementKind.IMAGE && element.imageUri.startsWith("android.resource://")) {
                return@map element.copy(
                    width = element.width.coerceAtLeast(MIN_ELEMENT_DOTS),
                    height = element.height.coerceAtLeast(MIN_ELEMENT_DOTS),
                )
            }
            val availableWidth = max(MIN_ELEMENT_DOTS, contentEnd - contentStart)
            val availableHeight = if (safePaper.mode == PaperMode.LABEL) maxHeight else MAX_DOCUMENT_HEIGHT_DOTS
            val w = element.width.coerceIn(MIN_ELEMENT_DOTS, availableWidth)
            val h = element.height.coerceIn(MIN_ELEMENT_DOTS, max(MIN_ELEMENT_DOTS, availableHeight))
            val positioned = element.copy(width = w, height = h).positionedWithinPrintablePaper(
                contentStart = contentStart,
                contentEnd = contentEnd,
                heightLimit = availableHeight,
            )
            element.copy(
                width = w,
                height = h,
                // Clamp the rotated visual footprint, not the hidden unrotated rectangle.  The
                // latter forced x/y back inside the paper after every edit and made a rotated
                // frame impossible to place flush with a physical edge.
                x = positioned.x,
                y = positioned.y,
            )
        }
        return base.copy(elements = safe)
    }
}

/**
 * Returns an element position whose rotated visual rectangle is inside the printable paper.
 * The local rectangle is intentionally allowed to have a negative x/y when rotation creates an
 * overhang; rendering is clipped by the paper, while the blue editor frame remains physically
 * aligned with the same four boundaries as the print output.
 */
private fun LabelElement.positionedWithinPrintablePaper(
    contentStart: Int,
    contentEnd: Int,
    heightLimit: Int,
): LabelElement {
    val radians = Math.toRadians(rotation.toDouble())
    val visualWidth = (abs(cos(radians)) * width + abs(sin(radians)) * height).toFloat()
    val visualHeight = (abs(sin(radians)) * width + abs(cos(radians)) * height).toFloat()
    val paperCenterX = (contentStart + contentEnd) / 2f
    val paperCenterY = heightLimit / 2f
    val localCenterX = x + width / 2f
    val localCenterY = y + height / 2f
    val minCenterX = contentStart + visualWidth / 2f
    val maxCenterX = contentEnd - visualWidth / 2f
    val minCenterY = visualHeight / 2f
    val maxCenterY = heightLimit - visualHeight / 2f
    val safeCenterX = if (minCenterX <= maxCenterX) {
        localCenterX.coerceIn(minCenterX, maxCenterX)
    } else {
        paperCenterX
    }
    val safeCenterY = if (minCenterY <= maxCenterY) {
        localCenterY.coerceIn(minCenterY, maxCenterY)
    } else {
        paperCenterY
    }
    return copy(
        x = (safeCenterX - width / 2f).roundToInt(),
        y = (safeCenterY - height / 2f).roundToInt(),
    )
}

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

data class TemplateSummary(
    val id: String,
    val title: String,
    val category: String,
    val widthMm: Float,
    val heightMm: Float,
    val document: LabelDocument,
)

data class PrintHistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val timestamp: Long = System.currentTimeMillis(),
    val copies: Int = 1,
    val success: Boolean = true,
    val message: String = "打印完成",
    val document: LabelDocument,
)

fun LabelElement.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("kind", kind.name); put("x", x); put("y", y)
    put("width", width); put("height", height); put("rotation", rotation); put("locked", locked); put("groupId", groupId)
    put("text", text); put("fontFamily", fontFamily); put("fontSizeDots", fontSizeDots)
    put("fontWeight", fontWeight); put("italic", italic); put("underline", underline)
    put("strikeThrough", strikeThrough); put("verticalText", verticalText)
    put("letterSpacingDots", letterSpacingDots); put("lineSpacingDots", lineSpacingDots)
    put("textAlignment", textAlignment.name); put("textEnhancement", textEnhancement.name)
    put("imageUri", imageUri); put("imageFit", imageFit.name)
    put("ditherMode", ditherMode.name); put("threshold", threshold); put("brightness", brightness)
    put("contrast", contrast); put("invert", invert); put("barcodeType", barcodeType.name)
    put("barcodeContent", barcodeContent); put("barcodeCaption", barcodeCaption)
    put("qrErrorCorrection", qrErrorCorrection); put("shapeKind", shapeKind.name)
    put("strokeWidthDots", strokeWidthDots); put("filled", filled); put("cornerRadiusDots", cornerRadiusDots)
    put("tableRows", tableRows); put("tableColumns", tableColumns); put("tableData", tableData); put("tableHeader", tableHeader)
    put("datePattern", datePattern); put("sequenceStart", sequenceStart); put("sequenceStep", sequenceStep)
    put("sequenceDigits", sequenceDigits); put("sequencePrefix", sequencePrefix); put("sequenceSuffix", sequenceSuffix)
    put("drawingPoints", JSONArray().apply { drawingPoints.forEach(::put) })
}

fun PaperSettings.toJson(): JSONObject = JSONObject().apply {
    put("mode", mode.name); put("shape", shape.name); put("protocol", protocol.name); put("dpi", dpi); put("headDots", headDots)
    put("mediaWidthMm", mediaWidthMm); put("contentWidthMm", contentWidthMm); put("labelHeightMm", labelHeightMm)
    put("horizontalAnchor", horizontalAnchor.name); put("topPaddingMm", topPaddingMm)
    put("bottomPaddingMm", bottomPaddingMm); put("offsetXmm", offsetXmm); put("offsetYmm", offsetYmm)
    put("tailFeedMm", tailFeedMm); put("labelGapMm", labelGapMm)
    put("defaultDither", defaultDither.name); put("threshold", threshold)
}

fun LabelDocument.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("title", title); put("category", category); put("paper", paper.toJson())
    put("createdAt", createdAt); put("updatedAt", updatedAt); put("builtIn", builtIn)
    put("elements", JSONArray().apply { elements.forEach { put(it.toJson()) } })
}

private inline fun <reified T : Enum<T>> enumOr(value: String?, fallback: T): T =
    runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(fallback)

fun paperSettingsFromJson(json: JSONObject): PaperSettings = PaperSettings(
    mode = enumOr(json.optString("mode"), PaperMode.CONTINUOUS),
    shape = enumOr(json.optString("shape"), PaperShape.RECTANGLE),
    protocol = enumOr(json.optString("protocol"), PrintProtocol.QRING_SPP),
    dpi = json.optInt("dpi", DEFAULT_DPI).coerceIn(100, 600),
    headDots = json.optInt("headDots", DEFAULT_HEAD_DOTS).coerceIn(128, 2048),
    mediaWidthMm = json.optDouble("mediaWidthMm", 57.0).toFloat().coerceIn(MIN_PAPER_WIDTH_MM, MAX_PAPER_WIDTH_MM),
    contentWidthMm = json.optDouble("contentWidthMm", 48.0).toFloat().coerceIn(MIN_PAPER_WIDTH_MM, MAX_PAPER_WIDTH_MM),
    labelHeightMm = json.optDouble("labelHeightMm", 30.0).toFloat(),
    horizontalAnchor = enumOr(json.optString("horizontalAnchor"), HorizontalAnchor.CENTER),
    topPaddingMm = json.optDouble("topPaddingMm", 1.5).toFloat(),
    bottomPaddingMm = json.optDouble("bottomPaddingMm", 2.0).toFloat(),
    offsetXmm = json.optDouble("offsetXmm", 0.0).toFloat(),
    offsetYmm = json.optDouble("offsetYmm", 0.0).toFloat(),
    tailFeedMm = json.optDouble("tailFeedMm", DEFAULT_TAIL_FEED_MM.toDouble()).toFloat(),
    labelGapMm = json.optDouble("labelGapMm", 2.0).toFloat(),
    defaultDither = enumOr(json.optString("defaultDither"), DitherMode.FLOYD_STEINBERG),
    threshold = json.optInt("threshold", 160).coerceIn(1, 254),
)

fun labelElementFromJson(json: JSONObject): LabelElement = LabelElement(
    id = json.optString("id", UUID.randomUUID().toString()),
    kind = enumOr(json.optString("kind"), ElementKind.TEXT),
    x = json.optInt("x", 8), y = json.optInt("y", 8), width = json.optInt("width", 200),
    height = json.optInt("height", 56), rotation = json.optDouble("rotation", 0.0).toFloat(),
    locked = json.optBoolean("locked", false), groupId = json.optString("groupId", ""),
    text = json.optString("text", "双击编辑文字"),
    fontFamily = json.optString("fontFamily", "sans-serif"),
    fontSizeDots = json.optDouble("fontSizeDots", 28.0).toFloat(),
    fontWeight = json.optInt("fontWeight", 400), italic = json.optBoolean("italic", false),
    underline = json.optBoolean("underline", false), strikeThrough = json.optBoolean("strikeThrough", false),
    verticalText = json.optBoolean("verticalText", false),
    letterSpacingDots = json.optDouble("letterSpacingDots", 0.0).toFloat(),
    lineSpacingDots = json.optDouble("lineSpacingDots", 3.0).toFloat(),
    textAlignment = enumOr(json.optString("textAlignment"), TextAlignment.LEFT),
    textEnhancement = enumOr(json.optString("textEnhancement"), TextEnhancementMode.NONE),
    imageUri = json.optString("imageUri", ""), imageFit = enumOr(json.optString("imageFit"), ImageFit.FIT),
    ditherMode = enumOr(json.optString("ditherMode"), DitherMode.FLOYD_STEINBERG),
    threshold = json.optInt("threshold", 160).coerceIn(1, 254),
    brightness = json.optDouble("brightness", 0.0).toFloat(), contrast = json.optDouble("contrast", 1.0).toFloat(),
    invert = json.optBoolean("invert", false), barcodeType = enumOr(json.optString("barcodeType"), BarcodeType.QR_CODE),
    barcodeContent = json.optString("barcodeContent", "https://example.com"),
    barcodeCaption = json.optBoolean("barcodeCaption", true), qrErrorCorrection = json.optString("qrErrorCorrection", "M"),
    shapeKind = enumOr(json.optString("shapeKind"), ShapeKind.RECTANGLE),
    strokeWidthDots = json.optDouble("strokeWidthDots", 2.0).toFloat(), filled = json.optBoolean("filled", false),
    cornerRadiusDots = json.optDouble("cornerRadiusDots", 8.0).toFloat(),
    tableRows = json.optInt("tableRows", 3).coerceIn(1, 12),
    tableColumns = json.optInt("tableColumns", 3).coerceIn(1, 8),
    tableData = json.optString("tableData", "品名|数量|备注\n示例|1|正常\n|||"),
    tableHeader = json.optBoolean("tableHeader", true),
    datePattern = json.optString("datePattern", "yyyy-MM-dd HH:mm"),
    sequenceStart = json.optLong("sequenceStart", 1), sequenceStep = json.optLong("sequenceStep", 1),
    sequenceDigits = json.optInt("sequenceDigits", 4), sequencePrefix = json.optString("sequencePrefix", "NO."),
    sequenceSuffix = json.optString("sequenceSuffix", ""),
    drawingPoints = json.optJSONArray("drawingPoints")?.let { array ->
        buildList { for (index in 0 until array.length()) add(array.optDouble(index, -1.0).toFloat()) }
    } ?: emptyList(),
)

fun labelDocumentFromJson(json: JSONObject): LabelDocument {
    val elementsJson = json.optJSONArray("elements") ?: JSONArray()
    val elements = buildList {
        for (index in 0 until elementsJson.length()) {
            elementsJson.optJSONObject(index)?.let { add(labelElementFromJson(it)) }
        }
    }
    return LabelDocument(
        id = json.optString("id", UUID.randomUUID().toString()),
        title = json.optString("title", "未命名标签"),
        category = json.optString("category", "通用"),
        paper = json.optJSONObject("paper")?.let(::paperSettingsFromJson) ?: PaperSettings(),
        elements = elements,
        createdAt = json.optLong("createdAt", System.currentTimeMillis()),
        updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
        builtIn = json.optBoolean("builtIn", false),
    )
}

fun PrintHistoryItem.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("title", title); put("timestamp", timestamp); put("copies", copies)
    put("success", success); put("message", message); put("document", document.toJson())
}

fun printHistoryFromJson(json: JSONObject): PrintHistoryItem = PrintHistoryItem(
    id = json.optString("id", UUID.randomUUID().toString()),
    title = json.optString("title", "打印记录"), timestamp = json.optLong("timestamp", System.currentTimeMillis()),
    copies = json.optInt("copies", 1), success = json.optBoolean("success", true),
    message = json.optString("message", "打印完成"),
    document = json.optJSONObject("document")?.let(::labelDocumentFromJson) ?: LabelDocument(),
)
