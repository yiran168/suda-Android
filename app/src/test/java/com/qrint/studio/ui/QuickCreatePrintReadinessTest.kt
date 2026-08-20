package com.qrint.studio.ui

import com.qrint.studio.model.ElementKind
import com.qrint.studio.model.PaperMode
import com.qrint.studio.model.PaperSettings
import com.qrint.studio.model.PrintProtocol
import com.qrint.studio.model.printReadiness
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickCreatePrintReadinessTest {
    @Test
    fun everyQuickCreationModeHasAValidPrintablePath() {
        QuickCreateKind.entries.forEach { kind ->
            val created = quickDocument(kind, PaperSettings())
            val readyDocument = if (kind == QuickCreateKind.IMAGE) {
                created.copy(elements = created.elements.map { it.copy(imageUri = "content://test/photo.jpg") })
            } else created

            assertTrue("${kind.title} should have printable content", readyDocument.printReadiness().ready)
            assertTrue("${kind.title} should have positive output height", readyDocument.outputHeightDots() > 0)
        }
    }

    @Test
    fun allCreationModesRemainPrintableForBothPaperModesAndProtocols() {
        QuickCreateKind.entries.forEach { kind ->
            PaperMode.entries.forEach { paperMode ->
                PrintProtocol.entries.forEach { protocol ->
                    val paper = PaperSettings(
                        mode = paperMode,
                        protocol = protocol,
                        mediaWidthMm = 40f,
                        contentWidthMm = 40f,
                        labelHeightMm = 30f,
                    )
                    val created = quickDocument(kind, paper)
                    val readyDocument = if (kind == QuickCreateKind.IMAGE) {
                        created.copy(elements = created.elements.map {
                            it.copy(imageUri = "content://test/photo.jpg")
                        })
                    } else created
                    val context = "${kind.name}/$paperMode/$protocol"

                    assertTrue("$context should pass semantic preflight", readyDocument.printReadiness().ready)
                    assertTrue("$context should have a positive raster height", readyDocument.outputHeightDots() > 0)
                    assertTrue(
                        "$context should remain inside the configured print head",
                        readyDocument.paper.contentWidthDots() <= readyDocument.paper.headDots,
                    )
                }
            }
        }
    }

    @Test
    fun freeCanvasStartsWithAnEditablePrintableTextLayer() {
        val canvas = quickDocument(QuickCreateKind.CANVAS, PaperSettings())

        assertTrue(canvas.printReadiness().ready)
        assertTrue(canvas.elements.any { it.kind == ElementKind.TEXT && it.text.isNotBlank() })
    }

    @Test
    fun incompleteImageAndEmptyCanvasAreBlockedBeforeTransport() {
        val image = quickDocument(QuickCreateKind.IMAGE, PaperSettings())
        val emptyCanvas = image.copy(elements = emptyList())

        assertFalse(image.printReadiness().ready)
        assertFalse(emptyCanvas.printReadiness().ready)
    }
}
