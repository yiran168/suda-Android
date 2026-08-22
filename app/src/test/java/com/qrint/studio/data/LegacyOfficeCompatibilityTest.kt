package com.qrint.studio.data

import org.apache.poi.hslf.usermodel.HSLFSlideShow
import org.apache.poi.hwpf.HWPFDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Read-only smoke tests for the Android-shaded POI runtime.
 *
 * Do not create HSLF shapes here: shape creation invokes java.awt drawing APIs that are
 * deliberately outside the Android adapter's supported surface. The app only reads documents,
 * so real files from Apache POI's official test corpus exercise the production path accurately.
 */
class LegacyOfficeCompatibilityTest {
    @Test fun bundledAndroidPoiReadsLegacyPowerPoint() {
        val bytes = resource("/legacy/with_textbox.ppt").use { it.readBytes() }
        assertEquals(
            LocalDocumentKind.LEGACY_POWERPOINT,
            LocalDocumentFormatDetector.detectOleStreamEvidence(bytes).kind(),
        )
        bytes.inputStream().use { input ->
            HSLFSlideShow(input).use { show ->
                assertTrue("Expected at least one slide", show.slides.isNotEmpty())
                val text = show.slides
                    .flatMap { it.textParagraphs }
                    .flatten()
                    .flatMap { it.textRuns }
                    .joinToString("") { it.rawText }
                assertTrue("Expected readable text in the legacy PPT", text.isNotBlank())
            }
        }
    }

    @Test fun bundledAndroidPoiReadsLegacyWord() {
        val bytes = resource("/legacy/lists-margins.doc").use { it.readBytes() }
        assertEquals(
            LocalDocumentKind.LEGACY_WORD,
            LocalDocumentFormatDetector.detectOleStreamEvidence(bytes).kind(),
        )
        bytes.inputStream().use { input ->
            HWPFDocument(input).use { document ->
                assertTrue(
                    "Expected readable text in the legacy DOC",
                    document.range.text().isNotBlank(),
                )
            }
        }
    }

    private fun resource(path: String) = requireNotNull(javaClass.getResourceAsStream(path)) {
        "Missing test resource: $path"
    }
}
