package com.qrint.studio.model

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

class DeviceProfileTest {
    @Test
    fun offsetsAndProtocolApplyWithoutReplacingJobPaperSettings() {
        val paper = PaperSettings(
            mode = PaperMode.LABEL,
            contentWidthMm = 40f,
            labelHeightMm = 30f,
            labelGapMm = 7f,
            horizontalAnchor = HorizontalAnchor.LEFT,
        )
        val element = LabelElement(kind = ElementKind.TEXT, x = 16, y = 20, width = 80, height = 40)
        val document = LabelDocument(paper = paper, elements = listOf(element))
        val profile = PaperSettings(
            protocol = PrintProtocol.GENERIC_ESC_POS,
            dpi = paper.dpi,
            headDots = paper.headDots,
            offsetXmm = 1.3f,
            offsetYmm = -0.8f,
        )

        val applied = document.withDeviceProfile(profile)

        assertEquals(PrintProtocol.GENERIC_ESC_POS, applied.paper.protocol)
        assertEquals(1.3f, applied.paper.offsetXmm, 0f)
        assertEquals(-0.8f, applied.paper.offsetYmm, 0f)
        assertEquals(PaperMode.LABEL, applied.paper.mode)
        assertEquals(40f, applied.paper.contentWidthMm, 0f)
        assertEquals(30f, applied.paper.labelHeightMm, 0f)
        assertEquals(7f, applied.paper.labelGapMm, 0f)
        assertEquals(element, applied.elements.single())
    }

    @Test
    fun dpiChangeKeepsElementPhysicalMeasurements() {
        val sourcePaper = PaperSettings(
            dpi = 203,
            headDots = 384,
            contentWidthMm = 40f,
            horizontalAnchor = HorizontalAnchor.LEFT,
        )
        val element = LabelElement(
            kind = ElementKind.TEXT,
            x = sourcePaper.printableStartX() + 16,
            y = 20,
            width = 80,
            height = 40,
            fontSizeDots = 28f,
        )
        val document = LabelDocument(paper = sourcePaper, elements = listOf(element))
        val profile = PaperSettings(dpi = 300, headDots = 576, offsetXmm = -1f, offsetYmm = 2f)

        val applied = document.withDeviceProfile(profile)
        val mapped = applied.elements.single()
        val scale = 300f / 203f

        assertEquals(300, applied.paper.dpi)
        assertEquals(576, applied.paper.headDots)
        assertEquals((16 * scale).roundToInt(), mapped.x - applied.paper.printableStartX())
        assertEquals((20 * scale).roundToInt(), mapped.y)
        assertEquals((80 * scale).roundToInt(), mapped.width)
        assertEquals((40 * scale).roundToInt(), mapped.height)
        assertEquals(28f * scale, mapped.fontSizeDots, 0.001f)
    }
}
