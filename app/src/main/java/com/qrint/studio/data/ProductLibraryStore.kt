package com.qrint.studio.data

import android.content.Context
import android.util.AtomicFile
import com.qrint.studio.model.BarcodeType
import com.qrint.studio.model.ElementKind
import com.qrint.studio.model.LabelDocument
import com.qrint.studio.model.LabelElement
import com.qrint.studio.model.MIN_ELEMENT_DOTS
import com.qrint.studio.model.PaperMode
import com.qrint.studio.model.PaperSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.UUID

data class ProductRecord(
    val id: String = UUID.randomUUID().toString(),
    val barcode: String = "",
    val name: String = "",
    val sku: String = "",
    val spec: String = "",
    val price: String = "",
    val unit: String = "",
    val category: String = "",
    val brand: String = "",
    val note: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun normalized(): ProductRecord = copy(
        id = id.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString(),
        barcode = barcode.trim().take(256),
        name = name.trim().take(256),
        sku = sku.trim().take(128),
        spec = spec.trim().take(256),
        price = price.trim().take(64),
        unit = unit.trim().take(64),
        category = category.trim().take(128),
        brand = brand.trim().take(128),
        note = note.trim().take(1_024),
    )

    fun variables(): Map<String, String> = mapOf(
        "条码" to barcode,
        "商品条码" to barcode,
        "barcode" to barcode,
        "code" to barcode,
        "商品名" to name,
        "商品名称" to name,
        "品名" to name,
        "名称" to name,
        "name" to name,
        "SKU" to sku,
        "货号" to sku,
        "商品编号" to sku,
        "规格" to spec,
        "型号" to spec,
        "spec" to spec,
        "价格" to price,
        "售价" to price,
        "单价" to price,
        "price" to price,
        "单位" to unit,
        "unit" to unit,
        "分类" to category,
        "category" to category,
        "品牌" to brand,
        "brand" to brand,
        "备注" to note,
        "note" to note,
    )
}

class ProductLibraryStore(private val context: Context) {
    companion object {
        const val MAX_PRODUCTS = 10_000

        private val FIELD_ALIASES = mapOf(
            "barcode" to listOf("条码", "商品条码", "barcode", "code", "编码", "ean", "upc"),
            "name" to listOf("商品名", "商品名称", "品名", "名称", "name", "title"),
            "sku" to listOf("sku", "货号", "商品编号", "编号"),
            "spec" to listOf("规格", "型号", "spec", "variant"),
            "price" to listOf("价格", "售价", "单价", "price"),
            "unit" to listOf("单位", "unit"),
            "category" to listOf("分类", "类目", "category"),
            "brand" to listOf("品牌", "brand"),
            "note" to listOf("备注", "说明", "note", "remark"),
        )

        internal fun fromRow(row: Map<String, String>): ProductRecord? {
            val normalized = row.entries.associate { normalizeField(it.key) to it.value.trim() }
            fun value(field: String): String = FIELD_ALIASES.getValue(field)
                .firstNotNullOfOrNull { alias -> normalized[normalizeField(alias)]?.takeIf(String::isNotBlank) }
                .orEmpty()
            val record = ProductRecord(
                barcode = value("barcode"),
                name = value("name"),
                sku = value("sku"),
                spec = value("spec"),
                price = value("price"),
                unit = value("unit"),
                category = value("category"),
                brand = value("brand"),
                note = value("note"),
            ).normalized()
            return record.takeIf { it.name.isNotBlank() || it.barcode.isNotBlank() || it.sku.isNotBlank() }
        }

        internal fun search(records: List<ProductRecord>, query: String): List<ProductRecord> {
            val needle = query.trim().lowercase()
            if (needle.isBlank()) return records.sortedByDescending(ProductRecord::updatedAt)
            return records.filter { item ->
                listOf(
                    item.barcode,
                    item.name,
                    item.sku,
                    item.spec,
                    item.price,
                    item.unit,
                    item.category,
                    item.brand,
                    item.note,
                ).any { it.lowercase().contains(needle) }
            }.sortedByDescending(ProductRecord::updatedAt)
        }

        private fun normalizeField(value: String): String = value
            .trim()
            .lowercase()
            .replace(Regex("[-\\s_（）()：:]+"), "")
    }

    private val file = File(context.filesDir, "product_library.json")
    private val mutex = Mutex()
    private val _records = MutableStateFlow(load())
    val records: StateFlow<List<ProductRecord>> = _records.asStateFlow()

    suspend fun upsert(record: ProductRecord): ProductRecord = withContext(Dispatchers.IO) {
        mutex.withLock {
            val safe = record.normalized().copy(updatedAt = System.currentTimeMillis())
            require(safe.name.isNotBlank() || safe.barcode.isNotBlank() || safe.sku.isNotBlank()) {
                "商品名称、条码和货号至少填写一项"
            }
            val list = _records.value.toMutableList()
            val index = list.indexOfFirst { it.id == safe.id }
            if (index >= 0) list[index] = safe else {
                require(list.size < MAX_PRODUCTS) { "本地商品资料最多 $MAX_PRODUCTS 条" }
                list.add(safe)
            }
            _records.value = list.sortedByDescending(ProductRecord::updatedAt)
            write(_records.value)
            safe
        }
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = _records.value.filterNot { it.id == id }
            if (next.size == _records.value.size) return@withLock false
            _records.value = next
            write(next)
            true
        }
    }

    suspend fun import(table: VariableDataTable): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val imported = table.rows.mapNotNull(::fromRow)
            require(imported.isNotEmpty()) {
                "没有找到商品字段；请包含名称/品名、条码、SKU、价格、规格等列"
            }
            val list = _records.value.toMutableList()
            var changed = 0
            imported.forEach { incoming ->
                val index = list.indexOfFirst { existing ->
                    incoming.barcode.isNotBlank() && existing.barcode == incoming.barcode ||
                        incoming.sku.isNotBlank() && existing.sku.equals(incoming.sku, ignoreCase = true)
                }
                val saved = incoming.copy(
                    id = if (index >= 0) list[index].id else incoming.id,
                    updatedAt = System.currentTimeMillis(),
                )
                if (index >= 0) list[index] = saved
                else if (list.size < MAX_PRODUCTS) list.add(saved)
                else return@forEach
                changed++
            }
            _records.value = list.sortedByDescending(ProductRecord::updatedAt)
            write(_records.value)
            changed
        }
    }

    fun findByBarcode(value: String): ProductRecord? {
        val code = value.trim()
        return _records.value.firstOrNull { it.barcode.equals(code, ignoreCase = true) }
    }

    private fun load(): List<ProductRecord> = runCatching {
        if (!file.isFile) return@runCatching emptyList()
        val array = JSONArray(file.readText(Charsets.UTF_8))
        buildList {
            for (index in 0 until minOf(array.length(), MAX_PRODUCTS)) {
                val json = array.optJSONObject(index) ?: continue
                val item = ProductRecord(
                    id = json.optString("id", UUID.randomUUID().toString()),
                    barcode = json.optString("barcode"),
                    name = json.optString("name"),
                    sku = json.optString("sku"),
                    spec = json.optString("spec"),
                    price = json.optString("price"),
                    unit = json.optString("unit"),
                    category = json.optString("category"),
                    brand = json.optString("brand"),
                    note = json.optString("note"),
                    updatedAt = json.optLong("updatedAt", 0L),
                ).normalized()
                if (item.name.isNotBlank() || item.barcode.isNotBlank() || item.sku.isNotBlank()) add(item)
            }
        }.distinctBy(ProductRecord::id).sortedByDescending(ProductRecord::updatedAt)
    }.getOrDefault(emptyList())

    private fun write(items: List<ProductRecord>) {
        val text = JSONArray().apply {
            items.forEach { item ->
                put(JSONObject().apply {
                    put("id", item.id)
                    put("barcode", item.barcode)
                    put("name", item.name)
                    put("sku", item.sku)
                    put("spec", item.spec)
                    put("price", item.price)
                    put("unit", item.unit)
                    put("category", item.category)
                    put("brand", item.brand)
                    put("note", item.note)
                    put("updatedAt", item.updatedAt)
                })
            }
        }.toString()
        val atomic = AtomicFile(file)
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            OutputStreamWriter(stream, Charsets.UTF_8).apply { write(text); flush() }
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            stream?.let(atomic::failWrite)
            throw error
        }
    }
}

fun ProductRecord.applyTo(document: LabelDocument): LabelDocument =
    document.resolveVariables(variables()).copy(title = name.ifBlank { document.title })

fun ProductRecord.toEditableElements(paper: PaperSettings, startYDots: Int): List<LabelElement> {
    val safe = normalized()
    val width = paper.contentWidthDots().coerceAtLeast(MIN_ELEMENT_DOTS)
    val left = paper.printableStartX()
    val available = if (paper.mode == PaperMode.LABEL) {
        (paper.fixedHeightDots() - startYDots).coerceAtLeast(MIN_ELEMENT_DOTS)
    } else 210
    val group = UUID.randomUUID().toString()
    val details = listOfNotNull(
        safe.spec.takeIf(String::isNotBlank),
        safe.price.takeIf(String::isNotBlank)?.let { price -> if (safe.unit.isBlank()) price else "$price / ${safe.unit}" },
        safe.sku.takeIf(String::isNotBlank)?.let { "SKU $it" },
    ).joinToString(" · ")
    if (available < 64 || width < 96) {
        return listOf(
            LabelElement(
                kind = ElementKind.TEXT,
                x = left,
                y = startYDots,
                width = width,
                height = available,
                groupId = group,
                text = listOf(safe.name, details, safe.barcode).filter(String::isNotBlank).joinToString("\n"),
                fontSizeDots = 18f,
                fontWeight = 600,
                lineSpacingDots = 0f,
            ),
        )
    }
    val titleHeight = (available * 0.28f).toInt().coerceAtLeast(MIN_ELEMENT_DOTS)
    val detailsHeight = (available * 0.18f).toInt().coerceAtLeast(MIN_ELEMENT_DOTS)
    val barcodeHeight = (available - titleHeight - detailsHeight).coerceAtLeast(MIN_ELEMENT_DOTS)
    return buildList {
        add(
            LabelElement(
                kind = ElementKind.TEXT,
                x = left,
                y = startYDots,
                width = width,
                height = titleHeight,
                groupId = group,
                text = safe.name.ifBlank { safe.sku.ifBlank { "商品" } },
                fontSizeDots = (titleHeight * 0.62f).coerceIn(14f, 52f),
                fontWeight = 700,
            ),
        )
        if (details.isNotBlank()) {
            add(
                LabelElement(
                    kind = ElementKind.TEXT,
                    x = left,
                    y = startYDots + titleHeight,
                    width = width,
                    height = detailsHeight,
                    groupId = group,
                    text = details,
                    fontSizeDots = (detailsHeight * 0.58f).coerceIn(12f, 34f),
                    fontWeight = 400,
                ),
            )
        }
        if (safe.barcode.isNotBlank()) {
            add(
                LabelElement(
                    kind = ElementKind.BARCODE,
                    x = left,
                    y = startYDots + titleHeight + detailsHeight,
                    width = width,
                    height = barcodeHeight,
                    groupId = group,
                    barcodeType = BarcodeType.CODE_128,
                    barcodeContent = safe.barcode,
                    barcodeCaption = true,
                ),
            )
        }
    }
}
