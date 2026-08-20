package com.qrint.studio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VariableDataQueryTest {
    private val table = VariableDataTable(
        sourceName = "商品.csv",
        headers = listOf("名称", "价格"),
        rows = listOf(
            mapOf("名称" to "红茶", "价格" to "18"),
            mapOf("名称" to "绿茶", "价格" to "9"),
            mapOf("名称" to "奶茶", "价格" to "12"),
        ),
    )

    @Test fun filterAndNumericSortUseTheSameRowsAsBatchPrinting() {
        assertEquals(listOf("9", "12", "18"), queryVariableRows(table, "", "价格", true).map { it["价格"] })
        assertEquals("红茶", queryVariableRows(table, "红", null, true).single()["名称"])
    }

    @Test fun emptyAndOversizedRangesAreSafe() {
        assertTrue(normalizeVariableRange(0, 0..9).isEmpty())
        assertEquals(1..2, normalizeVariableRange(3, 1..99))
        assertEquals(2, variableRowsIn(table.rows, 1..99).size)
    }
}
