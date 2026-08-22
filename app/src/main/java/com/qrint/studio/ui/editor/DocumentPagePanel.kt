package com.qrint.studio.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qrint.studio.model.LabelDocument

internal fun mergeActiveDocumentPage(
    pages: List<LabelDocument>,
    activeIndex: Int,
    activeDocument: LabelDocument,
): List<LabelDocument> {
    if (pages.isEmpty() || activeIndex !in pages.indices) return pages
    if (pages[activeIndex] == activeDocument) return pages
    return pages.toMutableList().also { it[activeIndex] = activeDocument }
}

internal fun selectedDocumentPages(
    pages: List<LabelDocument>,
    selectedIndices: Set<Int>,
): List<LabelDocument> = pages.filterIndexed { index, _ -> index in selectedIndices }

@Composable
internal fun DocumentPageDialog(
    pageCount: Int,
    activeIndex: Int,
    selectedIndices: Set<Int>,
    onOpenPage: (Int) -> Unit,
    onTogglePage: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    if (pageCount <= 1) return
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("选择打印页数") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "已选择 ${selectedIndices.size}/$pageCount 页。勾选需要打印的页，或进入任意一页继续编辑。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onSelectAll, modifier = Modifier.weight(1f)) {
                        Text("全部页")
                    }
                    TextButton(onClick = onClearSelection, modifier = Modifier.weight(1f)) {
                        Text("清空")
                    }
                }
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items((0 until pageCount).toList(), key = { it }) { index ->
                        val selected = index in selectedIndices
                        val active = index == activeIndex
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onTogglePage(index) },
                            color = when {
                                active -> MaterialTheme.colorScheme.primaryContainer
                                selected -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = { onTogglePage(index) },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text("第 ${index + 1} 页", style = MaterialTheme.typography.labelLarge)
                                    if (active) {
                                        Text(
                                            "当前编辑页",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        onOpenPage(index)
                                        onDismissRequest()
                                    },
                                ) { Text(if (active) "返回编辑" else "编辑") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismissRequest) { Text("完成") }
        },
    )
}
