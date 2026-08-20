package com.qrint.studio.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PrintFontCatalogTest {
    @Test fun quickWeightsOnlyExposeRegularAndBold() {
        assertEquals(listOf(400, 700), PrintFontCatalog.weightPresets.map { it.value })
    }

    @Test fun continuousWeightValuesAreOnlyClampedAtTheSupportedEdges() {
        assertEquals(100, PrintFontCatalog.normalizeWeight(1))
        assertEquals(537, PrintFontCatalog.normalizeWeight(537))
        assertEquals(900, PrintFontCatalog.normalizeWeight(999))
    }
}
