package com.qrint.studio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductLibraryStoreTest {
    @Test
    fun importsCommonChineseAndEnglishColumnAliases() {
        val record = ProductLibraryStore.fromRow(
            mapOf("商品名称" to "杨枝甘露", "EAN" to "6901234567892", "单价" to "18", "规格" to "中杯"),
        )!!
        assertEquals("杨枝甘露", record.name)
        assertEquals("6901234567892", record.barcode)
        assertEquals("18", record.price)
        assertEquals("中杯", record.spec)
        assertNull(ProductLibraryStore.fromRow(mapOf("未知" to "值")))
    }

    @Test
    fun searchCoversBusinessFieldsCaseInsensitively() {
        val records = listOf(
            ProductRecord(name = "Green Tea", barcode = "1001", brand = "Lingyin"),
            ProductRecord(name = "红茶", barcode = "1002", category = "饮品"),
        )
        assertEquals("1001", ProductLibraryStore.search(records, "LINGYIN").single().barcode)
        assertTrue(ProductLibraryStore.search(records, "饮品").single().name == "红茶")
    }
}
