package com.qrint.studio.model

import java.util.UUID

object EditorFactories {
    fun blankDocument(title: String = "新建标签", paper: PaperSettings = PaperSettings()): LabelDocument =
        LabelDocument(title = title, paper = paper)

    fun textElement(x: Int = 24, y: Int = 24) = LabelElement(
        kind = ElementKind.TEXT,
        x = x,
        y = y,
        width = 336,
        height = 64,
        text = "在这里输入文字",
        fontSizeDots = 30f,
    )

    fun imageElement(uri: String, x: Int = 72, y: Int = 24) = LabelElement(
        kind = ElementKind.IMAGE,
        x = x,
        y = y,
        width = 240,
        height = 180,
        imageUri = uri,
    )

    fun barcodeElement(type: BarcodeType = BarcodeType.QR_CODE, x: Int = 112, y: Int = 24) = LabelElement(
        kind = ElementKind.BARCODE,
        x = if (type.twoDimensional) x else 32,
        y = y,
        width = if (type.twoDimensional) 160 else 320,
        height = if (type.twoDimensional) 160 else 112,
        barcodeType = type,
            barcodeContent = if (type.twoDimensional) "https://example.com" else "LINGYIN-2026",
    )

    fun shapeElement(kind: ShapeKind = ShapeKind.ROUNDED_RECTANGLE, x: Int = 32, y: Int = 24) = LabelElement(
        kind = ElementKind.SHAPE,
        x = x,
        y = y,
        width = 320,
        height = 96,
        shapeKind = kind,
        strokeWidthDots = 2f,
    )

    fun tableElement(x: Int = 28, y: Int = 24) = LabelElement(
        kind = ElementKind.TABLE,
        x = x,
        y = y,
        width = 328,
        height = 156,
        fontSizeDots = 18f,
        strokeWidthDots = 2f,
        tableRows = 3,
        tableColumns = 3,
        tableData = "品名|数量|备注\n示例|1|正常\n|||",
    )

    fun dateElement(x: Int = 64, y: Int = 24) = LabelElement(
        kind = ElementKind.DATE_TIME,
        x = x,
        y = y,
        width = 256,
        height = 48,
        fontSizeDots = 24f,
        datePattern = "yyyy-MM-dd HH:mm",
    )

    fun sequenceElement(x: Int = 104, y: Int = 24) = LabelElement(
        kind = ElementKind.SEQUENCE,
        x = x,
        y = y,
        width = 176,
        height = 52,
        fontSizeDots = 28f,
        sequencePrefix = "NO.",
        sequenceStart = 1,
        sequenceDigits = 4,
    )

    fun drawingElement(points: List<Float>, x: Int = 42, y: Int = 24) = LabelElement(
        kind = ElementKind.DRAWING,
        x = x,
        y = y,
        width = 300,
        height = 130,
        strokeWidthDots = 3f,
        drawingPoints = points,
    )

    fun duplicate(element: LabelElement) = element.copy(
        id = UUID.randomUUID().toString(),
        x = element.x + 10,
        y = element.y + 10,
        locked = false,
    )
}
