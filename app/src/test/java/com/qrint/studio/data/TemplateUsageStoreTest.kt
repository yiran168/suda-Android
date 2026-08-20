package com.qrint.studio.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateUsageStoreTest {
    @Test
    fun recentIdsAreNewestFirstDistinctBoundedAndIgnoreBlanks() {
        assertEquals(
            listOf("b", "a", "c"),
            TemplateUsageStore.normalizeRecentIds(listOf(" b ", "a", "b", "", "c", "d"), max = 3),
        )
    }
}
