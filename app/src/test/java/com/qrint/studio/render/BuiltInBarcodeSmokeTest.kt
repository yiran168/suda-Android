package com.qrint.studio.render

import com.qrint.studio.data.TemplateCatalog
import com.qrint.studio.model.BarcodeType
import com.qrint.studio.model.ElementKind
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInBarcodeSmokeTest {
    @Test fun everyBarcodeFormatHasAnEncodableNormalizedDefault() {
        BarcodeType.entries.forEach { type ->
            assertTrue("${type.label} default should encode", isNativelyEncodable(type, ""))
        }
    }

    @Test fun allDecodedTemplateCodesEncodeInTheirStoredFormat() {
        val codes = TemplateCatalog.all.flatMap { template ->
            template.document.elements.filter { it.kind == ElementKind.BARCODE }
        }
        assertTrue(codes.size >= 180)
        codes.forEach { element ->
            assertTrue(
                "${element.id}: ${element.barcodeType.label} should encode ${element.barcodeContent.take(40)}",
                isNativelyEncodable(element.barcodeType, element.barcodeContent),
            )
        }
    }
}
