package com.qrint.studio.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qrint.studio.data.ProductLibraryStore
import com.qrint.studio.data.ProductRecord
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductLibrarySheet(
    store: ProductLibraryStore,
    onInsert: (ProductRecord) -> Unit,
    onImport: () -> Unit,
    onMessage: suspend (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val records by store.records.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ProductRecord?>(null) }
    val shown = remember(records, query) { ProductLibraryStore.search(records, query) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 760.dp).padding(horizontal = 20.dp).padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Inventory2, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("本地商品资料库", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${records.size} 条 · 完全离线 · 可搜索并插入可编辑标签", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text("搜索名称、条码、SKU、规格、品牌或备注") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { editing = ProductRecord() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("新建商品")
                }
                OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.FileOpen, null); Spacer(Modifier.width(6.dp)); Text("导入表格")
                }
            }
            if (shown.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 38.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(if (records.isEmpty()) "资料库还是空的" else "没有匹配的商品", fontWeight = FontWeight.Bold)
                    Text("可手动新建，或从 Excel / WPS / CSV 导入", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = ProductRecord::id) { product ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 14.dp, top = 11.dp, end = 4.dp, bottom = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(product.name.ifBlank { product.sku.ifBlank { "未命名商品" } }, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOf(product.spec, product.price, product.unit).filter(String::isNotBlank).joinToString(" · ").ifBlank { "暂无规格和价格" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        product.barcode.ifBlank { "无条码" },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                    )
                                }
                                TextButton(onClick = { onInsert(product) }) { Text("插入") }
                                IconButton(onClick = { editing = product }) { Icon(Icons.Rounded.Edit, "编辑") }
                                IconButton(onClick = {
                                    scope.launch {
                                        if (store.delete(product.id)) onMessage("已删除 ${product.name.ifBlank { "商品" }}")
                                    }
                                }) { Icon(Icons.Rounded.DeleteOutline, "删除", tint = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { record ->
        ProductEditorDialog(
            initial = record,
            onDismiss = { editing = null },
            onSave = { value ->
                scope.launch {
                    runCatching { store.upsert(value) }
                        .onSuccess { saved ->
                            editing = null
                            onMessage("已保存 ${saved.name.ifBlank { "商品资料" }}")
                        }
                        .onFailure { error -> onMessage("保存失败：${error.message.orEmpty()}") }
                }
            },
        )
    }
}

@Composable
private fun ProductEditorDialog(
    initial: ProductRecord,
    onDismiss: () -> Unit,
    onSave: (ProductRecord) -> Unit,
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var barcode by remember(initial.id) { mutableStateOf(initial.barcode) }
    var sku by remember(initial.id) { mutableStateOf(initial.sku) }
    var spec by remember(initial.id) { mutableStateOf(initial.spec) }
    var price by remember(initial.id) { mutableStateOf(initial.price) }
    var unit by remember(initial.id) { mutableStateOf(initial.unit) }
    var category by remember(initial.id) { mutableStateOf(initial.category) }
    var brand by remember(initial.id) { mutableStateOf(initial.brand) }
    var note by remember(initial.id) { mutableStateOf(initial.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank() && initial.barcode.isBlank()) "新建商品" else "编辑商品") },
        text = {
            LazyColumn(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(name, { name = it }, label = { Text("商品名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(barcode, { barcode = it }, label = { Text("条码") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(sku, { sku = it }, label = { Text("SKU / 货号") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(spec, { spec = it }, label = { Text("规格") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(price, { price = it }, label = { Text("价格") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(unit, { unit = it }, label = { Text("单位") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(category, { category = it }, label = { Text("分类") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                }
                item { OutlinedTextField(brand, { brand = it }, label = { Text("品牌") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(note, { note = it }, label = { Text("备注") }, minLines = 2, modifier = Modifier.fillMaxWidth()) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        initial.copy(
                            name = name,
                            barcode = barcode,
                            sku = sku,
                            spec = spec,
                            price = price,
                            unit = unit,
                            category = category,
                            brand = brand,
                            note = note,
                        ),
                    )
                },
                enabled = name.isNotBlank() || barcode.isNotBlank() || sku.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
