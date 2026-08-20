package com.qrint.studio.printer

import com.qrint.studio.model.PaperMode
import com.qrint.studio.model.PaperSettings
import com.qrint.studio.model.DEFAULT_TAIL_FEED_MM
import com.qrint.studio.render.RasterData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PrintJobPolicyTest {
    @Test
    fun continuousPaperDefaultsToFiveMillimetres() {
        val paper = PaperSettings(mode = PaperMode.CONTINUOUS)

        assertEquals(5f, DEFAULT_TAIL_FEED_MM, 0f)
        assertEquals(paper.mmToDots(5f), PrintJobPolicy.trailingFeedDots(paper))
    }

    @Test
    fun continuousPaperUsesTailFeed() {
        val paper = PaperSettings(mode = PaperMode.CONTINUOUS, tailFeedMm = 8f)

        assertEquals(paper.mmToDots(8f), PrintJobPolicy.trailingFeedDots(paper))
        assertTrue(PrintJobPolicy.trailingFeedCommands(paper).isNotEmpty())
    }

    @Test
    fun labelPaperUsesLabelGap() {
        val paper = PaperSettings(mode = PaperMode.LABEL, labelGapMm = 2f)

        assertEquals(paper.mmToDots(2f), PrintJobPolicy.trailingFeedDots(paper))
        assertTrue(PrintJobPolicy.trailingFeedCommands(paper).isNotEmpty())
    }

    @Test
    fun qringLabelGapIsPartOfRasterMotorHeight() {
        val paper = PaperSettings(mode = PaperMode.LABEL, labelGapMm = 7f)
        val source = RasterData(384, 48, 10, ByteArray(480).also { it[0] = 0x01 })

        val prepared = PrintJobPolicy.qringRasterWithTrailingFeed(source, paper)

        assertEquals(source.heightDots + paper.mmToDots(7f), prepared.heightDots)
        assertEquals(prepared.widthBytes * prepared.heightDots, prepared.bytes.size)
        assertTrue(prepared.bytes.drop(source.bytes.size).all { it == 0.toByte() })
    }

    @Test
    fun negativeCalibrationCannotReversePaper() {
        val paper = PaperSettings(mode = PaperMode.CONTINUOUS, tailFeedMm = -8f)

        assertEquals(0, PrintJobPolicy.trailingFeedDots(paper))
        assertTrue(PrintJobPolicy.trailingFeedCommands(paper).isEmpty())
    }

    @Test
    fun manualFeedUsesTheSamePhysicalMillimetreConversion() {
        val paper = PaperSettings(dpi = 203)

        assertEquals(paper.mmToDots(1f), PrintJobPolicy.manualFeedPlan(paper, 1f).dots)
        assertEquals(paper.mmToDots(5f), PrintJobPolicy.manualFeedPlan(paper, 5f).dots)
        assertTrue(PrintJobPolicy.manualFeedPlan(paper, 100f).commands.size > 1)
    }

    @Test
    fun manualFeedRejectsUnsafeOrNonFiniteDistances() {
        val paper = PaperSettings()

        assertThrows(IllegalArgumentException::class.java) { PrintJobPolicy.manualFeedPlan(paper, 0f) }
        assertThrows(IllegalArgumentException::class.java) { PrintJobPolicy.manualFeedPlan(paper, 100.1f) }
        assertThrows(IllegalArgumentException::class.java) { PrintJobPolicy.manualFeedPlan(paper, Float.NaN) }
        assertTrue(PrintJobPolicy.isManualFeedDistanceValid(0.1f))
        assertTrue(PrintJobPolicy.isManualFeedDistanceValid(100f))
    }

    @Test
    fun everyQringCopyKeepsItsOwnRasterAndTrailingMovement() {
        val paper = PaperSettings(mode = PaperMode.LABEL, labelGapMm = 2f)
        val first = RasterData(16, 2, 2, byteArrayOf(1, 2, 3, 4))
        val second = RasterData(16, 2, 2, byteArrayOf(5, 6, 7, 8))

        val prepared = listOf(first, second).map { PrintJobPolicy.qringRasterWithTrailingFeed(it, paper) }

        assertEquals(2, prepared.size)
        assertEquals(first.heightDots + paper.mmToDots(2f), prepared[0].heightDots)
        assertEquals(second.heightDots + paper.mmToDots(2f), prepared[1].heightDots)
        assertEquals(1, prepared[0].bytes.first().toInt())
        assertEquals(5, prepared[1].bytes.first().toInt())
    }
}
