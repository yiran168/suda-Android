package com.qrint.studio.render

import com.qrint.studio.model.BarcodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeNormalizerTest {
    @Test fun ean13ShortInputIsPaddedAndGetsChecksum() {
        val result = BarcodeNormalizer.normalize(BarcodeType.EAN_13, "1234567890")
        assertEquals(13, result.value.length)
        assertTrue(result.value.all(Char::isDigit))
        assertEquals(BarcodeNormalizer.mod10(result.value.dropLast(1)), result.value.last().digitToInt())
    }

    @Test fun ean8LongInputNeverFails() {
        val result = BarcodeNormalizer.normalize(BarcodeType.EAN_8, "1234567890")
        assertEquals(8, result.value.length)
        assertTrue(result.changed)
    }

    @Test fun upcVariantsAreNormalized() {
        assertEquals(12, BarcodeNormalizer.normalize(BarcodeType.UPC_A, "12345678901").value.length)
        assertEquals(7, BarcodeNormalizer.normalize(BarcodeType.UPC_E, "1234567890").value.length)
    }

    @Test fun itfDropsLettersAndUsesEvenLength() {
        val value = BarcodeNormalizer.normalize(BarcodeType.ITF, "AB12-345").value
        assertTrue(value.all(Char::isDigit))
        assertEquals(0, value.length % 2)
    }

    @Test fun pdf417AcceptsArbitraryText() {
        val result = BarcodeNormalizer.normalize(BarcodeType.PDF_417, "你好 / PDF417")
        assertFalse(result.value.isBlank())
    }

    @Test fun publishingAndRetailAliasesAlwaysProduceLegalEan13() {
        listOf(
            BarcodeType.ISBN_13 to "978-7-121-00000-0",
            BarcodeType.ISSN_13 to "1000-1234",
            BarcodeType.JAN_13 to "490123456789",
        ).forEach { (type, raw) ->
            val value = BarcodeNormalizer.normalize(type, raw).value
            assertEquals(type.label, 13, value.length)
            assertTrue(type.label, value.all(Char::isDigit))
            assertEquals(type.label, BarcodeNormalizer.mod10(value.dropLast(1)), value.last().digitToInt())
        }
    }

    @Test fun gs1AndCodabarAreNeverBlank() {
        assertFalse(BarcodeNormalizer.normalize(BarcodeType.GS1_128, "").value.isBlank())
        val codabar = BarcodeNormalizer.normalize(BarcodeType.CODABAR, "abc").value
        assertTrue(codabar.startsWith("A") && codabar.endsWith("A"))
    }
}
