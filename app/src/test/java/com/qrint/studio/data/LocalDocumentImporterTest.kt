package com.qrint.studio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDocumentImporterTest {
    @Test fun concreteWordAndPowerPointExtensionsWinOverWrongExcelMime() {
        assertEquals(
            LocalDocumentKind.LEGACY_WORD,
            LocalDocumentFormatDetector.detectFromMetadata("合同.doc", "application/vnd.ms-excel"),
        )
        assertEquals(
            LocalDocumentKind.LEGACY_POWERPOINT,
            LocalDocumentFormatDetector.detectFromMetadata("演示.ppt", "application/vnd.ms-excel"),
        )
        assertEquals(
            LocalDocumentKind.DOCX,
            LocalDocumentFormatDetector.detectFromMetadata("报告.docx", "application/vnd.ms-excel"),
        )
        assertEquals(
            LocalDocumentKind.PPTX,
            LocalDocumentFormatDetector.detectFromMetadata("介绍.pptx", "application/vnd.ms-excel"),
        )
    }

    @Test fun ooxmlPackageEntriesIdentifyTheRealDocumentKind() {
        assertEquals(LocalDocumentKind.DOCX, LocalDocumentFormatDetector.packageEntryKind("word/document.xml"))
        assertEquals(LocalDocumentKind.PPTX, LocalDocumentFormatDetector.packageEntryKind("ppt/presentation.xml"))
        assertEquals(LocalDocumentKind.PPTX, LocalDocumentFormatDetector.packageEntryKind("ppt/slides/slide12.xml"))
        assertEquals(LocalDocumentKind.SPREADSHEET, LocalDocumentFormatDetector.packageEntryKind("xl/workbook.xml"))
        assertEquals(LocalDocumentKind.UNKNOWN, LocalDocumentFormatDetector.packageEntryKind("[Content_Types].xml"))
    }

    @Test fun oleStreamNamesOverrideMisleadingExcelMetadata() {
        val word = "WordDocument\u0000".toByteArray(Charsets.UTF_16LE)
        val powerPoint = "PowerPoint Document\u0000".toByteArray(Charsets.UTF_16LE)
        val workbook = "Workbook\u0000".toByteArray(Charsets.UTF_16LE)

        assertEquals(
            LocalDocumentKind.LEGACY_WORD,
            LocalDocumentFormatDetector.detectOleStreamEvidence(word).kind(),
        )
        assertEquals(
            LocalDocumentKind.LEGACY_POWERPOINT,
            LocalDocumentFormatDetector.detectOleStreamEvidence(powerPoint).kind(),
        )
        assertEquals(
            LocalDocumentKind.SPREADSHEET,
            LocalDocumentFormatDetector.detectOleStreamEvidence(workbook).kind(),
        )
    }

    @Test fun encryptedOfficeContainerIsNotSentToSpreadsheetParser() {
        val bytes = "EncryptedPackage\u0000Workbook\u0000".toByteArray(Charsets.UTF_16LE)

        assertEquals(
            LocalDocumentKind.ENCRYPTED_OFFICE,
            LocalDocumentFormatDetector.detectOleStreamEvidence(bytes).kind(),
        )
    }

    @Test fun textWrappingCountsChineseAsDoubleWidthAndPreservesParagraphs() {
        val lines = wrapTextForPrint("AB中文CD\n第二段", maximumUnits = 6)

        assertEquals(listOf("AB中文", "CD", "第二段"), lines)
    }

    @Test fun textWrappingNeverDropsCharacters() {
        val source = "仓储物流-BOX-1888-2026"
        val lines = wrapTextForPrint(source, maximumUnits = 7)

        assertEquals(source, lines.joinToString(""))
        assertTrue(lines.all(String::isNotEmpty))
    }
}
