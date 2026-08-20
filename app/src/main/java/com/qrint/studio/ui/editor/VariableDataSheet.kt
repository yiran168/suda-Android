package com.qrint.studio.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qrint.studio.data.VariableDataTable
import com.qrint.studio.model.LabelElement
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VariableDataSheet(
    table: VariableDataTable,
    viewRows: List<Map<String, String>>,
    sheets: List<VariableDataTable>,
    currentRow: Int,
    batchRange: IntRange,
    selected: LabelElement?,
    boundFields: Set<String>,
    filterQuery: String,
    sortField: String?,
    sortAscending: Boolean,
    onFilterQueryChange: (String) -> Unit,
    onSortChange: (field: String?, ascending: Boolean) -> Unit,
    onRowChange: (Int) -> Unit,
    onBatchRangeChange: (IntRange) -> Unit,
    onSheetChange: (VariableDataTable) -> Unit,
    onInsertField: (String) -> Unit,
    onReplaceFile: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val safeRow = if (viewRows.isEmpty()) 0 else currentRow.coerceIn(0, viewRows.lastIndex)
    val safeBatchRange = com.qrint.studio.data.normalizeVariableRange(viewRows.size, batchRange)
    val values = viewRows.getOrNull(safeRow).orEmpty()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Rounded.DataObject, null, modifier = Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("变量数据批打", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${table.sourceName} · ${table.sheetName}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        "${table.headers.size} 个字段 · ${viewRows.size}/${table.rows.size} 条记录",
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "本地支持 Excel .xls/.xlsx、兼容 BIFF 的 WPS .et、CSV/TSV；加密或专有 .et 请另存为 .xlsx。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (boundFields.isEmpty()) "尚未绑定字段：点击下方字段即可插入 {{字段名}}"
                        else "已绑定：${boundFields.joinToString("、")}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (sheets.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("选择工作表", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        sheets.forEach { sheet ->
                            FilterChip(
                                selected = sheet === table,
                                onClick = { if (sheet !== table) onSheetChange(sheet) },
                                label = { Text("${sheet.sheetName} · ${sheet.rows.size} 条") },
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = filterQuery,
                onValueChange = onFilterQueryChange,
                label = { Text("筛选所有字段") },
                placeholder = { Text("输入名称、编号、价格等内容") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("排序（当前筛选结果也按此顺序批打）", fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = sortField == null,
                        onClick = { onSortChange(null, true) },
                        label = { Text("原始顺序") },
                    )
                    table.headers.forEach { header ->
                        FilterChip(
                            selected = sortField == header,
                            onClick = { onSortChange(header, if (sortField == header) !sortAscending else true) },
                            label = { Text(if (sortField == header) "$header ${if (sortAscending) "↑" else "↓"}" else header) },
                        )
                    }
                }
            }

            if (viewRows.isEmpty()) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer) {
                    Text("筛选后没有记录，请修改关键词。", modifier = Modifier.fillMaxWidth().padding(14.dp))
                }
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onRowChange((safeRow - 1).coerceAtLeast(0)) },
                    enabled = safeRow > 0,
                    modifier = Modifier.weight(1f),
                ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null); Spacer(Modifier.width(5.dp)); Text("上一条") }
                Text("${safeRow + 1} / ${viewRows.size}", fontWeight = FontWeight.Bold)
                OutlinedButton(
                    onClick = { onRowChange((safeRow + 1).coerceAtMost(viewRows.lastIndex)) },
                    enabled = safeRow < viewRows.lastIndex,
                    modifier = Modifier.weight(1f),
                ) { Text("下一条"); Spacer(Modifier.width(5.dp)); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null) }
            }

            if (viewRows.isNotEmpty()) Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("批量打印范围", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(
                            "${safeBatchRange.first + 1}–${safeBatchRange.last + 1} · ${safeBatchRange.count()} 条",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (viewRows.size > 1) {
                        RangeSlider(
                            value = safeBatchRange.first.toFloat()..safeBatchRange.last.toFloat(),
                            onValueChange = { values ->
                                val first = values.start.roundToInt().coerceIn(0, viewRows.lastIndex)
                                val last = values.endInclusive.roundToInt().coerceIn(first, viewRows.lastIndex)
                                onBatchRangeChange(first..last)
                            },
                            valueRange = 0f..viewRows.lastIndex.toFloat(),
                            steps = if (viewRows.size <= 1_000) (viewRows.size - 2).coerceAtLeast(0) else 0,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onBatchRangeChange(safeRow..safeBatchRange.last.coerceAtLeast(safeRow)) },
                            modifier = Modifier.weight(1f),
                        ) { Text("从当前条开始") }
                        OutlinedButton(
                            onClick = { onBatchRangeChange(safeBatchRange.first.coerceAtMost(safeRow)..safeRow) },
                            modifier = Modifier.weight(1f),
                        ) { Text("到当前条结束") }
                    }
                }
            }

            Text(
                selected?.let { "点击字段，绑定到当前选中的${elementTargetName(it)}" } ?: "未选中元素：点击字段会新建一项文字",
                style = MaterialTheme.typography.titleMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                table.headers.forEach { header ->
                    FilterChip(
                        selected = header in boundFields,
                        onClick = { onInsertField(header) },
                        label = {
                            Column {
                                Text(header, fontWeight = FontWeight.SemiBold)
                                Text(values[header].orEmpty().ifBlank { "（空）" }, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        },
                    )
                }
            }

            Text(
                "打印时使用当前筛选和排序后的顺序逐行替换字段；空单元格保留为空，未找到的字段保留原占位符以便检查。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReplaceFile, modifier = Modifier.weight(1f)) { Text("更换文件") }
                OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Close, null); Spacer(Modifier.width(5.dp)); Text("清除数据")
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("完成字段绑定") }
        }
    }
}

private fun elementTargetName(element: LabelElement): String = when (element.kind.name) {
    "BARCODE" -> "编码内容"
    "TABLE" -> "表格内容"
    "TEXT", "DATE_TIME", "SEQUENCE" -> "文字内容"
    else -> "画布（将新建文字）"
}
