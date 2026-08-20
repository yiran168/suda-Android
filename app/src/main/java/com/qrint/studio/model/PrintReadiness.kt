package com.qrint.studio.model

data class PrintReadiness(
    val ready: Boolean,
    val message: String = "",
)

/**
 * Fast semantic preflight shared by every editor entry point.
 * The printer performs a second, raster-level check after rendering because a valid image can
 * still become completely white after thresholding.
 */
fun LabelDocument.printReadiness(): PrintReadiness {
    if (elements.isEmpty()) return PrintReadiness(false, "画布为空，请先添加文字、图片、条码或图形")
    if (elements.none(LabelElement::hasVisiblePrintContent)) {
        return PrintReadiness(false, "画布没有可打印内容，请填写文字、选择图片或完成手绘")
    }
    return PrintReadiness(true)
}

private fun LabelElement.hasVisiblePrintContent(): Boolean = when (kind) {
    ElementKind.TEXT -> text.isNotBlank()
    ElementKind.DATE_TIME, ElementKind.SEQUENCE -> true
    ElementKind.IMAGE -> imageUri.isNotBlank()
    ElementKind.BARCODE -> barcodeContent.isNotBlank()
    ElementKind.SHAPE, ElementKind.TABLE -> true
    ElementKind.DRAWING -> drawingPoints.size >= 4
}
