package com.qrint.studio.data

import com.qrint.studio.model.BarcodeType
import com.qrint.studio.model.ElementKind
import com.qrint.studio.model.LabelDocument
import com.qrint.studio.model.LabelElement
import com.qrint.studio.render.DecodedBarcode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeTemplateMatcherTest {
    private val decoded = DecodedBarcode("6901234567892", BarcodeType.EAN_13)

    @Test
    fun exactCodeRanksAheadOfVariableTemplate() {
        val exact = LabelDocument(title = "精确", elements = listOf(LabelElement(kind = ElementKind.BARCODE, barcodeContent = decoded.content)))
        val variable = LabelDocument(title = "变量", elements = listOf(LabelElement(kind = ElementKind.BARCODE, barcodeContent = "{{条码}}")))
        val matches = CodeTemplateMatcher.match(decoded, listOf(variable, exact), null)
        assertEquals("精确", matches.first().document.title)
        assertEquals(100, matches.first().score)
        assertEquals(85, matches.last().score)
    }

    @Test
    fun applyingMatchFillsProductFieldsAndUsesScannedSymbology() {
        val template = LabelDocument(
            title = "商品标签",
            elements = listOf(
                LabelElement(kind = ElementKind.TEXT, text = "{{商品名}} {{价格}}"),
                LabelElement(kind = ElementKind.BARCODE, barcodeType = BarcodeType.QR_CODE, barcodeContent = "{{条码}}"),
            ),
        )
        val result = CodeTemplateMatcher.apply(template, decoded, ProductRecord(name = "红茶", price = "12", barcode = decoded.content))
        assertEquals("红茶 12", result.elements[0].text)
        assertEquals(BarcodeType.EAN_13, result.elements[1].barcodeType)
        assertEquals(decoded.content, result.elements[1].barcodeContent)
        assertTrue(!result.builtIn)
    }
}
