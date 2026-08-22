package com.qrint.studio.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
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
internal fun DocumentPagePanel(
    pageCount: Int,
    activeIndex: Int,
    selectedIndices: Set<Int>,
    onOpenPage: (Int) -> Unit,
    onTogglePage: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pageCount <= 1) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onOpenPage((activeIndex - 1).coerceAtLeast(0)) }, enabled = activeIndex > 0) {
                    Text("上一页")
                }
                Text(
                    "正在编辑第 ${activeIndex + 1}/$pageCount 页",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                TextButton(
                    onClick = { onOpenPage((activeIndex + 1).coerceAtMost(pageCount - 1)) },
                    enabled = activeIndex < pageCount - 1,
                ) { Text("下一页") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("打印页：", style = MaterialTheme.typography.labelMedium)
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(pageCount) { index ->
                        Surface(
                            onClick = { onOpenPage(index) },
                            color = if (index == activeIndex) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Row(
                                Modifier.padding(start = 5.dp, end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = index in selectedIndices,
                                    onCheckedChange = { onTogglePage(index) },
                                )
                                Text("${index + 1}", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                OutlinedButton(onClick = onSelectAll) { Text("全选") }
                TextButton(onClick = onClearSelection) { Text("清空") }
            }
        }
    }
}
