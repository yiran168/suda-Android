package com.qrint.studio.ui

import com.qrint.studio.model.BarcodeType
import com.qrint.studio.model.EditorFactories
import com.qrint.studio.model.ElementKind
import com.qrint.studio.model.LabelDocument
import com.qrint.studio.model.LabelElement
import com.qrint.studio.model.PaperMode
import com.qrint.studio.model.PaperSettings
import com.qrint.studio.model.ShapeKind
import com.qrint.studio.model.TextAlignment

enum class QuickCreateKind(val title: String, val subtitle: String) {
    TEXT("文字排版", "标题、段落、日期与流水号"),
    IMAGE("图片打印", "照片、Logo 与 8 种半色调"),
    QR("二维码", "网址、文本、Wi-Fi 与联系方式"),
    BARCODE("一维条码", "商品码、资产码与 GS1 编码"),
    NOTE("便签纸", "横线、方格、清单与空白便签"),
    CANVAS("自由画布", "文字、图形、表格与手绘混排"),
}

enum class NoteStyle(val title: String) { RULED("横线"), GRID("方格"), CHECKLIST("清单"), BLANK("空白") }

fun quickDocument(
    kind: QuickCreateKind,
    paper: PaperSettings,
    title: String = kind.title,
    noteStyle: NoteStyle = NoteStyle.RULED,
): LabelDocument {
    val base = EditorFactories.blankDocument(title = title.ifBlank { kind.title }, paper = paper)
    return when (kind) {
        QuickCreateKind.TEXT -> base.copy(elements = listOf(EditorFactories.textElement()))
        QuickCreateKind.IMAGE -> base.copy(elements = listOf(EditorFactories.imageElement("")))
        QuickCreateKind.QR -> base.copy(elements = listOf(EditorFactories.barcodeElement(BarcodeType.QR_CODE)))
        QuickCreateKind.BARCODE -> base.copy(elements = listOf(EditorFactories.barcodeElement(BarcodeType.CODE_128)))
        QuickCreateKind.NOTE -> base.copy(elements = noteElements(paper, noteStyle))
        // A completely empty bitmap is ignored by some Qring firmware and therefore does not feed
        // paper. Start with one ordinary, removable layer so the canvas is immediately editable
        // and printable while still allowing the user to delete it for a clean composition.
        QuickCreateKind.CANVAS -> base.copy(elements = listOf(EditorFactories.textElement()))
    }
}

private fun noteElements(paper: PaperSettings, style: NoteStyle): List<LabelElement> {
    val x = paper.contentStartX() + 12
    val width = (paper.contentWidthDots() - 24).coerceAtLeast(80)
    val height = if (paper.mode == PaperMode.LABEL) paper.fixedHeightDots() else paper.mmToDots(65f)
    val header = LabelElement(
        id = "note-title", kind = ElementKind.TEXT, x = x, y = 12, width = width, height = 46,
        text = "随手记", fontSizeDots = 28f, fontWeight = 700, textAlignment = TextAlignment.LEFT,
    )
    val usableBottom = (height - 16).coerceAtLeast(88)
    return when (style) {
        NoteStyle.BLANK -> listOf(header, LabelElement(
            id = "note-body", kind = ElementKind.TEXT, x = x, y = 70, width = width, height = usableBottom - 70,
            text = "点击这里开始记录……", fontSizeDots = 20f, lineSpacingDots = 8f,
        ))
        NoteStyle.RULED -> buildList {
            add(header)
            var y = 72
            var index = 0
            while (y < usableBottom) {
                add(LabelElement(
                    id = "rule-${index++}", kind = ElementKind.SHAPE, x = x, y = y,
                    width = width, height = 16, shapeKind = ShapeKind.DASHED_LINE, strokeWidthDots = 1f,
                ))
                y += 40
            }
        }
        NoteStyle.GRID -> buildList {
            add(header)
            val grid = 38
            var y = 70
            var index = 0
            while (y <= usableBottom) {
                add(LabelElement(id = "grid-h-${index++}", kind = ElementKind.SHAPE, x = x, y = y, width = width, height = 16, shapeKind = ShapeKind.LINE, strokeWidthDots = 1f))
                y += grid
            }
            var columnX = x
            index = 0
            while (columnX <= x + width) {
                add(LabelElement(id = "grid-v-${index++}", kind = ElementKind.SHAPE, x = columnX, y = 70, width = usableBottom - 70, height = 16, rotation = 90f, shapeKind = ShapeKind.LINE, strokeWidthDots = 1f))
                columnX += grid
            }
        }
        NoteStyle.CHECKLIST -> buildList {
            add(header)
            var y = 72
            var index = 1
            while (y + 32 < usableBottom) {
                add(LabelElement(id = "check-$index", kind = ElementKind.SHAPE, x = x, y = y, width = 24, height = 24, shapeKind = ShapeKind.ROUNDED_RECTANGLE, strokeWidthDots = 2f, cornerRadiusDots = 4f))
                add(LabelElement(id = "check-text-$index", kind = ElementKind.TEXT, x = x + 36, y = y - 2, width = width - 36, height = 30, text = "待办事项 $index", fontSizeDots = 19f))
                y += 42
                index++
            }
        }
    }
}
